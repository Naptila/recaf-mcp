package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchInstructionsToolTest {
    private static final String CURRENT_ASSEMBLY = ".method private static fixture ()I {\n"
            + "    bipush 7\n"
            + "    ireturn\n"
            + "}";

    @Test
    void dryRunValidatesWithoutMutation() throws IOException {
        JvmClassInfo existing = readClass(PatchInstructionsToolTest.class);
        JvmClassInfo replacement = withDifferentMinorVersion(existing);
        Workspace workspace = workspace(existing);
        PatchInstructionsTool tool = tool(workspace, replacement, new AtomicInteger());
        JsonObject arguments = arguments(existing, true);

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean());
        assertFalse(structured.get("applied").getAsBoolean());
        assertTrue(structured.get("changed").getAsBoolean());
        assertEquals(MethodAssemblySupport.sha256(existing.getBytecode()),
                MethodAssemblySupport.sha256(workspace.getPrimaryResource()
                        .getJvmClassBundle().get(existing.getName()).getBytecode()));
        assertEquals(1, structured.get("diagnostic_count").getAsInt());
    }

    @Test
    void atomicallyAppliesValidatedMethodAssembly() throws IOException {
        JvmClassInfo existing = readClass(PatchInstructionsToolTest.class);
        JvmClassInfo replacement = withDifferentMinorVersion(existing);
        Workspace workspace = workspace(existing);
        AtomicInteger assemblies = new AtomicInteger();
        PatchInstructionsTool tool = tool(workspace, replacement, assemblies);

        JsonObject result = tool.execute(arguments(existing, false));
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean());
        assertTrue(structured.get("applied").getAsBoolean());
        assertTrue(structured.get("changed").getAsBoolean());
        assertEquals(1, assemblies.get());
        assertEquals(MethodAssemblySupport.sha256(replacement.getBytecode()),
                MethodAssemblySupport.sha256(workspace.getPrimaryResource()
                        .getJvmClassBundle().get(existing.getName()).getBytecode()));
    }

    @Test
    void rejectsStaleHashBeforeAssemblyAndPreservesWorkspace() throws IOException {
        JvmClassInfo existing = readClass(PatchInstructionsToolTest.class);
        Workspace workspace = workspace(existing);
        AtomicInteger assemblies = new AtomicInteger();
        PatchInstructionsTool tool = tool(
                workspace,
                withDifferentMinorVersion(existing),
                assemblies
        );
        JsonObject arguments = arguments(existing, false);
        arguments.addProperty("expected_assembly_sha256", "0".repeat(64));

        JsonObject result = tool.execute(arguments);

        assertTrue(result.get("isError").getAsBoolean());
        assertEquals(0, assemblies.get());
        assertEquals(MethodAssemblySupport.sha256(existing.getBytecode()),
                MethodAssemblySupport.sha256(workspace.getPrimaryResource()
                        .getJvmClassBundle().get(existing.getName()).getBytecode()));
        assertTrue(result.getAsJsonObject("structuredContent").get("error")
                .getAsString().contains("read it again"));
    }

    @Test
    void rejectsAssemblerFailureAndDeclarationChangesWithoutMutation()
            throws IOException {
        JvmClassInfo existing = readClass(PatchInstructionsToolTest.class);
        JvmClassInfo unrelated = readClass(GetMethodAssemblyToolTest.class);
        Workspace workspace = workspace(existing);
        PatchInstructionsTool failing = new PatchInstructionsTool(
                () -> true,
                () -> workspace,
                new StubAssembler(
                        existing,
                        new MethodAssemblySupport.OperationResult<>(
                                null,
                                List.of(new MethodAssemblySupport.Diagnostic(
                                        "error", "parse", 2, 3, 4, "bad opcode"
                                ))
                        ),
                        new AtomicInteger()
                )
        );
        JsonObject failed = failing.execute(arguments(existing, false));
        assertTrue(failed.get("isError").getAsBoolean());
        assertEquals("bad opcode", failed.getAsJsonObject("structuredContent")
                .getAsJsonArray("diagnostics").get(0).getAsJsonObject()
                .get("message").getAsString());

        PatchInstructionsTool changedDeclarations = new PatchInstructionsTool(
                () -> true,
                () -> workspace,
                new StubAssembler(
                        existing,
                        new MethodAssemblySupport.OperationResult<>(unrelated, List.of()),
                        new AtomicInteger()
                )
        );
        JsonObject rejected = changedDeclarations.execute(arguments(existing, false));
        assertTrue(rejected.get("isError").getAsBoolean());
        assertTrue(rejected.getAsJsonObject("structuredContent").get("error")
                .getAsString().contains("class name changed"));
        assertEquals(MethodAssemblySupport.sha256(existing.getBytecode()),
                MethodAssemblySupport.sha256(workspace.getPrimaryResource()
                        .getJvmClassBundle().get(existing.getName()).getBytecode()));
    }

    @Test
    void validatesHashAssemblyBoundsAndFlatSchema() throws IOException {
        JvmClassInfo existing = readClass(PatchInstructionsToolTest.class);
        PatchInstructionsTool tool = tool(
                workspace(existing),
                withDifferentMinorVersion(existing),
                new AtomicInteger()
        );
        JsonObject invalidHash = arguments(existing, false);
        invalidHash.addProperty("expected_assembly_sha256", "A".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> tool.execute(invalidHash));

        JsonObject oversized = arguments(existing, false);
        oversized.addProperty(
                "assembly",
                "x".repeat(MethodAssemblySupport.MAX_ASSEMBLY_LENGTH + 1)
        );
        assertThrows(IllegalArgumentException.class, () -> tool.execute(oversized));

        JsonObject schema = tool.inputSchema();
        assertFalse(schema.has("anyOf"));
        assertFalse(schema.get("additionalProperties").getAsBoolean());
        assertEquals(false, schema.getAsJsonObject("properties")
                .getAsJsonObject("dry_run").get("default").getAsBoolean());
        assertEquals("^[0-9a-f]{64}$", schema.getAsJsonObject("properties")
                .getAsJsonObject("expected_assembly_sha256")
                .get("pattern").getAsString());
    }

    private static PatchInstructionsTool tool(Workspace workspace,
                                              JvmClassInfo replacement,
                                              AtomicInteger assemblies) {
        JvmClassInfo existing = workspace.getPrimaryResource()
                .getJvmClassBundle().get(replacement.getName());
        return new PatchInstructionsTool(
                () -> true,
                () -> workspace,
                new StubAssembler(
                        existing,
                        new MethodAssemblySupport.OperationResult<>(
                                replacement,
                                List.of(new MethodAssemblySupport.Diagnostic(
                                        "warning", "assemble", 1, 1, 1, "fixture warning"
                                ))
                        ),
                        assemblies
                )
        );
    }

    private static JsonObject arguments(JvmClassInfo classInfo, boolean dryRun) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("owner", classInfo.getName());
        arguments.addProperty("name", "fixture");
        arguments.addProperty("descriptor", "()I");
        arguments.addProperty("assembly", CURRENT_ASSEMBLY.replace("7", "8"));
        arguments.addProperty(
                "expected_assembly_sha256",
                MethodAssemblySupport.sha256(CURRENT_ASSEMBLY)
        );
        arguments.addProperty("dry_run", dryRun);
        return arguments;
    }

    private static Workspace workspace(JvmClassInfo classInfo) {
        BasicJvmClassBundle bundle = new BasicJvmClassBundle();
        bundle.put(classInfo);
        WorkspaceResource primary = new WorkspaceResourceBuilder()
                .withJvmClassBundle(bundle)
                .build();
        return new BasicWorkspace(primary, List.of(), false);
    }

    private static JvmClassInfo readClass(Class<?> type) throws IOException {
        String resourceName = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("test class resource is missing");
            }
            return new JvmClassInfoBuilder(input.readAllBytes()).build();
        }
    }

    private static JvmClassInfo withDifferentMinorVersion(JvmClassInfo classInfo) {
        byte[] bytecode = Arrays.copyOf(classInfo.getBytecode(), classInfo.getBytecode().length);
        bytecode[4] = 0;
        bytecode[5] = 1;
        return new JvmClassInfoBuilder(bytecode).build();
    }

    private static int fixture() {
        return 7;
    }

    private record StubAssembler(
            JvmClassInfo existing,
            MethodAssemblySupport.OperationResult<JvmClassInfo> assemblyResult,
            AtomicInteger assemblies
    ) implements MethodAssemblySupport.MethodAssembler {
        @Override
        public MethodAssemblySupport.OperationResult<String> disassemble(
                Workspace workspace,
                MethodAssemblySupport.Target target) {
            assertEquals(existing, target.classInfo());
            return new MethodAssemblySupport.OperationResult<>(CURRENT_ASSEMBLY, List.of());
        }

        @Override
        public MethodAssemblySupport.OperationResult<JvmClassInfo> assemble(
                Workspace workspace,
                MethodAssemblySupport.Target target,
                String assembly) {
            assemblies.incrementAndGet();
            assertEquals(CURRENT_ASSEMBLY.replace("7", "8"), assembly);
            return assemblyResult;
        }
    }
}
