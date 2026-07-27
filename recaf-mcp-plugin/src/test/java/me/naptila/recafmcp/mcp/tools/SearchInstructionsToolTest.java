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

class SearchInstructionsToolTest {
    @Test
    void returnsStableFilteredInstructionPages() throws IOException {
        JvmClassInfo first = readClass(SearchCodeToolTest.class);
        JvmClassInfo second = readClass(SearchInstructionsToolTest.class);
        Workspace workspace = workspace(first, second);
        SearchInstructionsTool tool = new SearchInstructionsTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, request, filter) -> {
                    assertEquals(workspace, currentWorkspace);
                    assertEquals("invoke.*", request.text());
                    assertTrue(request.regex());
                    assertEquals("mcp/tools/", filter);
                    return List.of(
                            new SearchInstructionsTool.InstructionHit(
                                    second.getName(),
                                    "zMethod",
                                    "()V",
                                    8,
                                    "invokevirtual java/lang/String.length ()I"
                            ),
                            new SearchInstructionsTool.InstructionHit(
                                    first.getName(),
                                    "aMethod",
                                    "()V",
                                    2,
                                    "invokestatic java/util/List.of ()Ljava/util/List;"
                            )
                    );
                }
        );
        JsonObject arguments = new JsonObject();
        arguments.addProperty("text", "invoke.*");
        arguments.addProperty("regex", true);
        arguments.addProperty("filter", "mcp/tools/");
        arguments.addProperty("offset", 1);
        arguments.addProperty("limit", 1);

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");
        JsonArray hits = structured.getAsJsonArray("hits");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals(2, structured.get("classes_selected").getAsInt());
        assertEquals(2, structured.get("total").getAsInt());
        assertEquals(1, structured.get("returned").getAsInt());
        assertEquals(second.getName(), hits.get(0).getAsJsonObject()
                .get("class_name").getAsString());
        assertEquals(8, hits.get(0).getAsJsonObject()
                .get("instruction_index").getAsInt());
        assertTrue(structured.get("next_offset").isJsonNull());
        assertEquals(500, tool.inputSchema()
                .getAsJsonObject("properties")
                .getAsJsonObject("filter")
                .get("maxLength").getAsInt());
    }

    @Test
    void rejectsMissingWorkspaceAndUnknownArguments() {
        SearchInstructionsTool tool = new SearchInstructionsTool(
                () -> false,
                () -> null,
                (workspace, request, filter) -> List.of()
        );
        JsonObject arguments = new JsonObject();
        arguments.addProperty("text", "invokevirtual");
        assertTrue(tool.execute(arguments).get("isError").getAsBoolean());

        arguments.addProperty("decompiler", "CFR");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(arguments));
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
