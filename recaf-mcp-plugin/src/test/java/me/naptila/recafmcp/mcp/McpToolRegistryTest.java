package me.naptila.recafmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpToolRegistryTest {
    @Test
    void catalogIsStableOrderedAndDefensivelyCopied() {
        McpToolRegistry registry = new McpToolRegistry(List.of(
                tool("first_tool", "First tool", schema()),
                tool("second_tool", "Second tool", schema())
        ));

        JsonObject firstCatalog = registry.catalog();
        JsonArray firstTools = firstCatalog.getAsJsonArray("tools");
        assertEquals("first_tool", firstTools.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("second_tool", firstTools.get(1).getAsJsonObject().get("name").getAsString());
        assertEquals(64, registry.schemaFingerprint().length());
        assertNotNull(registry.find("first_tool"));

        firstTools.remove(0);
        JsonObject secondCatalog = registry.catalog();
        assertEquals(2, secondCatalog.getAsJsonArray("tools").size());
        assertNotEquals(firstCatalog, secondCatalog);
        assertFalse(secondCatalog.toString().contains("database"));
    }

    @Test
    void rejectsDuplicateReservedAndBridgeOwnedSchemas() {
        McpTool duplicate = tool("same_name", "Duplicate", schema());
        assertThrows(IllegalArgumentException.class, () ->
                new McpToolRegistry(List.of(duplicate, duplicate))
        );
        assertThrows(IllegalArgumentException.class, () ->
                new McpToolRegistry(List.of(tool("health", "Reserved", schema())))
        );

        JsonObject bridgeOwnedSchema = schema();
        bridgeOwnedSchema.getAsJsonObject("properties").add("database", new JsonObject());
        assertThrows(IllegalArgumentException.class, () ->
                new McpToolRegistry(List.of(
                        tool("bad_schema", "Bridge-owned property", bridgeOwnedSchema)
                ))
        );
    }

    @Test
    void fingerprintUsesParsedJsonCharactersInsteadOfHtmlEscapes() throws Exception {
        McpToolRegistry registry = new McpToolRegistry(List.of(
                tool("quoted_tool", "Owner's <tool>", schema())
        ));
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(registry.catalog().toString().getBytes(UTF_8));

        assertEquals(java.util.HexFormat.of().formatHex(digest), registry.schemaFingerprint());
    }

    private static McpTool tool(String name, String description, JsonObject inputSchema) {
        return new McpTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public JsonObject inputSchema() {
                return inputSchema.deepCopy();
            }

            @Override
            public JsonObject execute(JsonObject arguments) {
                return McpTool.successResult("ok", new JsonObject());
            }
        };
    }

    private static JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        schema.addProperty("additionalProperties", false);
        return schema;
    }
}
