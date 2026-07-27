package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Returns JVM Jasm or read-only DEX disassembly for one exact primary method.
 */
public final class GetMethodAssemblyTool implements McpTool {
    private static final int MAX_OWNER_LENGTH = 1_000;
    private static final int MAX_MEMBER_NAME_LENGTH = 1_000;
    private static final int MAX_DESCRIPTOR_LENGTH = 4_000;
    private static final Set<String> ARGUMENTS = Set.of(
            "owner", "name", "descriptor", "scope", "dex_name",
            ConditionalReadSupport.ARGUMENT
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;
    private final MethodAssemblySupport.MethodAssembler assembler;

    public GetMethodAssemblyTool(WorkspaceManager workspaceManager,
                                 AssemblerPipelineManager pipelineManager) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                new MethodAssemblySupport.RecafMethodAssembler(pipelineManager)
        );
    }

    GetMethodAssemblyTool(BooleanSupplier hasWorkspace,
                          Supplier<Workspace> workspaceSupplier,
                          MethodAssemblySupport.MethodAssembler assembler) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
    }

    @Override
    public String name() {
        return "get_method_assembly";
    }

    @Override
    public String description() {
        return "Read JVM Jasm or read-only Android DEX instructions for one exact method.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        Request request = parseRequest(arguments);
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", request, List.of());
        }

        Workspace workspace = workspaceSupplier.get();
        List<ClassToolSupport.Entry> matches = ClassToolSupport.exact(
                workspace.getPrimaryResource(),
                request.selection(),
                request.owner()
        );
        if (matches.isEmpty()) {
            return error(
                    "owner class not found in requested primary scope",
                    request,
                    List.of()
            );
        }
        if (matches.size() > 1) {
            return error(
                    "owner class is ambiguous; specify dex_name",
                    request,
                    List.of(),
                    matches
            );
        }
        ClassToolSupport.Entry entry = matches.getFirst();
        if (entry.scope() == ClassToolSupport.Scope.ANDROID) {
            return executeAndroid(workspace, request, entry);
        }

        MethodAssemblySupport.Target target = MethodAssemblySupport.resolve(
                workspace,
                request.owner(),
                request.name(),
                request.descriptor()
        );
        if (target == null) {
            return error(
                    "method with exact name and descriptor not found in owner class",
                    request,
                    List.of()
            );
        }

        MethodAssemblySupport.OperationResult<String> disassembly;
        try {
            ToolCancellation.check();
            disassembly = assembler.disassemble(workspace, target);
        } catch (Exception ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error(
                    "method disassembly failed: "
                            + MethodAssemblySupport.exceptionMessage(ex),
                    request,
                    List.of()
            );
        }
        if (!disassembly.success()) {
            return error(
                    "Recaf could not disassemble the requested method",
                    request,
                    disassembly.diagnostics()
            );
        }
        String assembly = disassembly.value();
        if (assembly.length() > MethodAssemblySupport.MAX_ASSEMBLY_LENGTH) {
            return error(
                    "method assembly exceeds the response length limit",
                    request,
                    disassembly.diagnostics()
            );
        }

        JsonObject structured = metadata(request);
        ClassToolSupport.addIdentity(structured, entry);
        structured.addProperty("assembly", assembly);
        structured.addProperty(
                "assembly_sha256",
                MethodAssemblySupport.sha256(assembly)
        );
        structured.addProperty(
                "class_sha256",
                MethodAssemblySupport.sha256(target.classInfo().getBytecode())
        );
        structured.addProperty("access", target.method().getAccess());
        structured.addProperty("classfile_version", target.classInfo().getVersion());
        structured.add("dex_version", JsonNull.INSTANCE);
        structured.addProperty("assembly_format", "recaf_jasm_method");
        structured.addProperty("editable", true);
        structured.addProperty("patch_supported", true);
        structured.addProperty("patch_tool", "patch_instructions");
        MethodAssemblySupport.addDiagnostics(structured, disassembly.diagnostics());
        if (ConditionalReadSupport.matches(
                request.ifSha256(),
                structured.get("assembly_sha256").getAsString()
        )) {
            ConditionalReadSupport.markUnchanged(structured, "assembly");
            return McpTool.successResult("method assembly unchanged", structured);
        }
        return McpTool.successResult("read complete method assembly", structured);
    }

    private static JsonObject executeAndroid(Workspace workspace,
                                             Request request,
                                             ClassToolSupport.Entry entry) {
        AndroidInstructionSupport.MethodCode method = AndroidInstructionSupport.exact(
                entry,
                request.name(),
                request.descriptor()
        );
        if (method == null) {
            return error(
                    "method with exact name and descriptor not found in owner class",
                    request,
                    List.of()
            );
        }
        List<AndroidInstructionSupport.InstructionLine> instructions =
                AndroidInstructionSupport.instructions(method);
        String assembly = dexDisassembly(method, instructions);
        if (assembly.length() > MethodAssemblySupport.MAX_ASSEMBLY_LENGTH) {
            return error(
                    "method DEX disassembly exceeds the response length limit",
                    request,
                    List.of()
            );
        }
        int dexVersion = workspace.getPrimaryResource()
                .getAndroidClassBundles()
                .get(entry.dexName())
                .getVersion();
        JsonObject structured = metadata(request);
        ClassToolSupport.addIdentity(structured, entry);
        structured.addProperty("assembly", assembly);
        structured.addProperty(
                "assembly_sha256",
                MethodAssemblySupport.sha256(assembly)
        );
        structured.add("class_sha256", JsonNull.INSTANCE);
        structured.addProperty("access", method.access());
        structured.add("classfile_version", JsonNull.INSTANCE);
        structured.addProperty("dex_version", dexVersion);
        structured.addProperty("assembly_format", "android_dex_method_disassembly");
        structured.addProperty("editable", false);
        structured.addProperty("patch_supported", false);
        structured.add("patch_tool", JsonNull.INSTANCE);
        structured.addProperty("instruction_count", instructions.size());
        if (method.code() == null) {
            structured.add("registers", JsonNull.INSTANCE);
            structured.add("incoming_registers", JsonNull.INSTANCE);
            structured.add("outgoing_registers", JsonNull.INSTANCE);
        } else {
            structured.addProperty("registers", method.code().getRegisters());
            structured.addProperty("incoming_registers", method.code().getIn());
            structured.addProperty("outgoing_registers", method.code().getOut());
        }
        MethodAssemblySupport.addDiagnostics(structured, List.of());
        if (ConditionalReadSupport.matches(
                request.ifSha256(),
                structured.get("assembly_sha256").getAsString()
        )) {
            ConditionalReadSupport.markUnchanged(structured, "assembly");
            return McpTool.successResult("method assembly unchanged", structured);
        }
        return McpTool.successResult("read complete Android DEX method disassembly", structured);
    }

    private static String dexDisassembly(
            AndroidInstructionSupport.MethodCode method,
            List<AndroidInstructionSupport.InstructionLine> instructions) {
        StringBuilder text = new StringBuilder();
        text.append("# Read-only DEX method disassembly; patch_instructions is JVM-only\n")
                .append(".method ")
                .append(method.owner().className())
                .append('.')
                .append(method.name())
                .append(method.descriptor())
                .append('\n')
                .append(".access ")
                .append(method.access())
                .append('\n');
        if (method.code() == null) {
            text.append("# no code (abstract or native)\n");
        } else {
            text.append(".registers ").append(method.code().getRegisters()).append('\n')
                    .append(".ins ").append(method.code().getIn()).append('\n')
                    .append(".outs ").append(method.code().getOut()).append('\n');
            for (AndroidInstructionSupport.InstructionLine instruction : instructions) {
                text.append(String.format("%06x", instruction.offset()))
                        .append(": ")
                        .append(instruction.text())
                        .append('\n');
            }
        }
        return text.append(".end method\n").toString();
    }

    private static JsonObject error(String message,
                                    Request request,
                                    List<MethodAssemblySupport.Diagnostic> diagnostics) {
        return error(message, request, diagnostics, List.of());
    }

    private static JsonObject error(
            String message,
            Request request,
            List<MethodAssemblySupport.Diagnostic> diagnostics,
            List<ClassToolSupport.Entry> candidates) {
        JsonObject structured = metadata(request);
        if (!candidates.isEmpty()) {
            structured.add("candidates", ClassToolSupport.candidates(candidates));
        }
        structured.addProperty("error", message);
        MethodAssemblySupport.addDiagnostics(structured, diagnostics);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject metadata(Request request) {
        JsonObject structured = new JsonObject();
        structured.addProperty("owner", request.owner());
        structured.addProperty("name", request.name());
        structured.addProperty("descriptor", request.descriptor());
        structured.addProperty("requested_scope", request.selection().scope().value());
        if (request.selection().dexName() == null) {
            structured.add("dex_name", JsonNull.INSTANCE);
        } else {
            structured.addProperty("dex_name", request.selection().dexName());
        }
        return structured;
    }

    private static Request parseRequest(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(
                arguments,
                false
        );
        String owner = ToolArguments.requiredString(arguments, "owner");
        String name = ToolArguments.requiredString(arguments, "name");
        String descriptor = ToolArguments.requiredString(arguments, "descriptor");
        if (owner.length() > MAX_OWNER_LENGTH) {
            throw new IllegalArgumentException("owner is too long");
        }
        if (name.length() > MAX_MEMBER_NAME_LENGTH) {
            throw new IllegalArgumentException("name is too long");
        }
        if (descriptor.length() > MAX_DESCRIPTOR_LENGTH) {
            throw new IllegalArgumentException("descriptor is too long");
        }
        String ifSha256 = ConditionalReadSupport.parse(arguments);
        return new Request(owner, name, descriptor, selection, ifSha256);
    }

    private static JsonObject createInputSchema() {
        JsonObject owner = stringProperty(
                MAX_OWNER_LENGTH,
                "Exact primary JVM internal owner name."
        );
        JsonObject name = stringProperty(
                MAX_MEMBER_NAME_LENGTH,
                "Exact method name; constructors use <init> or <clinit>."
        );
        JsonObject descriptor = stringProperty(
                MAX_DESCRIPTOR_LENGTH,
                "Exact JVM method descriptor."
        );

        JsonObject properties = new JsonObject();
        properties.add("owner", owner);
        properties.add("name", name);
        properties.add("descriptor", descriptor);
        properties.add(
                ConditionalReadSupport.ARGUMENT,
                ConditionalReadSupport.schemaProperty()
        );
        ClassToolSupport.addScopeProperties(properties, false);
        JsonArray required = new JsonArray();
        required.add("owner");
        required.add("name");
        required.add("descriptor");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty(
                "description",
                "JVM: editable Jasm; Android: read-only DEX text with no APK/DEX patching."
        );
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject stringProperty(int maximum, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("minLength", 1);
        property.addProperty("maxLength", maximum);
        property.addProperty("description", description);
        return property;
    }

    record Request(String owner,
                   String name,
                   String descriptor,
                   ClassToolSupport.Selection selection,
                   String ifSha256) {
    }
}
