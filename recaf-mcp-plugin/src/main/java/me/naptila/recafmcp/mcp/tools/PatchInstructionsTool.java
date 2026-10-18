package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Assembles and atomically replaces one exact primary JVM or Dalvik method body.
 */
public final class PatchInstructionsTool implements McpTool {
    private static final int SHA256_LENGTH = 64;
    private static final Set<String> ARGUMENTS = Set.of(
            "owner",
            "name",
            "descriptor",
            "assembly",
            "expected_assembly_sha256",
            "dry_run", "scope", "dex_name"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;
    private final MethodAssemblySupport.MethodAssembler assembler;
    private final AssemblerPipelineManager androidPipelineManager;

    public PatchInstructionsTool(WorkspaceManager workspaceManager,
                                 AssemblerPipelineManager pipelineManager) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                new MethodAssemblySupport.RecafMethodAssembler(pipelineManager),
                pipelineManager
        );
    }

    PatchInstructionsTool(BooleanSupplier hasWorkspace,
                          Supplier<Workspace> workspaceSupplier,
                          MethodAssemblySupport.MethodAssembler assembler) {
        this(hasWorkspace, workspaceSupplier, assembler, null);
    }

    PatchInstructionsTool(BooleanSupplier hasWorkspace,
                          Supplier<Workspace> workspaceSupplier,
                          MethodAssemblySupport.MethodAssembler assembler,
                          AssemblerPipelineManager androidPipelineManager) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.androidPipelineManager = androidPipelineManager;
    }

    @Override
    public String name() {
        return "patch_instructions";
    }

    @Override
    public String description() {
        return "Validate and atomically replace one method from complete Recaf assembler text.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        Request request = parseRequest(arguments);
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", request, List.of(), null);
        }

        Workspace workspace = workspaceSupplier.get();
        if (request.selection().scope() == ClassToolSupport.Scope.ANDROID) {
            return executeAndroid(workspace, request);
        }
        MethodAssemblySupport.Target target = MethodAssemblySupport.resolve(
                workspace,
                request.owner(),
                request.name(),
                request.descriptor()
        );
        if (target == null) {
            return error(
                    "method with exact owner, name, and descriptor not found in primary JVM resource",
                    request,
                    List.of(),
                    null
            );
        }

        List<MethodAssemblySupport.Diagnostic> diagnostics = new ArrayList<>();
        MethodAssemblySupport.OperationResult<String> disassembly;
        try {
            ToolCancellation.check();
            disassembly = assembler.disassemble(workspace, target);
        } catch (Exception ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error(
                    "current method disassembly failed: "
                            + MethodAssemblySupport.exceptionMessage(ex),
                    request,
                    diagnostics,
                    null
            );
        }
        diagnostics.addAll(disassembly.diagnostics());
        if (!disassembly.success()) {
            return error(
                    "Recaf could not disassemble the current method",
                    request,
                    diagnostics,
                    null
            );
        }

        String currentAssemblyHash = MethodAssemblySupport.sha256(disassembly.value());
        if (!currentAssemblyHash.equals(request.expectedAssemblySha256())) {
            return error(
                    "method assembly changed; read it again with get_method_assembly",
                    request,
                    diagnostics,
                    currentAssemblyHash
            );
        }

        MethodAssemblySupport.OperationResult<JvmClassInfo> assemblyResult;
        try {
            ToolCancellation.check();
            assemblyResult = assembler.assemble(workspace, target, request.assembly());
        } catch (Exception ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error(
                    "method assembly failed: " + MethodAssemblySupport.exceptionMessage(ex),
                    request,
                    diagnostics,
                    currentAssemblyHash
            );
        }
        diagnostics.addAll(assemblyResult.diagnostics());
        if (!assemblyResult.success()) {
            return error(
                    "Recaf rejected the submitted method assembly",
                    request,
                    diagnostics,
                    currentAssemblyHash
            );
        }

        JvmClassInfo replacement = assemblyResult.value();
        String validationError = MethodAssemblySupport.validateReplacement(target, replacement);
        if (validationError != null) {
            return error(
                    validationError,
                    request,
                    diagnostics,
                    currentAssemblyHash
            );
        }

        String originalClassHash = MethodAssemblySupport.sha256(
                target.classInfo().getBytecode()
        );
        String patchedClassHash = MethodAssemblySupport.sha256(replacement.getBytecode());
        boolean changed = !originalClassHash.equals(patchedClassHash);
        ToolCancellation.check();
        JvmClassInfo latest = target.bundle().get(request.owner());
        if (latest == null || !originalClassHash.equals(
                MethodAssemblySupport.sha256(latest.getBytecode()))) {
            return error(
                    "owner class changed while the patch was being prepared; retry from "
                            + "get_method_assembly",
                    request,
                    diagnostics,
                    currentAssemblyHash
            );
        }

        boolean applied = false;
        if (!request.dryRun() && changed) {
            target.bundle().put(replacement);
            applied = true;
        }

        JsonObject structured = metadata(request);
        structured.addProperty("applied", applied);
        structured.addProperty("changed", changed);
        structured.addProperty("current_assembly_sha256", currentAssemblyHash);
        structured.addProperty(
                "submitted_assembly_sha256",
                MethodAssemblySupport.sha256(request.assembly())
        );
        structured.addProperty("original_class_sha256", originalClassHash);
        structured.addProperty("patched_class_sha256", patchedClassHash);
        structured.addProperty("classfile_version", replacement.getVersion());
        structured.addProperty("assembly_format", "recaf_jasm_method");
        structured.addProperty("scope", "primary_jvm");
        MethodAssemblySupport.addDiagnostics(structured, diagnostics);
        String message;
        if (request.dryRun()) {
            message = changed
                    ? "method assembly validated; dry-run made no workspace changes"
                    : "method assembly validated as a no-op; dry-run made no workspace changes";
        } else if (applied) {
            message = "assembled and replaced one JVM method";
        } else {
            message = "method assembly validated as a no-op";
        }
        return McpTool.successResult(message, structured);
    }

    private JsonObject executeAndroid(Workspace workspace, Request request) {
        List<MethodAssemblySupport.Diagnostic> diagnostics = new ArrayList<>();
        String currentHash = null;
        try {
            ToolCancellation.check();
            var matches = ClassToolSupport.exact(workspace.getPrimaryResource(),
                    request.selection(), request.owner());
            if (matches.size() != 1) {
                JsonObject result = error(matches.isEmpty() ? "Android owner not found"
                        : "Android owner is ambiguous; specify dex_name", request, diagnostics, null);
                result.getAsJsonObject("structuredContent").add("candidates", ClassToolSupport.candidates(matches));
                return result;
            }
            var entry = matches.getFirst();
            var original = entry.classInfo().asAndroidClass();
            if (original.getDeclaredMethod(request.name(), request.descriptor()) == null) {
                return error("method not found in Android owner", request, diagnostics, null);
            }
            if (androidPipelineManager == null) {
                return error("Dalvik assembler is unavailable", request, diagnostics, null);
            }
            var bundle = workspace.getPrimaryResource().getAndroidClassBundles().get(entry.dexName());
            int version = bundle.getVersion();
            String assembly = AndroidAssemblySupport.disassemble(original, request.name(), request.descriptor());
            currentHash = MethodAssemblySupport.sha256(assembly);
            if (!currentHash.equals(request.expectedAssemblySha256())) {
                return error("method assembly changed; read it again with get_method_assembly",
                        request, diagnostics, currentHash);
            }
            String originalHash = MethodAssemblySupport.sha256(AndroidAssemblySupport.bytes(original, version));
            var result = AndroidAssemblySupport.assemble(androidPipelineManager, workspace, entry,
                    request.name(), request.descriptor(), request.assembly());
            diagnostics.addAll(result.diagnostics());
            if (!result.success()) {
                return error("Recaf rejected the submitted Dalvik assembly", request, diagnostics, currentHash);
            }
            var replacement = result.value();
            String invalid = AndroidAssemblySupport.validateReplacement(original, replacement,
                    request.name(), request.descriptor(), version);
            if (invalid != null) {
                return error(invalid, request, diagnostics, currentHash);
            }
            String patchedHash = MethodAssemblySupport.sha256(AndroidAssemblySupport.bytes(replacement, version));
            boolean changed = !originalHash.equals(patchedHash);
            ToolCancellation.check();
            var latest = bundle.get(entry.className());
            if (latest == null || !originalHash.equals(MethodAssemblySupport.sha256(
                    AndroidAssemblySupport.bytes(latest, version)))) {
                return error("owner class changed while preparing the patch; read get_method_assembly again",
                        request, diagnostics, currentHash);
            }
            boolean applied = changed && !request.dryRun();
            if (applied) {
                bundle.put(replacement);
            }
            JsonObject structured = metadata(request);
            ClassToolSupport.addIdentity(structured, entry);
            structured.addProperty("scope", "primary_android");
            structured.addProperty("assembly_format", "recaf_jasm_dalvik_method");
            structured.addProperty("dex_version", version);
            structured.addProperty("applied", applied);
            structured.addProperty("changed", changed);
            structured.addProperty("current_assembly_sha256", currentHash);
            structured.addProperty("submitted_assembly_sha256", MethodAssemblySupport.sha256(request.assembly()));
            structured.addProperty("original_class_sha256", originalHash);
            structured.addProperty("patched_class_sha256", patchedHash);
            MappingMutationSupport.addPersistenceMetadata(structured, workspace, applied);
            MethodAssemblySupport.addDiagnostics(structured, diagnostics);
            return McpTool.successResult(applied ? "assembled and replaced one Dalvik method"
                    : "Dalvik method validated without workspace changes", structured);
        } catch (Exception ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error("Dalvik patch failed: " + MethodAssemblySupport.exceptionMessage(ex),
                    request, diagnostics, currentHash);
        }
    }

    private static JsonObject error(
            String message,
            Request request,
            List<MethodAssemblySupport.Diagnostic> diagnostics,
            String currentAssemblyHash) {
        JsonObject structured = metadata(request);
        structured.addProperty("applied", false);
        structured.addProperty("error", message);
        if (currentAssemblyHash != null) {
            structured.addProperty("current_assembly_sha256", currentAssemblyHash);
        }
        MethodAssemblySupport.addDiagnostics(structured, diagnostics);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject metadata(Request request) {
        JsonObject structured = new JsonObject();
        structured.addProperty("owner", request.owner());
        structured.addProperty("name", request.name());
        structured.addProperty("descriptor", request.descriptor());
        structured.addProperty("expected_assembly_sha256", request.expectedAssemblySha256());
        structured.addProperty("dry_run", request.dryRun());
        structured.addProperty("requested_scope", request.selection().scope().value());
        structured.addProperty("dex_name", request.selection().dexName());
        return structured;
    }

    private static Request parseRequest(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String owner = ToolArguments.requiredString(arguments, "owner");
        String name = ToolArguments.requiredString(arguments, "name");
        String descriptor = ToolArguments.requiredString(arguments, "descriptor");
        String assembly = ToolArguments.requiredString(arguments, "assembly");
        String expectedHash = ToolArguments.requiredString(
                arguments,
                "expected_assembly_sha256"
        );
        boolean dryRun = ToolArguments.bool(arguments, "dry_run", false);
        if (owner.length() > 1_000) {
            throw new IllegalArgumentException("owner is too long");
        }
        if (name.length() > 1_000) {
            throw new IllegalArgumentException("name is too long");
        }
        if (descriptor.length() > 4_000) {
            throw new IllegalArgumentException("descriptor is too long");
        }
        if (assembly.length() > MethodAssemblySupport.MAX_ASSEMBLY_LENGTH) {
            throw new IllegalArgumentException("assembly exceeds the maximum length");
        }
        if (!isLowercaseSha256(expectedHash)) {
            throw new IllegalArgumentException(
                    "expected_assembly_sha256 must be 64 lowercase hexadecimal characters"
            );
        }
        return new Request(owner, name, descriptor, assembly, expectedHash, dryRun,
                ClassToolSupport.parseSelection(arguments, false));
    }

    private static boolean isLowercaseSha256(String value) {
        if (value.length() != SHA256_LENGTH) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static JsonObject createInputSchema() {
        JsonObject properties = new JsonObject();
        ClassToolSupport.addScopeProperties(properties, false);
        properties.add("owner", stringProperty(
                1_000,
                "Exact primary JVM or Android internal owner name."
        ));
        properties.add("name", stringProperty(
                1_000,
                "Exact method name."
        ));
        properties.add("descriptor", stringProperty(
                4_000,
                "Exact JVM method descriptor."
        ));
        properties.add("assembly", stringProperty(
                MethodAssemblySupport.MAX_ASSEMBLY_LENGTH,
                "Complete edited get_method_assembly text; fragments are invalid."
        ));
        JsonObject expectedHash = stringProperty(
                SHA256_LENGTH,
                "Prior assembly_sha256 for concurrency control."
        );
        expectedHash.addProperty("minLength", SHA256_LENGTH);
        expectedHash.addProperty("pattern", "^[0-9a-f]{64}$");
        properties.add("expected_assembly_sha256", expectedHash);
        JsonObject dryRun = new JsonObject();
        dryRun.addProperty("type", "boolean");
        dryRun.addProperty("default", false);
        dryRun.addProperty(
                "description",
                "Assemble and validate without mutation."
        );
        properties.add("dry_run", dryRun);

        JsonArray required = new JsonArray();
        required.add("owner");
        required.add("name");
        required.add("descriptor");
        required.add("assembly");
        required.add("expected_assembly_sha256");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty(
                "description",
                "Edit complete get_method_assembly text and pass its unchanged hash; "
                        + "saving is explicit."
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
                   String assembly,
                   String expectedAssemblySha256,
                   boolean dryRun,
                   ClassToolSupport.Selection selection) {
    }
}
