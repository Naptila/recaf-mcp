package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.android.AndroidJvmConverter;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.workspace.model.Workspace;

import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared JVM decompiler selection and schema helpers.
 */
final class DecompilerToolSupport {
    static final String PER_CLASS_TIMEOUT_ARGUMENT = "per_class_timeout_sec";
    static final int MAX_PER_CLASS_TIMEOUT_SEC = 300;
    private static final int MAX_ISOLATED_DECOMPILER_THREADS = 32;
    private static final java.util.concurrent.atomic.AtomicInteger DECOMPILER_THREAD_IDS =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.ExecutorService ISOLATED_DECOMPILE_EXECUTOR =
            new java.util.concurrent.ThreadPoolExecutor(
                    2,
                    MAX_ISOLATED_DECOMPILER_THREADS,
                    30L,
                    TimeUnit.SECONDS,
                    new java.util.concurrent.SynchronousQueue<>(),
                    runnable -> {
                        Thread thread = new Thread(
                                runnable,
                                "recaf-mcp-decompile-" + DECOMPILER_THREAD_IDS.incrementAndGet()
                        );
                        thread.setDaemon(true);
                        return thread;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
            );

    private DecompilerToolSupport() {
    }

    static JvmDecompiler select(DecompilerManager manager, String requested) {
        if (requested == null) {
            return manager.getTargetJvmDecompiler();
        }
        return manager.getJvmDecompilers().stream()
                .filter(decompiler -> decompiler.getName().equalsIgnoreCase(requested))
                .findFirst()
                .orElse(null);
    }

    static List<Descriptor> descriptors(DecompilerManager manager) {
        JvmDecompiler defaultDecompiler = manager.getTargetJvmDecompiler();
        return manager.getJvmDecompilers().stream()
                .sorted(Comparator.comparing(
                        JvmDecompiler::getName,
                        String.CASE_INSENSITIVE_ORDER
                ))
                .map(decompiler -> new Descriptor(
                        decompiler.getName(),
                        decompiler.getVersion(),
                        defaultDecompiler != null
                                && decompiler.getName().equalsIgnoreCase(
                                        defaultDecompiler.getName()
                                )
                ))
                .toList();
    }

    static JsonObject inputSchemaProperty(List<Descriptor> descriptors) {
        JsonObject decompiler = new JsonObject();
        decompiler.addProperty("type", "string");
        decompiler.addProperty("minLength", 1);
        if (!descriptors.isEmpty()) {
            JsonArray values = new JsonArray();
            descriptors.forEach(item -> values.add(item.name()));
            decompiler.add("enum", values);
            descriptors.stream()
                    .filter(Descriptor::isDefault)
                    .findFirst()
                    .ifPresent(item -> decompiler.addProperty("default", item.name()));
        }
        decompiler.addProperty(
                "description",
                "Installed JVM decompiler; Android classes are converted first."
        );
        return decompiler;
    }

    static JsonObject timeoutSchemaProperty(int defaultSeconds) {
        JsonObject timeout = new JsonObject();
        timeout.addProperty("type", "integer");
        timeout.addProperty("minimum", 1);
        timeout.addProperty("maximum", MAX_PER_CLASS_TIMEOUT_SEC);
        timeout.addProperty("default", defaultSeconds);
        timeout.addProperty(
                "description",
                "Maximum seconds allowed per class decompilation before cancelling."
        );
        return timeout;
    }

    static CompletableFuture<DecompileResult> decompile(DecompilerManager manager,
                                                        JvmDecompiler decompiler,
                                                        Workspace workspace,
                                                        JvmClassInfo classInfo) {
        return decompile(manager, decompiler, false, workspace, classInfo);
    }

    static CompletableFuture<DecompileResult> decompile(DecompilerManager manager,
                                                        JvmDecompiler decompiler,
                                                        boolean allowVineflowerFallback,
                                                        Workspace workspace,
                                                        JvmClassInfo classInfo) {
        return decompileWithMetadata(
                manager,
                decompiler,
                allowVineflowerFallback,
                workspace,
                classInfo
        ).thenApply(IsolatedDecompilation::result);
    }

    static CompletableFuture<IsolatedDecompilation> decompileWithMetadata(
            DecompilerManager manager,
            JvmDecompiler decompiler,
            boolean allowVineflowerFallback,
            Workspace workspace,
            JvmClassInfo classInfo) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                DecompileResult primaryResult = runSingleDecompiler(decompiler, workspace, classInfo);
                if (isSuccessfulSource(primaryResult) || !allowVineflowerFallback) {
                    return new IsolatedDecompilation(
                            decompiler.getName(),
                            decompiler.getVersion(),
                            primaryResult,
                            null
                    );
                }
                JvmDecompiler vineflower = select(manager, "Vineflower");
                if (vineflower == null
                        || vineflower.getName().equalsIgnoreCase(decompiler.getName())
                        || Thread.currentThread().isInterrupted()) {
                    return new IsolatedDecompilation(
                            decompiler.getName(),
                            decompiler.getVersion(),
                            primaryResult,
                            null
                    );
                }
                DecompileResult fallbackResult = runSingleDecompiler(vineflower, workspace, classInfo);
                if (isSuccessfulSource(fallbackResult)) {
                    return new IsolatedDecompilation(
                            vineflower.getName(),
                            vineflower.getVersion(),
                            fallbackResult,
                            decompiler.getName()
                    );
                }
                return new IsolatedDecompilation(
                        decompiler.getName(),
                        decompiler.getVersion(),
                        primaryResult,
                        null
                );
            }, ISOLATED_DECOMPILE_EXECUTOR);
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "decompiler thread pool is saturated by timed-out decompilations; "
                            + "retry with decompiler=\"Vineflower\" or \"Fallback\"",
                    ex
            ));
        }
    }

    private static DecompileResult runSingleDecompiler(JvmDecompiler decompiler,
                                                       Workspace workspace,
                                                       JvmClassInfo classInfo) {
        DecompileResult result = decompiler.decompile(workspace, classInfo);
        if (shouldRetryIsolatedInnerClass(classInfo, result)
                && !Thread.currentThread().isInterrupted()) {
            try {
                JvmClassInfo isolated = isolateInnerClassForDecompilation(classInfo);
                return decompiler.decompile(workspace, isolated);
            } catch (RuntimeException ignored) {
                return result;
            }
        }
        return result;
    }

    private static boolean isSuccessfulSource(DecompileResult result) {
        return result != null
                && result.getType() == DecompileResult.ResultType.SUCCESS
                && result.getText() != null
                && !result.getText().isBlank();
    }

    record IsolatedDecompilation(String name,
                                 String version,
                                 DecompileResult result,
                                 String fallbackFrom) {
        IsolatedDecompilation(String name, String version, DecompileResult result) {
            this(name, version, result, null);
        }
    }

    static boolean shouldRetryIsolatedInnerClass(JvmClassInfo classInfo,
                                                 DecompileResult result) {
        if (result != null
                && result.getType() == DecompileResult.ResultType.SUCCESS
                && result.getText() != null) {
            return false;
        }
        return classInfo.getName().contains("$") || !classInfo.getInnerClasses().isEmpty();
    }

    static JvmClassInfo isolateInnerClassForDecompilation(JvmClassInfo classInfo) {
        String targetName = classInfo.getName();
        String childPrefix = targetName + "$";
        ClassReader reader = new ClassReader(classInfo.getBytecode());
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitOuterClass(String owner, String name, String descriptor) {
                // Omit enclosing method so nested/local classes emit standalone output.
            }

            @Override
            public void visitNestHost(String nestHost) {
                // Omit nest host so Vineflower does not fold into an ancestor class.
            }

            @Override
            public void visitInnerClass(String name,
                                        String outerName,
                                        String innerName,
                                        int access) {
                if (name != null && name.startsWith(childPrefix)) {
                    super.visitInnerClass(name, outerName, innerName, access);
                }
            }
        }, 0);
        return new JvmClassInfoBuilder(writer.toByteArray()).build();
    }

    static void addAvailability(JsonObject target, List<Descriptor> descriptors) {
        JsonArray available = new JsonArray();
        descriptors.forEach(item -> {
            JsonObject descriptor = new JsonObject();
            descriptor.addProperty("name", item.name());
            descriptor.addProperty("version", item.version());
            descriptor.addProperty("default", item.isDefault());
            available.add(descriptor);
        });
        target.add("available_decompilers", available);
        descriptors.stream()
                .filter(Descriptor::isDefault)
                .findFirst()
                .ifPresentOrElse(
                        item -> target.addProperty("default_decompiler", item.name()),
                        () -> target.add("default_decompiler", JsonNull.INSTANCE)
                );
    }

    static int fillPending(Deque<PendingDecompilation> pending,
                           List<ClassToolSupport.Entry> classes,
                           int nextClassIndex,
                           int maxInFlight,
                           Workspace workspace,
                           AsyncDecompileOperation operation) {
        while (pending.size() < maxInFlight && nextClassIndex < classes.size()) {
            ToolCancellation.check();
            ClassToolSupport.Entry entry = classes.get(nextClassIndex++);
            CompletableFuture<DecompileResult> result;
            try {
                JvmClassInfo classInfo = entry.scope() == ClassToolSupport.Scope.ANDROID
                        ? AndroidJvmConverter.convertForDecompilation(
                                entry.classInfo().asAndroidClass()
                        ).classInfo()
                        : entry.classInfo().asJvmClass();
                result = Objects.requireNonNull(
                        operation.run(workspace, classInfo),
                        "decompilation future"
                );
            } catch (Exception ex) {
                result = CompletableFuture.failedFuture(ex);
            }
            pending.addLast(new PendingDecompilation(entry, result));
        }
        return nextClassIndex;
    }

    static AwaitOutcome awaitPending(PendingDecompilation current, int perClassTimeoutSec) {
        try {
            DecompileResult result = current.result().get(perClassTimeoutSec, TimeUnit.SECONDS);
            return new AwaitOutcome(result, false, null);
        } catch (TimeoutException ex) {
            current.result().cancel(true);
            return new AwaitOutcome(
                    null,
                    true,
                    "decompilation timed out after " + perClassTimeoutSec + "s"
            );
        } catch (InterruptedException ex) {
            current.result().cancel(true);
            Thread.currentThread().interrupt();
            throw new CancellationException("tool operation was cancelled");
        } catch (ExecutionException ex) {
            ToolCancellation.propagateIfRequested(ex.getCause());
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            return new AwaitOutcome(null, false, cause.getMessage() != null && !cause.getMessage().isBlank()
                    ? cause.getMessage()
                    : cause.getClass().getSimpleName());
        } catch (CancellationException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return new AwaitOutcome(null, false, ex.getMessage() != null && !ex.getMessage().isBlank()
                    ? ex.getMessage()
                    : ex.getClass().getSimpleName());
        }
    }

    static void drainPending(Deque<PendingDecompilation> pending) {
        while (!pending.isEmpty()) {
            CompletableFuture<DecompileResult> future = pending.removeFirst().result();
            future.cancel(true);
            try {
                future.get(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (CancellationException | CompletionException | ExecutionException | TimeoutException ignored) {
                // Cancelled tasks are interrupted and bounded so a hung decompiler never blocks cleanup.
            }
        }
    }

    @FunctionalInterface
    interface AsyncDecompileOperation {
        CompletableFuture<DecompileResult> run(Workspace workspace,
                                               JvmClassInfo classInfo) throws Exception;
    }

    record AwaitOutcome(DecompileResult result, boolean timedOut, String failureMessage) {
    }

    record PendingDecompilation(ClassToolSupport.Entry entry,
                                CompletableFuture<DecompileResult> result) {
    }

    record Descriptor(String name, String version, boolean isDefault) {
        Descriptor {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
        }
    }
}
