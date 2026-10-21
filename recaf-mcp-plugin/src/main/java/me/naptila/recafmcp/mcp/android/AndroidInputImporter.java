package me.naptila.recafmcp.mcp.android;

import software.coley.recaf.info.Info;
import software.coley.recaf.info.builder.ApkFileInfoBuilder;
import software.coley.recaf.info.builder.DexFileInfoBuilder;
import software.coley.recaf.info.properties.builtin.InputFilePathProperty;
import software.coley.recaf.services.workspace.io.InfoImporter;
import software.coley.recaf.util.io.ByteSources;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.bundle.AndroidDexMetadata.DexEntryProperties;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResourceBuilder;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;

/** Indexes APK resources with bounded reads and parses each DEX exactly once. */
public final class AndroidInputImporter {
    private final InfoImporter importer;

    public AndroidInputImporter(InfoImporter importer) { this.importer = importer; }

    public AndroidInputResource readIfAndroid(Path input) throws IOException {
        byte[] prefix;
        try (var stream = Files.newInputStream(input)) { prefix = stream.readNBytes(8); }
        // Initialize Recaf's info importer before entering the DEX debug-info guard.
        importer.readInfo("empty", ByteSources.wrap(new byte[0]));
        if (isDex(prefix)) {
            long size = Files.size(input);
            if (size > Math.min(Integer.MAX_VALUE - 8L, Runtime.getRuntime().maxMemory() / 3)) {
                throw new IOException("DEX exceeds the available materialization budget");
            }
            var before = Files.readAttributes(input, java.nio.file.attribute.BasicFileAttributes.class);
            var bundle = AndroidDexReader.readBundle(Files.readAllBytes(input), DexEntryProperties.empty());
            var info = new DexFileInfoBuilder().withName(input.getFileName().toString())
                    .withRawContent(new byte[0]).build();
            InputFilePathProperty.set(info, input.toRealPath());
            var result = new AndroidInputResource(new WorkspaceFileResourceBuilder().withFileInfo(info)
                    .withAndroidClassBundles(Map.of(info.getName(), bundle)), input, null, Map.of(), Map.of());
            if (!before.lastModifiedTime().equals(Files.getLastModifiedTime(input)) || before.size() != Files.size(input)) {
                throw new IOException("DEX changed during import");
            }
            return result;
        }
        if (prefix.length < 4 || prefix[0] != 'P' || prefix[1] != 'K') return null;
        ArchiveBacking archive = new ArchiveBacking(input);
        boolean apk = input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".apk")
                || (archive.entries().containsKey("AndroidManifest.xml")
                && archive.entries().keySet().stream().anyMatch(name -> name.matches("classes(?:[0-9]+)?\\.dex")));
        if (!apk) { archive.close(); return null; }
        try {
            return readApk(archive);
        } catch (IOException | RuntimeException | Error error) {
            archive.close();
            throw error;
        }
    }

    private AndroidInputResource readApk(ArchiveBacking archive) throws IOException {
        var files = new DeferredFileBundle();
        var jvm = new BasicJvmClassBundle();
        Map<String, DeferredFileInfo> originalFiles = new LinkedHashMap<>();
        Map<String, String> jvmPaths = new LinkedHashMap<>();
        List<ZipEntry> dexEntries = new ArrayList<>();
        List<Callable<Indexed>> tasks = new ArrayList<>();
        for (ZipEntry entry : archive.entries().values()) {
            if (!entry.isDirectory()) tasks.add(() -> index(archive, entry));
        }
        try (var executor = Executors.newFixedThreadPool(Math.min(4, Runtime.getRuntime().availableProcessors()))) {
            var results = executor.invokeAll(tasks);
            for (var result : results) {
                Indexed indexed = result.get();
                String name = indexed.entry().getName();
                if (indexed.dex()) dexEntries.add(indexed.entry());
                else if (indexed.jvm() != null) {
                    var info = indexed.jvm().asClass().asJvmClass();
                    if (jvm.containsKey(info.getName())) throw new IOException("duplicate JVM class in APK: " + name);
                    jvm.initialPut(info);
                    DeferredFileInfo.copyEntryProperties(info, indexed.entry());
                    jvmPaths.put(info.getName(), name);
                } else {
                    files.initialPut(indexed.file());
                    originalFiles.put(name, indexed.file());
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("APK indexing interrupted");
        } catch (ExecutionException error) { throw importError(error); }
        Map<String, AndroidClassBundle> bundles = new LinkedHashMap<>();
        int parallelism = Runtime.getRuntime().maxMemory() >= (1L << 30) ? 2 : 1;
        try (var executor = Executors.newFixedThreadPool(parallelism)) {
            List<Callable<AndroidClassBundle>> dexTasks = new ArrayList<>();
            for (ZipEntry entry : dexEntries) {
                dexTasks.add(() -> {
                    try {
                        return AndroidDexReader.readBundle(archive.read(entry), new DexEntryProperties(entry.getMethod(),
                                entry.getComment(), millis(entry.getCreationTime()), millis(entry.getLastModifiedTime()),
                                millis(entry.getLastAccessTime())));
                    } catch (IOException | RuntimeException error) {
                        throw new IOException("DEX import failed for " + entry.getName() + ": " + error.getMessage(), error);
                    }
                });
            }
            var results = executor.invokeAll(dexTasks);
            for (int index = 0; index < results.size(); index++) {
                bundles.put(dexEntries.get(index).getName(), results.get(index).get());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("DEX import interrupted");
        } catch (ExecutionException error) { throw importError(error); }
        archive.checkUnchanged();
        var info = new ApkFileInfoBuilder().withName(archive.path().getFileName().toString())
                .withRawContent(new byte[0]).build();
        InputFilePathProperty.set(info, archive.path());
        return new AndroidInputResource(new WorkspaceFileResourceBuilder().withFileInfo(info).withFileBundle(files)
                .withJvmClassBundle(jvm).withAndroidClassBundles(bundles), archive.path(), archive, originalFiles, jvmPaths);
    }

    private Indexed index(ArchiveBacking archive, ZipEntry entry) throws IOException {
        byte[] prefix = archive.prefix(entry, 4096);
        if (isDex(prefix)) return new Indexed(entry, true, null, null);
        if (entry.getName().matches("classes(?:[0-9]+)?\\.dex")) {
            throw new IOException("invalid DEX entry: " + entry.getName());
        }
        if (prefix.length >= 4 && prefix[0] == (byte) 0xca && prefix[1] == (byte) 0xfe
                && prefix[2] == (byte) 0xba && prefix[3] == (byte) 0xbe) {
            Info info = importer.readInfo(entry.getName(), ByteSources.wrap(archive.read(entry)));
            if (info.isClass() && info.asClass().isJvmClass()) return new Indexed(entry, false, info, null);
        }
        return new Indexed(entry, false, null, new DeferredFileInfo(archive, entry, importer, prefix));
    }

    private static boolean isDex(byte[] bytes) {
        return bytes.length >= 8 && bytes[0] == 'd' && bytes[1] == 'e' && bytes[2] == 'x'
                && bytes[3] == '\n' && bytes[7] == 0;
    }

    private static Long millis(FileTime time) { return time == null ? null : time.toMillis(); }

    private static IOException importError(ExecutionException error) {
        if (error.getCause() instanceof Error fatal) throw fatal;
        return error.getCause() instanceof IOException io ? io : new IOException("APK import failed", error.getCause());
    }

    private record Indexed(ZipEntry entry, boolean dex, Info jvm, DeferredFileInfo file) {}
}
