package me.naptila.recafmcp.mcp.internal;

import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.android.AndroidDexValidator;
import me.naptila.recafmcp.mcp.android.AndroidDexWriter;
import me.naptila.recafmcp.mcp.android.AndroidArchiveExporter;
import me.naptila.recafmcp.mcp.android.AndroidInputResource;
import me.naptila.recafmcp.mcp.android.RawZipWriter;
import software.coley.recaf.info.ApkFileInfo;
import software.coley.recaf.info.DexFileInfo;
import software.coley.recaf.info.ZipFileInfo;
import software.coley.recaf.info.properties.builtin.InputFilePathProperty;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.io.PathWorkspaceExportConsumer;
import software.coley.recaf.services.workspace.io.WorkspaceExportOptions;
import software.coley.recaf.services.workspace.io.WorkspaceOutputType;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

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
        String lowerDestination = destination.toLowerCase(java.util.Locale.ROOT);
        if (format.equals("jar")) {
            if (!lowerDestination.endsWith(".jar") && !lowerDestination.endsWith(".zip")) {
                throw new IOException("destination extension must be .jar or .zip");
            }
        } else if (!lowerDestination.endsWith("." + format)) {
            throw new IOException("destination extension must be ." + format);
        }
        Path input = InputFilePathProperty.get(fileResource.getFileInfo());
        if (input == null) {
            throw new IOException("workspace primary resource has no input path");
        }
        if (fileResource instanceof AndroidInputResource android) android.checkInput();

        AndroidArchiveExporter streaming = fileResource instanceof AndroidInputResource android && android.archive() != null
                ? new AndroidArchiveExporter(android) : null;
        String[] jarSignatureStatus = {"not_applicable"};
        AtomicFileInstaller.PathWriter writer = temporary -> {
            if (format.equals("dex") && isUnchangedStandaloneDex(fileResource, input)) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            if (streaming != null) {
                streaming.export(temporary);
                return;
            }
            if (format.equals("jar")) {
                jarSignatureStatus[0] = JarArchiveExporter.export(
                        workspace,
                        input,
                        temporary,
                        cleanEmbeddedEntryNames(workspace.getPrimaryResource())
                ).jarSignatureStatus();
                return;
            }
            WorkspaceExportOptions options = new WorkspaceExportOptions(
                    WorkspaceOutputType.FILE, new PathWorkspaceExportConsumer(temporary));
            options.setBundleSupporting(false);
            AndroidDexWriter.withCompatibility(() -> {
                options.create().export(workspace);
                return null;
            });
            if (format.equals("apk")) {
                restoreUnchangedZipEntries(
                        input,
                        temporary,
                        cleanDexEntryNames(workspace.getPrimaryResource())
                );
                RawZipWriter.realignApk(temporary);
            }
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
                        if (format.equals("jar") && hasManifestInWorkspace(workspace.getPrimaryResource())) {
                            try (var jarIn = new java.util.jar.JarInputStream(Files.newInputStream(path))) {
                                if (jarIn.getManifest() == null) {
                                    throw new IOException(
                                            "JAR export failed JarInputStream manifest-first verification"
                                    );
                                }
                            }
                        }
                        if (format.equals("apk") && !RawZipWriter.verifyZipAlignment(path)) {
                            throw new IOException(
                                    "APK export failed 4-byte/16-KiB STORED entry alignment verification"
                            );
                        }
                        return zip.size();
                    }
                });

        String nativeLibAlignmentStatus = "not_applicable";
        if (format.equals("apk")) {
            nativeLibAlignmentStatus = RawZipWriter.hasUncompressedSharedObjects(installed.path())
                    ? "page_aligned_16k"
                    : "no_uncompressed_so";
        }
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("path", installed.path().toString());
        result.addProperty("size_bytes", installed.sizeBytes());
        result.addProperty("format", format);
        result.addProperty("apk_signature_status", format.equals("apk") ? "unsigned" : "not_applicable");
        result.addProperty("jar_signature_status", jarSignatureStatus[0]);
        result.addProperty("apk_alignment_status", format.equals("apk") ? "aligned_16k" : "not_applicable");
        result.addProperty("native_lib_alignment_status", nativeLibAlignmentStatus);
        if (format.equals("apk") || format.equals("dex")) {
            addDexSaveMetrics(result, workspace, input, installed.path(), format);
        }
        return result;
    }

    private static void addDexSaveMetrics(JsonObject result,
                                          Workspace workspace,
                                          Path input,
                                          Path savedPath,
                                          String format) {
        com.google.gson.JsonArray rewritten = new com.google.gson.JsonArray();
        com.google.gson.JsonArray unchanged = new com.google.gson.JsonArray();
        try {
            if (format.equals("dex")) {
                String dexName = workspace.getPrimaryResource().getAndroidClassBundles().isEmpty()
                        ? "classes.dex"
                        : workspace.getPrimaryResource().getAndroidClassBundles().keySet().iterator().next();
                if (Files.isRegularFile(input)) {
                    byte[] origBytes = Files.readAllBytes(input);
                    byte[] savedBytes = Files.readAllBytes(savedPath);
                    if (Arrays.equals(origBytes, savedBytes)) {
                        unchanged.add(dexName);
                    } else {
                        JsonObject item = new JsonObject();
                        item.addProperty("dex_name", dexName);
                        item.addProperty("original_size_bytes", (long) origBytes.length);
                        item.addProperty("saved_size_bytes", (long) savedBytes.length);
                        item.addProperty("delta_bytes", (long) savedBytes.length - origBytes.length);
                        rewritten.add(item);
                    }
                }
            } else if (format.equals("apk") && Files.isRegularFile(savedPath)) {
                java.util.List<String> dexNames = new java.util.ArrayList<>(
                        workspace.getPrimaryResource().getAndroidClassBundles().keySet()
                );
                dexNames.sort(String::compareTo);
                if (Files.isRegularFile(input)) {
                    try (ZipFile origZip = new ZipFile(input.toFile());
                         ZipFile savedZip = new ZipFile(savedPath.toFile())) {
                        for (String dexName : dexNames) {
                            ZipEntry origEntry = origZip.getEntry(dexName);
                            ZipEntry savedEntry = savedZip.getEntry(dexName);
                            if (savedEntry == null) {
                                continue;
                            }
                            byte[] savedBytes;
                            try (var in = savedZip.getInputStream(savedEntry)) {
                                savedBytes = in.readAllBytes();
                            }
                            if (origEntry != null) {
                                byte[] origBytes;
                                try (var in = origZip.getInputStream(origEntry)) {
                                    origBytes = in.readAllBytes();
                                }
                                if (Arrays.equals(origBytes, savedBytes)) {
                                    unchanged.add(dexName);
                                } else {
                                    JsonObject item = new JsonObject();
                                    item.addProperty("dex_name", dexName);
                                    item.addProperty("original_size_bytes", (long) origBytes.length);
                                    item.addProperty("saved_size_bytes", (long) savedBytes.length);
                                    item.addProperty("delta_bytes", (long) savedBytes.length - origBytes.length);
                                    rewritten.add(item);
                                }
                            } else {
                                JsonObject item = new JsonObject();
                                item.addProperty("dex_name", dexName);
                                item.add("original_size_bytes", com.google.gson.JsonNull.INSTANCE);
                                item.addProperty("saved_size_bytes", (long) savedBytes.length);
                                item.addProperty("delta_bytes", (long) savedBytes.length);
                                rewritten.add(item);
                            }
                        }
                    } catch (java.util.zip.ZipException ignored) {
                        // Synthetic non-ZIP test input.
                    }
                }
            }
        } catch (IOException ignored) {
            // Best-effort save telemetry.
        }
        result.add("rewritten_dex_entries", rewritten);
        result.add("unchanged_dex_entries", unchanged);
    }

    private static boolean hasManifestInWorkspace(WorkspaceResource primary) {
        return primary.getFileBundle().keySet().stream()
                .anyMatch(key -> key.equalsIgnoreCase(java.util.jar.JarFile.MANIFEST_NAME));
    }

    private static boolean isUnchangedStandaloneDex(WorkspaceFileResource fileResource, Path input) {
        if (!Files.isRegularFile(input)) {
            return false;
        }
        Map<String, AndroidClassBundle> bundles = fileResource.getAndroidClassBundles();
        if (bundles.size() != 1) {
            return false;
        }
        Map.Entry<String, AndroidClassBundle> single = bundles.entrySet().iterator().next();
        if (fileResource instanceof AndroidInputResource android) {
            return android.isOriginalDex(single.getKey(), single.getValue());
        }
        AndroidClassBundle bundle = single.getValue();
        return bundle.getDirtyKeys().isEmpty() && bundle.getRemovedKeys().isEmpty();
    }

    private static Set<String> cleanEmbeddedEntryNames(WorkspaceResource primary) {
        Set<String> names = new LinkedHashSet<>();
        for (Map.Entry<String, WorkspaceFileResource> entry : primary.getEmbeddedResources().entrySet()) {
            WorkspaceFileResource embedded = entry.getValue();
            boolean classesClean = embedded.classBundleStreamRecursive()
                    .allMatch(b -> b.getDirtyKeys().isEmpty() && b.getRemovedKeys().isEmpty());
            boolean filesClean = embedded.fileBundleStreamRecursive()
                    .allMatch(b -> b.getDirtyKeys().isEmpty() && b.getRemovedKeys().isEmpty());
            if (classesClean && filesClean) {
                names.add(entry.getKey());
            }
        }
        return names;
    }

    private static Set<String> cleanDexEntryNames(WorkspaceResource primary) {
        Set<String> names = new LinkedHashSet<>();
        for (Map.Entry<String, AndroidClassBundle> entry : primary.getAndroidClassBundles().entrySet()) {
            AndroidClassBundle bundle = entry.getValue();
            if (bundle.getDirtyKeys().isEmpty() && bundle.getRemovedKeys().isEmpty()) {
                names.add(entry.getKey());
            }
        }
        return names;
    }

    private static void restoreUnchangedZipEntries(Path inputZip,
                                                   Path exportedZip,
                                                   Set<String> candidateNames) throws IOException {
        if (candidateNames.isEmpty() || !Files.isRegularFile(inputZip)) {
            return;
        }
        Map<String, OriginalEntryPayload> replacements = new LinkedHashMap<>();
        try (ZipFile original = new ZipFile(inputZip.toFile());
             ZipFile exported = new ZipFile(exportedZip.toFile())) {
            for (String name : candidateNames) {
                ZipEntry origEntry = original.getEntry(name);
                ZipEntry expEntry = exported.getEntry(name);
                if (origEntry == null || expEntry == null || origEntry.isDirectory()) {
                    continue;
                }
                byte[] origBytes;
                byte[] expBytes;
                try (var in = original.getInputStream(origEntry)) {
                    origBytes = in.readAllBytes();
                }
                try (var in = exported.getInputStream(expEntry)) {
                    expBytes = in.readAllBytes();
                }
                if (!Arrays.equals(origBytes, expBytes) || origEntry.getMethod() != expEntry.getMethod()) {
                    replacements.put(name, new OriginalEntryPayload(origEntry, origBytes));
                }
            }
            if (replacements.isEmpty()) {
                return;
            }
            Path rewritten = Files.createTempFile(exportedZip.getParent(), ".recaf-preserve-", ".zip");
            try {
                try (ZipOutputStream out = new ZipOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(rewritten), 65536),
                        StandardCharsets.UTF_8
                )) {
                    Enumeration<? extends ZipEntry> entries = exported.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry current = entries.nextElement();
                        String name = current.getName();
                        OriginalEntryPayload preserved = replacements.get(name);
                        if (preserved != null) {
                            writeZipEntry(out, name, preserved.template(), preserved.bytes());
                        } else if (current.isDirectory()) {
                            ZipEntry dir = copyEntryMetadata(name, current);
                            dir.setMethod(ZipEntry.STORED);
                            dir.setSize(0L);
                            dir.setCompressedSize(0L);
                            dir.setCrc(0L);
                            out.putNextEntry(dir);
                            out.closeEntry();
                        } else {
                            byte[] content;
                            try (var in = exported.getInputStream(current)) {
                                content = in.readAllBytes();
                            }
                            writeZipEntry(out, name, current, content);
                        }
                    }
                }
                Files.move(rewritten, exportedZip, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(rewritten);
            }
        } catch (java.util.zip.ZipException ignored) {
            // Input path is not a ZIP archive (for example a synthetic unit-test stub).
        }
    }

    private static void writeZipEntry(ZipOutputStream out,
                                      String name,
                                      ZipEntry template,
                                      byte[] bytes) throws IOException {
        ZipEntry entry = copyEntryMetadata(name, template);
        int method = template.getMethod() == ZipEntry.STORED ? ZipEntry.STORED : ZipEntry.DEFLATED;
        entry.setMethod(method);
        if (method == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(bytes);
            entry.setSize(bytes.length);
            entry.setCompressedSize(bytes.length);
            entry.setCrc(crc.getValue());
        }
        out.putNextEntry(entry);
        out.write(bytes);
        out.closeEntry();
    }

    private static ZipEntry copyEntryMetadata(String name, ZipEntry source) {
        return RawZipWriter.outputEntry(name, source);
    }

    private record OriginalEntryPayload(ZipEntry template, byte[] bytes) {
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
