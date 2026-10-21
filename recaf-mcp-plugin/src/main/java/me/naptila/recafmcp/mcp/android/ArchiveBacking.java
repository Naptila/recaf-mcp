package me.naptila.recafmcp.mcp.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Keeps archive contents on disk and bounds every requested decompression by its entry size. */
public final class ArchiveBacking implements AutoCloseable {
    private final Path path;
    private final BasicFileAttributes original;
    private final ZipFile zip;
    private final Map<String, ZipEntry> entries;
    private final AtomicLong materializedBytes = new AtomicLong();
    private volatile boolean closed;

    public ArchiveBacking(Path input) throws IOException {
        path = input.toRealPath();
        original = Files.readAttributes(path, BasicFileAttributes.class);
        zip = new ZipFile(path.toFile());
        try {
            Map<String, ZipEntry> indexed = new LinkedHashMap<>();
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (indexed.putIfAbsent(entry.getName(), new ZipEntry(entry)) != null) {
                    throw new IOException("duplicate ZIP entry: " + entry.getName());
                }
            }
            entries = Collections.unmodifiableMap(indexed);
            checkUnchanged();
        } catch (IOException | RuntimeException error) {
            zip.close();
            throw error;
        }
    }

    public Path path() { return path; }
    public Map<String, ZipEntry> entries() { return entries; }
    public long materializedBytes() { return materializedBytes.get(); }

    public void checkUnchanged() throws IOException {
        if (closed) throw new IOException("original APK is closed");
        var current = Files.readAttributes(path, BasicFileAttributes.class);
        if (current.size() != original.size()
                || !current.lastModifiedTime().equals(original.lastModifiedTime())
                || !Objects.equals(current.fileKey(), original.fileKey())) {
            throw new IOException("original APK changed since opening: " + path.getFileName());
        }
    }

    byte[] prefix(ZipEntry entry, int limit) throws IOException {
        try (InputStream stream = zip.getInputStream(entry)) {
            return stream.readNBytes(limit);
        }
    }

    public InputStream open(ZipEntry entry) throws IOException {
        return zip.getInputStream(entry);
    }

    public byte[] read(ZipEntry entry) throws IOException {
        checkUnchanged();
        long size = entry.getSize();
        long budget = Math.min(Integer.MAX_VALUE - 8L, Runtime.getRuntime().maxMemory() / 3);
        if (size < 0 || size > budget) {
            throw new IOException("ZIP entry exceeds the available materialization budget: " + entry.getName());
        }
        var output = new ByteArrayOutputStream((int) Math.min(size, 65536));
        CRC32 crc = new CRC32();
        long count = 0;
        byte[] buffer = new byte[65536];
        try (InputStream stream = zip.getInputStream(entry)) {
            for (int amount; (amount = stream.read(buffer)) != -1;) {
                count += amount;
                if (count > size) throw new IOException("ZIP entry exceeds its declared size: " + entry.getName());
                output.write(buffer, 0, amount);
                crc.update(buffer, 0, amount);
            }
        }
        if (count != size || crc.getValue() != entry.getCrc()) {
            throw new IOException("ZIP entry size or CRC differs from its directory: " + entry.getName());
        }
        checkUnchanged();
        materializedBytes.addAndGet(count);
        return output.toByteArray();
    }

    @Override
    public void close() throws IOException {
        closed = true;
        zip.close();
    }
}
