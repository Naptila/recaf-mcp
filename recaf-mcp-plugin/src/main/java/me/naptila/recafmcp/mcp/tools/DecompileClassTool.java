package me.naptila.recafmcp.mcp.tools;

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
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Decompiles one exact primary-resource JVM or Android class without UI services.
 */
public final class DecompileClassTool implements McpTool {
    private static final int DEFAULT_SOURCE_LIMIT = 32 * 1024;
    private static final int MAX_SOURCE_LIMIT = 500_000;
    private static final Set<String> ARGUMENTS = Set.of(
            "class_name", "decompiler", "scope", "dex_name", "offset", "limit",
            ConditionalReadSupport.ARGUMENT
    );

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;
    private final DecompileOperation decompileOperation;
    private final List<DecompilerToolSupport.Descriptor> decompilers;
    private final JsonObject inputSchema;

    public DecompileClassTool(WorkspaceManager workspaceManager,
                              DecompilerManager decompilerManager) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                (workspace, classInfo, requestedName) -> {
                    JvmDecompiler decompiler = DecompilerToolSupport.select(
                            decompilerManager,
                            requestedName
                    );
                    if (decompiler == null) {
                        return null;
                    }
                    return new Decompilation(
                            decompiler.getName(),
                            decompiler.getVersion(),
                            decompiler.decompile(workspace, classInfo)
                    );
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
        if (!hasWorkspace.getAsBoolean()) {
            return error(
                    "workspace not open",
                    className,
                    null,
                    requestedDecompiler,
                    selection,
                    List.of()
            );
        }

        Workspace workspace = workspaceSupplier.get();
        List<ClassToolSupport.Entry> matches = ClassToolSupport.exact(
                workspace.getPrimaryResource(),
                selection,
                className
        );
        if (matches.isEmpty()) {
            return error(
                    "class not found in requested primary scope",
                    className,
                    null,
                    requestedDecompiler,
                    selection,
                    matches
            );
        }
        if (matches.size() > 1) {
            return error(
                    "class name is ambiguous; specify dex_name",
                    className,
                    null,
                    requestedDecompiler,
                    selection,
                    matches
            );
        }
        ClassToolSupport.Entry target = matches.getFirst();
        JvmClassInfo classInfo;
        try {
            if (target.scope() == ClassToolSupport.Scope.ANDROID) {
                if (!target.classInfo().asAndroidClass().canMapToJvmClass()) {
                    return error(
                            "Android class cannot be mapped to JVM bytecode for decompilation",
                            className,
                            target,
                            requestedDecompiler,
                            selection,
                            List.of()
                    );
                }
                classInfo = target.classInfo().asAndroidClass().asJvmClass();
            } else {
                classInfo = target.classInfo().asJvmClass();
            }
        } catch (Exception ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error(
                    "class conversion failed: " + exceptionMessage(ex),
                    className,
                    target,
                    requestedDecompiler,
                    selection,
                    List.of()
            );
        }

        Decompilation decompilation;
        try {
            decompilation = decompileOperation.run(workspace, classInfo, requestedDecompiler);
        } catch (Exception ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error(
                    "decompilation failed: " + exceptionMessage(ex),
                    className,
                    target,
                    requestedDecompiler,
                    selection,
                    List.of()
            );
        }
        if (decompilation == null) {
            return error(
                    "requested JVM decompiler is not available",
                    className,
                    target,
                    requestedDecompiler,
                    selection,
                    List.of()
            );
        }

        DecompileResult result = decompilation.result();
        String resultType = result.getType().name().toLowerCase(Locale.ROOT);
        String source = result.getText();
        if (result.getType() != DecompileResult.ResultType.SUCCESS || source == null) {
            String message = result.getException() == null
                    ? "decompiler returned " + resultType
                    : "decompiler failed: " + exceptionMessage(result.getException());
            return error(
                    message,
                    className,
                    target,
                    decompilation,
                    requestedDecompiler,
                    selection,
                    List.of()
            );
        }

        JsonObject structuredContent = metadata(target, decompilation);
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
                "decompiled " + className + " with " + decompilation.name(),
                structuredContent
        );
    }

    private JsonObject error(String message,
                             String className,
                             ClassToolSupport.Entry target,
                             String requestedDecompiler,
                             ClassToolSupport.Selection selection,
                             List<ClassToolSupport.Entry> candidates) {
        return error(
                message,
                className,
                target,
                null,
                requestedDecompiler,
                selection,
                candidates
        );
    }

    private JsonObject error(String message,
                             String className,
                             ClassToolSupport.Entry target,
                             Decompilation decompilation,
                             String requestedDecompiler,
                             ClassToolSupport.Selection selection,
                             List<ClassToolSupport.Entry> candidates) {
        JsonObject structuredContent = decompilation == null || target == null
                ? new JsonObject()
                : metadata(target, decompilation);
        structuredContent.addProperty("class_name", className);
        if (target == null) {
            structuredContent.addProperty("requested_scope", selection.scope().value());
            if (selection.dexName() == null) {
                structuredContent.add("dex_name", JsonNull.INSTANCE);
            } else {
                structuredContent.addProperty("dex_name", selection.dexName());
            }
        } else {
            ClassToolSupport.addIdentity(structuredContent, target);
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
        structuredContent.addProperty("error", message);
        return McpTool.errorResult(message, structuredContent);
    }

    private static JsonObject metadata(ClassToolSupport.Entry target,
                                       Decompilation decompilation) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("class_name", target.className());
        ClassToolSupport.addIdentity(metadata, target);
        metadata.addProperty(
                "decompilation_input",
                target.scope() == ClassToolSupport.Scope.ANDROID
                        ? "recaf_android_to_jvm"
                        : "jvm_classfile"
        );
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
        ClassToolSupport.addScopeProperties(properties, false);

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
                          String requestedDecompiler);
    }

    record Decompilation(String name, String version, DecompileResult result) {
        Decompilation {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(result, "result");
        }
    }
}
