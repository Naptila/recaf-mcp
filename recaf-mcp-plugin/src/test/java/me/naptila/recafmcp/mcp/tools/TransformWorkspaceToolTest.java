package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformWorkspaceToolTest {
    private static software.coley.recaf.workspace.model.Workspace workspace() {
        return new software.coley.recaf.workspace.model.BasicWorkspace(
                new software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder().build(),
                List.of(), false);
    }

    @Test
    void rejectsPureAndroidWorkspaceBeforeRunningJvmTransformers() {
        var workspace = new software.coley.recaf.workspace.model.BasicWorkspace(
                new software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder()
                        .withAndroidClassBundles(Map.of("classes.dex",
                                new software.coley.recaf.workspace.model.bundle.BasicAndroidClassBundle(35, new byte[0])))
                        .build(), List.of(), false);
        var tool = new TransformWorkspaceTool(() -> true, () -> workspace, (current, request) -> {
            throw new AssertionError("JVM transformer must not run on an Android-only workspace");
        });
        var result = tool.execute(new JsonObject());
        assertTrue(result.get("isError").getAsBoolean());
        assertTrue(result.getAsJsonObject("structuredContent").get("error").getAsString()
                .contains("Android/DEX transformations are not supported"));
    }
    @Test
    void appliesConservativeDefaultsAndReturnsPaginatedSummary() {
        TransformWorkspaceTool tool = new TransformWorkspaceTool(
                () -> true,
                TransformWorkspaceToolTest::workspace,
                (workspace, request) -> {
                    assertEquals(List.of(
                            "duplicate_annotations",
                            "illegal_annotations",
                            "illegal_signatures",
                            "illegal_varargs"
                    ), request.transformers());
                    assertFalse(request.dryRun());
                    assertEquals(1, request.maxPasses());
                    return summary(true, List.of("a/A", "b/B"), List.of());
                }
        );
        JsonObject arguments = new JsonObject();
        arguments.addProperty("offset", 1);
        arguments.addProperty("limit", 1);

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean());
        assertTrue(structured.get("applied").getAsBoolean());
        assertEquals(2, structured.get("modified_class_count").getAsInt());
        assertEquals("b/B", structured.getAsJsonArray("modified_classes")
                .get(0).getAsString());
        assertTrue(structured.get("next_offset").isJsonNull());
        assertEquals(2, structured.getAsJsonObject("modified_classes_per_transformer")
                .get("illegal_signatures").getAsInt());
    }

    @Test
    void preservesExplicitOrderAndDryRunState() {
        TransformWorkspaceTool tool = new TransformWorkspaceTool(
                () -> true,
                TransformWorkspaceToolTest::workspace,
                (workspace, request) -> {
                    assertEquals(
                            List.of("frame_removal", "dead_code"),
                            request.transformers()
                    );
                    assertEquals("sample/", request.classPrefix());
                    assertTrue(request.dryRun());
                    return summary(false, List.of("sample/A"), List.of());
                }
        );
        JsonObject arguments = new JsonObject();
        JsonArray transformers = new JsonArray();
        transformers.add("frame_removal");
        transformers.add("dead_code");
        arguments.add("transformers", transformers);
        arguments.addProperty("class_prefix", "sample/");
        arguments.addProperty("dry_run", true);

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");
        assertFalse(result.get("isError").getAsBoolean());
        assertFalse(structured.get("applied").getAsBoolean());
        assertTrue(structured.get("dry_run").getAsBoolean());
    }

    @Test
    void transformerFailureIsAtomicToolError() {
        TransformWorkspaceTool tool = new TransformWorkspaceTool(
                () -> true,
                TransformWorkspaceToolTest::workspace,
                (workspace, request) -> summary(
                        false,
                        List.of("sample/Broken"),
                        List.of(new TransformWorkspaceTool.TransformFailure(
                                "sample/Broken",
                                "illegal_signatures",
                                "bad input"
                        ))
                )
        );

        JsonObject result = tool.execute(new JsonObject());
        JsonObject structured = result.getAsJsonObject("structuredContent");
        assertTrue(result.get("isError").getAsBoolean());
        assertFalse(structured.get("applied").getAsBoolean());
        assertEquals(1, structured.get("failure_count").getAsInt());
        assertEquals("sample/Broken", structured.getAsJsonArray("failures")
                .get(0).getAsJsonObject().get("class_name").getAsString());
    }

    @Test
    void rejectsNonAllowlistedDuplicateAndOutOfRangeInputs() {
        TransformWorkspaceTool tool = new TransformWorkspaceTool(
                () -> false,
                () -> null,
                (workspace, request) -> summary(false, List.of(), List.of())
        );
        assertTrue(tool.execute(new JsonObject()).get("isError").getAsBoolean());

        JsonObject unknown = new JsonObject();
        JsonArray unknownNames = new JsonArray();
        unknownNames.add("arbitrary.ReflectionTransformer");
        unknown.add("transformers", unknownNames);
        assertThrows(IllegalArgumentException.class, () -> tool.execute(unknown));

        JsonObject duplicate = new JsonObject();
        JsonArray duplicateNames = new JsonArray();
        duplicateNames.add("dead_code");
        duplicateNames.add("dead_code");
        duplicate.add("transformers", duplicateNames);
        assertThrows(IllegalArgumentException.class, () -> tool.execute(duplicate));

        JsonObject passes = new JsonObject();
        passes.addProperty("max_passes", 4);
        assertThrows(IllegalArgumentException.class, () -> tool.execute(passes));

        JsonObject empty = new JsonObject();
        empty.add("transformers", new JsonArray());
        assertThrows(IllegalArgumentException.class, () -> tool.execute(empty));
    }

    private static TransformWorkspaceTool.TransformSummary summary(
            boolean applied,
            List<String> modified,
            List<TransformWorkspaceTool.TransformFailure> failures
    ) {
        return new TransformWorkspaceTool.TransformSummary(
                modified,
                List.of(),
                Map.of("illegal_signatures", modified.size()),
                failures,
                new TransformWorkspaceTool.MappingCounts(0, 0, 0, 0),
                applied
        );
    }
}
