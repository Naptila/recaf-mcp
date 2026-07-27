package me.naptila.recafmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Explicitly registered agent-facing Worker tool.
 */
public interface McpTool {
    String name();

    String description();

    JsonObject inputSchema();

    JsonObject execute(JsonObject arguments);

    default JsonObject definition() {
        JsonObject definition = new JsonObject();
        definition.addProperty("name", name());
        definition.addProperty("description", description());
        definition.add("inputSchema", inputSchema().deepCopy());
        return definition;
    }

    static JsonObject successResult(String text, JsonObject structuredContent) {
        return result(text, structuredContent, false);
    }

    static JsonObject errorResult(String text, JsonObject structuredContent) {
        return result(text, structuredContent, true);
    }

    private static JsonObject result(String text, JsonObject structuredContent, boolean error) {
        JsonObject contentItem = new JsonObject();
        contentItem.addProperty("type", "text");
        contentItem.addProperty("text", text);

        JsonArray content = new JsonArray();
        content.add(contentItem);

        JsonObject result = new JsonObject();
        result.add("content", content);
        result.add("structuredContent", structuredContent);
        result.addProperty("isError", error);
        return result;
    }
}
