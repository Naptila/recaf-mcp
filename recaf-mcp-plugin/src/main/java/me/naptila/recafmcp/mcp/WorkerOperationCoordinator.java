package me.naptila.recafmcp.mcp;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes workspace operations and tracks cancellable tool tasks as busy until drained.
 */
public final class WorkerOperationCoordinator implements AutoCloseable {
    private final ReentrantLock operationLock = new ReentrantLock(true);
    private final AtomicInteger busyCount = new AtomicInteger();
    private final ConcurrentMap<String, OperationState<?>> activeOperations =
            new ConcurrentHashMap<>();
    private final ExecutorService operationExecutor = Executors.newCachedThreadPool(
            Thread.ofPlatform().daemon(true).name("recaf-mcp-operation-", 0).factory()
    );
    private volatile boolean closing;

    public <T> T execute(Callable<T> operation) throws Exception {
        if (closing) {
            throw new WorkerClosingException();
        }

        busyCount.incrementAndGet();
        try {
            operationLock.lockInterruptibly();
            try {
                if (closing) {
                    throw new WorkerClosingException();
                }
                return operation.call();
            } finally {
                operationLock.unlock();
            }
        } finally {
            busyCount.decrementAndGet();
        }
    }

    public <T> T execute(String operationId,
                         long timeout,
                         TimeUnit unit,
                         Callable<T> operation) throws Exception {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(operation, "operation");
        if (operationId.isBlank() || operationId.length() > 128) {
            throw new IllegalArgumentException("invalid operation ID");
        }
        if (timeout <= 0) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (closing) {
            throw new WorkerClosingException();
        }

        OperationState<T> state = new OperationState<>(operationId);
        OperationTask<T> task = new OperationTask<>(state, () -> {
            operationLock.lockInterruptibly();
            try {
                if (closing) {
                    throw new WorkerClosingException();
                }
                return operation.call();
            } finally {
                operationLock.unlock();
            }
        });
        state.task = task;
        if (activeOperations.putIfAbsent(operationId, state) != null) {
            throw new IllegalArgumentException("operation ID is already active");
        }
        busyCount.incrementAndGet();
        try {
            operationExecutor.execute(task);
        } catch (RejectedExecutionException ex) {
            state.drain();
            throw ex;
        }

        try {
            return task.get(timeout, unit);
        } catch (TimeoutException ex) {
            task.cancel(true);
            throw new OperationTimeoutException();
        } catch (CancellationException ex) {
            throw new OperationCancelledException();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException ex) {
            task.cancel(true);
            throw ex;
        }
    }

    public boolean cancel(String operationId) {
        OperationState<?> state = activeOperations.get(operationId);
        return state != null && state.task.cancel(true);
    }

    public void beginShutdown() throws InterruptedException {
        if (closing) {
            throw new WorkerClosingException();
        }

        busyCount.incrementAndGet();
        try {
            operationLock.lockInterruptibly();
            try {
                if (closing) {
                    throw new WorkerClosingException();
                }
                closing = true;
            } finally {
                operationLock.unlock();
            }
        } finally {
            busyCount.decrementAndGet();
        }
    }

    public boolean isBusy() {
        return busyCount.get() > 0;
    }

    public boolean isClosing() {
        return closing;
    }

    @Override
    public void close() {
        operationExecutor.shutdownNow();
    }

    private final class OperationState<T> {
        private final String operationId;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean drained = new AtomicBoolean();
        private OperationTask<T> task;

        private OperationState(String operationId) {
            this.operationId = operationId;
        }

        private void drain() {
            if (drained.compareAndSet(false, true)) {
                activeOperations.remove(operationId, this);
                busyCount.decrementAndGet();
            }
        }
    }

    private final class OperationTask<T> extends FutureTask<T> {
        private final OperationState<T> state;

        private OperationTask(OperationState<T> state, Callable<T> operation) {
            super(operation);
            this.state = state;
        }

        @Override
        public void run() {
            state.started.set(true);
            try {
                super.run();
            } finally {
                state.drain();
            }
        }

        @Override
        protected void done() {
            if (!state.started.get()) {
                state.drain();
            }
        }
    }

    public static final class WorkerClosingException extends IllegalStateException {
        public WorkerClosingException() {
            super("worker is shutting down");
        }
    }

    public static final class OperationTimeoutException extends Exception {
        public OperationTimeoutException() {
            super("tool operation timed out");
        }
    }

    public static final class OperationCancelledException extends Exception {
        public OperationCancelledException() {
            super("tool operation was cancelled");
        }
    }
}
