package me.naptila.recafmcp.mcp.internal;

import com.google.gson.JsonObject;

/**
 * Internal M1.3 probe for selected Recaf services.
 */
@FunctionalInterface
public interface HeadlessServicesProbe {
    JsonObject run(String className, String searchText) throws Exception;
}
