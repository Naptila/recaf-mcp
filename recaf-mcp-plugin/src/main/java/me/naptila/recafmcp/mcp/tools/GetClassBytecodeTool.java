package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.io.GrowingOutput;
import me.darknet.dex.tree.DexFile;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Returns bounded classfile or reconstructed single-class DEX bytes.
 */
public final class GetClassBytecodeTool implements McpTool {
    private static final int MAX_BYTECODE_SIZE = 16 * 1024 * 1024;
    private static final int DEFAULT_CHUNK_SIZE = 32 * 1024;
    private static final int MAX_CHUNK_SIZE = 1024 * 1024;
    private static final Set<String> ARGUMENTS = Set.of(
            "class_name", "scope", "dex_name", "offset", "limit",
            ConditionalReadSupport.ARGUMENT
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public GetClassBytecodeTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    GetClassBytecodeTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "get_class_bytecode";
    }

    @Override
    public String description() {
        return "Return paged Base64 JVM classfile or reconstructed single-class DEX bytes.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String className = ToolArguments.requiredString(arguments, "class_name");
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(arguments, false);
        String ifSha256 = ConditionalReadSupport.parse(arguments);
        int offset = ToolArguments.integer(arguments, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = ToolArguments.integer(
                arguments,
                "limit",
                DEFAULT_CHUNK_SIZE,
                1,
                MAX_CHUNK_SIZE
        );
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", className, selection, List.of());
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
                    selection,
                    matches
            );
        }
        if (matches.size() > 1) {
            return error(
                    "class name is ambiguous; specify dex_name",
                    className,
                    selection,
                    matches
            );
        }
        ClassToolSupport.Entry target = matches.getFirst();
        byte[] bytecode;
        JvmClassInfo jvmClassInfo = null;
        Integer dexVersion = null;
        try {
            if (target.scope() == ClassToolSupport.Scope.ANDROID) {
                AndroidClassInfo androidClassInfo = target.classInfo().asAndroidClass();
                dexVersion = workspace.getPrimaryResource()
                        .getAndroidClassBundles()
                        .get(target.dexName())
                        .getVersion();
                bytecode = singleClassDex(androidClassInfo, dexVersion);
            } else {
                jvmClassInfo = target.classInfo().asJvmClass();
                bytecode = jvmClassInfo.getBytecode();
            }
        } catch (Exception ex) {
            return error(
                    "class bytecode encoding failed: " + exceptionMessage(ex),
                    className,
                    selection,
                    List.of()
            );
        }
        if (bytecode.length > MAX_BYTECODE_SIZE) {
            return error(
                    "class bytecode exceeds the 16 MiB response limit",
                    className,
                    selection,
                    List.of()
            );
        }
        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", className);
        ClassToolSupport.addIdentity(structured, target);
        structured.addProperty("size_bytes", bytecode.length);
        String sha256 = sha256(bytecode);
        structured.addProperty("sha256", sha256);
        if (jvmClassInfo != null) {
            structured.addProperty("format", "jvm_classfile");
            structured.addProperty("representation", "original_classfile");
            structured.addProperty("original_bytes", true);
            structured.addProperty("classfile_version", jvmClassInfo.getVersion());
            structured.addProperty("java_version", jvmClassInfo.getVersion() - 44);
            structured.add("dex_version", JsonNull.INSTANCE);
        } else {
            structured.addProperty("format", "dex");
            structured.addProperty("representation", "reconstructed_single_class_dex");
            structured.addProperty("original_bytes", false);
            structured.add("classfile_version", JsonNull.INSTANCE);
            structured.add("java_version", JsonNull.INSTANCE);
            structured.addProperty("dex_version", dexVersion);
        }
        if (ConditionalReadSupport.matches(ifSha256, sha256)) {
            ConditionalReadSupport.markUnchanged(structured);
            return McpTool.successResult(
                    "bytecode unchanged for " + className,
                    structured
            );
        }
        int start = Math.min(offset, bytecode.length);
        int end = (int) Math.min((long) start + limit, bytecode.length);
        byte[] chunk = Arrays.copyOfRange(bytecode, start, end);
        structured.addProperty("encoding", "base64");
        structured.addProperty("bytecode_base64", Base64.getEncoder().encodeToString(chunk));
        structured.addProperty("offset", offset);
        structured.addProperty("limit", limit);
        structured.addProperty("returned_bytes", chunk.length);
        if (end < bytecode.length) {
            structured.addProperty("next_offset", end);
        } else {
            structured.add("next_offset", JsonNull.INSTANCE);
        }
        return McpTool.successResult(
                "returned " + chunk.length + " of " + bytecode.length + " bytes for " + className,
                structured
        );
    }

    private static JsonObject error(String message,
                                    String className,
                                    ClassToolSupport.Selection selection,
                                    List<ClassToolSupport.Entry> candidates) {
        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", className);
        structured.addProperty("requested_scope", selection.scope().value());
        if (selection.dexName() == null) {
            structured.add("dex_name", JsonNull.INSTANCE);
        } else {
            structured.addProperty("dex_name", selection.dexName());
        }
        if (!candidates.isEmpty()) {
            structured.add("candidates", ClassToolSupport.candidates(candidates));
        }
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    static byte[] singleClassDex(AndroidClassInfo classInfo,
                                         int dexVersion) throws IOException {
        DexFile file = new DexFile(
                dexVersion,
                List.of(classInfo.getBackingDefinition())
        );
        DexHeader header = DexFile.CODEC.unmap(file, new DexMapBuilder());
        GrowingOutput output = new GrowingOutput();
        DexHeader.CODEC.write(header, output);
        ByteBuffer buffer = output.buffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static String exceptionMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message.replace('\n', ' ').replace('\r', ' ');
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value)
            );
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static JsonObject createInputSchema() {
        JsonObject className = new JsonObject();
        className.addProperty("type", "string");
        className.addProperty("minLength", 1);
        className.addProperty("description", "Exact primary JVM or Android internal class name.");
        JsonObject properties = new JsonObject();
        properties.add("class_name", className);
        JsonObject offset = new JsonObject();
        offset.addProperty("type", "integer");
        offset.addProperty("minimum", 0);
        offset.addProperty("default", 0);
        properties.add("offset", offset);
        JsonObject limit = new JsonObject();
        limit.addProperty("type", "integer");
        limit.addProperty("minimum", 1);
        limit.addProperty("maximum", MAX_CHUNK_SIZE);
        limit.addProperty("default", DEFAULT_CHUNK_SIZE);
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
}
