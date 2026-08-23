package me.naptila.recafmcp.mcp.internal;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpToolRegistry;
import me.naptila.recafmcp.mcp.WorkerHealth;
import me.naptila.recafmcp.mcp.WorkerOperationCoordinator;

import java.util.Objects;

/**
 * Builds the authenticated internal Worker health response.
 */
public final class HealthHandler {
    private final WorkerHealth health;
    private final McpToolRegistry toolRegistry;
    private final WorkerOperationCoordinator operationCoordinator;

    public HealthHandler(WorkerHealth health,
                         McpToolRegistry toolRegistry,
                         WorkerOperationCoordinator operationCoordinator) {
        this.health = Objects.requireNonNull(health, "health");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.operationCoordinator = Objects.requireNonNull(
                operationCoordinator,
                "operationCoordinator"
        );
    }

    public JsonObject get() {
        boolean closing = operationCoordinator.isClosing();
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("pid", ProcessHandle.current().pid());
        result.addProperty("ready", health.isReady() && !closing);
        result.addProperty("workspace_open", health.isWorkspaceOpen());
        result.addProperty("workspace_ready", health.isWorkspaceReady());
        result.addProperty(
                "workspace_archive_export_supported",
                health.isWorkspaceArchiveExportSupported()
        );
        result.addProperty("busy", operationCoordinator.isBusy());
        result.addProperty("closing", closing);
        addNullableString(result, "expected_input_path", health.getExpectedInputPath());
        addNullableString(result, "input_path", health.getActualInputPath());
        result.addProperty("plugin_build", health.getPluginBuild());
        result.addProperty("recaf_version", health.getRecafVersion());
        result.addProperty("recaf_commit", health.getRecafCommit());
        result.addProperty("schema_fingerprint", toolRegistry.schemaFingerprint());
        addNullableString(result, "last_error", health.getLastError());
        return result;
    }

    private static void addNullableString(JsonObject object, String name, String value) {
        if (value == null) {
            object.add(name, JsonNull.INSTANCE);
        } else {
            object.addProperty(name, value);
        }
    }
}
