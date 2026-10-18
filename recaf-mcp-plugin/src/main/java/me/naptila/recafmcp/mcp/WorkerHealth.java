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
    private final RecafRuntimeInfo recafRuntime;
    private final AtomicBoolean workspaceReady = new AtomicBoolean();
    private final AtomicBoolean workspaceArchiveExportSupported = new AtomicBoolean();
    private final AtomicReference<String> workspaceOutputFormat = new AtomicReference<>();
    private final AtomicReference<String> actualInputPath = new AtomicReference<>();
    private final AtomicReference<String> startupError = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();

    public WorkerHealth(BooleanSupplier workspaceOpen,
                        String expectedInputPath,
                        String pluginBuild,
                        String recafVersion,
                        String recafCommit) {
        this(workspaceOpen, expectedInputPath, pluginBuild,
                new RecafRuntimeInfo(recafVersion, recafCommit, java.util.List.of()));
    }

    public WorkerHealth(BooleanSupplier workspaceOpen,
                        String expectedInputPath,
                        String pluginBuild,
                        RecafRuntimeInfo recafRuntime) {
        this.workspaceOpen = Objects.requireNonNull(workspaceOpen);
        this.expectedInputPath = expectedInputPath;
        this.pluginBuild = Objects.requireNonNull(pluginBuild);
        this.recafRuntime = Objects.requireNonNull(recafRuntime);
        if (!recafRuntime.compatible()) {
            setLastError("Incompatible Recaf runtime: " + String.join("; ", recafRuntime.errors()));
        }
    }

    public void markWorkspaceReady(String inputPath) {
        markWorkspaceReady(inputPath, true);
    }

    public void markWorkspaceReady(String inputPath,
                                   boolean archiveExportSupported) {
        markWorkspaceReady(inputPath, archiveExportSupported ? "jar" : null);
    }

    public void markWorkspaceReady(String inputPath, String outputFormat) {
        actualInputPath.set(inputPath);
        if (expectedInputPath == null) {
            setLastError("workspace opened without an expected input path");
            return;
        }
        if (!expectedInputPath.equals(inputPath)) {
            setLastError("workspace input path does not match the expected input path");
            return;
        }
        workspaceArchiveExportSupported.set(outputFormat != null);
        workspaceOutputFormat.set(outputFormat);
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

    public boolean isWorkspaceArchiveExportSupported() {
        return workspaceReady.get() && workspaceArchiveExportSupported.get();
    }

    public String getWorkspaceOutputFormat() {
        return workspaceReady.get() ? workspaceOutputFormat.get() : null;
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
        return recafRuntime.version();
    }

    public String getRecafCommit() {
        return recafRuntime.commit();
    }

    public RecafRuntimeInfo getRecafRuntime() {
        return recafRuntime;
    }

    public String getLastError() {
        return lastError.get();
    }
}
