package me.naptila.recafmcp.mcp.internal;

import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.android.AndroidDexValidator;
import me.naptila.recafmcp.mcp.android.AndroidDexWriter;
import me.naptila.recafmcp.mcp.android.AndroidArchiveExporter;
import me.naptila.recafmcp.mcp.android.AndroidInputResource;
import software.coley.recaf.info.ApkFileInfo;
import software.coley.recaf.info.DexFileInfo;
import software.coley.recaf.info.ZipFileInfo;
import software.coley.recaf.info.properties.builtin.InputFilePathProperty;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.io.PathWorkspaceExportConsumer;
import software.coley.recaf.services.workspace.io.WorkspaceExportOptions;
import software.coley.recaf.services.workspace.io.WorkspaceOutputType;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.zip.ZipFile;

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
        String format = outputFormat(workspace);
        if (format == null) {
            throw new IOException("workspace format does not support saving");
        }
        if (!format.equals("jar") && !destination.toLowerCase(java.util.Locale.ROOT).endsWith("." + format)) {
            throw new IOException("destination extension must be ." + format);
        }
        Path input = InputFilePathProperty.get(fileResource.getFileInfo());
        if (input == null) {
            throw new IOException("workspace primary resource has no input path");
        }
        if (fileResource instanceof AndroidInputResource android) android.checkInput();

        AndroidArchiveExporter streaming = fileResource instanceof AndroidInputResource android && android.archive() != null
                ? new AndroidArchiveExporter(android) : null;
        AtomicFileInstaller.PathWriter writer = temporary -> {
            if (streaming != null) {
                streaming.export(temporary);
                return;
            }
            WorkspaceExportOptions options = new WorkspaceExportOptions(
                    WorkspaceOutputType.FILE, new PathWorkspaceExportConsumer(temporary));
            options.setBundleSupporting(false);
            AndroidDexWriter.withCompatibility(() -> {
                options.create().export(workspace);
                return null;
            });
        };
        AtomicFileInstaller.Result installed = format.equals("dex")
                ? AtomicFileInstaller.install(input, Path.of(destination), overwrite, writer, path -> {
                    var bundles = workspace.getPrimaryResource().getAndroidClassBundles();
                    if (bundles.size() != 1) {
                        throw new IOException("standalone DEX has an unexpected bundle count");
                    }
                    AndroidDexValidator.validate(bundles.values().iterator().next(), Files.readAllBytes(path));
                    return 0;
                })
                : AtomicFileInstaller.install(input, Path.of(destination), overwrite, writer, path -> {
                    try (ZipFile zip = new ZipFile(path.toFile())) {
                        if (zip.size() == 0) throw new IOException("export ZIP has no entries");
                        if (streaming != null) streaming.validateFiles(zip);
                        for (var entry : workspace.getPrimaryResource().getAndroidClassBundles().entrySet()) {
                            var dex = zip.getEntry(entry.getKey());
                            if (dex == null) throw new IOException("DEX export is missing: " + entry.getKey());
                            try (var stream = zip.getInputStream(dex)) {
                                AndroidDexValidator.validate(entry.getValue(), stream.readAllBytes());
                            }
                        }
                        return zip.size();
                    }
                });

        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("path", installed.path().toString());
        result.addProperty("size_bytes", installed.sizeBytes());
        result.addProperty("format", format);
        result.addProperty("apk_signature_status", format.equals("apk") ? "unsigned" : "not_applicable");
        return result;
    }

    /**
     * @return {@code true} when Recaf can persist the primary resource without
     *         converting an Android workspace into a different archive type.
     */
    public static boolean supportsArchiveExport(Workspace workspace) {
        return outputFormat(workspace) != null;
    }

    public static String outputFormat(Workspace workspace) {
        WorkspaceResource primary = workspace.getPrimaryResource();
        if (!(primary instanceof WorkspaceFileResource fileResource)) {
            return null;
        }
        if (fileResource.getFileInfo() instanceof ApkFileInfo) {
            return "apk";
        }
        if (fileResource.getFileInfo() instanceof DexFileInfo) {
            return "dex";
        }
        return fileResource.getFileInfo() instanceof ZipFileInfo ? "jar" : null;
    }
}
