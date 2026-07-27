package me.naptila.recafmcp.mcp.internal;

import com.google.gson.JsonObject;
import software.coley.recaf.info.properties.builtin.InputFilePathProperty;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.io.PathWorkspaceExportConsumer;
import software.coley.recaf.services.workspace.io.WorkspaceExportOptions;
import software.coley.recaf.services.workspace.io.WorkspaceOutputType;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Saves the current primary workspace resource with Recaf's low-level exporter.
 */
public final class RecafSaveWorkspaceHandler implements SaveWorkspaceHandler {
    private final WorkspaceManager workspaceManager;

    public RecafSaveWorkspaceHandler(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    @Override
    public JsonObject save(String destination, boolean overwrite) throws IOException {
        if (!workspaceManager.hasCurrentWorkspace()) {
            throw new IllegalStateException("workspace not open");
        }

        Workspace workspace = workspaceManager.getCurrent();
        if (!(workspace.getPrimaryResource() instanceof WorkspaceFileResource fileResource)) {
            throw new IOException("workspace primary resource is not file-backed");
        }
        Path input = InputFilePathProperty.get(fileResource.getFileInfo());
        if (input == null) {
            throw new IOException("workspace primary resource has no input path");
        }

        AtomicFileInstaller.Result installed = AtomicFileInstaller.install(
                input,
                Path.of(destination),
                overwrite,
                temporary -> {
                    WorkspaceExportOptions options = new WorkspaceExportOptions(
                            WorkspaceOutputType.FILE,
                            new PathWorkspaceExportConsumer(temporary)
                    );
                    options.setBundleSupporting(false);
                    options.create().export(workspace);
                }
        );

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("path", installed.path().toString());
        result.addProperty("size_bytes", installed.sizeBytes());
        return result;
    }
}
