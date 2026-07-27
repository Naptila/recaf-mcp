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
 * Returns a stable, bounded page of primary-resource JVM or Android classes.
 */
public final class ListClassesTool implements McpTool {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 500;
    private static final Set<String> ARGUMENTS = Set.of(
            "filter", "scope", "dex_name", "offset", "limit"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public ListClassesTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    ListClassesTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "list_classes";
    }

    @Override
    public String description() {
        return "List primary JVM and/or Android classes.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String filter = ToolArguments.optionalString(arguments, "filter");
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(arguments, true);
        int offset = ToolArguments.integer(arguments, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = ToolArguments.integer(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        if (!hasWorkspace.getAsBoolean()) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "workspace not open");
            return McpTool.errorResult("workspace not open", error);
        }

        List<ClassToolSupport.Entry> entries = ClassToolSupport.entries(
                        workspaceSupplier.get().getPrimaryResource(),
                        selection
                )
                .stream()
                .filter(entry -> filter == null || entry.className().contains(filter))
                .toList();
        int start = Math.min(offset, entries.size());
        int end = Math.min(start + limit, entries.size());

        JsonArray page = new JsonArray();
        JsonArray classEntries = new JsonArray();
        entries.subList(start, end).forEach(entry -> {
            page.add(entry.className());
            JsonObject classEntry = new JsonObject();
            classEntry.addProperty("class_name", entry.className());
            ClassToolSupport.addHitIdentity(classEntry, entry);
            classEntries.add(classEntry);
        });
        JsonObject structuredContent = new JsonObject();
        structuredContent.add("classes", page);
        structuredContent.add("class_entries", classEntries);
        structuredContent.addProperty(
                "scope",
                ClassToolSupport.outputScope(selection.scope(), false)
        );
        structuredContent.addProperty("requested_scope", selection.scope().value());
        if (selection.dexName() == null) {
            structuredContent.add("dex_name", JsonNull.INSTANCE);
        } else {
            structuredContent.addProperty("dex_name", selection.dexName());
        }
        if (filter == null) {
            structuredContent.add("filter", JsonNull.INSTANCE);
        } else {
            structuredContent.addProperty("filter", filter);
        }
        structuredContent.addProperty("offset", offset);
        structuredContent.addProperty("limit", limit);
        structuredContent.addProperty("total", entries.size());
        structuredContent.addProperty("returned", end - start);
        if (end < entries.size()) {
            structuredContent.addProperty("next_offset", end);
        } else {
            structuredContent.add("next_offset", JsonNull.INSTANCE);
        }
        return McpTool.successResult(
                "listed " + (end - start) + " of " + entries.size() + " matching classes",
                structuredContent
        );
    }

    private static JsonObject createInputSchema() {
        JsonObject properties = new JsonObject();
        JsonObject filter = new JsonObject();
        filter.addProperty("type", "string");
        filter.addProperty("minLength", 1);
        filter.addProperty("description", "Optional case-sensitive internal-name substring.");
        properties.add("filter", filter);
        JsonObject offset = new JsonObject();
        offset.addProperty("type", "integer");
        offset.addProperty("minimum", 0);
        offset.addProperty("default", 0);
        properties.add("offset", offset);
        JsonObject limit = new JsonObject();
        limit.addProperty("type", "integer");
        limit.addProperty("minimum", 1);
        limit.addProperty("maximum", MAX_LIMIT);
        limit.addProperty("default", DEFAULT_LIMIT);
        properties.add("limit", limit);
        ClassToolSupport.addScopeProperties(properties, true);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.addProperty("additionalProperties", false);
        return schema;
    }
}
