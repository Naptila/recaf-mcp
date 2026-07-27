package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.compile.CompilerDiagnostic;
import software.coley.recaf.services.script.GenerateResult;
import software.coley.recaf.services.script.ScriptEngine;
import software.coley.recaf.services.script.ScriptResult;
import software.coley.recaf.services.script.ScriptSourceAugmentation;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.ResourceJvmClassListener;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Compiles or runs a generic Recaf Java script inside the isolated Worker.
 */
public final class RunRecafScriptTool implements McpTool {
    static final int MAX_SCRIPT_LENGTH = 500_000;
    static final int MAX_STDOUT_BYTES = 256 * 1024;
    private static final int MAX_DIAGNOSTICS = 100;
    private static final int MAX_DIAGNOSTIC_MESSAGE = 1_000;
    private static final int MAX_ERROR_MESSAGE = 1_000;
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;
    private static final String RESULT_PREFIX = "MCP_RESULT ";
    private static final Object STDOUT_LOCK = new Object();
    private static final Set<String> ARGUMENTS = Set.of(
            "script",
            "compile_only",
            "acknowledge_full_worker_access",
            "offset",
            "limit"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;
    private final ScriptOperation operation;

    public RunRecafScriptTool(WorkspaceManager workspaceManager, ScriptEngine scriptEngine) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                (workspace, request) -> runScript(
                        workspaceManager,
                        scriptEngine,
                        workspace,
                        request
                )
        );
    }

    RunRecafScriptTool(BooleanSupplier hasWorkspace,
                       Supplier<Workspace> workspaceSupplier,
                       ScriptOperation operation) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public String name() {
        return "run_recaf_script";
    }

    @Override
    public String description() {
        return "Compile or explicitly run a high-privilege generic Recaf Java script.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        Request request = parseRequest(arguments);
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", request, null);
        }

        ScriptExecution execution;
        try {
            execution = operation.run(workspaceSupplier.get(), request);
        } catch (Exception ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error("Recaf script operation failed: " + exceptionMessage(ex), request, null);
        }

        JsonObject structured = resultJson(request, execution);
        if (!execution.compileSuccess()) {
            structured.addProperty("error", "Recaf rejected the script during compilation");
            return McpTool.errorResult(
                    "Recaf rejected the script during compilation",
                    structured
            );
        }
        if (execution.cancelled()) {
            structured.addProperty("error", "Recaf script execution was cancelled");
            return McpTool.errorResult("Recaf script execution was cancelled", structured);
        }
        if (execution.runtimeErrorType() != null) {
            structured.addProperty(
                    "error",
                    "Recaf script failed at runtime; partial workspace mutations may remain"
            );
            return McpTool.errorResult(
                    "Recaf script failed at runtime; partial workspace mutations may remain",
                    structured
            );
        }
        if (request.compileOnly()) {
            return McpTool.successResult(
                    "Recaf script compiled successfully and was not executed",
                    structured
            );
        }
        return McpTool.successResult(
                "Recaf script completed; inspect reported changes before saving",
                structured
        );
    }

    private static ScriptExecution runScript(WorkspaceManager workspaceManager,
                                             ScriptEngine scriptEngine,
                                             Workspace workspace,
                                             Request request) throws Exception {
        WorkspaceResource primary = workspace.getPrimaryResource();
        JvmClassBundle bundle = primary.getJvmClassBundle();
        ChangeTracker tracker = new ChangeTracker(bundle);
        primary.addResourceJvmClassListener(tracker);
        CapturedExecution captured;
        RestorationOutcome restoration = new RestorationOutcome(false, false, null);
        try {
            captured = captureAndRun(scriptEngine, request);
        } finally {
            primary.removeResourceJvmClassListener(tracker);
            restoration = restoreWorkspace(workspaceManager, workspace);
        }
        return new ScriptExecution(
                captured.compileSuccess(),
                captured.executed(),
                captured.cancelled(),
                captured.diagnostics(),
                captured.diagnosticCount(),
                captured.runtimeErrorType(),
                captured.runtimeErrorMessage(),
                captured.stdout(),
                captured.stdoutBytes(),
                captured.stdoutTruncated(),
                captured.scriptResult(),
                captured.scriptResultParseError(),
                tracker.added(),
                tracker.modified(),
                tracker.removed(),
                restoration.changed(),
                restoration.restored(),
                restoration.error()
        );
    }

    private static RestorationOutcome restoreWorkspace(WorkspaceManager workspaceManager,
                                                       Workspace workspace) {
        boolean changed;
        try {
            changed = !workspaceManager.hasCurrentWorkspace()
                    || workspaceManager.getCurrent() != workspace;
        } catch (Exception ex) {
            return new RestorationOutcome(true, false, exceptionMessage(ex));
        }
        if (!changed) {
            return new RestorationOutcome(false, false, null);
        }
        try {
            workspaceManager.setCurrentIgnoringConditions(workspace);
            boolean restored = workspaceManager.hasCurrentWorkspace()
                    && workspaceManager.getCurrent() == workspace;
            return new RestorationOutcome(true, restored, null);
        } catch (Exception ex) {
            return new RestorationOutcome(true, false, exceptionMessage(ex));
        }
    }

    private static CapturedExecution captureAndRun(ScriptEngine scriptEngine,
                                                   Request request) throws Exception {
        synchronized (STDOUT_LOCK) {
            PrintStream original = System.out;
            BoundedOutputStream capture = new BoundedOutputStream(MAX_STDOUT_BYTES);
            CoreExecution core;
            try (PrintStream output = new PrintStream(capture, true, StandardCharsets.UTF_8)) {
                System.setOut(output);
                try {
                    core = compileAndRun(scriptEngine, request);
                } finally {
                    System.setOut(original);
                }
            } finally {
                System.setOut(original);
            }

            String stdout = capture.text();
            ParsedScriptResult parsed = parseScriptResult(stdout);
            return new CapturedExecution(
                    core.compileSuccess(),
                    core.executed(),
                    core.cancelled(),
                    core.diagnostics(),
                    core.diagnosticCount(),
                    core.runtimeErrorType(),
                    core.runtimeErrorMessage(),
                    stdout,
                    capture.totalBytes(),
                    capture.truncated(),
                    parsed.value(),
                    parsed.error()
            );
        }
    }

    private static CoreExecution compileAndRun(ScriptEngine scriptEngine,
                                               Request request) throws Exception {
        GenerateResult generated = await(scriptEngine.compile(request.script()), null);
        ToolCancellation.check();
        DiagnosticSummary diagnostics = diagnostics(generated.diagnostics());
        if (!generated.wasSuccess()) {
            return new CoreExecution(
                    false,
                    false,
                    false,
                    diagnostics.values(),
                    diagnostics.total(),
                    null,
                    null
            );
        }
        if (request.compileOnly()) {
            return new CoreExecution(
                    true,
                    false,
                    false,
                    diagnostics.values(),
                    diagnostics.total(),
                    null,
                    null
            );
        }

        ScriptResult result = await(scriptEngine.run(generated), generated::requestStop);
        ToolCancellation.check();
        Throwable runtimeFailure = result.getRuntimeThrowable();
        DiagnosticSummary runDiagnostics = diagnostics(result.getCompileDiagnostics());
        return new CoreExecution(
                !result.wasCompileFailure(),
                true,
                result.wasCancelled(),
                runDiagnostics.values(),
                runDiagnostics.total(),
                runtimeFailure == null ? null : runtimeFailure.getClass().getName(),
                runtimeFailure == null ? null : exceptionMessage(runtimeFailure)
        );
    }

    private static <T> T await(CompletableFuture<T> future, Runnable cancellation)
            throws Exception {
        boolean interrupted = false;
        boolean cancellationRequested = false;
        try {
            while (true) {
                try {
                    return future.get();
                } catch (InterruptedException ex) {
                    interrupted = true;
                    if (cancellation != null && !cancellationRequested) {
                        cancellationRequested = true;
                        try {
                            cancellation.run();
                        } catch (RuntimeException ignored) {
                            // Continue draining the actual script future. The Bridge will terminate
                            // the Worker if the operation cannot cooperatively stop in time.
                        }
                    }
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof Exception exception) {
                        throw exception;
                    }
                    if (cause instanceof Error error) {
                        throw error;
                    }
                    throw new IllegalStateException(cause);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static DiagnosticSummary diagnostics(List<CompilerDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return new DiagnosticSummary(List.of(), 0);
        }
        List<ScriptDiagnostic> values = diagnostics.stream()
                .limit(MAX_DIAGNOSTICS)
                .map(diagnostic -> new ScriptDiagnostic(
                        diagnostic.line(),
                        diagnostic.column(),
                        diagnostic.length(),
                        diagnostic.level().name().toLowerCase(Locale.ROOT),
                        bounded(diagnostic.message(), MAX_DIAGNOSTIC_MESSAGE)
                ))
                .toList();
        return new DiagnosticSummary(values, diagnostics.size());
    }

    private static ParsedScriptResult parseScriptResult(String stdout) {
        String candidate = null;
        for (String line : stdout.lines().toList()) {
            if (line.startsWith(RESULT_PREFIX)) {
                candidate = line.substring(RESULT_PREFIX.length()).strip();
            }
        }
        if (candidate == null) {
            return new ParsedScriptResult(null, null);
        }
        try {
            return new ParsedScriptResult(JsonParser.parseString(candidate), null);
        } catch (JsonParseException ex) {
            return new ParsedScriptResult(
                    null,
                    "last MCP_RESULT line is not valid JSON: " + exceptionMessage(ex)
            );
        }
    }

    private static JsonObject resultJson(Request request, ScriptExecution execution) {
        JsonObject structured = metadata(request);
        structured.addProperty("compiled", execution.compileSuccess());
        structured.addProperty("executed", execution.executed());
        structured.addProperty("cancelled", execution.cancelled());
        structured.addProperty("stdout", execution.stdout());
        structured.addProperty("stdout_bytes", execution.stdoutBytes());
        structured.addProperty("stdout_truncated", execution.stdoutTruncated());
        if (execution.scriptResult() != null) {
            structured.add("script_result", execution.scriptResult());
        }
        if (execution.scriptResultParseError() != null) {
            structured.addProperty(
                    "script_result_parse_error",
                    execution.scriptResultParseError()
            );
        }
        if (execution.runtimeErrorType() != null) {
            structured.addProperty("runtime_error_type", execution.runtimeErrorType());
            structured.addProperty("runtime_error_message", execution.runtimeErrorMessage());
        }

        JsonArray diagnostics = new JsonArray();
        execution.diagnostics().stream().limit(MAX_DIAGNOSTICS).forEach(diagnostic -> {
            JsonObject item = new JsonObject();
            item.addProperty("line", diagnostic.line());
            item.addProperty("column", diagnostic.column());
            item.addProperty("length", diagnostic.length());
            item.addProperty("level", diagnostic.level());
            item.addProperty("message", diagnostic.message());
            diagnostics.add(item);
        });
        structured.add("diagnostics", diagnostics);
        structured.addProperty("diagnostic_count", execution.diagnosticCount());
        structured.addProperty(
                "diagnostics_truncated",
                execution.diagnosticCount() > execution.diagnostics().size()
        );

        List<String> changed = changedClasses(execution);
        int start = Math.min(request.offset(), changed.size());
        int end = Math.min(start + request.limit(), changed.size());
        JsonArray page = new JsonArray();
        changed.subList(start, end).forEach(page::add);
        structured.add("changed_classes", page);
        structured.addProperty("changed_class_count", changed.size());
        structured.addProperty("returned", end - start);
        structured.addProperty("offset", request.offset());
        structured.addProperty("limit", request.limit());
        if (end < changed.size()) {
            structured.addProperty("next_offset", end);
        } else {
            structured.add("next_offset", JsonNull.INSTANCE);
        }
        addCategory(structured, "added", execution.addedClasses());
        addCategory(structured, "modified", execution.modifiedClasses());
        addCategory(structured, "removed", execution.removedClasses());
        structured.addProperty("change_observation_scope", "primary_jvm_bundle_events");
        structured.addProperty("workspace_changed", execution.workspaceChanged());
        structured.addProperty("workspace_restored", execution.workspaceRestored());
        if (execution.workspaceRestoreError() != null) {
            structured.addProperty(
                    "workspace_restore_error",
                    execution.workspaceRestoreError()
            );
        }
        structured.addProperty("atomic", false);
        structured.addProperty("save_required", true);
        return structured;
    }

    private static void addCategory(JsonObject structured,
                                    String prefix,
                                    List<String> classes) {
        JsonArray values = new JsonArray();
        classes.stream().limit(MAX_LIMIT).forEach(values::add);
        structured.add(prefix + "_classes", values);
        structured.addProperty(prefix + "_class_count", classes.size());
        structured.addProperty(prefix + "_classes_truncated", classes.size() > MAX_LIMIT);
    }

    private static List<String> changedClasses(ScriptExecution execution) {
        Set<String> changed = new LinkedHashSet<>();
        changed.addAll(execution.addedClasses());
        changed.addAll(execution.modifiedClasses());
        changed.addAll(execution.removedClasses());
        return changed.stream().sorted().toList();
    }

    private static JsonObject error(String message, Request request, ScriptExecution execution) {
        JsonObject structured = execution == null ? metadata(request) : resultJson(request, execution);
        structured.addProperty("error", message);
        structured.addProperty("atomic", false);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject metadata(Request request) {
        JsonObject structured = new JsonObject();
        structured.addProperty("script_sha256", sha256(request.script()));
        structured.addProperty(
                "script_form",
                ScriptSourceAugmentation.isClassScript(request.script())
                        ? "full_class"
                        : "shorthand"
        );
        structured.addProperty("compile_only", request.compileOnly());
        structured.addProperty(
                "acknowledge_full_worker_access",
                request.acknowledgeFullWorkerAccess()
        );
        return structured;
    }

    private static Request parseRequest(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String script = ToolArguments.requiredString(arguments, "script");
        boolean compileOnly = ToolArguments.bool(arguments, "compile_only", true);
        boolean acknowledge = ToolArguments.bool(
                arguments,
                "acknowledge_full_worker_access",
                false
        );
        int offset = ToolArguments.integer(arguments, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = ToolArguments.integer(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        if (script.length() > MAX_SCRIPT_LENGTH) {
            throw new IllegalArgumentException("script exceeds the maximum length");
        }
        if (!compileOnly && !acknowledge) {
            throw new IllegalArgumentException(
                    "acknowledge_full_worker_access must be true when compile_only is false"
            );
        }
        return new Request(script, compileOnly, acknowledge, offset, limit);
    }

    private static JsonObject createInputSchema() {
        JsonObject script = new JsonObject();
        script.addProperty("type", "string");
        script.addProperty("minLength", 1);
        script.addProperty("maxLength", MAX_SCRIPT_LENGTH);
        script.addProperty(
                "description",
                "Recaf Java shorthand or full class; read get_recaf_script_help."
        );
        JsonObject compileOnly = new JsonObject();
        compileOnly.addProperty("type", "boolean");
        compileOnly.addProperty("default", true);
        compileOnly.addProperty(
                "description",
                "Compile without running; keep true first."
        );
        JsonObject acknowledge = new JsonObject();
        acknowledge.addProperty("type", "boolean");
        acknowledge.addProperty("default", false);
        acknowledge.addProperty(
                "description",
                "Required to execute; grants Worker filesystem, network, process, JVM, "
                        + "and Recaf-service access."
        );
        JsonObject offset = integerProperty(0, Integer.MAX_VALUE, 0,
                "Offset into sorted changed class names.");
        JsonObject limit = integerProperty(1, MAX_LIMIT, DEFAULT_LIMIT,
                "Maximum changed class names returned in this page.");

        JsonObject properties = new JsonObject();
        properties.add("script", script);
        properties.add("compile_only", compileOnly);
        properties.add("acknowledge_full_worker_access", acknowledge);
        properties.add("offset", offset);
        properties.add("limit", limit);
        JsonArray required = new JsonArray();
        required.add("script");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty(
                "description",
                "Script execution is not sandboxed or atomic; compile-only is default and "
                        + "saving is explicit."
        );
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject integerProperty(int minimum,
                                              int maximum,
                                              int defaultValue,
                                              String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("minimum", minimum);
        property.addProperty("maximum", maximum);
        property.addProperty("default", defaultValue);
        property.addProperty("description", description);
        return property;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String exceptionMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return bounded(message.replace('\n', ' ').replace('\r', ' ').strip(), MAX_ERROR_MESSAGE);
    }

    private static String bounded(String value, int maximum) {
        if (value == null) {
            return "";
        }
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    @FunctionalInterface
    interface ScriptOperation {
        ScriptExecution run(Workspace workspace, Request request) throws Exception;
    }

    record Request(String script,
                   boolean compileOnly,
                   boolean acknowledgeFullWorkerAccess,
                   int offset,
                   int limit) {
    }

    record ScriptDiagnostic(int line,
                            int column,
                            int length,
                            String level,
                            String message) {
    }

    record ScriptExecution(boolean compileSuccess,
                           boolean executed,
                           boolean cancelled,
                           List<ScriptDiagnostic> diagnostics,
                           int diagnosticCount,
                           String runtimeErrorType,
                           String runtimeErrorMessage,
                           String stdout,
                           long stdoutBytes,
                           boolean stdoutTruncated,
                           JsonElement scriptResult,
                           String scriptResultParseError,
                           List<String> addedClasses,
                           List<String> modifiedClasses,
                           List<String> removedClasses,
                           boolean workspaceChanged,
                           boolean workspaceRestored,
                           String workspaceRestoreError) {
    }

    private record CoreExecution(boolean compileSuccess,
                                 boolean executed,
                                 boolean cancelled,
                                 List<ScriptDiagnostic> diagnostics,
                                 int diagnosticCount,
                                 String runtimeErrorType,
                                 String runtimeErrorMessage) {
    }

    private record CapturedExecution(boolean compileSuccess,
                                     boolean executed,
                                     boolean cancelled,
                                     List<ScriptDiagnostic> diagnostics,
                                     int diagnosticCount,
                                     String runtimeErrorType,
                                     String runtimeErrorMessage,
                                     String stdout,
                                     long stdoutBytes,
                                     boolean stdoutTruncated,
                                     JsonElement scriptResult,
                                     String scriptResultParseError) {
    }

    private record ParsedScriptResult(JsonElement value, String error) {
    }

    private record DiagnosticSummary(List<ScriptDiagnostic> values, int total) {
    }

    private record RestorationOutcome(boolean changed, boolean restored, String error) {
    }

    private static final class ChangeTracker implements ResourceJvmClassListener {
        private final JvmClassBundle bundle;
        private final Set<String> originalNames;
        private final Set<String> added = Collections.synchronizedSet(new HashSet<>());
        private final Set<String> modified = Collections.synchronizedSet(new HashSet<>());
        private final Set<String> removed = Collections.synchronizedSet(new HashSet<>());

        private ChangeTracker(JvmClassBundle bundle) {
            this.bundle = bundle;
            originalNames = Set.copyOf(bundle.keySet());
        }

        @Override
        public void onNewClass(WorkspaceResource resource,
                               JvmClassBundle currentBundle,
                               JvmClassInfo newValue) {
            if (currentBundle != bundle) {
                return;
            }
            String name = newValue.getName();
            removed.remove(name);
            if (originalNames.contains(name)) {
                modified.add(name);
            } else {
                added.add(name);
            }
        }

        @Override
        public void onUpdateClass(WorkspaceResource resource,
                                  JvmClassBundle currentBundle,
                                  JvmClassInfo oldValue,
                                  JvmClassInfo newValue) {
            if (currentBundle != bundle) {
                return;
            }
            if (!oldValue.getName().equals(newValue.getName())) {
                recordRemoval(oldValue.getName());
                onNewClass(resource, currentBundle, newValue);
            } else if (originalNames.contains(newValue.getName())) {
                removed.remove(newValue.getName());
                modified.add(newValue.getName());
            } else {
                added.add(newValue.getName());
            }
        }

        @Override
        public void onRemoveClass(WorkspaceResource resource,
                                  JvmClassBundle currentBundle,
                                  JvmClassInfo removedValue) {
            if (currentBundle == bundle) {
                recordRemoval(removedValue.getName());
            }
        }

        private void recordRemoval(String name) {
            modified.remove(name);
            if (originalNames.contains(name)) {
                removed.add(name);
            } else {
                added.remove(name);
            }
        }

        private List<String> added() {
            return sortedCopy(added);
        }

        private List<String> modified() {
            return sortedCopy(modified);
        }

        private List<String> removed() {
            return sortedCopy(removed);
        }

        private static List<String> sortedCopy(Set<String> values) {
            synchronized (values) {
                return values.stream().sorted().toList();
            }
        }
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final int maximum;
        private final ByteArrayOutputStream stored;
        private long totalBytes;

        private BoundedOutputStream(int maximum) {
            this.maximum = maximum;
            stored = new ByteArrayOutputStream(maximum);
        }

        @Override
        public synchronized void write(int value) {
            totalBytes++;
            if (stored.size() < maximum) {
                stored.write(value);
            }
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            totalBytes += length;
            int writable = Math.min(length, maximum - stored.size());
            if (writable > 0) {
                stored.write(bytes, offset, writable);
            }
        }

        private synchronized String text() {
            return stored.toString(StandardCharsets.UTF_8);
        }

        private synchronized long totalBytes() {
            return totalBytes;
        }

        private synchronized boolean truncated() {
            return totalBytes > stored.size();
        }
    }
}
