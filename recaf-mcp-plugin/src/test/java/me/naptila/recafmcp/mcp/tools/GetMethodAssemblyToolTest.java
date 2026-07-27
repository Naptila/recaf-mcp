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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GetMethodAssemblyToolTest {
    @Test
    void returnsCompleteAssemblyAndConcurrencyHash() throws IOException {
        JvmClassInfo classInfo = readClass(GetMethodAssemblyToolTest.class);
        Workspace workspace = workspace(classInfo);
        String assembly = ".method public fixture ()V {\n    return\n}";
        GetMethodAssemblyTool tool = new GetMethodAssemblyTool(
                () -> true,
                () -> workspace,
                new StubAssembler(new MethodAssemblySupport.OperationResult<>(
                        assembly,
                        List.of(new MethodAssemblySupport.Diagnostic(
                                "warning",
                                "disassemble",
                                1,
                                2,
                                3,
                                "fixture warning"
                        ))
                ))
        );

        JsonObject result = tool.execute(arguments(
                classInfo.getName(),
                "fixture",
                "()V"
        ));
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals(assembly, structured.get("assembly").getAsString());
        assertEquals(MethodAssemblySupport.sha256(assembly),
                structured.get("assembly_sha256").getAsString());
        assertEquals(64, structured.get("class_sha256").getAsString().length());
        assertEquals("recaf_jasm_method",
                structured.get("assembly_format").getAsString());
        assertEquals(1, structured.get("diagnostic_count").getAsInt());

        JsonObject conditionalArguments = arguments(
                classInfo.getName(),
                "fixture",
                "()V"
        );
        conditionalArguments.addProperty(
                ConditionalReadSupport.ARGUMENT,
                structured.get("assembly_sha256").getAsString()
        );
        JsonObject unchanged = tool.execute(conditionalArguments)
                .getAsJsonObject("structuredContent");
        assertTrue(unchanged.get("unchanged").getAsBoolean());
        assertFalse(unchanged.has("assembly"));
        assertEquals(
                structured.get("assembly_sha256").getAsString(),
                unchanged.get("assembly_sha256").getAsString()
        );
    }

    @Test
    void reportsMissingWorkspaceTargetAndDisassemblyErrors() throws IOException {
        JvmClassInfo classInfo = readClass(GetMethodAssemblyToolTest.class);
        Workspace workspace = workspace(classInfo);
        GetMethodAssemblyTool unavailable = new GetMethodAssemblyTool(
                () -> false,
                () -> null,
                new StubAssembler(null)
        );
        assertTrue(unavailable.execute(arguments(classInfo.getName(), "fixture", "()V"))
                .get("isError").getAsBoolean());

        GetMethodAssemblyTool missing = new GetMethodAssemblyTool(
                () -> true,
                () -> workspace,
                new StubAssembler(null)
        );
        assertTrue(missing.execute(arguments(classInfo.getName(), "missing", "()V"))
                .get("isError").getAsBoolean());

        GetMethodAssemblyTool failing = new GetMethodAssemblyTool(
                () -> true,
                () -> workspace,
                new StubAssembler(new MethodAssemblySupport.OperationResult<>(
                        null,
                        List.of(new MethodAssemblySupport.Diagnostic(
                                "error", "disassemble", 4, 5, 6, "cannot print"
                        ))
                ))
        );
        JsonObject failure = failing.execute(arguments(
                classInfo.getName(),
                "fixture",
                "()V"
        ));
        assertTrue(failure.get("isError").getAsBoolean());
        assertEquals("cannot print", failure.getAsJsonObject("structuredContent")
                .getAsJsonArray("diagnostics").get(0).getAsJsonObject()
                .get("message").getAsString());
    }

    @Test
    void advertisesFlatExactMethodSchema() {
        JsonObject schema = new GetMethodAssemblyTool(
                () -> false,
                () -> null,
                new StubAssembler(null)
        ).inputSchema();

        assertFalse(schema.has("anyOf"));
        assertEquals(List.of("owner", "name", "descriptor"),
                schema.getAsJsonArray("required").asList().stream()
                        .map(element -> element.getAsString())
                        .toList());
        assertFalse(schema.get("additionalProperties").getAsBoolean());
        assertTrue(schema.getAsJsonObject("properties")
                .has(ConditionalReadSupport.ARGUMENT));
    }

    private static JsonObject arguments(String owner, String name, String descriptor) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("owner", owner);
        arguments.addProperty("name", name);
        arguments.addProperty("descriptor", descriptor);
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

    private static void fixture() {
    }

    private record StubAssembler(
            MethodAssemblySupport.OperationResult<String> disassembly
    ) implements MethodAssemblySupport.MethodAssembler {
        @Override
        public MethodAssemblySupport.OperationResult<String> disassemble(
                Workspace workspace,
                MethodAssemblySupport.Target target) {
            return disassembly;
        }

        @Override
        public MethodAssemblySupport.OperationResult<JvmClassInfo> assemble(
                Workspace workspace,
                MethodAssemblySupport.Target target,
                String assembly) {
            throw new AssertionError("assemble must not run");
        }
    }
}
