package me.naptila.recafmcp.mcp;

import me.naptila.recafmcp.mcp.WorkerOperationCoordinator.WorkerClosingException;
import me.naptila.recafmcp.mcp.WorkerOperationCoordinator.OperationCancelledException;
import me.naptila.recafmcp.mcp.WorkerOperationCoordinator.OperationTimeoutException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerOperationCoordinatorTest {
    @Test
    void serializesOperationsAndReportsQueuedWorkAsBusy() throws Exception {
        WorkerOperationCoordinator coordinator = new WorkerOperationCoordinator();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        try {
            Future<String> first = executor.submit(() -> coordinator.execute(() -> {
                int current = active.incrementAndGet();
                maximumActive.accumulateAndGet(current, Math::max);
                firstStarted.countDown();
                try {
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("first operation was not released");
                    }
                    return "first";
                } finally {
                    active.decrementAndGet();
                }
            }));
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

            Future<String> second = executor.submit(() -> coordinator.execute(() -> {
                int current = active.incrementAndGet();
                maximumActive.accumulateAndGet(current, Math::max);
                try {
                    return "second";
                } finally {
                    active.decrementAndGet();
                }
            }));
            assertTrue(coordinator.isBusy());

            releaseFirst.countDown();
            assertEquals("first", first.get(5, TimeUnit.SECONDS));
            assertEquals("second", second.get(5, TimeUnit.SECONDS));
            assertEquals(1, maximumActive.get());
            assertFalse(coordinator.isBusy());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shutdownClosesCoordinatorToNewOperations() throws Exception {
        WorkerOperationCoordinator coordinator = new WorkerOperationCoordinator();

        coordinator.beginShutdown();

        assertTrue(coordinator.isClosing());
        assertFalse(coordinator.isBusy());
        assertThrows(WorkerClosingException.class, () -> coordinator.execute(() -> "late"));
        assertThrows(WorkerClosingException.class, coordinator::beginShutdown);
    }

    @Test
    void cancelsNamedOperationAndStaysBusyUntilTaskDrains() throws Exception {
        WorkerOperationCoordinator coordinator = new WorkerOperationCoordinator();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch drained = new CountDownLatch(1);
        try {
            Future<String> operation = executor.submit(() -> coordinator.execute(
                    "cancel-me",
                    10,
                    TimeUnit.SECONDS,
                    () -> {
                        started.countDown();
                        try {
                            try {
                                release.await();
                            } catch (InterruptedException ex) {
                                interrupted.countDown();
                                release.await();
                            }
                            return "unexpected";
                        } finally {
                            drained.countDown();
                        }
                    }
            ));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(coordinator.isBusy());
            assertTrue(coordinator.cancel("cancel-me"));
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> operation.get(5, TimeUnit.SECONDS)
            );
            try {
                operation.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException ex) {
                assertTrue(ex.getCause() instanceof OperationCancelledException);
            }
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertEquals(1, drained.getCount());
            assertTrue(coordinator.isBusy());
            release.countDown();
            assertTrue(drained.await(5, TimeUnit.SECONDS));
            for (int attempt = 0; attempt < 100 && coordinator.isBusy(); attempt++) {
                Thread.sleep(10);
            }
            assertFalse(coordinator.isBusy());
            assertFalse(coordinator.cancel("cancel-me"));
        } finally {
            release.countDown();
            coordinator.close();
            executor.shutdownNow();
        }
    }

    @Test
    void enforcesNamedOperationTimeout() throws Exception {
        WorkerOperationCoordinator coordinator = new WorkerOperationCoordinator();
        try {
            assertThrows(OperationTimeoutException.class, () -> coordinator.execute(
                    "timeout",
                    1,
                    TimeUnit.MILLISECONDS,
                    () -> {
                        Thread.sleep(10_000);
                        return "unexpected";
                    }
            ));
            for (int attempt = 0; attempt < 100 && coordinator.isBusy(); attempt++) {
                Thread.sleep(10);
            }
            assertFalse(coordinator.isBusy());
        } finally {
            coordinator.close();
        }
    }
}
