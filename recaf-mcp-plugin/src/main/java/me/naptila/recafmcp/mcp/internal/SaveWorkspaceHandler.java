package me.naptila.recafmcp.mcp.internal;

import com.google.gson.JsonObject;

/**
 * Authenticated internal workspace-save operation.
 */
@FunctionalInterface
public interface SaveWorkspaceHandler {
    JsonObject save(String destination, boolean overwrite) throws Exception;
}
