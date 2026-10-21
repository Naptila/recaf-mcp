package me.naptila.recafmcp.mcp.android;

import software.coley.lljzip.ZipIO;
import software.coley.lljzip.format.model.CentralDirectoryFileHeader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Streams original compressed payloads, rebuilding offsets and removing data descriptors. */
final class RawZipWriter {
    private static final long MAX = 0xffffffffL;
    private final OutputStream output;
    private final List<byte[]> directory = new ArrayList<>();
    private final byte[] buffer = new byte[65536];
    private long position;

    RawZipWriter(OutputStream output) { this.output = output; }

    void copy(CentralDirectoryFileHeader central) throws IOException {
        var local = central.getLinkedFileHeader();
        if (local == null || !local.getFileNameAsString().equals(central.getFileNameAsString())) {
            throw new IOException("ZIP local header does not match directory: " + central.getFileNameAsString());
        }
        int flags = central.getGeneralPurposeBitFlag() & ~8;
        if ((flags & 1) != 0) throw new IOException("encrypted APK entries are unsupported");
        long compressed = central.getCompressedSize();
        if (compressed < 0 || compressed >= MAX || central.getUncompressedSize() >= MAX || position >= MAX) {
            throw new IOException("ZIP64 entry requires streaming recompression");
        }
        if (local.getFileData().byteSize() < compressed) throw new IOException("truncated compressed ZIP entry");
        byte[] header = local.getBackingData().asSlice(local.offset(),
                30L + local.getFileNameLength() + local.getExtraFieldLength()).toArray(ValueLayout.JAVA_BYTE);
        header = stripZip64(header, 30, local.getFileNameLength(), local.getExtraFieldLength(), 28);
        put(header, 6, flags, 2);
        put(header, 8, central.getCompressionMethod(), 2);
        put(header, 14, Integer.toUnsignedLong(central.getCrc32()), 4);
        put(header, 18, compressed, 4);
        put(header, 22, central.getUncompressedSize(), 4);
        byte[] record = central.getBackingData().asSlice(central.offset(), central.length())
                .toArray(ValueLayout.JAVA_BYTE);
        record = stripZip64(record, 46, central.getFileNameLength(), central.getExtraFieldLength(), 30);
        put(record, 8, flags, 2);
        put(record, 20, compressed, 4);
        put(record, 24, central.getUncompressedSize(), 4);
        put(record, 34, 0, 2);
        put(record, 42, position, 4);
        directory.add(record);
        write(header);
        var data = local.getFileData();
        for (long offset = 0; offset < compressed;) {
            int length = (int) Math.min(buffer.length, compressed - offset);
            MemorySegment.copy(data, offset, MemorySegment.ofArray(buffer), 0, length);
            output.write(buffer, 0, length);
            position += length;
            offset += length;
        }
    }

    void writeFile(String name, byte[] content, ZipEntry original) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            ZipEntry entry = outputEntry(name, original);
            if (entry.getMethod() == ZipEntry.STORED) {
                CRC32 crc = new CRC32();
                crc.update(content);
                entry.setSize(content.length);
                entry.setCompressedSize(content.length);
                entry.setCrc(crc.getValue());
            }
            zip.putNextEntry(entry);
            zip.write(content);
            zip.closeEntry();
        }
        try (var archive = ZipIO.readJvm(bytes.toByteArray())) {
            copy(archive.getCentralDirectories().getFirst());
        }
    }

    void finish(byte[] comment) throws IOException {
        long start = position;
        for (byte[] record : directory) write(record);
        if (start >= MAX || position - start >= MAX || directory.size() >= 65535 || comment.length > 65535) {
            throw new IOException("ZIP64 directory requires streaming recompression");
        }
        byte[] end = new byte[22];
        put(end, 0, 0x06054b50, 4);
        put(end, 8, directory.size(), 2);
        put(end, 10, directory.size(), 2);
        put(end, 12, position - start, 4);
        put(end, 16, start, 4);
        put(end, 20, comment.length, 2);
        write(end);
        write(comment);
    }

    static ZipEntry outputEntry(String name, ZipEntry original) {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(original == null ? ZipEntry.DEFLATED : original.getMethod());
        if (original != null) {
            entry.setComment(original.getComment());
            if (original.getTime() >= 0) entry.setTime(original.getTime());
            if (original.getCreationTime() != null) entry.setCreationTime(original.getCreationTime());
            if (original.getLastModifiedTime() != null) entry.setLastModifiedTime(original.getLastModifiedTime());
            if (original.getLastAccessTime() != null) entry.setLastAccessTime(original.getLastAccessTime());
            byte[] extra = original.getExtra();
            if (extra != null) {
                // ZIP64 sizes and offsets belong to the old entry, not the newly encoded one.
                var kept = new ByteArrayOutputStream();
                for (int offset = 0; offset < extra.length;) {
                    if (offset + 4 > extra.length) {
                        kept.write(extra, offset, extra.length - offset);
                        break;
                    }
                    int id = unsignedShort(extra, offset), length = unsignedShort(extra, offset + 2);
                    if (offset + 4 + length > extra.length) {
                        kept.write(extra, offset, extra.length - offset);
                        break;
                    }
                    if (id != 1) kept.write(extra, offset, 4 + length);
                    offset += 4 + length;
                }
                entry.setExtra(kept.toByteArray());
            }
        }
        return entry;
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }

    private static byte[] stripZip64(byte[] header, int fixed, int nameLength, int extraLength, int lengthOffset) {
        int start = fixed + nameLength;
        var kept = new ByteArrayOutputStream();
        for (int offset = start; offset < start + extraLength;) {
            // Android zipalign may append opaque padding rather than another TLV.
            // Retain it exactly; only remove complete ZIP64 records we understand.
            if (offset + 4 > start + extraLength) {
                kept.write(header, offset, start + extraLength - offset);
                break;
            }
            int id = unsignedShort(header, offset), length = unsignedShort(header, offset + 2);
            if (offset + 4 + length > start + extraLength) {
                kept.write(header, offset, start + extraLength - offset);
                break;
            }
            if (id != 1) kept.write(header, offset, 4 + length);
            offset += 4 + length;
        }
        if (kept.size() == extraLength) return header;
        byte[] result = new byte[header.length - extraLength + kept.size()];
        System.arraycopy(header, 0, result, 0, start);
        System.arraycopy(kept.toByteArray(), 0, result, start, kept.size());
        System.arraycopy(header, start + extraLength, result, start + kept.size(), header.length - start - extraLength);
        put(result, lengthOffset, kept.size(), 2);
        return result;
    }

    private void write(byte[] bytes) throws IOException { output.write(bytes); position += bytes.length; }

    private static void put(byte[] bytes, int offset, long value, int width) {
        for (int index = 0; index < width; index++) bytes[offset + index] = (byte) (value >>> (index * 8));
    }
}
