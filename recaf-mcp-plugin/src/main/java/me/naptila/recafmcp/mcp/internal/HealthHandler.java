package me.naptila.recafmcp.mcp.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpToolRegistry;
import me.naptila.recafmcp.mcp.WorkerHealth;
import me.naptila.recafmcp.mcp.WorkerOperationCoordinator;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the authenticated internal Worker health response.
 */
public final class HealthHandler {
    private static final int MAX_ANCESTOR_DEPTH = 8;

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
        ProcessHandle currentProcess = ProcessHandle.current();
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("pid", currentProcess.pid());
        result.add("ancestor_pids", collectAncestorPids(currentProcess));
        result.addProperty("ready", health.isReady() && !closing);
        result.addProperty("workspace_open", health.isWorkspaceOpen());
        result.addProperty("workspace_ready", health.isWorkspaceReady());
        result.addProperty(
                "workspace_archive_export_supported",
                health.isWorkspaceArchiveExportSupported()
        );
        result.addProperty("busy", operationCoordinator.isBusy());
        addNullableString(result, "workspace_output_format", health.getWorkspaceOutputFormat());
        result.addProperty("closing", closing);
        addNullableString(result, "expected_input_path", health.getExpectedInputPath());
        addNullableString(result, "input_path", health.getActualInputPath());
        result.addProperty("plugin_build", health.getPluginBuild());
        health.getRecafRuntime().addTo(result);
        result.addProperty("schema_fingerprint", toolRegistry.schemaFingerprint());
        addNullableString(result, "last_error", health.getLastError());
        return result;
    }

    private static JsonArray collectAncestorPids(ProcessHandle currentProcess) {
        JsonArray ancestors = new JsonArray();
        Set<Long> seen = new HashSet<>();
        seen.add(currentProcess.pid());
        ProcessHandle cursor = currentProcess;
        for (int depth = 0; depth < MAX_ANCESTOR_DEPTH; depth++) {
            Optional<ProcessHandle> parent;
            try {
                parent = cursor.parent();
            } catch (RuntimeException ex) {
                break;
            }
            if (parent.isEmpty()) {
                break;
            }
            ProcessHandle handle = parent.get();
            long pid = handle.pid();
            if (pid <= 0 || !seen.add(pid)) {
                break;
            }
            ancestors.add(pid);
            cursor = handle;
        }
        return ancestors;
    }

    private static void addNullableString(JsonObject object, String name, String value) {
        if (value == null) {
            object.add(name, JsonNull.INSTANCE);
        } else {
            object.addProperty(name, value);
        }
    }
}
