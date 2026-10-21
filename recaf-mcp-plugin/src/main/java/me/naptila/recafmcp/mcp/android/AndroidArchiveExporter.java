package me.naptila.recafmcp.mcp.android;

import software.coley.lljzip.ZipIO;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.properties.builtin.*;
import software.coley.recaf.services.workspace.io.WorkspaceExportConsumer;
import software.coley.recaf.services.workspace.io.WorkspaceExportOptions;
import software.coley.recaf.services.workspace.io.WorkspaceOutputType;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.bundle.BasicAndroidClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Encodes DEX with Recaf and streams resources without inflating the entire APK. */
public final class AndroidArchiveExporter {
    private final AndroidInputResource resource;

    public AndroidArchiveExporter(AndroidInputResource resource) { this.resource = resource; }

    public void export(Path destination) throws IOException {
        resource.checkInput();
        if (!resource.getEmbeddedResources().isEmpty()) {
            throw new IOException("streaming APK export requires embedded archives in the file bundle");
        }
        var replacements = encodeClasses();
        Map<String, FileInfo> files = new LinkedHashMap<>(resource.getFileBundle());
        for (String name : files.keySet()) {
            if (replacements.containsKey(name)) throw new IOException("duplicate export path: " + name);
        }
        var backing = resource.archive();
        long upperBound = Files.size(backing.path()) + 65536;
        for (byte[] content : replacements.values()) upperBound += content.length * 2L + 65536;
        for (var entry : files.entrySet()) {
            if (!resource.isOriginalFile(entry.getKey(), entry.getValue())) {
                upperBound += entry.getValue().getRawContent().length * 2L + 65536;
            }
        }
        Set<String> additional = new HashSet<>(files.keySet());
        additional.addAll(replacements.keySet());
        additional.removeAll(backing.entries().keySet());
        long count = backing.entries().size() + additional.size();
        if (upperBound >= 0xffffffffL || count >= 65535) {
            recompress(destination, files, replacements);
        } else {
            copyCompressed(destination, files, replacements);
        }
        resource.checkInput();
    }

    private void copyCompressed(Path destination, Map<String, FileInfo> files,
                                Map<String, byte[]> replacements) throws IOException {
        var backing = resource.archive();
        try (var archive = ZipIO.readJvm(backing.path());
             var output = new BufferedOutputStream(Files.newOutputStream(destination), 65536)) {
            if (archive.getCentralDirectories().size() != backing.entries().size()) {
                throw new IOException("ZIP directory inventory differs from the opened APK");
            }
            RawZipWriter writer = new RawZipWriter(output);
            Set<String> seen = new HashSet<>();
            for (var central : archive.getCentralDirectories()) {
                String name = central.getFileNameAsString();
                ZipEntry original = backing.entries().get(name);
                if (original == null || !seen.add(name)) throw new IOException("unexpected ZIP directory entry: " + name);
                if (signature(name)) continue;
                byte[] replacement = replacements.remove(name);
                FileInfo file = files.remove(name);
                if (replacement != null) writer.writeFile(name, replacement, original);
                else if (original.isDirectory() || file != null && resource.isOriginalFile(name, file)) writer.copy(central);
                else if (file != null) writer.writeFile(name, file.getRawContent(), properties(name, file, original));
            }
            for (var entry : replacements.entrySet()) writer.writeFile(entry.getKey(), entry.getValue(), null);
            for (var entry : files.entrySet()) {
                if (!signature(entry.getKey())) writer.writeFile(entry.getKey(), entry.getValue().getRawContent(),
                        properties(entry.getKey(), entry.getValue(), null));
            }
            writer.finish(archive.getEnd().getZipCommentAsString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void recompress(Path destination, Map<String, FileInfo> files,
                            Map<String, byte[]> replacements) throws IOException {
        var backing = resource.archive();
        try (var output = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(destination), 65536))) {
            for (ZipEntry original : backing.entries().values()) {
                String name = original.getName();
                if (signature(name)) continue;
                byte[] replacement = replacements.remove(name);
                FileInfo file = files.remove(name);
                if (replacement == null && file == null && !original.isDirectory()) continue;
                boolean unchanged = replacement == null && (original.isDirectory() || resource.isOriginalFile(name, file));
                ZipEntry entry = file != null && !unchanged ? properties(name, file, original)
                        : RawZipWriter.outputEntry(name, original);
                byte[] content = replacement != null ? replacement : unchanged ? null : file.getRawContent();
                if (entry.getMethod() == ZipEntry.STORED) {
                    CRC32 crc = new CRC32();
                    if (content != null) crc.update(content);
                    entry.setSize(content == null ? original.getSize() : content.length);
                    entry.setCrc(content == null ? original.getCrc() : crc.getValue());
                }
                output.putNextEntry(entry);
                if (content != null) output.write(content);
                else if (!original.isDirectory()) {
                    try (var stream = backing.open(original)) { stream.transferTo(output); }
                }
                output.closeEntry();
            }
            for (var entry : replacements.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey())); output.write(entry.getValue()); output.closeEntry();
            }
            for (var entry : files.entrySet()) {
                if (!signature(entry.getKey())) {
                    ZipEntry file = properties(entry.getKey(), entry.getValue(), null);
                    byte[] content = entry.getValue().getRawContent();
                    if (file.getMethod() == ZipEntry.STORED) {
                        CRC32 crc = new CRC32(); crc.update(content);
                        file.setSize(content.length); file.setCrc(crc.getValue());
                    }
                    output.putNextEntry(file); output.write(content); output.closeEntry();
                }
            }
        }
    }

    public void validateFiles(ZipFile zip) throws IOException {
        Set<String> expected = new HashSet<>(resource.getFileBundle().keySet());
        expected.addAll(resource.getAndroidClassBundles().keySet());
        resource.getJvmClassBundle().keySet().forEach(name -> expected.add(resource.originalJvmPaths().getOrDefault(name, name + ".class")));
        resource.getVersionedJvmClassBundles().forEach((version, bundle) -> bundle.keySet()
                .forEach(name -> expected.add("META-INF/versions/" + version + "/" + name + ".class")));
        expected.removeIf(AndroidArchiveExporter::signature);
        Set<String> actual = new HashSet<>();
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            if (!entry.isDirectory() && !actual.add(entry.getName())) throw new IOException("duplicate exported ZIP entry");
        }
        if (!expected.equals(actual)) throw new IOException("APK export resource inventory differs from workspace");
        for (var entry : resource.getFileBundle().entrySet()) {
            String name = entry.getKey();
            if (!signature(name) && resource.isOriginalFile(name, entry.getValue())) {
                var before = resource.archive().entries().get(name);
                var after = zip.getEntry(name);
                if (before.getSize() != after.getSize() || before.getCrc() != after.getCrc()) {
                    throw new IOException("unchanged resource metadata differs after export: " + name);
                }
            }
        }
    }

    private Map<String, byte[]> encodeClasses() throws IOException {
        Map<String, byte[]> output = new LinkedHashMap<>();
        for (var entry : resource.getAndroidClassBundles().entrySet()) {
            output.put(entry.getKey(), encodeDex(entry.getKey(), entry.getValue()));
        }
        resource.getJvmClassBundle().forEach((name, info) -> output.put(
                resource.originalJvmPaths().getOrDefault(name, name + ".class"), info.getBytecode()));
        resource.getVersionedJvmClassBundles().forEach((version, bundle) -> bundle.forEach((name, info) ->
                output.put("META-INF/versions/" + version + "/" + name + ".class", info.getBytecode())));
        return output;
    }

    private static ZipEntry properties(String name, FileInfo info, ZipEntry original) {
        ZipEntry entry = RawZipWriter.outputEntry(name, original);
        entry.setMethod(ZipCompressionProperty.getOr(info, ZipEntry.DEFLATED));
        entry.setComment(ZipCommentProperty.get(info));
        Long time = ZipCreationTimeProperty.get(info);
        if (time != null) entry.setCreationTime(FileTime.fromMillis(time));
        time = ZipModificationTimeProperty.get(info);
        if (time != null) entry.setLastModifiedTime(FileTime.fromMillis(time));
        time = ZipAccessTimeProperty.get(info);
        if (time != null) entry.setLastAccessTime(FileTime.fromMillis(time));
        return entry;
    }

    private static byte[] encodeDex(String name, AndroidClassBundle original) throws IOException {
        // The public exporter retains class order, link data and valid hidden-API metadata.
        // Clone the bundle map because closing a temporary resource clears its bundles.
        var bundle = new BasicAndroidClassBundle(original.getVersion(), original.getLinkData(), original.getDexMetadata());
        original.values().forEach(bundle::initialPut);
        var resource = new WorkspaceResourceBuilder().withAndroidClassBundles(Map.of(name, bundle)).build();
        var workspace = new BasicWorkspace(resource, List.of(), false);
        byte[][] result = new byte[1][];
        WorkspaceExportConsumer consumer = new WorkspaceExportConsumer() {
            public void write(byte[] bytes) throws IOException { throw new IOException("expected a relative DEX export"); }
            public void writeRelative(String relative, byte[] bytes) throws IOException {
                if (!relative.equals(name) || result[0] != null) throw new IOException("unexpected DEX export path");
                result[0] = bytes;
            }
            public void commit() {}
        };
        try {
            var options = new WorkspaceExportOptions(WorkspaceOutputType.DIRECTORY, consumer);
            options.setBundleSupporting(false);
            AndroidDexWriter.withCompatibility(() -> { options.create().export(workspace); return null; });
            if (result[0] == null) throw new IOException("Recaf did not encode DEX: " + name);
            return result[0];
        } finally { workspace.close(); }
    }

    private static boolean signature(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) return false;
        String file = upper.substring(9);
        return !file.contains("/") && (file.equals("MANIFEST.MF") || file.endsWith(".SF")
                || file.endsWith(".RSA") || file.endsWith(".DSA") || file.endsWith(".EC") || file.startsWith("SIG-"));
    }
}
