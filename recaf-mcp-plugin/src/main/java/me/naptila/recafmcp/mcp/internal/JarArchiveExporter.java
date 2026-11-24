package me.naptila.recafmcp.mcp.internal;

import me.naptila.recafmcp.mcp.android.AndroidDexWriter;
import me.naptila.recafmcp.mcp.android.RawZipWriter;
import software.coley.lljzip.ZipIO;
import software.coley.lljzip.format.model.CentralDirectoryFileHeader;
import software.coley.recaf.services.workspace.io.PathWorkspaceExportConsumer;
import software.coley.recaf.services.workspace.io.WorkspaceExportOptions;
import software.coley.recaf.services.workspace.io.WorkspaceOutputType;
import software.coley.recaf.workspace.model.Workspace;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Exports JAR/ZIP workspaces while keeping {@code META-INF/MANIFEST.MF} at the head of the
 * archive and preserving untouched original ZIP entries, directories, timestamps, and payloads.
 */
final class JarArchiveExporter {
    private static final String META_INF_DIR = "META-INF/";
    private static final String MANIFEST_NAME = JarFile.MANIFEST_NAME;
    private static final long MAX_CLASSIC_ZIP_BYTES = 0x70000000L;

    private JarArchiveExporter() {
    }

    record ExportReport(String jarSignatureStatus) {
    }

    static ExportReport export(Workspace workspace,
                               Path inputPath,
                               Path destination,
                               Set<String> cleanEmbeddedEntries) throws IOException {
        Path staged = Files.createTempFile(destination.getParent(), ".recaf-jar-stage-", ".jar");
        try {
            WorkspaceExportOptions options = new WorkspaceExportOptions(
                    WorkspaceOutputType.FILE,
                    new PathWorkspaceExportConsumer(staged)
            );
            options.setBundleSupporting(false);
            AndroidDexWriter.withCompatibility(() -> {
                options.create().export(workspace);
                return null;
            });

            if (Files.isRegularFile(inputPath) && isZipArchive(inputPath)) {
                return mergeWithOriginalZip(workspace, inputPath, staged, destination, cleanEmbeddedEntries);
            } else {
                return ensureManifestFirst(staged, destination);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static ExportReport mergeWithOriginalZip(Workspace workspace,
                                                     Path inputPath,
                                                     Path stagedPath,
                                                     Path destination,
                                                     Set<String> cleanEmbeddedEntries) throws IOException {
        try (ZipFile originalZip = new ZipFile(inputPath.toFile());
             ZipFile stagedZip = new ZipFile(stagedPath.toFile())) {
            Map<String, ZipEntry> originalEntries = indexAllEntries(originalZip);
            Map<String, ZipEntry> stagedFiles = indexFileEntries(stagedZip);
            boolean hadSignatureBlock = originalEntries.keySet().stream()
                    .anyMatch(JarArchiveExporter::isSignatureBlockFile);

            Set<String> unchangedNames = new HashSet<>();
            boolean archiveMutated = false;
            for (Map.Entry<String, ZipEntry> origEntry : originalEntries.entrySet()) {
                if (origEntry.getValue().isDirectory()) {
                    continue;
                }
                String name = origEntry.getKey();
                ZipEntry stagedEntry = stagedFiles.get(name);
                if (stagedEntry == null) {
                    archiveMutated = true;
                    continue;
                }
                if (cleanEmbeddedEntries.contains(name)) {
                    unchangedNames.add(name);
                    continue;
                }
                byte[] origBytes = readAll(originalZip, origEntry.getValue());
                byte[] stagedBytes = readAll(stagedZip, stagedEntry);
                if (Arrays.equals(origBytes, stagedBytes)) {
                    unchangedNames.add(name);
                } else {
                    archiveMutated = true;
                }
            }
            for (String stagedName : stagedFiles.keySet()) {
                ZipEntry orig = originalEntries.get(stagedName);
                if (orig == null || orig.isDirectory()) {
                    archiveMutated = true;
                    break;
                }
            }

            Set<String> activeFiles = new HashSet<>(stagedFiles.keySet());
            if (archiveMutated) {
                activeFiles.removeIf(JarArchiveExporter::isSignatureBlockFile);
            }
            String jarSignatureStatus = !hadSignatureBlock
                    ? "unsigned"
                    : (archiveMutated ? "removed" : "preserved");
            Map<String, String> renamedSlots = renamedClassSlots(
                    workspace,
                    originalEntries.keySet(),
                    activeFiles
            );
            Set<String> originalParentDirs = parentDirectories(
                    originalEntries.values().stream()
                            .filter(e -> !e.isDirectory())
                            .map(ZipEntry::getName)
                            .toList()
            );
            Set<String> activeParentDirs = parentDirectories(activeFiles);

            long estimatedUpperBound = Files.size(inputPath) + Files.size(stagedPath) + 65536L;
            long estimatedCount = (long) originalEntries.size() + activeFiles.size() + 4L;
            if (estimatedUpperBound < MAX_CLASSIC_ZIP_BYTES && estimatedCount < 65000L) {
                try {
                    writeRawStreaming(
                            inputPath,
                            originalZip,
                            originalEntries,
                            stagedZip,
                            stagedFiles,
                            activeFiles,
                            renamedSlots,
                            unchangedNames,
                            originalParentDirs,
                            activeParentDirs,
                            archiveMutated,
                            destination
                    );
                    return new ExportReport(jarSignatureStatus);
                } catch (IOException ex) {
                    Files.deleteIfExists(destination);
                }
            }

            writeStandardZip(
                    originalZip,
                    originalEntries,
                    stagedZip,
                    stagedFiles,
                    activeFiles,
                    renamedSlots,
                    unchangedNames,
                    originalParentDirs,
                    activeParentDirs,
                    archiveMutated,
                    destination
            );
            return new ExportReport(jarSignatureStatus);
        } catch (ZipException ignored) {
            return ensureManifestFirst(stagedPath, destination);
        }
    }

    private static Map<String, String> renamedClassSlots(Workspace workspace,
                                                         Set<String> originalEntryNames,
                                                         Set<String> activeFiles) {
        if (workspace == null) {
            return Map.of();
        }
        Map<String, String> slots = new LinkedHashMap<>();
        Set<String> claimedTargets = new HashSet<>();
        var primary = workspace.getPrimaryResource();
        primary.getJvmClassBundle().values().forEach(info -> {
            String orig = software.coley.recaf.info.properties.builtin.OriginalClassNameProperty.get(info);
            if (orig != null && !orig.equals(info.getName())) {
                String oldEntry = orig + ".class";
                String newEntry = info.getName() + ".class";
                if (originalEntryNames.contains(oldEntry)
                        && !activeFiles.contains(oldEntry)
                        && activeFiles.contains(newEntry)
                        && !slots.containsKey(oldEntry)
                        && claimedTargets.add(newEntry)) {
                    slots.put(oldEntry, newEntry);
                }
            }
        });
        primary.getVersionedJvmClassBundles().forEach((version, bundle) -> {
            String prefix = "META-INF/versions/" + version + "/";
            bundle.values().forEach(info -> {
                String orig = software.coley.recaf.info.properties.builtin.OriginalClassNameProperty.get(info);
                if (orig != null && !orig.equals(info.getName())) {
                    String oldEntry = prefix + orig + ".class";
                    String newEntry = prefix + info.getName() + ".class";
                    if (originalEntryNames.contains(oldEntry)
                            && !activeFiles.contains(oldEntry)
                            && activeFiles.contains(newEntry)
                            && !slots.containsKey(oldEntry)
                            && claimedTargets.add(newEntry)) {
                        slots.put(oldEntry, newEntry);
                    }
                }
            });
        });
        return slots;
    }

    private static void writeRawStreaming(Path inputPath,
                                          ZipFile originalZip,
                                          Map<String, ZipEntry> originalEntries,
                                          ZipFile stagedZip,
                                          Map<String, ZipEntry> stagedFiles,
                                          Set<String> activeFiles,
                                          Map<String, String> renamedSlots,
                                          Set<String> unchangedNames,
                                          Set<String> originalParentDirs,
                                          Set<String> activeParentDirs,
                                          boolean archiveMutated,
                                          Path destination) throws IOException {
        try (var channel = FileChannel.open(inputPath, StandardOpenOption.READ);
             var arena = Arena.ofConfined();
             var archive = ZipIO.readJvm(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena));
             var output = new BufferedOutputStream(Files.newOutputStream(destination), 65536)) {
            Map<String, CentralDirectoryFileHeader> centralByName = new LinkedHashMap<>();
            for (CentralDirectoryFileHeader central : archive.getCentralDirectories()) {
                centralByName.putIfAbsent(central.getFileNameAsString(), central);
            }

            RawZipWriter writer = new RawZipWriter(output, false);
            Set<String> emitted = new HashSet<>();

            String manifestKey = findManifestKey(activeFiles);
            if (manifestKey != null) {
                CentralDirectoryFileHeader metaInfDir = centralByName.get(META_INF_DIR);
                if (metaInfDir != null) {
                    writer.copy(metaInfDir);
                    emitted.add(META_INF_DIR);
                }
                emitFileRaw(
                        writer,
                        manifestKey,
                        centralByName.get(manifestKey),
                        originalEntries.get(manifestKey),
                        stagedZip,
                        stagedFiles.get(manifestKey),
                        unchangedNames.contains(manifestKey)
                );
                emitted.add(manifestKey);
            }

            for (CentralDirectoryFileHeader central : archive.getCentralDirectories()) {
                String name = central.getFileNameAsString();
                if (!emitted.add(name)) {
                    continue;
                }
                ZipEntry original = originalEntries.get(name);
                if (original != null && original.isDirectory()) {
                    if (shouldKeepDirectory(name, originalParentDirs, activeParentDirs, archiveMutated)) {
                        writer.copy(central);
                    }
                    continue;
                }
                if (!activeFiles.contains(name)) {
                    String replacement = renamedSlots.get(name);
                    if (replacement != null && activeFiles.contains(replacement) && emitted.add(replacement)) {
                        emitFileRaw(
                                writer,
                                replacement,
                                null,
                                original,
                                stagedZip,
                                stagedFiles.get(replacement),
                                false
                        );
                    }
                    continue;
                }
                emitFileRaw(
                        writer,
                        name,
                        central,
                        original,
                        stagedZip,
                        stagedFiles.get(name),
                        unchangedNames.contains(name)
                );
            }

            List<String> appended = activeFiles.stream()
                    .filter(name -> !emitted.contains(name))
                    .sorted()
                    .toList();
            for (String name : appended) {
                ZipEntry stagedEntry = stagedFiles.get(name);
                byte[] content = readAll(stagedZip, stagedEntry);
                writer.writeFile(name, content, stagedEntry);
            }

            byte[] comment = archive.getEnd() != null && archive.getEnd().getZipCommentAsString() != null
                    ? archive.getEnd().getZipCommentAsString().getBytes(StandardCharsets.UTF_8)
                    : new byte[0];
            writer.finish(comment);
        }
    }

    private static void emitFileRaw(RawZipWriter writer,
                                    String name,
                                    CentralDirectoryFileHeader central,
                                    ZipEntry original,
                                    ZipFile stagedZip,
                                    ZipEntry stagedEntry,
                                    boolean unchanged) throws IOException {
        if (unchanged && central != null) {
            writer.copy(central);
            return;
        }
        byte[] content = readAll(stagedZip, stagedEntry);
        writer.writeFile(name, content, original != null ? original : stagedEntry);
    }

    private static void writeStandardZip(ZipFile originalZip,
                                         Map<String, ZipEntry> originalEntries,
                                         ZipFile stagedZip,
                                         Map<String, ZipEntry> stagedFiles,
                                         Set<String> activeFiles,
                                         Map<String, String> renamedSlots,
                                         Set<String> unchangedNames,
                                         Set<String> originalParentDirs,
                                         Set<String> activeParentDirs,
                                         boolean archiveMutated,
                                         Path destination) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(destination), 65536),
                StandardCharsets.UTF_8
        )) {
            if (originalZip.getComment() != null) {
                out.setComment(originalZip.getComment());
            }
            Set<String> emitted = new HashSet<>();
            String manifestKey = findManifestKey(activeFiles);
            if (manifestKey != null) {
                ZipEntry metaInfDir = originalEntries.get(META_INF_DIR);
                if (metaInfDir != null) {
                    writeDirectoryEntry(out, META_INF_DIR, metaInfDir);
                    emitted.add(META_INF_DIR);
                }
                emitStandardFile(
                        out,
                        manifestKey,
                        originalZip,
                        originalEntries.get(manifestKey),
                        stagedZip,
                        stagedFiles.get(manifestKey),
                        unchangedNames.contains(manifestKey)
                );
                emitted.add(manifestKey);
            }

            for (Map.Entry<String, ZipEntry> item : originalEntries.entrySet()) {
                String name = item.getKey();
                if (!emitted.add(name)) {
                    continue;
                }
                ZipEntry original = item.getValue();
                if (original.isDirectory()) {
                    if (shouldKeepDirectory(name, originalParentDirs, activeParentDirs, archiveMutated)) {
                        writeDirectoryEntry(out, name, original);
                    }
                    continue;
                }
                if (!activeFiles.contains(name)) {
                    String replacement = renamedSlots.get(name);
                    if (replacement != null && activeFiles.contains(replacement) && emitted.add(replacement)) {
                        emitStandardFile(
                                out,
                                replacement,
                                originalZip,
                                original,
                                stagedZip,
                                stagedFiles.get(replacement),
                                false
                        );
                    }
                    continue;
                }
                emitStandardFile(
                        out,
                        name,
                        originalZip,
                        original,
                        stagedZip,
                        stagedFiles.get(name),
                        unchangedNames.contains(name)
                );
            }

            List<String> appended = activeFiles.stream()
                    .filter(name -> !emitted.contains(name))
                    .sorted()
                    .toList();
            for (String name : appended) {
                ZipEntry stagedEntry = stagedFiles.get(name);
                writeFileEntry(out, name, stagedEntry, readAll(stagedZip, stagedEntry));
            }
        }
    }

    private static void emitStandardFile(ZipOutputStream out,
                                         String name,
                                         ZipFile originalZip,
                                         ZipEntry original,
                                         ZipFile stagedZip,
                                         ZipEntry stagedEntry,
                                         boolean unchanged) throws IOException {
        if (unchanged && original != null) {
            writeFileEntry(out, name, original, readAll(originalZip, original));
        } else {
            writeFileEntry(out, name, original != null ? original : stagedEntry, readAll(stagedZip, stagedEntry));
        }
    }

    private static ExportReport ensureManifestFirst(Path stagedPath, Path destination) throws IOException {
        try (ZipFile stagedZip = new ZipFile(stagedPath.toFile())) {
            List<ZipEntry> entries = new ArrayList<>();
            Enumeration<? extends ZipEntry> enumeration = stagedZip.entries();
            boolean hasSignature = false;
            while (enumeration.hasMoreElements()) {
                ZipEntry next = enumeration.nextElement();
                entries.add(next);
                if (isSignatureBlockFile(next.getName())) {
                    hasSignature = true;
                }
            }
            String sigStatus = hasSignature ? "preserved" : "unsigned";
            int manifestIndex = -1;
            for (int index = 0; index < entries.size(); index++) {
                if (entries.get(index).getName().equalsIgnoreCase(MANIFEST_NAME)) {
                    manifestIndex = index;
                    break;
                }
            }
            boolean alreadyValid = manifestIndex == -1
                    || manifestIndex == 0
                    || (manifestIndex == 1 && entries.getFirst().getName().equalsIgnoreCase(META_INF_DIR));
            if (alreadyValid) {
                Files.copy(stagedPath, destination, StandardCopyOption.REPLACE_EXISTING);
                return new ExportReport(sigStatus);
            }
            entries.sort(Comparator.comparingInt(entry -> {
                String name = entry.getName();
                if (name.equalsIgnoreCase(META_INF_DIR)) {
                    return 0;
                }
                if (name.equalsIgnoreCase(MANIFEST_NAME)) {
                    return 1;
                }
                return 2;
            }));
            try (ZipOutputStream out = new ZipOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(destination), 65536),
                    StandardCharsets.UTF_8
            )) {
                for (ZipEntry entry : entries) {
                    if (entry.isDirectory()) {
                        writeDirectoryEntry(out, entry.getName(), entry);
                    } else {
                        writeFileEntry(out, entry.getName(), entry, readAll(stagedZip, entry));
                    }
                }
            }
            return new ExportReport(sigStatus);
        }
    }

    private static void writeDirectoryEntry(ZipOutputStream out,
                                            String name,
                                            ZipEntry template) throws IOException {
        ZipEntry dir = RawZipWriter.outputEntry(name, template);
        dir.setMethod(ZipEntry.STORED);
        dir.setSize(0L);
        dir.setCompressedSize(0L);
        dir.setCrc(0L);
        out.putNextEntry(dir);
        out.closeEntry();
    }

    private static void writeFileEntry(ZipOutputStream out,
                                       String name,
                                       ZipEntry template,
                                       byte[] bytes) throws IOException {
        ZipEntry entry = RawZipWriter.outputEntry(name, template);
        int method = template != null && template.getMethod() == ZipEntry.STORED
                ? ZipEntry.STORED
                : ZipEntry.DEFLATED;
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

    private static boolean shouldKeepDirectory(String dirName,
                                               Set<String> originalParentDirs,
                                               Set<String> activeParentDirs,
                                               boolean archiveMutated) {
        if (!archiveMutated) {
            return true;
        }
        if (!originalParentDirs.contains(dirName)) {
            return true;
        }
        return activeParentDirs.contains(dirName);
    }

    private static Set<String> parentDirectories(Iterable<String> fileNames) {
        Set<String> dirs = new HashSet<>();
        for (String name : fileNames) {
            int slash = name.indexOf('/');
            while (slash >= 0) {
                dirs.add(name.substring(0, slash + 1));
                slash = name.indexOf('/', slash + 1);
            }
        }
        return dirs;
    }

    private static String findManifestKey(Set<String> activeFiles) {
        if (activeFiles.contains(MANIFEST_NAME)) {
            return MANIFEST_NAME;
        }
        for (String name : activeFiles) {
            if (name.equalsIgnoreCase(MANIFEST_NAME)) {
                return name;
            }
        }
        return null;
    }

    private static boolean isSignatureBlockFile(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith(META_INF_DIR)) {
            return false;
        }
        String file = upper.substring(META_INF_DIR.length());
        return !file.contains("/")
                && (file.endsWith(".SF")
                || file.endsWith(".RSA")
                || file.endsWith(".DSA")
                || file.endsWith(".EC")
                || file.startsWith("SIG-"));
    }

    private static Map<String, ZipEntry> indexAllEntries(ZipFile zip) {
        Map<String, ZipEntry> map = new LinkedHashMap<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            map.putIfAbsent(entry.getName(), entry);
        }
        return map;
    }

    private static Map<String, ZipEntry> indexFileEntries(ZipFile zip) {
        Map<String, ZipEntry> map = new LinkedHashMap<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory()) {
                map.putIfAbsent(entry.getName(), entry);
            }
        }
        return map;
    }

    private static byte[] readAll(ZipFile zip, ZipEntry entry) throws IOException {
        try (var stream = zip.getInputStream(entry)) {
            return stream.readAllBytes();
        }
    }

    private static boolean isZipArchive(Path path) throws IOException {
        byte[] prefix;
        try (var stream = Files.newInputStream(path)) {
            prefix = stream.readNBytes(4);
        }
        return prefix.length == 4 && prefix[0] == 'P' && prefix[1] == 'K';
    }
}
