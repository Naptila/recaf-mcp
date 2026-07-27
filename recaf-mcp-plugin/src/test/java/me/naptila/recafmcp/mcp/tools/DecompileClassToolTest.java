package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.decompile.DecompileResult;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecompileClassToolTest {
    @Test
    void returnsSourceAndDecompilerMetadata() throws IOException {
        JvmClassInfo classInfo = readThisClass();
        Workspace workspace = workspace(classInfo);
        DecompileClassTool tool = new DecompileClassTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, currentClass, requested) -> {
                    assertEquals(workspace, currentWorkspace);
                    assertEquals(classInfo, currentClass);
                    assertEquals("cfr", requested);
                    return new DecompileClassTool.Decompilation(
                            "CFR",
                            "1.0",
                            new DecompileResult("public class DecompileClassToolTest {}", 0)
                    );
                },
                decompilers()
        );
        JsonObject arguments = new JsonObject();
        arguments.addProperty("class_name", classInfo.getName());
        arguments.addProperty("decompiler", "cfr");

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals(classInfo.getName(), structured.get("class_name").getAsString());
        assertEquals("CFR", structured.get("decompiler").getAsString());
        assertEquals("success", structured.get("result_type").getAsString());
        assertTrue(structured.get("source").getAsString().contains("public class"));
        assertEquals(
                structured.get("source").getAsString().length(),
                structured.get("source_length").getAsInt()
        );
        assertEquals(
                MethodAssemblySupport.sha256(structured.get("source").getAsString()),
                structured.get("source_sha256").getAsString()
        );
        JsonObject conditionalArguments = arguments(classInfo.getName());
        conditionalArguments.addProperty("decompiler", "cfr");
        conditionalArguments.addProperty(
                ConditionalReadSupport.ARGUMENT,
                structured.get("source_sha256").getAsString()
        );
        JsonObject unchanged = tool.execute(conditionalArguments)
                .getAsJsonObject("structuredContent");
        assertTrue(unchanged.get("unchanged").getAsBoolean());
        assertFalse(unchanged.has("source"));
        assertFalse(unchanged.has("offset"));
        assertEquals(
                structured.get("source_sha256").getAsString(),
                unchanged.get("source_sha256").getAsString()
        );
        JsonObject decompilerSchema = tool.inputSchema()
                .getAsJsonObject("properties")
                .getAsJsonObject("decompiler");
        assertEquals("CFR", decompilerSchema.get("default").getAsString());
        assertEquals(List.of("CFR", "Procyon", "Vineflower"),
                decompilerSchema.getAsJsonArray("enum").asList().stream()
                        .map(item -> item.getAsString())
                        .toList());
        assertEquals(
                "Installed JVM decompiler; Android classes are converted first.",
                decompilerSchema.get("description").getAsString()
        );
        JsonObject limit = tool.inputSchema().getAsJsonObject("properties")
                .getAsJsonObject("limit");
        assertEquals(32 * 1024, limit.get("default").getAsInt());
        assertEquals(500_000, limit.get("maximum").getAsInt());
        assertTrue(tool.inputSchema().getAsJsonObject("properties")
                .has(ConditionalReadSupport.ARGUMENT));
    }

    @Test
    void reportsMissingWorkspaceClassAndDecompilerAsToolErrors() throws IOException {
        JvmClassInfo classInfo = readThisClass();
        Workspace workspace = workspace(classInfo);

        DecompileClassTool noWorkspace = new DecompileClassTool(
                () -> false,
                () -> null,
                (currentWorkspace, currentClass, requested) -> null
        );
        assertTrue(noWorkspace.execute(arguments(classInfo.getName()))
                .get("isError").getAsBoolean());

        DecompileClassTool tool = new DecompileClassTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, currentClass, requested) -> null,
                decompilers()
        );
        JsonObject missingClass = tool.execute(arguments("missing/Class"));
        assertTrue(missingClass.get("isError").getAsBoolean());
        assertTrue(missingClass.getAsJsonObject("structuredContent")
                .get("error").getAsString().contains("not found"));

        JsonObject unavailable = tool.execute(arguments(classInfo.getName()));
        assertTrue(unavailable.get("isError").getAsBoolean());
        assertTrue(unavailable.getAsJsonObject("structuredContent")
                .get("error").getAsString().contains("not available"));
        assertEquals("CFR", unavailable.getAsJsonObject("structuredContent")
                .get("default_decompiler").getAsString());
        assertEquals(3, unavailable.getAsJsonObject("structuredContent")
                .getAsJsonArray("available_decompilers").size());
    }

    @Test
    void pagesLargeSourceWithBoundedOffsets() throws IOException {
        JvmClassInfo classInfo = readThisClass();
        Workspace workspace = workspace(classInfo);
        DecompileClassTool tool = new DecompileClassTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, currentClass, requested) ->
                        new DecompileClassTool.Decompilation(
                                "CFR",
                                "1.0",
                                new DecompileResult("0123456789", 0)
                        )
        );
        JsonObject arguments = arguments(classInfo.getName());
        arguments.addProperty("offset", 2);
        arguments.addProperty("limit", 4);

        JsonObject structured = tool.execute(arguments)
                .getAsJsonObject("structuredContent");

        assertEquals("2345", structured.get("source").getAsString());
        assertEquals(10, structured.get("source_length").getAsInt());
        assertEquals(4, structured.get("returned_chars").getAsInt());
        assertEquals(6, structured.get("next_offset").getAsInt());

        arguments.addProperty("limit", 500_001);
        assertThrows(IllegalArgumentException.class, () -> tool.execute(arguments));
    }

    private static JsonObject arguments(String className) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("class_name", className);
        return arguments;
    }

    private static List<DecompilerToolSupport.Descriptor> decompilers() {
        return List.of(
                new DecompilerToolSupport.Descriptor("CFR", "0.152", true),
                new DecompilerToolSupport.Descriptor("Procyon", "0.6.0", false),
                new DecompilerToolSupport.Descriptor("Vineflower", "1.11.1", false)
        );
    }

    private static Workspace workspace(JvmClassInfo classInfo) {
        BasicJvmClassBundle bundle = new BasicJvmClassBundle();
        bundle.put(classInfo);
        WorkspaceResource primary = new WorkspaceResourceBuilder()
                .withJvmClassBundle(bundle)
                .build();
        return new BasicWorkspace(primary, List.of(), false);
    }

    private static JvmClassInfo readThisClass() throws IOException {
        String resourceName = "/" + DecompileClassToolTest.class.getName()
                .replace('.', '/') + ".class";
        try (InputStream input = DecompileClassToolTest.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("test class resource is missing");
            }
            return new JvmClassInfoBuilder(input.readAllBytes()).build();
        }
    }
}
