package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Lists non-class files in the primary resource.
 */
public final class ListFilesTool implements McpTool {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 500;
    private static final int MAX_FILTER_LENGTH = 1_000;
    private static final Set<String> ARGUMENTS = Set.of("filter", "offset", "limit");
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public ListFilesTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    ListFilesTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public String description() {
        return "List sorted non-class files in the primary resource.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String filter = ToolArguments.optionalString(arguments, "filter");
        if (filter != null && filter.length() > MAX_FILTER_LENGTH) {
            throw new IllegalArgumentException("filter is too long");
        }
        int offset = ToolArguments.integer(arguments, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = ToolArguments.integer(
                arguments,
                "limit",
                DEFAULT_LIMIT,
                1,
                MAX_LIMIT
        );
        if (!hasWorkspace.getAsBoolean()) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "workspace not open");
            return McpTool.errorResult("workspace not open", error);
        }

        List<FileToolSupport.Entry> files = FileToolSupport.entries(
                        workspaceSupplier.get().getPrimaryResource()
                )
                .stream()
                .filter(entry -> filter == null || entry.path().contains(filter))
                .toList();
        int start = Math.min(offset, files.size());
        int end = Math.min(start + limit, files.size());
        JsonArray paths = new JsonArray();
        JsonArray entries = new JsonArray();
        files.subList(start, end).forEach(entry -> {
            paths.add(entry.path());
            JsonObject file = new JsonObject();
            FileToolSupport.addSummary(file, entry);
            entries.add(file);
        });

        JsonObject structured = new JsonObject();
        structured.add("paths", paths);
        structured.add("files", entries);
        structured.addProperty("scope", "primary_files");
        if (filter == null) {
            structured.add("filter", JsonNull.INSTANCE);
        } else {
            structured.addProperty("filter", filter);
        }
        structured.addProperty("offset", offset);
        structured.addProperty("limit", limit);
        structured.addProperty("total", files.size());
        structured.addProperty("returned", end - start);
        if (end < files.size()) {
            structured.addProperty("next_offset", end);
        } else {
            structured.add("next_offset", JsonNull.INSTANCE);
        }
        return McpTool.successResult(
                "listed " + (end - start) + " of " + files.size() + " matching files",
                structured
        );
    }

    private static JsonObject createInputSchema() {
        JsonObject properties = new JsonObject();
        JsonObject filter = new JsonObject();
        filter.addProperty("type", "string");
        filter.addProperty("minLength", 1);
        filter.addProperty("maxLength", MAX_FILTER_LENGTH);
        filter.addProperty(
                "description",
                "Optional case-sensitive archive-path substring."
        );
        properties.add("filter", filter);
        properties.add("offset", integerProperty(0, Integer.MAX_VALUE, 0));
        properties.add("limit", integerProperty(1, MAX_LIMIT, DEFAULT_LIMIT));

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("description", "Lists archive files, excluding classes.");
        schema.add("properties", properties);
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
}
