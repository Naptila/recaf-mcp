package me.naptila.recafmcp.mcp.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Strict helpers for tool argument validation beyond advertised JSON schemas.
 */
public final class ToolArguments {
    private ToolArguments() {
    }

    public static void rejectUnknown(JsonObject arguments, Set<String> allowed) {
        if (arguments.keySet().stream().anyMatch(name -> !allowed.contains(name))) {
            throw new IllegalArgumentException("unknown argument");
        }
    }

    public static String requiredString(JsonObject arguments, String name) {
        String value = optionalString(arguments, name);
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    public static String optionalString(JsonObject arguments, String name) {
        JsonElement element = arguments.get(name);
        if (element == null) {
            return null;
        }
        if (!element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw new IllegalArgumentException(name + " must be a nonblank string");
        }
        return element.getAsString();
    }

    public static int integer(JsonObject arguments,
                              String name,
                              int defaultValue,
                              int minimum,
                              int maximum) {
        JsonElement element = arguments.get(name);
        if (element == null) {
            return defaultValue;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        try {
            int value = new BigDecimal(element.getAsString()).intValueExact();
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(name + " is out of range");
            }
            return value;
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IllegalArgumentException(name + " must be an integer", ex);
        }
    }

    public static boolean bool(JsonObject arguments, String name, boolean defaultValue) {
        JsonElement element = arguments.get(name);
        if (element == null) {
            return defaultValue;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return element.getAsBoolean();
    }
}
