package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListClassesToolTest {
    @Test
    void returnsSortedFilteredPagesWithStableMetadata() throws IOException {
        JvmClassInfo first = readClass(ListClassesToolTest.class);
        JvmClassInfo second = readClass(WorkspaceInfoToolTest.class);
        Workspace workspace = workspace(first, second);
        ListClassesTool tool = new ListClassesTool(() -> true, () -> workspace);

        JsonObject arguments = new JsonObject();
        arguments.addProperty("filter", "recafmcp/mcp/tools");
        arguments.addProperty("offset", 1);
        arguments.addProperty("limit", 1);
        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");
        JsonArray classes = structured.getAsJsonArray("classes");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals(2, structured.get("total").getAsInt());
        assertEquals(1, structured.get("returned").getAsInt());
        assertEquals(1, structured.get("offset").getAsInt());
        assertEquals("primary_jvm", structured.get("scope").getAsString());
        assertEquals(second.getName(), classes.get(0).getAsString());
        assertTrue(structured.get("next_offset").isJsonNull());
        JsonObject limit = tool.inputSchema().getAsJsonObject("properties")
                .getAsJsonObject("limit");
        assertEquals(20, limit.get("default").getAsInt());
        assertEquals(500, limit.get("maximum").getAsInt());
    }

    @Test
    void rejectsInvalidPaginationAndReportsMissingWorkspace() {
        ListClassesTool tool = new ListClassesTool(() -> false, () -> null);
        JsonObject missing = tool.execute(new JsonObject());
        assertTrue(missing.get("isError").getAsBoolean());

        JsonObject tooLarge = new JsonObject();
        tooLarge.addProperty("limit", 501);
        assertThrows(IllegalArgumentException.class, () -> tool.execute(tooLarge));

        JsonObject fractional = new JsonObject();
        fractional.addProperty("offset", 1.5);
        assertThrows(IllegalArgumentException.class, () -> tool.execute(fractional));

        JsonObject unknown = new JsonObject();
        unknown.addProperty("database", "bridge-owned");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(unknown));
    }

    private static Workspace workspace(JvmClassInfo... classes) {
        BasicJvmClassBundle bundle = new BasicJvmClassBundle();
        for (JvmClassInfo classInfo : classes) {
            bundle.put(classInfo);
        }
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
}
