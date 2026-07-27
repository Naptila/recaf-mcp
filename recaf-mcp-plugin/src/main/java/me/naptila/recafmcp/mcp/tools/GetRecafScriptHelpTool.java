package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.RecafMCPPlugin;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import software.coley.recaf.RecafBuildConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serves version-matched Recaf scripting guidance to MCP clients.
 */
public final class GetRecafScriptHelpTool implements McpTool {
    private static final Set<String> ARGUMENTS = Set.of("topic");
    private static final Map<String, String> TOPICS = topics();
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    @Override
    public String name() {
        return "get_recaf_script_help";
    }

    @Override
    public String description() {
        return "Return version-matched Recaf Java scripting guidance and reusable templates.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String topic = ToolArguments.optionalString(arguments, "topic");
        if (topic == null) {
            topic = "overview";
        }
        String resource = TOPICS.get(topic);
        if (resource == null) {
            throw new IllegalArgumentException("unknown script help topic");
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("topic", topic);
        structured.addProperty("guide", load(resource));
        JsonArray available = new JsonArray();
        TOPICS.keySet().forEach(available::add);
        structured.add("available_topics", available);
        structured.addProperty("recaf_version", RecafBuildConfig.VERSION);
        structured.addProperty("recaf_commit", RecafBuildConfig.GIT_SHA);
        structured.addProperty("plugin_version", RecafMCPPlugin.PLUGIN_VERSION);
        structured.addProperty("source", "bundled_from_repository_recaf_docs");
        structured.addProperty("high_privilege", true);
        return McpTool.successResult("returned Recaf script help for " + topic, structured);
    }

    private static String load(String resource) {
        String path = "/me/naptila/recafmcp/script-help/" + resource;
        try (InputStream input = GetRecafScriptHelpTool.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("script help resource is missing: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException ex) {
            throw new IllegalStateException("could not read script help resource: " + resource, ex);
        }
    }

    private static Map<String, String> topics() {
        Map<String, String> topics = new LinkedHashMap<>();
        topics.put("overview", "overview.md");
        topics.put("workspace", "workspace.md");
        topics.put("asm", "asm.md");
        topics.put("services", "services.md");
        topics.put("deobfuscation", "deobfuscation.md");
        return Collections.unmodifiableMap(topics);
    }

    private static JsonObject createInputSchema() {
        JsonArray choices = new JsonArray();
        List.of("overview", "workspace", "asm", "services", "deobfuscation")
                .forEach(choices::add);
        JsonObject topic = new JsonObject();
        topic.addProperty("type", "string");
        topic.add("enum", choices);
        topic.addProperty("default", "overview");
        topic.addProperty(
                "description",
                "Guide topic; start with overview."
        );

        JsonObject properties = new JsonObject();
        properties.add("topic", topic);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty(
                "description",
                "Read before writing run_recaf_script input."
        );
        schema.add("properties", properties);
        schema.addProperty("additionalProperties", false);
        return schema;
    }
}
