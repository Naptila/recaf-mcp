package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.services.mapping.Mappings;
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.mapping.aggregate.AggregatedMappings;
import software.coley.recaf.services.mapping.format.MappingFileFormat;
import software.coley.recaf.services.mapping.format.MappingFormatManager;
import software.coley.recaf.services.workspace.WorkspaceManager;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Exports Recaf's accumulated workspace mappings as bounded inline text pages.
 */
public final class ExportMappingsTool implements McpTool {
    private static final int DEFAULT_LIMIT = 32 * 1024;
    private static final int MAX_LIMIT = 512 * 1024;
    private static final int MAX_EXPORTED_CHARACTERS = 16 * 1024 * 1024;
    private static final Set<String> ARGUMENTS = Set.of(
            "format", "offset", "limit", ConditionalReadSupport.ARGUMENT
    );

    private final BooleanSupplier hasWorkspace;
    private final Supplier<AggregatedMappings> mappingsSupplier;
    private final Function<String, MappingFileFormat> formatResolver;
    private final ExportOperation exportOperation;
    private final List<MappingToolSupport.Descriptor> formats;
    private final JsonObject inputSchema;

    public ExportMappingsTool(WorkspaceManager workspaceManager,
                              MappingFormatManager formatManager,
                              AggregateMappingManager aggregateMappingManager) {
        this(
                workspaceManager::hasCurrentWorkspace,
                aggregateMappingManager::getAggregatedMappings,
                formatManager::createFormatInstance,
                MappingFileFormat::exportText,
                MappingToolSupport.descriptors(formatManager)
        );
    }

    ExportMappingsTool(BooleanSupplier hasWorkspace,
                       Supplier<AggregatedMappings> mappingsSupplier,
                       Function<String, MappingFileFormat> formatResolver,
                       ExportOperation exportOperation,
                       List<MappingToolSupport.Descriptor> formats) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.mappingsSupplier = Objects.requireNonNull(
                mappingsSupplier,
                "mappingsSupplier"
        );
        this.formatResolver = Objects.requireNonNull(formatResolver, "formatResolver");
        this.exportOperation = Objects.requireNonNull(exportOperation, "exportOperation");
        this.formats = List.copyOf(formats);
        inputSchema = createInputSchema(this.formats);
    }

    @Override
    public String name() {
        return "export_mappings";
    }

    @Override
    public String description() {
        return "Export Recaf's accumulated JVM and Android/DEX renames as mapping text.";
    }

    @Override
    public JsonObject inputSchema() {
        return inputSchema.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        Request request = parseRequest(arguments);
        MappingToolSupport.Descriptor descriptor = MappingToolSupport.descriptor(
                formats,
                request.format()
        );
        if (descriptor == null) {
            throw new IllegalArgumentException("format is not an installed Recaf mapping format");
        }
        request = request.withFormat(descriptor.name());
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", request, descriptor, null, null);
        }
        AggregatedMappings mappings = mappingsSupplier.get();
        if (mappings == null) {
            return error(
                    "Recaf aggregate mappings are unavailable for the current workspace",
                    request,
                    descriptor,
                    null,
                    null
            );
        }
        MappingToolSupport.MappingCounts counts = MappingToolSupport.counts(mappings);
        if (counts.isEmpty()) {
            return error(
                    "no mappings have been applied in the current workspace",
                    request,
                    descriptor,
                    mappings,
                    null
            );
        }
        if (!descriptor.supportsExport()) {
            return error(
                    "the selected Recaf mapping format does not support text export",
                    request,
                    descriptor,
                    mappings,
                    null
            );
        }
        if (mappings.isMissingFieldDescriptors()
                && descriptor.fieldTypeDifferentiation()) {
            return error(
                    "the selected format requires field descriptors, but the accumulated "
                            + "mappings contain descriptor-less fields",
                    request,
                    descriptor,
                    mappings,
                    null
            );
        }

        MappingFileFormat format = formatResolver.apply(descriptor.name());
        if (format == null) {
            return error(
                    "mapping format became unavailable after tool registration",
                    request,
                    descriptor,
                    mappings,
                    null
            );
        }
        if (!format.supportsExportText()) {
            return error(
                    "the selected Recaf mapping format no longer supports text export",
                    request,
                    descriptor,
                    mappings,
                    null
            );
        }

        String text;
        try {
            ToolCancellation.check();
            text = exportOperation.export(format, mappings);
        } catch (Exception ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error(
                    "mapping export failed: " + MappingToolSupport.exceptionMessage(ex),
                    request,
                    descriptor,
                    mappings,
                    null
            );
        }
        if (text == null) {
            return error(
                    "mapping format returned no exported text",
                    request,
                    descriptor,
                    mappings,
                    null
            );
        }
        if (text.isEmpty()) {
            return error(
                    "mapping format produced empty text for nonempty accumulated mappings",
                    request,
                    descriptor,
                    mappings,
                    0
            );
        }
        if (text.length() > MAX_EXPORTED_CHARACTERS) {
            return error(
                    "exported mapping text exceeds the 16777216-character safety limit",
                    request,
                    descriptor,
                    mappings,
                    text.length()
            );
        }

        JsonObject structured = metadata(request, descriptor);
        MappingToolSupport.addCounts(structured, counts);
        structured.addProperty(
                "missing_field_descriptors",
                mappings.isMissingFieldDescriptors()
        );
        String mappingSha256 = MappingToolSupport.sha256(text);
        addDocumentMetadata(structured, text, mappingSha256);
        structured.addProperty("exported", true);
        structured.addProperty("mapping_text_exported", true);
        structured.addProperty("workspace_archive_exported", false);
        structured.addProperty("save_required", false);
        if (ConditionalReadSupport.matches(
                request.ifSha256(),
                mappingSha256
        )) {
            ConditionalReadSupport.markUnchanged(
                    structured,
                    "offset",
                    "limit"
            );
            return McpTool.successResult(
                    "mappings unchanged as " + descriptor.name(),
                    structured
            );
        }
        addPage(structured, request, text);
        return McpTool.successResult(
                "exported " + structured.get("returned").getAsInt()
                        + " of " + text.length() + " mapping characters as "
                        + descriptor.name(),
                structured
        );
    }

    private static void addPage(JsonObject target, Request request, String text) {
        int start = Math.min(request.offset(), text.length());
        int end = (int) Math.min((long) start + request.limit(), text.length());
        String page = text.substring(start, end);
        target.addProperty("content_text", page);
        target.addProperty("offset_unit", "characters");
        target.addProperty("returned", page.length());
        target.addProperty("returned_characters", page.length());
        if (end < text.length()) {
            target.addProperty("next_offset", end);
        } else {
            target.add("next_offset", JsonNull.INSTANCE);
        }
    }

    private static void addDocumentMetadata(JsonObject target,
                                            String text,
                                            String sha256) {
        target.addProperty("encoding", StandardCharsets.UTF_8.name());
        target.addProperty("size_characters", text.length());
        target.addProperty("size_bytes", text.getBytes(StandardCharsets.UTF_8).length);
        target.addProperty("mapping_sha256", sha256);
    }

    private JsonObject error(String message,
                             Request request,
                             MappingToolSupport.Descriptor descriptor,
                             AggregatedMappings mappings,
                             Integer generatedCharacters) {
        JsonObject structured = metadata(request, descriptor);
        if (mappings != null) {
            MappingToolSupport.addCounts(
                    structured,
                    MappingToolSupport.counts(mappings)
            );
            structured.addProperty(
                    "missing_field_descriptors",
                    mappings.isMissingFieldDescriptors()
            );
        }
        if (generatedCharacters != null) {
            structured.addProperty("size_characters", generatedCharacters);
        }
        structured.addProperty("exported", false);
        structured.addProperty("mapping_text_exported", false);
        structured.addProperty("workspace_archive_exported", false);
        structured.addProperty("save_required", false);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private JsonObject metadata(Request request,
                                MappingToolSupport.Descriptor descriptor) {
        JsonObject structured = new JsonObject();
        MappingToolSupport.addFormatMetadata(structured, descriptor, formats);
        structured.addProperty("scope", "aggregate_workspace_mappings");
        structured.addProperty("source", "recaf_aggregate_mapping_manager");
        structured.addProperty("mapping_direction", "original_to_current");
        structured.addProperty("offset", request.offset());
        structured.addProperty("limit", request.limit());
        return structured;
    }

    private static Request parseRequest(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String format = ToolArguments.requiredString(arguments, "format");
        int offset = ToolArguments.integer(
                arguments,
                "offset",
                0,
                0,
                Integer.MAX_VALUE
        );
        int limit = ToolArguments.integer(
                arguments,
                "limit",
                DEFAULT_LIMIT,
                1,
                MAX_LIMIT
        );
        String ifSha256 = ConditionalReadSupport.parse(arguments);
        return new Request(format, offset, limit, ifSha256);
    }

    private static JsonObject createInputSchema(
            List<MappingToolSupport.Descriptor> descriptors) {
        JsonObject properties = new JsonObject();
        properties.add("format", MappingToolSupport.formatProperty(descriptors));
        properties.add("offset", integerProperty(0, Integer.MAX_VALUE, 0));
        properties.add("limit", integerProperty(1, MAX_LIMIT, DEFAULT_LIMIT));
        properties.add(
                ConditionalReadSupport.ARGUMENT,
                ConditionalReadSupport.schemaProperty()
        );

        JsonArray required = new JsonArray();
        required.add("format");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty(
                "description",
                "Returns accumulated mapping text, not a rewritten archive or host file."
        );
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject integerProperty(int minimum,
                                              int maximum,
                                              int defaultValue) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("minimum", minimum);
        property.addProperty("maximum", maximum);
        property.addProperty("default", defaultValue);
        return property;
    }

    record Request(String format, int offset, int limit, String ifSha256) {
        private Request withFormat(String canonicalFormat) {
            return new Request(canonicalFormat, offset, limit, ifSha256);
        }
    }

    @FunctionalInterface
    interface ExportOperation {
        String export(MappingFileFormat format, Mappings mappings) throws Exception;
    }
}
