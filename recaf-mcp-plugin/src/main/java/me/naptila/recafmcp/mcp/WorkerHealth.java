package me.naptila.recafmcp.mcp;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Thread-safe health and readiness state shared by the plugin and HTTP handler.
 */
public final class WorkerHealth {
    private final BooleanSupplier workspaceOpen;
    private final String expectedInputPath;
    private final String pluginBuild;
    private final String recafVersion;
    private final String recafCommit;
    private final AtomicBoolean workspaceReady = new AtomicBoolean();
    private final AtomicReference<String> actualInputPath = new AtomicReference<>();
    private final AtomicReference<String> startupError = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();

    public WorkerHealth(BooleanSupplier workspaceOpen,
                        String expectedInputPath,
                        String pluginBuild,
                        String recafVersion,
                        String recafCommit) {
        this.workspaceOpen = Objects.requireNonNull(workspaceOpen);
        this.expectedInputPath = expectedInputPath;
        this.pluginBuild = Objects.requireNonNull(pluginBuild);
        this.recafVersion = Objects.requireNonNull(recafVersion);
        this.recafCommit = Objects.requireNonNull(recafCommit);
    }

    public void markWorkspaceReady(String inputPath) {
        actualInputPath.set(inputPath);
        if (expectedInputPath == null) {
            setLastError("workspace opened without an expected input path");
            return;
        }
        if (!expectedInputPath.equals(inputPath)) {
            setLastError("workspace input path does not match the expected input path");
            return;
        }
        workspaceReady.set(true);
    }

    public void setLastError(String message) {
        String error = Objects.requireNonNull(message);
        startupError.compareAndSet(null, error);
        lastError.set(startupError.get());
        workspaceReady.set(false);
    }

    public void recordOperationError(String message) {
        lastError.set(Objects.requireNonNull(message));
    }

    public void clearOperationError() {
        lastError.set(startupError.get());
    }

    public boolean isReady() {
        if (startupError.get() != null) {
            return false;
        }
        if (expectedInputPath == null) {
            return !workspaceOpen.getAsBoolean();
        }
        return workspaceOpen.getAsBoolean() && workspaceReady.get();
    }

    public boolean isWorkspaceOpen() {
        return workspaceOpen.getAsBoolean();
    }

    public boolean isWorkspaceReady() {
        return workspaceReady.get();
    }

    public String getExpectedInputPath() {
        return expectedInputPath;
    }

    public String getActualInputPath() {
        return actualInputPath.get();
    }

    public String getPluginBuild() {
        return pluginBuild;
    }

    public String getRecafVersion() {
        return recafVersion;
    }

    public String getRecafCommit() {
        return recafCommit;
    }

    public String getLastError() {
        return lastError.get();
    }
}
