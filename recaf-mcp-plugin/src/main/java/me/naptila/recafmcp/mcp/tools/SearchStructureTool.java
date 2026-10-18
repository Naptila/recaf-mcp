package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.services.search.query.structure.android.AndroidClassStructureMatcher;
import software.coley.recaf.services.search.query.structure.jvm.JvmClassStructureMatcher;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Searches class/member/instruction structure using Recaf's native backends. */
public final class SearchStructureTool implements McpTool {
    private static final int MAX_RESULTS = 20_000;
    private static final Set<String> ARGUMENTS = Set.of("query", "scope", "dex_name", "offset", "limit");
    private static final JsonObject INPUT_SCHEMA = createInputSchema();
    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public SearchStructureTool(WorkspaceManager manager) {
        this(manager::hasCurrentWorkspace, manager::getCurrent);
    }

    SearchStructureTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace);
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier);
    }

    @Override
    public String name() {
        return "search_structure";
    }

    @Override
    public String description() {
        return "Find JVM/Android classes by combined class, field, method, access, and native opcode constraints.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        JsonObject specification = StructureQuerySupport.object(arguments.get("query"), "query");
        var query = StructureQuerySupport.parse(specification);
        var selection = ClassToolSupport.parseSelection(arguments, true);
        int offset = ToolArguments.integer(arguments, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = ToolArguments.integer(arguments, "limit", SearchToolSupport.DEFAULT_LIMIT, 1, SearchToolSupport.MAX_LIMIT);
        if (!hasWorkspace.getAsBoolean()) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "workspace not open");
            return McpTool.errorResult("workspace not open", error);
        }
        var entries = ClassToolSupport.entries(workspaceSupplier.get().getPrimaryResource(), selection);
        JsonArray hits = new JsonArray();
        int total = 0;
        int scanned = 0;
        boolean truncated = false;
        for (var entry : entries) {
            ToolCancellation.check();
            scanned++;
            boolean matches = entry.classInfo().isJvmClass()
                    ? JvmClassStructureMatcher.matches(query, entry.classInfo().asJvmClass())
                    : AndroidClassStructureMatcher.matches(query, entry.classInfo().asAndroidClass());
            if (!matches) {
                continue;
            }
            if (total == MAX_RESULTS) {
                truncated = true;
                break;
            }
            if (total >= offset && total < (long) offset + limit) {
                JsonObject hit = new JsonObject();
                hit.addProperty("class_name", entry.className());
                ClassToolSupport.addHitIdentity(hit, entry);
                hit.addProperty("access", entry.classInfo().getAccess());
                hit.addProperty("field_count", entry.classInfo().getFields().size());
                hit.addProperty("method_count", entry.classInfo().getMethods().size());
                hits.add(hit);
            }
            total++;
        }
        JsonObject result = new JsonObject();
        result.add("query", specification.deepCopy());
        result.add("hits", hits);
        result.addProperty("scope", ClassToolSupport.outputScope(selection.scope(), false));
        result.addProperty("requested_scope", selection.scope().value());
        result.addProperty("dex_name", selection.dexName());
        result.addProperty("search_backend", "recaf_class_query");
        result.addProperty("classes_selected", entries.size());
        result.addProperty("classes_scanned", scanned);
        result.addProperty("collection_truncated", truncated);
        result.addProperty("offset", offset);
        result.addProperty("limit", limit);
        result.addProperty("total", total);
        result.addProperty("returned", hits.size());
        long next = (long) offset + hits.size();
        if (!hits.isEmpty() && next < total) {
            result.addProperty("next_offset", next);
        } else {
            result.add("next_offset", JsonNull.INSTANCE);
        }
        return McpTool.successResult("found " + total + " classes matching the structural query", result);
    }

    private static JsonObject createInputSchema() {
        JsonObject schema = SearchToolSupport.inputSchema("unused");
        JsonObject properties = schema.getAsJsonObject("properties");
        properties.remove("text");
        properties.remove("regex");
        properties.add("query", StructureQuerySupport.schema());
        ClassToolSupport.addScopeProperties(properties, true);
        JsonArray required = new JsonArray();
        required.add("query");
        schema.add("required", required);
        return schema;
    }
}
