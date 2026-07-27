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
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GetClassBytecodeToolTest {
    @Test
    void returnsDecodableBytecodeAndIntegrityMetadata() throws IOException {
        JvmClassInfo classInfo = readThisClass();
        Workspace workspace = workspace(classInfo);
        GetClassBytecodeTool tool = new GetClassBytecodeTool(() -> true, () -> workspace);

        JsonObject result = tool.execute(arguments(classInfo.getName()));
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean());
        assertArrayEquals(
                classInfo.getBytecode(),
                Base64.getDecoder().decode(structured.get("bytecode_base64").getAsString())
        );
        assertEquals(classInfo.getBytecode().length, structured.get("size_bytes").getAsInt());
        assertEquals(classInfo.getVersion(), structured.get("classfile_version").getAsInt());
        assertEquals(classInfo.getVersion() - 44, structured.get("java_version").getAsInt());
        assertEquals(64, structured.get("sha256").getAsString().length());
        assertEquals("base64", structured.get("encoding").getAsString());
        JsonObject conditionalArguments = arguments(classInfo.getName());
        conditionalArguments.addProperty(
                ConditionalReadSupport.ARGUMENT,
                structured.get("sha256").getAsString()
        );
        JsonObject unchanged = tool.execute(conditionalArguments)
                .getAsJsonObject("structuredContent");
        assertTrue(unchanged.get("unchanged").getAsBoolean());
        assertFalse(unchanged.has("bytecode_base64"));
        assertFalse(unchanged.has("offset"));
        assertEquals(classInfo.getBytecode().length,
                unchanged.get("size_bytes").getAsInt());
        assertEquals(structured.get("sha256").getAsString(),
                unchanged.get("sha256").getAsString());
        JsonObject limit = tool.inputSchema().getAsJsonObject("properties")
                .getAsJsonObject("limit");
        assertEquals(32 * 1024, limit.get("default").getAsInt());
        assertEquals(1024 * 1024, limit.get("maximum").getAsInt());
        assertTrue(tool.inputSchema().getAsJsonObject("properties")
                .has(ConditionalReadSupport.ARGUMENT));
    }

    @Test
    void reportsMissingWorkspaceAndClassAndRejectsUnknownArguments() throws IOException {
        JvmClassInfo classInfo = readThisClass();
        GetClassBytecodeTool unavailable = new GetClassBytecodeTool(() -> false, () -> null);
        assertTrue(unavailable.execute(arguments(classInfo.getName()))
                .get("isError").getAsBoolean());

        Workspace workspace = workspace(classInfo);
        GetClassBytecodeTool tool = new GetClassBytecodeTool(() -> true, () -> workspace);
        assertTrue(tool.execute(arguments("missing/Class"))
                .get("isError").getAsBoolean());

        JsonObject unknown = arguments(classInfo.getName());
        unknown.addProperty("database", "bridge-owned");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(unknown));
    }

    @Test
    void returnsBoundedBytecodeChunks() throws IOException {
        JvmClassInfo classInfo = readThisClass();
        Workspace workspace = workspace(classInfo);
        GetClassBytecodeTool tool = new GetClassBytecodeTool(() -> true, () -> workspace);
        JsonObject arguments = arguments(classInfo.getName());
        arguments.addProperty("offset", 3);
        arguments.addProperty("limit", 7);

        JsonObject structured = tool.execute(arguments)
                .getAsJsonObject("structuredContent");

        assertArrayEquals(
                java.util.Arrays.copyOfRange(classInfo.getBytecode(), 3, 10),
                Base64.getDecoder().decode(structured.get("bytecode_base64").getAsString())
        );
        assertEquals(7, structured.get("returned_bytes").getAsInt());
        assertEquals(10, structured.get("next_offset").getAsInt());

        arguments.addProperty("limit", 1024 * 1024 + 1);
        assertThrows(IllegalArgumentException.class, () -> tool.execute(arguments));
    }

    private static JsonObject arguments(String className) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("class_name", className);
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

    private static JvmClassInfo readThisClass() throws IOException {
        String resourceName = "/" + GetClassBytecodeToolTest.class.getName()
                .replace('.', '/') + ".class";
        try (InputStream input = GetClassBytecodeToolTest.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("test class resource is missing");
            }
            return new JvmClassInfoBuilder(input.readAllBytes()).build();
        }
    }
}
