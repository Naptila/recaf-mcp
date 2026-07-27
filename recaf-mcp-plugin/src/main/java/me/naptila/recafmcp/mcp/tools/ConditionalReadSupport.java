package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.util.ToolArguments;

import java.util.regex.Pattern;

/**
 * Shared validation and response shaping for whole-content conditional reads.
 */
final class ConditionalReadSupport {
    static final String ARGUMENT = "if_sha256";
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private ConditionalReadSupport() {
    }

    static String parse(JsonObject arguments) {
        String value = ToolArguments.optionalString(arguments, ARGUMENT);
        if (value != null && !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    ARGUMENT + " must be 64 lowercase hexadecimal characters"
            );
        }
        return value;
    }

    static boolean matches(String expected, String actual) {
        return expected != null && expected.equals(actual);
    }

    static void markUnchanged(JsonObject target, String... omittedFields) {
        for (String field : omittedFields) {
            target.remove(field);
        }
        target.addProperty("unchanged", true);
    }

    static JsonObject schemaProperty() {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("pattern", "^[0-9a-f]{64}$");
        property.addProperty("description", "Omit the body if its full SHA-256 matches.");
        return property;
    }
}
