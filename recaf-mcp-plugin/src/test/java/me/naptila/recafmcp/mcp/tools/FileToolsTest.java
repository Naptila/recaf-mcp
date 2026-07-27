package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.BinaryXmlFileInfo;
import software.coley.recaf.info.builder.BinaryXmlFileInfoBuilder;
import software.coley.recaf.info.builder.FileInfoBuilder;
import software.coley.recaf.info.builder.TextFileInfoBuilder;
import software.coley.recaf.info.properties.builtin.BinaryXmlDecodedProperty;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicFileBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileToolsTest {
    private static final String TEXT_PATH = "a/config.txt";
    private static final String TEXT = "hello world";
    private static final String JSON_PATH = "b/data.json";
    private static final String BINARY_PATH = "z/payload.bin";
    private static final byte[] BINARY = {0, 1, 2, (byte) 0xFF};
    private static final String MANIFEST_PATH = "AndroidManifest.xml";
    private static final byte[] BINARY_XML = {3, 0, 8, 0, 8, 0, 0, 0};
    private static final String DECODED_MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest package="me.naptila.example" />
            """;

    @Test
    void listsStableFilteredFilePagesWithMetadata() {
        Workspace workspace = workspace();
        ListFilesTool tool = new ListFilesTool(() -> true, () -> workspace);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("offset", 1);
        arguments.addProperty("limit", 1);

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");
        JsonArray paths = structured.getAsJsonArray("paths");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals("primary_files", structured.get("scope").getAsString());
        assertEquals(3, structured.get("total").getAsInt());
        assertEquals(1, structured.get("returned").getAsInt());
        assertEquals(JSON_PATH, paths.get(0).getAsString());
        assertEquals(2, structured.get("next_offset").getAsInt());
        JsonObject file = structured.getAsJsonArray("files").get(0).getAsJsonObject();
        assertEquals("text", file.get("kind").getAsString());
        assertTrue(file.get("is_text").getAsBoolean());
        assertEquals("UTF-8", file.get("charset").getAsString());
        JsonObject listLimit = tool.inputSchema().getAsJsonObject("properties")
                .getAsJsonObject("limit");
        assertEquals(20, listLimit.get("default").getAsInt());
        assertEquals(500, listLimit.get("maximum").getAsInt());

        JsonObject filteredArguments = new JsonObject();
        filteredArguments.addProperty("filter", "config");
        JsonObject filtered = tool.execute(filteredArguments)
                .getAsJsonObject("structuredContent");
        assertEquals(1, filtered.get("total").getAsInt());
        assertEquals(TEXT_PATH, filtered.getAsJsonArray("paths").get(0).getAsString());
    }

    @Test
    void readsRecafTextAsPagedTextByDefault() {
        Workspace workspace = workspace();
        ReadFileTool tool = new ReadFileTool(() -> true, () -> workspace);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", TEXT_PATH);
        arguments.addProperty("offset", 6);
        arguments.addProperty("limit", 3);

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals("auto", structured.get("requested_format").getAsString());
        assertEquals("text", structured.get("format").getAsString());
        assertEquals("wor", structured.get("content_text").getAsString());
        assertEquals("characters", structured.get("offset_unit").getAsString());
        assertEquals(9, structured.get("next_offset").getAsInt());
        assertEquals(64, structured.get("sha256").getAsString().length());
        assertTrue(structured.get("content_base64").isJsonNull());
        JsonObject conditionalArguments = new JsonObject();
        conditionalArguments.addProperty("path", TEXT_PATH);
        conditionalArguments.addProperty(
                ConditionalReadSupport.ARGUMENT,
                structured.get("sha256").getAsString()
        );
        JsonObject unchanged = tool.execute(conditionalArguments)
                .getAsJsonObject("structuredContent");
        assertTrue(unchanged.get("unchanged").getAsBoolean());
        assertFalse(unchanged.has("content_text"));
        assertFalse(unchanged.has("content_base64"));
        assertFalse(unchanged.has("offset"));
        assertEquals(TEXT.length(), unchanged.get("size_bytes").getAsInt());
        JsonObject readLimit = tool.inputSchema().getAsJsonObject("properties")
                .getAsJsonObject("limit");
        assertEquals(32 * 1024, readLimit.get("default").getAsInt());
        assertEquals(512 * 1024, readLimit.get("maximum").getAsInt());
        assertTrue(tool.inputSchema().getAsJsonObject("properties")
                .has(ConditionalReadSupport.ARGUMENT));
    }

    @Test
    void readsBinaryAsPagedBase64AndRejectsForcedText() {
        Workspace workspace = workspace();
        ReadFileTool tool = new ReadFileTool(() -> true, () -> workspace);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", BINARY_PATH);
        arguments.addProperty("offset", 1);
        arguments.addProperty("limit", 2);

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals("base64", structured.get("format").getAsString());
        assertEquals(Base64.getEncoder().encodeToString(new byte[]{1, 2}),
                structured.get("content_base64").getAsString());
        assertEquals("bytes", structured.get("offset_unit").getAsString());
        assertEquals(3, structured.get("next_offset").getAsInt());
        assertTrue(structured.get("content_text").isJsonNull());

        arguments.addProperty("format", "text");
        JsonObject forcedText = tool.execute(arguments);
        assertTrue(forcedText.get("isError").getAsBoolean());
        assertEquals("binary", forcedText.getAsJsonObject("structuredContent")
                .get("kind").getAsString());
    }

    @Test
    void readsCachedAndroidBinaryXmlAsTextAndPreservesRawBase64() {
        Workspace workspace = binaryXmlWorkspace();
        ReadFileTool tool = new ReadFileTool(() -> true, () -> workspace);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", MANIFEST_PATH);

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals("android_binary_xml", structured.get("kind").getAsString());
        assertFalse(structured.get("is_text").getAsBoolean());
        assertTrue(structured.get("text_available").getAsBoolean());
        assertEquals("text", structured.get("format").getAsString());
        assertEquals(DECODED_MANIFEST, structured.get("content_text").getAsString());
        assertEquals("UTF-8", structured.get("encoding").getAsString());
        assertEquals("recaf_android_binary_xml_decoder",
                structured.get("text_source").getAsString());
        assertTrue(structured.get("decoded").getAsBoolean());
        assertEquals(FileToolSupport.sha256(BINARY_XML),
                structured.get("sha256").getAsString());

        arguments.addProperty("format", "text");
        JsonObject forcedText = tool.execute(arguments)
                .getAsJsonObject("structuredContent");
        assertEquals(DECODED_MANIFEST, forcedText.get("content_text").getAsString());
        assertTrue(forcedText.get("decoded").getAsBoolean());

        arguments.addProperty("format", "base64");
        JsonObject raw = tool.execute(arguments).getAsJsonObject("structuredContent");
        assertEquals("base64", raw.get("format").getAsString());
        assertFalse(raw.get("decoded").getAsBoolean());
        assertTrue(raw.get("text_source").isJsonNull());
        assertArrayEquals(BINARY_XML, Base64.getDecoder().decode(
                raw.get("content_base64").getAsString()));

        JsonObject listed = new ListFilesTool(() -> true, () -> workspace)
                .execute(new JsonObject())
                .getAsJsonObject("structuredContent")
                .getAsJsonArray("files")
                .get(0)
                .getAsJsonObject();
        assertEquals("android_binary_xml", listed.get("kind").getAsString());
        assertFalse(listed.get("is_text").getAsBoolean());
        assertTrue(listed.get("text_available").getAsBoolean());
    }

    @Test
    void validatesWorkspacePathFormatAndBounds() {
        ReadFileTool unavailable = new ReadFileTool(() -> false, () -> null);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", TEXT_PATH);
        assertTrue(unavailable.execute(arguments).get("isError").getAsBoolean());

        ReadFileTool tool = new ReadFileTool(() -> true, FileToolsTest::workspace);
        JsonObject missing = new JsonObject();
        missing.addProperty("path", "missing.txt");
        assertTrue(tool.execute(missing).get("isError").getAsBoolean());

        arguments.addProperty("format", "hex");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(arguments));
        arguments.addProperty("format", "base64");
        arguments.addProperty("limit", 512 * 1024 + 1);
        assertThrows(IllegalArgumentException.class, () -> tool.execute(arguments));

        ListFilesTool list = new ListFilesTool(() -> false, () -> null);
        assertTrue(list.execute(new JsonObject()).get("isError").getAsBoolean());
    }

    private static Workspace workspace() {
        BasicFileBundle files = new BasicFileBundle();
        files.put(new TextFileInfoBuilder()
                .withName(TEXT_PATH)
                .withRawContent(TEXT.getBytes(StandardCharsets.UTF_8))
                .build());
        files.put(new TextFileInfoBuilder()
                .withName(JSON_PATH)
                .withRawContent("{\"ok\":true}".getBytes(StandardCharsets.UTF_8))
                .build());
        files.put(new FileInfoBuilder<>()
                .withName(BINARY_PATH)
                .withRawContent(BINARY)
                .build());
        WorkspaceResource primary = new WorkspaceResourceBuilder()
                .withFileBundle(files)
                .build();
        return new BasicWorkspace(primary, List.of(), false);
    }

    private static Workspace binaryXmlWorkspace() {
        BinaryXmlFileInfo manifest = new BinaryXmlFileInfoBuilder()
                .withName(MANIFEST_PATH)
                .withRawContent(BINARY_XML)
                .build();
        BinaryXmlDecodedProperty.set(manifest, DECODED_MANIFEST);
        BasicFileBundle files = new BasicFileBundle();
        files.put(manifest);
        WorkspaceResource primary = new WorkspaceResourceBuilder()
                .withFileBundle(files)
                .build();
        return new BasicWorkspace(primary, List.of(), false);
    }
}
