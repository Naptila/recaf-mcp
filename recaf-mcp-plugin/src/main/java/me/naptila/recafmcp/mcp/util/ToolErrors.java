package me.naptila.recafmcp.mcp.util;

/**
 * Shared single-line, length-bounded exception message formatting for MCP tool responses.
 */
public final class ToolErrors {
    public static final int DEFAULT_MAX_LENGTH = 500;

    private ToolErrors() {
    }

    public static String message(Throwable throwable) {
        return message(throwable, DEFAULT_MAX_LENGTH);
    }

    public static String message(Throwable throwable, int maxLength) {
        if (throwable == null) {
            return "unknown failure";
        }
        String raw = throwable.getMessage();
        if (raw == null || raw.isBlank()) {
            return bounded(throwable.getClass().getSimpleName(), maxLength);
        }
        String singleLine = raw.replace('\n', ' ').replace('\r', ' ').strip();
        if (singleLine.isEmpty()) {
            return bounded(throwable.getClass().getSimpleName(), maxLength);
        }
        return bounded(singleLine, maxLength);
    }

    public static String bounded(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
