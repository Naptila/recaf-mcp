package me.naptila.recafmcp.mcp.tools;

import me.naptila.recafmcp.mcp.android.AndroidJvmConverter;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Decompiles one exact JVM or Android class without UI services.
 */
public final class DecompileClassTool implements McpTool {
    private static final int DEFAULT_SOURCE_LIMIT = 32 * 1024;
    private static final int MAX_SOURCE_LIMIT = 500_000;
    private static final int DEFAULT_PER_CLASS_TIMEOUT_SEC = 25;
    private static final Set<String> ARGUMENTS = Set.of(
            "class_name", "decompiler", "scope", "dex_name", "resource",
            "offset", "limit", DecompilerToolSupport.PER_CLASS_TIMEOUT_ARGUMENT,
            ConditionalReadSupport.ARGUMENT
    );

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;
    private final TimedDecompileOperation decompileOperation;
    private final List<DecompilerToolSupport.Descriptor> decompilers;
    private final JsonObject inputSchema;

    public DecompileClassTool(WorkspaceManager workspaceManager,
                              DecompilerManager decompilerManager) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                (workspace, classInfo, requestedName, timeoutSec) -> {
                    JvmDecompiler decompiler = DecompilerToolSupport.select(
                            decompilerManager,
                            requestedName
                    );
                    if (decompiler == null) {
                        return null;
                    }
                    CompletableFuture<DecompilerToolSupport.IsolatedDecompilation> future =
                            DecompilerToolSupport.decompileWithMetadata(
                                    decompilerManager,
                                    decompiler,
                                    requestedName == null,
                                    workspace,
                                    classInfo
                            );
                    try {
                        DecompilerToolSupport.IsolatedDecompilation completed =
                                future.get(timeoutSec, TimeUnit.SECONDS);
                        return new Decompilation(
                                completed.name(),
                                completed.version(),
                                completed.result(),
                                completed.fallbackFrom()
                        );
                    } catch (TimeoutException ex) {
                        future.cancel(true);
                        throw new DecompileTimeoutException(
                                "decompilation timed out after " + timeoutSec + "s",
                                decompiler.getName(),
                                decompiler.getVersion()
                        );
                    } catch (InterruptedException ex) {
                        future.cancel(true);
                        Thread.currentThread().interrupt();
                        throw new CancellationException("tool operation was cancelled");
                    } catch (ExecutionException ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        ToolCancellation.propagateIfRequested(cause);
                        if (cause instanceof Exception exception) {
                            throw exception;
                        }
                        throw new IllegalStateException(cause);
                    }
                },
                DecompilerToolSupport.descriptors(decompilerManager)
        );
    }

    DecompileClassTool(BooleanSupplier hasWorkspace,
                       Supplier<Workspace> workspaceSupplier,
                       DecompileOperation decompileOperation) {
        this(hasWorkspace, workspaceSupplier, decompileOperation, List.of());
    }

    DecompileClassTool(BooleanSupplier hasWorkspace,
                       Supplier<Workspace> workspaceSupplier,
                       DecompileOperation decompileOperation,
                       List<DecompilerToolSupport.Descriptor> decompilers) {
        this(
                hasWorkspace,
                workspaceSupplier,
                (workspace, classInfo, requestedName, timeoutSec) -> {
                    Objects.requireNonNull(decompileOperation, "decompileOperation");
                    return decompileOperation.run(workspace, classInfo, requestedName);
                },
                decompilers
        );
    }

    DecompileClassTool(BooleanSupplier hasWorkspace,
                       Supplier<Workspace> workspaceSupplier,
                       TimedDecompileOperation decompileOperation,
                       List<DecompilerToolSupport.Descriptor> decompilers) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.decompileOperation = Objects.requireNonNull(decompileOperation, "decompileOperation");
        this.decompilers = List.copyOf(decompilers);
        inputSchema = createInputSchema(this.decompilers);
    }

    @Override
    public String name() {
        return "decompile_class";
    }

    @Override
    public String description() {
        return "Decompile one exact JVM or Android class.";
    }

    @Override
    public JsonObject inputSchema() {
        return inputSchema.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String className = ToolArguments.requiredString(arguments, "class_name");
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(arguments, false);
        String requestedDecompiler = ToolArguments.optionalString(arguments, "decompiler");
        String ifSha256 = ConditionalReadSupport.parse(arguments);
        int offset = ToolArguments.integer(arguments, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = ToolArguments.integer(
                arguments,
                "limit",
                DEFAULT_SOURCE_LIMIT,
                1,
                MAX_SOURCE_LIMIT
        );
        int perClassTimeoutSec = ToolArguments.integer(
                arguments,
                DecompilerToolSupport.PER_CLASS_TIMEOUT_ARGUMENT,
                DEFAULT_PER_CLASS_TIMEOUT_SEC,
                1,
                DecompilerToolSupport.MAX_PER_CLASS_TIMEOUT_SEC
        );
        if (!hasWorkspace.getAsBoolean()) {
            return error(
                    "workspace not open",
                    null,
                    className,
                    null,
                    requestedDecompiler,
                    selection,
                    List.of(),
                    null,
                    false
            );
        }

        Workspace workspace = workspaceSupplier.get();
        JsonObject selectionError = ClassToolSupport.validateSelection(
                workspace.getPrimaryResource(),
                selection
        );
        if (selectionError != null) {
            JsonObject structured = selectionError.getAsJsonObject("structuredContent");
            structured.addProperty("class_name", className);
            if (requestedDecompiler == null) {
                structured.add("requested_decompiler", JsonNull.INSTANCE);
            } else {
                structured.addProperty("requested_decompiler", requestedDecompiler);
            }
            DecompilerToolSupport.addAvailability(structured, decompilers);
            return selectionError;
        }
        List<ClassToolSupport.Entry> matches = ClassToolSupport.exact(
                workspace.getPrimaryResource(),
                selection,
                className
        );
        if (matches.isEmpty()) {
            JsonObject err = error(
                    "class not found in requested scope",
                    workspace,
                    className,
                    null,
                    requestedDecompiler,
                    selection,
                    matches,
                    null,
                    false
            );
            ClassToolSupport.addDidYouMeanClasses(
                    err.getAsJsonObject("structuredContent"),
                    workspace.getPrimaryResource(),
                    selection,
                    className
            );
            return err;
        }
        if (matches.size() > 1) {
            return error(
                    "class name is ambiguous; specify dex_name",
                    workspace,
                    className,
                    null,
                    requestedDecompiler,
                    selection,
                    matches,
                    null,
                    false
            );
        }
        ClassToolSupport.Entry target = matches.getFirst();
        JvmClassInfo classInfo;
        Boolean conversionVerified = null;
        if (target.scope() == ClassToolSupport.Scope.ANDROID) {
            try {
                AndroidJvmConverter.ConversionOutcome outcome =
                        AndroidJvmConverter.convertForDecompilation(
                                target.classInfo().asAndroidClass()
                        );
                classInfo = outcome.classInfo();
                conversionVerified = outcome.verified();
            } catch (Exception ex) {
                ToolCancellation.propagateIfRequested(ex);
                return dalvikJasmFallbackOrError(
                        workspace,
                        className,
                        target,
                        requestedDecompiler,
                        selection,
                        ifSha256,
                        offset,
                        limit,
                        "class conversion failed: " + exceptionMessage(ex),
                        false
                );
            }
        } else {
            classInfo = target.classInfo().asJvmClass();
        }

        Decompilation decompilation;
        try {
            decompilation = decompileOperation.run(
                    workspace,
                    classInfo,
                    requestedDecompiler,
                    perClassTimeoutSec
            );
        } catch (DecompileTimeoutException ex) {
            if (target.scope() == ClassToolSupport.Scope.ANDROID
                    && "fallback".equalsIgnoreCase(ex.decompilerName())) {
                return dalvikJasmFallbackOrError(
                        workspace,
                        className,
                        target,
                        requestedDecompiler,
                        selection,
                        ifSha256,
                        offset,
                        limit,
                        ex.getMessage(),
                        Boolean.TRUE.equals(conversionVerified)
                );
            }
            return error(
                    ex.getMessage(),
                    workspace,
                    className,
                    target,
                    null,
                    requestedDecompiler,
                    selection,
                    List.of(),
                    ex.decompilerName(),
                    true,
                    conversionVerified
            );
        } catch (Exception ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error(
                    "decompilation failed: " + exceptionMessage(ex),
                    workspace,
                    className,
                    target,
                    requestedDecompiler,
                    selection,
                    List.of(),
                    requestedDecompiler,
                    true
            );
        }
        if (decompilation == null) {
            return error(
                    "requested JVM decompiler is not available",
                    workspace,
                    className,
                    target,
                    requestedDecompiler,
                    selection,
                    List.of(),
                    null,
                    false
            );
        }

        DecompileResult result = decompilation.result();
        String resultType = result.getType().name().toLowerCase(Locale.ROOT);
        String source = result.getText();
        if (result.getType() != DecompileResult.ResultType.SUCCESS || source == null) {
            String message = result.getException() == null
                    ? "decompiler returned " + resultType
                    : "decompiler failed: " + exceptionMessage(result.getException());
            if (target.scope() == ClassToolSupport.Scope.ANDROID
                    && "fallback".equalsIgnoreCase(decompilation.name())) {
                return dalvikJasmFallbackOrError(
                        workspace,
                        className,
                        target,
                        requestedDecompiler,
                        selection,
                        ifSha256,
                        offset,
                        limit,
                        message,
                        Boolean.TRUE.equals(conversionVerified)
                );
            }
            return error(
                    message,
                    workspace,
                    className,
                    target,
                    decompilation,
                    requestedDecompiler,
                    selection,
                    List.of(),
                    decompilation.name(),
                    true,
                    conversionVerified
            );
        }

        JsonObject structuredContent = metadata(target, decompilation, conversionVerified);
        if (requestedDecompiler != null) {
            structuredContent.addProperty("requested_decompiler", requestedDecompiler);
        } else if (decompilation.fallbackFrom() != null) {
            structuredContent.addProperty("requested_decompiler", decompilation.fallbackFrom());
            structuredContent.addProperty("decompiler_fallback_from", decompilation.fallbackFrom());
        }
        return buildSourceResult(
                target.className(),
                decompilation.name(),
                source,
                ifSha256,
                offset,
                limit,
                structuredContent
        );
    }

    private JsonObject dalvikJasmFallbackOrError(Workspace workspace,
                                                 String className,
                                                 ClassToolSupport.Entry target,
                                                 String requestedDecompiler,
                                                 ClassToolSupport.Selection selection,
                                                 String ifSha256,
                                                 int offset,
                                                 int limit,
                                                 String fallbackReason,
                                                 boolean conversionVerified) {
        try {
            String jasm = AndroidAssemblySupport.disassembleClass(
                    target.classInfo().asAndroidClass()
            );
            JsonObject structuredContent = new JsonObject();
            structuredContent.addProperty("class_name", target.className());
            ClassToolSupport.addIdentity(structuredContent, target);
            structuredContent.addProperty("decompilation_input", "dalvik_jasm_fallback");
            structuredContent.addProperty("conversion_verified", conversionVerified);
            structuredContent.addProperty("decompiler", "DalvikJasmFallback");
            structuredContent.addProperty("decompiler_version", "recaf-jasm");
            structuredContent.addProperty("result_type", "fallback");
            structuredContent.addProperty("fallback_reason", fallbackReason);
            return buildSourceResult(
                    target.className(),
                    "DalvikJasmFallback",
                    jasm,
                    ifSha256,
                    offset,
                    limit,
                    structuredContent
            );
        } catch (Exception fallbackEx) {
            ToolCancellation.propagateIfRequested(fallbackEx);
            return error(
                    fallbackReason + "; Dalvik Jasm fallback also failed: "
                            + exceptionMessage(fallbackEx),
                    workspace,
                    className,
                    target,
                    requestedDecompiler,
                    selection,
                    List.of(),
                    requestedDecompiler,
                    true
            );
        }
    }

    private static JsonObject buildSourceResult(String className,
                                                String decompilerName,
                                                String source,
                                                String ifSha256,
                                                int offset,
                                                int limit,
                                                JsonObject structuredContent) {
        String sourceSha256 = MethodAssemblySupport.sha256(source);
        structuredContent.addProperty("source_length", source.length());
        structuredContent.addProperty("source_sha256", sourceSha256);
        if (ConditionalReadSupport.matches(ifSha256, sourceSha256)) {
            ConditionalReadSupport.markUnchanged(structuredContent);
            return McpTool.successResult(
                    "source unchanged for " + className,
                    structuredContent
            );
        }
        int start = Math.min(offset, source.length());
        int end = (int) Math.min((long) start + limit, source.length());
        structuredContent.addProperty("source", source.substring(start, end));
        structuredContent.addProperty("offset", offset);
        structuredContent.addProperty("limit", limit);
        structuredContent.addProperty("returned_chars", end - start);
        if (end < source.length()) {
            structuredContent.addProperty("next_offset", end);
        } else {
            structuredContent.add("next_offset", JsonNull.INSTANCE);
        }
        return McpTool.successResult(
                "decompiled " + className + " with " + decompilerName,
                structuredContent
        );
    }

    private JsonObject error(String message,
                             Workspace workspace,
                             String className,
                             ClassToolSupport.Entry target,
                             String requestedDecompiler,
                             ClassToolSupport.Selection selection,
                             List<ClassToolSupport.Entry> candidates,
                             String failedDecompilerName,
                             boolean addRetrySuggestions) {
        return error(
                message,
                workspace,
                className,
                target,
                null,
                requestedDecompiler,
                selection,
                candidates,
                failedDecompilerName,
                addRetrySuggestions,
                null
        );
    }

    private JsonObject error(String message,
                             Workspace workspace,
                             String className,
                             ClassToolSupport.Entry target,
                             Decompilation decompilation,
                             String requestedDecompiler,
                             ClassToolSupport.Selection selection,
                             List<ClassToolSupport.Entry> candidates,
                             String failedDecompilerName,
                             boolean addRetrySuggestions,
                             Boolean conversionVerified) {
        JsonObject structuredContent = decompilation == null || target == null
                ? new JsonObject()
                : metadata(target, decompilation, conversionVerified);
        structuredContent.addProperty("class_name", className);
        if (target == null) {
            structuredContent.addProperty("requested_scope", selection.scope().value());
            if (selection.dexName() == null) {
                structuredContent.add("dex_name", JsonNull.INSTANCE);
            } else {
                structuredContent.addProperty("dex_name", selection.dexName());
            }
            ClassToolSupport.addSelectionMetadata(structuredContent, selection);
            if (workspace != null && candidates.isEmpty()) {
                ClassToolSupport.addScopeDiagnostics(
                        structuredContent,
                        workspace.getPrimaryResource(),
                        selection
                );
            }
        } else {
            ClassToolSupport.addIdentity(structuredContent, target);
            if (conversionVerified != null && !structuredContent.has("conversion_verified")) {
                structuredContent.addProperty("conversion_verified", conversionVerified);
            }
        }
        if (requestedDecompiler == null) {
            structuredContent.add("requested_decompiler", JsonNull.INSTANCE);
        } else {
            structuredContent.addProperty("requested_decompiler", requestedDecompiler);
        }
        if (!candidates.isEmpty()) {
            structuredContent.add("candidates", ClassToolSupport.candidates(candidates));
        }
        DecompilerToolSupport.addAvailability(structuredContent, decompilers);
        if (addRetrySuggestions && target != null) {
            structuredContent.add(
                    "retry_suggestions",
                    retrySuggestions(target, failedDecompilerName)
            );
        }
        structuredContent.addProperty("error", message);
        return McpTool.errorResult(message, structuredContent);
    }

    private JsonArray retrySuggestions(ClassToolSupport.Entry target,
                                       String failedDecompilerName) {
        JsonArray suggestions = new JsonArray();
        List<String> preferredOrder = List.of("CFR", "Procyon", "Vineflower", "Fallback");
        for (String preferred : preferredOrder) {
            if (failedDecompilerName != null && preferred.equalsIgnoreCase(failedDecompilerName)) {
                continue;
            }
            boolean installed = decompilers.stream()
                    .anyMatch(item -> item.name().equalsIgnoreCase(preferred));
            if (!installed) {
                continue;
            }
            JsonObject args = new JsonObject();
            args.addProperty("class_name", target.className());
            args.addProperty("decompiler", preferred);
            args.addProperty("scope", target.scope().value());
            if (target.dexName() != null) {
                args.addProperty("dex_name", target.dexName());
            }
            if (target.resourcePath() != null) {
                args.addProperty("resource", target.resourcePath());
            }
            JsonObject suggestion = new JsonObject();
            suggestion.addProperty("tool", "decompile_class");
            suggestion.addProperty("decompiler", preferred);
            suggestion.add("arguments", args);
            suggestions.add(suggestion);
        }
        for (DecompilerToolSupport.Descriptor descriptor : decompilers) {
            String candidate = descriptor.name();
            if (failedDecompilerName != null && candidate.equalsIgnoreCase(failedDecompilerName)) {
                continue;
            }
            boolean alreadyAdded = preferredOrder.stream()
                    .anyMatch(item -> item.equalsIgnoreCase(candidate));
            if (alreadyAdded) {
                continue;
            }
            JsonObject args = new JsonObject();
            args.addProperty("class_name", target.className());
            args.addProperty("decompiler", candidate);
            args.addProperty("scope", target.scope().value());
            if (target.dexName() != null) {
                args.addProperty("dex_name", target.dexName());
            }
            if (target.resourcePath() != null) {
                args.addProperty("resource", target.resourcePath());
            }
            JsonObject suggestion = new JsonObject();
            suggestion.addProperty("tool", "decompile_class");
            suggestion.addProperty("decompiler", candidate);
            suggestion.add("arguments", args);
            suggestions.add(suggestion);
        }
        if (target.scope() == ClassToolSupport.Scope.ANDROID) {
            JsonObject assemblySuggestion = new JsonObject();
            assemblySuggestion.addProperty("tool", "get_method_assembly");
            assemblySuggestion.addProperty(
                    "hint",
                    "Inspect native Dalvik Jasm directly with get_method_assembly (scope=\"android\")."
            );
            suggestions.add(assemblySuggestion);
        }
        return suggestions;
    }

    private static JsonObject metadata(ClassToolSupport.Entry target,
                                       Decompilation decompilation,
                                       Boolean conversionVerified) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("class_name", target.className());
        ClassToolSupport.addIdentity(metadata, target);
        metadata.addProperty(
                "decompilation_input",
                target.scope() == ClassToolSupport.Scope.ANDROID
                        ? "recaf_android_to_jvm"
                        : "jvm_classfile"
        );
        if (conversionVerified != null) {
            metadata.addProperty("conversion_verified", conversionVerified);
        }
        metadata.addProperty("decompiler", decompilation.name());
        metadata.addProperty("decompiler_version", decompilation.version());
        metadata.addProperty(
                "result_type",
                decompilation.result().getType().name().toLowerCase(Locale.ROOT)
        );
        return metadata;
    }

    private static String exceptionMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        String singleLine = message.replace('\n', ' ').replace('\r', ' ').strip();
        return singleLine.length() <= 512 ? singleLine : singleLine.substring(0, 512);
    }

    private static JsonObject createInputSchema(
            List<DecompilerToolSupport.Descriptor> decompilers) {
        JsonObject properties = new JsonObject();
        JsonObject className = new JsonObject();
        className.addProperty("type", "string");
        className.addProperty("minLength", 1);
        className.addProperty("description", "Exact JVM or Android internal class name, such as a/b/C.");
        properties.add("class_name", className);
        properties.add(
                "decompiler",
                DecompilerToolSupport.inputSchemaProperty(decompilers)
        );
        JsonObject offset = new JsonObject();
        offset.addProperty("type", "integer");
        offset.addProperty("minimum", 0);
        offset.addProperty("default", 0);
        properties.add("offset", offset);
        JsonObject limit = new JsonObject();
        limit.addProperty("type", "integer");
        limit.addProperty("minimum", 1);
        limit.addProperty("maximum", MAX_SOURCE_LIMIT);
        limit.addProperty("default", DEFAULT_SOURCE_LIMIT);
        properties.add("limit", limit);
        properties.add(
                ConditionalReadSupport.ARGUMENT,
                ConditionalReadSupport.schemaProperty()
        );
        properties.add(
                DecompilerToolSupport.PER_CLASS_TIMEOUT_ARGUMENT,
                DecompilerToolSupport.timeoutSchemaProperty(DEFAULT_PER_CLASS_TIMEOUT_SEC)
        );
        ClassToolSupport.addScopeProperties(properties, false, true, false);

        JsonArray required = new JsonArray();
        required.add("class_name");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    @FunctionalInterface
    interface DecompileOperation {
        Decompilation run(Workspace workspace,
                          JvmClassInfo classInfo,
                          String requestedDecompiler) throws Exception;
    }

    @FunctionalInterface
    interface TimedDecompileOperation {
        Decompilation run(Workspace workspace,
                          JvmClassInfo classInfo,
                          String requestedDecompiler,
                          int perClassTimeoutSec) throws Exception;
    }

    static final class DecompileTimeoutException extends Exception {
        private final String decompilerName;
        private final String decompilerVersion;

        DecompileTimeoutException(String message, String decompilerName, String decompilerVersion) {
            super(message);
            this.decompilerName = decompilerName;
            this.decompilerVersion = decompilerVersion;
        }

        String decompilerName() {
            return decompilerName;
        }

        String decompilerVersion() {
            return decompilerVersion;
        }
    }

    record Decompilation(String name,
                         String version,
                         DecompileResult result,
                         String fallbackFrom) {
        Decompilation {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(result, "result");
        }

        Decompilation(String name, String version, DecompileResult result) {
            this(name, version, result, null);
        }
    }
}
