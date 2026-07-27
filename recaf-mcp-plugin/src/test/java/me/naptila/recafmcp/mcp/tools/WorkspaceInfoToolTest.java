package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.info.builder.TextFileInfoBuilder;
import software.coley.recaf.info.properties.builtin.InputFilePathProperty;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicFileBundle;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResourceBuilder;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceInfoToolTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsExplicitErrorWithoutWorkspaceAndRejectsArguments() {
        WorkspaceInfoTool tool = new WorkspaceInfoTool(() -> false, () -> null);

        JsonObject result = tool.execute(new JsonObject());

        assertTrue(result.get("isError").getAsBoolean());
        assertFalse(result.getAsJsonObject("structuredContent")
                .get("workspace_open").getAsBoolean());

        JsonObject arguments = new JsonObject();
        arguments.addProperty("unexpected", true);
        assertThrows(IllegalArgumentException.class, () -> tool.execute(arguments));
    }

    @Test
    void returnsBoundedPrimaryResourceSummary() throws IOException {
        Path input = temporaryDirectory.resolve("input.jar");
        Files.write(input, new byte[]{1, 2, 3});

        BasicJvmClassBundle classes = new BasicJvmClassBundle();
        classes.put(readThisClass());
        BasicFileBundle files = new BasicFileBundle();
        files.put(new TextFileInfoBuilder()
                .withName("example.txt")
                .withRawContent("example".getBytes(StandardCharsets.UTF_8))
                .build());

        WorkspaceFileResource embedded = new WorkspaceFileResourceBuilder()
                .withFileInfo(fileInfo("embedded.jar"))
                .build();
        FileInfo primaryFile = fileInfo("input.jar");
        InputFilePathProperty.set(primaryFile, input);
        WorkspaceFileResource primary = new WorkspaceFileResourceBuilder()
                .withFileInfo(primaryFile)
                .withJvmClassBundle(classes)
                .withFileBundle(files)
                .withEmbeddedResources(Map.of("embedded.jar", embedded))
                .build();
        WorkspaceResource supporting = new WorkspaceResourceBuilder().build();
        Workspace workspace = new BasicWorkspace(primary, List.of(supporting), false);
        WorkspaceInfoTool tool = new WorkspaceInfoTool(() -> true, () -> workspace);

        JsonObject result = tool.execute(new JsonObject());
        JsonObject summary = result.getAsJsonObject("structuredContent");
        JsonObject primarySummary = summary.getAsJsonObject("primary_resource");

        assertFalse(result.get("isError").getAsBoolean());
        assertTrue(summary.get("workspace_open").getAsBoolean());
        assertEquals("file", primarySummary.get("kind").getAsString());
        assertEquals("input.jar", primarySummary.get("name").getAsString());
        assertEquals(input.toRealPath().toString(), primarySummary.get("input_path").getAsString());
        assertEquals(1, primarySummary.get("jvm_class_count").getAsInt());
        assertEquals(1, primarySummary.get("class_count").getAsInt());
        assertEquals(1, primarySummary.get("file_count").getAsInt());
        assertEquals(1, primarySummary.get("embedded_resource_count").getAsInt());
        assertEquals(0, primarySummary.get("dirty_item_count").getAsInt());
        assertEquals(0, primarySummary.get("removed_item_count").getAsInt());
        assertEquals(1, summary.get("supporting_resource_count").getAsInt());
        assertEquals(0, summary.get("internal_supporting_resource_count").getAsInt());
    }

    private static FileInfo fileInfo(String name) {
        return new TextFileInfoBuilder()
                .withName(name)
                .withRawContent("fixture".getBytes(StandardCharsets.UTF_8))
                .build();
    }

    private static JvmClassInfo readThisClass() throws IOException {
        String resourceName = "/" + WorkspaceInfoToolTest.class.getName().replace('.', '/') + ".class";
        try (InputStream input = WorkspaceInfoToolTest.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("test class resource is missing");
            }
            return new JvmClassInfoBuilder(input.readAllBytes()).build();
        }
    }
}
