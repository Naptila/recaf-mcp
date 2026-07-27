package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.aggregate.AggregatedMappings;
import software.coley.recaf.services.mapping.format.SimpleMappings;
import software.coley.recaf.services.mapping.format.SrgMappings;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportMappingsToolTest {
    private static final String OLD_NAME = "example/OldName";
    private static final String NEW_NAME = "example/NewName";
    private static final List<MappingToolSupport.Descriptor> FORMATS = List.of(
            format("Enigma", true, true, true),
            format("Jadx (Legacy)", true, true, true),
            format("Proguard", true, true, false),
            format("SRG", true, false, false),
            format("Simple", true, true, true),
            format("Tiny-V1", true, true, true),
            format("Tiny-V2", true, true, true)
    );

    @Test
    void exportsCanonicalFormatAsBoundedUtf8TextPage() throws Exception {
        AggregatedMappings mappings = populatedMappings();
        ExportMappingsTool tool = simpleTool(mappings);
        JsonObject arguments = arguments("simple");
        arguments.addProperty("limit", 12);

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");
        String complete = new SimpleMappings().exportText(mappings);

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals("Simple", structured.get("format").getAsString());
        assertEquals("original_to_current",
                structured.get("mapping_direction").getAsString());
        assertEquals("recaf_aggregate_mapping_manager",
                structured.get("source").getAsString());
        assertEquals("aggregate_workspace_mappings",
                structured.get("scope").getAsString());
        assertEquals("UTF-8", structured.get("encoding").getAsString());
        assertEquals("characters", structured.get("offset_unit").getAsString());
        assertEquals(complete.substring(0, 12),
                structured.get("content_text").getAsString());
        assertEquals(complete.length(), structured.get("size_characters").getAsInt());
        assertEquals(complete.getBytes(StandardCharsets.UTF_8).length,
                structured.get("size_bytes").getAsInt());
        assertEquals(12, structured.get("returned").getAsInt());
        assertEquals(12, structured.get("next_offset").getAsInt());
        assertEquals(MappingToolSupport.sha256(complete),
                structured.get("mapping_sha256").getAsString());
        assertTrue(structured.get("exported").getAsBoolean());
        assertTrue(structured.get("mapping_text_exported").getAsBoolean());
        assertFalse(structured.get("workspace_archive_exported").getAsBoolean());
        assertFalse(structured.get("save_required").getAsBoolean());
        assertFalse(structured.get("missing_field_descriptors").getAsBoolean());
        assertEquals(1, structured.getAsJsonObject("mapping_counts")
                .get("classes").getAsInt());
        assertEquals(1, structured.getAsJsonObject("mapping_counts")
                .get("methods").getAsInt());

        JsonObject conditionalArguments = arguments("Simple");
        conditionalArguments.addProperty(
                ConditionalReadSupport.ARGUMENT,
                structured.get("mapping_sha256").getAsString()
        );
        JsonObject unchanged = tool.execute(conditionalArguments)
                .getAsJsonObject("structuredContent");
        assertTrue(unchanged.get("unchanged").getAsBoolean());
        assertFalse(unchanged.has("content_text"));
        assertFalse(unchanged.has("offset"));
        assertEquals(complete.length(), unchanged.get("size_characters").getAsInt());
        assertEquals(
                structured.get("mapping_sha256").getAsString(),
                unchanged.get("mapping_sha256").getAsString()
        );

        JsonObject schema = tool.inputSchema();
        JsonArray required = schema.getAsJsonArray("required");
        assertEquals(1, required.size());
        assertEquals("format", required.get(0).getAsString());
        assertEquals(7, schema.getAsJsonObject("properties")
                .getAsJsonObject("format").getAsJsonArray("enum").size());
        assertEquals(512 * 1024, schema.getAsJsonObject("properties")
                .getAsJsonObject("limit").get("maximum").getAsInt());
        assertEquals(32 * 1024, schema.getAsJsonObject("properties")
                .getAsJsonObject("limit").get("default").getAsInt());
        assertTrue(schema.getAsJsonObject("properties")
                .has(ConditionalReadSupport.ARGUMENT));
    }

    @Test
    void rejectsUnavailableEmptyAndUnsupportedExportStates() {
        AggregatedMappings empty = new AggregatedMappings(workspace());
        assertErrorContains(simpleTool(empty), arguments("Simple"),
                "no mappings have been applied");

        ExportMappingsTool unavailable = new ExportMappingsTool(
                () -> false,
                () -> null,
                name -> new SimpleMappings(),
                (format, mappings) -> {
                    throw new AssertionError("closed workspace must not export");
                },
                FORMATS
        );
        assertErrorContains(unavailable, arguments("Simple"), "workspace not open");

        List<MappingToolSupport.Descriptor> unsupportedFormats = List.of(
                format("Unsupported", false, true, true)
        );
        ExportMappingsTool unsupported = new ExportMappingsTool(
                () -> true,
                ExportMappingsToolTest::populatedMappings,
                name -> new SimpleMappings(),
                (format, mappings) -> {
                    throw new AssertionError("unsupported format must not export");
                },
                unsupportedFormats
        );
        assertErrorContains(unsupported, arguments("Unsupported"),
                "does not support text export");
    }

    @Test
    void followsRecafFieldDescriptorCompatibilityAndBoundsFailures() {
        AggregatedMappings mappings = new AggregatedMappings(workspace());
        IntermediateMappings update = new IntermediateMappings();
        update.addField(OLD_NAME, null, "oldField", "newField");
        mappings.update(update);
        assertTrue(mappings.isMissingFieldDescriptors());

        AtomicBoolean exported = new AtomicBoolean();
        ExportMappingsTool strict = new ExportMappingsTool(
                () -> true,
                () -> mappings,
                name -> new SimpleMappings(),
                (format, currentMappings) -> {
                    exported.set(true);
                    return format.exportText(currentMappings);
                },
                FORMATS
        );
        assertErrorContains(strict, arguments("Simple"),
                "requires field descriptors");
        assertFalse(exported.get());

        ExportMappingsTool srg = new ExportMappingsTool(
                () -> true,
                () -> mappings,
                name -> "SRG".equals(name) ? new SrgMappings() : null,
                (format, currentMappings) -> format.exportText(currentMappings),
                FORMATS
        );
        JsonObject compatible = srg.execute(arguments("SRG"));
        assertFalse(compatible.get("isError").getAsBoolean());
        assertTrue(compatible.getAsJsonObject("structuredContent")
                .get("content_text").getAsString().startsWith("FD: "));

        ExportMappingsTool failed = new ExportMappingsTool(
                () -> true,
                ExportMappingsToolTest::populatedMappings,
                name -> new SimpleMappings(),
                (format, currentMappings) -> {
                    throw new IllegalStateException("synthetic\nexport failure");
                },
                FORMATS
        );
        assertErrorContains(failed, arguments("Simple"),
                "mapping export failed: synthetic export failure");

        JsonObject unknown = arguments("Unknown");
        assertThrows(IllegalArgumentException.class,
                () -> simpleTool(populatedMappings()).execute(unknown));
        JsonObject excessivePage = arguments("Simple");
        excessivePage.addProperty("limit", 512 * 1024 + 1);
        assertThrows(IllegalArgumentException.class,
                () -> simpleTool(populatedMappings()).execute(excessivePage));
    }

    private static ExportMappingsTool simpleTool(AggregatedMappings mappings) {
        return new ExportMappingsTool(
                () -> true,
                () -> mappings,
                name -> "Simple".equals(name) ? new SimpleMappings() : null,
                (format, currentMappings) -> format.exportText(currentMappings),
                FORMATS
        );
    }

    private static void assertErrorContains(ExportMappingsTool tool,
                                            JsonObject arguments,
                                            String expected) {
        JsonObject result = tool.execute(arguments);
        assertTrue(result.get("isError").getAsBoolean());
        assertTrue(result.getAsJsonObject("structuredContent")
                .get("error").getAsString().contains(expected));
        assertFalse(result.getAsJsonObject("structuredContent")
                .get("exported").getAsBoolean());
        assertFalse(result.getAsJsonObject("structuredContent")
                .get("mapping_text_exported").getAsBoolean());
    }

    private static AggregatedMappings populatedMappings() {
        AggregatedMappings mappings = new AggregatedMappings(workspace());
        IntermediateMappings update = new IntermediateMappings();
        update.addClass(OLD_NAME, NEW_NAME);
        update.addMethod(OLD_NAME, "()V", "oldMethod", "newMethod");
        mappings.update(update);
        return mappings;
    }

    private static JsonObject arguments(String format) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("format", format);
        return arguments;
    }

    private static Workspace workspace() {
        WorkspaceResource primary = new WorkspaceResourceBuilder()
                .withJvmClassBundle(new BasicJvmClassBundle())
                .build();
        return new BasicWorkspace(primary, List.of(), false);
    }

    private static MappingToolSupport.Descriptor format(
            String name,
            boolean supportsExport,
            boolean fieldTypes,
            boolean variableTypes) {
        return new MappingToolSupport.Descriptor(
                name,
                supportsExport,
                fieldTypes,
                variableTypes
        );
    }
}
