package me.naptila.recafmcp.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ordered registry which owns the stable plugin-side tool catalog.
 */
public final class McpToolRegistry {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> RESERVED_NAMES = Set.of(
            "health",
            "save_workspace",
            "cancel_operation",
            "shutdown"
    );

    private final Map<String, McpTool> tools = new LinkedHashMap<>();
    private final JsonObject catalog;
    private final String schemaFingerprint;

    public McpToolRegistry(List<? extends McpTool> registeredTools) {
        if (registeredTools == null || registeredTools.isEmpty()) {
            throw new IllegalArgumentException("at least one tool must be registered");
        }

        JsonArray definitions = new JsonArray();
        for (McpTool tool : registeredTools) {
            validate(tool);
            if (tools.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("duplicate tool name: " + tool.name());
            }
            definitions.add(tool.definition());
        }

        catalog = new JsonObject();
        catalog.add("tools", definitions);
        schemaFingerprint = sha256(GSON.toJson(catalog));
    }

    public JsonObject catalog() {
        return catalog.deepCopy();
    }

    public String schemaFingerprint() {
        return schemaFingerprint;
    }

    public McpTool find(String name) {
        return tools.get(name);
    }

    private static void validate(McpTool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("registered tool must not be null");
        }
        String name = tool.name();
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("invalid tool name: " + name);
        }
        if (RESERVED_NAMES.contains(name) || name.startsWith("probe_")) {
            throw new IllegalArgumentException("reserved tool name: " + name);
        }
        if (tool.description() == null || tool.description().isBlank()) {
            throw new IllegalArgumentException("tool description must not be blank: " + name);
        }

        JsonObject inputSchema = tool.inputSchema();
        if (inputSchema == null || !isObjectSchema(inputSchema)) {
            throw new IllegalArgumentException("tool input schema must have type object: " + name);
        }
        JsonElement propertiesElement = inputSchema.get("properties");
        if (propertiesElement != null
                && propertiesElement.isJsonObject()
                && propertiesElement.getAsJsonObject().has("database")) {
            throw new IllegalArgumentException("plugin tool schema must not define database: " + name);
        }
    }

    private static boolean isObjectSchema(JsonObject schema) {
        JsonElement type = schema.get("type");
        return type != null
                && type.isJsonPrimitive()
                && type.getAsJsonPrimitive().isString()
                && "object".equals(type.getAsString());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
