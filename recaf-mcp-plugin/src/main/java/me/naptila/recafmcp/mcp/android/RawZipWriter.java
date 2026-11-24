package me.naptila.recafmcp.mcp.android;

import software.coley.lljzip.ZipIO;
import software.coley.lljzip.format.model.CentralDirectoryFileHeader;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Streams original compressed payloads, rebuilding offsets, aligning STORED entries, and removing data descriptors. */
public final class RawZipWriter {
    private static final long MAX = 0xffffffffL;
    private static final int ZIPALIGN_EXTRA_HEADER_ID = 0xd935;
    private static final int DEFAULT_STORED_ALIGNMENT = 4;
    private static final int SHARED_OBJECT_ALIGNMENT = 16384;
    private final OutputStream output;
    private final boolean alignStoredEntries;
    private final List<byte[]> directory = new ArrayList<>();
    private final byte[] buffer = new byte[65536];
    private long position;

    public RawZipWriter(OutputStream output) {
        this(output, true);
    }

    public RawZipWriter(OutputStream output, boolean alignStoredEntries) {
        this.output = output;
        this.alignStoredEntries = alignStoredEntries;
    }

    public void copy(CentralDirectoryFileHeader central) throws IOException {
        copy(central, false);
    }

    private void copy(CentralDirectoryFileHeader central, boolean stripExtendedTimestamp) throws IOException {
        var local = central.getLinkedFileHeader();
        String name = central.getFileNameAsString();
        if (local == null || !local.getFileNameAsString().equals(name)) {
            throw new IOException("ZIP local header does not match directory: " + name);
        }
        int flags = central.getGeneralPurposeBitFlag() & ~8;
        if ((flags & 1) != 0) throw new IOException("encrypted APK entries are unsupported");
        long compressed = central.getCompressedSize();
        if (compressed < 0 || compressed >= MAX || central.getUncompressedSize() >= MAX || position >= MAX) {
            throw new IOException("ZIP64 entry requires streaming recompression");
        }
        if (local.getFileData().byteSize() < compressed) throw new IOException("truncated compressed ZIP entry");
        boolean alignStored = alignStoredEntries
                && central.getCompressionMethod() == ZipEntry.STORED
                && !name.endsWith("/");
        byte[] header = local.getBackingData().asSlice(local.offset(),
                30L + local.getFileNameLength() + local.getExtraFieldLength()).toArray(ValueLayout.JAVA_BYTE);
        header = rebuildHeaderExtra(
                header,
                30,
                local.getFileNameLength(),
                local.getExtraFieldLength(),
                28,
                alignStored,
                stripExtendedTimestamp,
                alignStored ? storedAlignment(name) : 0,
                position
        );
        put(header, 6, flags, 2);
        put(header, 8, central.getCompressionMethod(), 2);
        put(header, 14, Integer.toUnsignedLong(central.getCrc32()), 4);
        put(header, 18, compressed, 4);
        put(header, 22, central.getUncompressedSize(), 4);
        byte[] record = central.getBackingData().asSlice(central.offset(), central.length())
                .toArray(ValueLayout.JAVA_BYTE);
        record = rebuildHeaderExtra(
                record,
                46,
                central.getFileNameLength(),
                central.getExtraFieldLength(),
                30,
                alignStoredEntries,
                stripExtendedTimestamp,
                0,
                0L
        );
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

    public static int storedAlignment(String entryName) {
        return entryName.endsWith(".so") ? SHARED_OBJECT_ALIGNMENT : DEFAULT_STORED_ALIGNMENT;
    }

    public static boolean hasUncompressedSharedObjects(Path apkPath) throws IOException {
        try (var channel = FileChannel.open(apkPath, StandardOpenOption.READ);
             var arena = Arena.ofConfined();
             var archive = ZipIO.readJvm(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena))) {
            for (var central : archive.getCentralDirectories()) {
                String name = central.getFileNameAsString();
                if (central.getCompressionMethod() == ZipEntry.STORED && name.endsWith(".so")) {
                    return true;
                }
            }
            return false;
        }
    }

    public static boolean verifyZipAlignment(Path apkPath) throws IOException {
        try (var channel = FileChannel.open(apkPath, StandardOpenOption.READ);
             var arena = Arena.ofConfined();
             var archive = ZipIO.readJvm(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena))) {
            for (var central : archive.getCentralDirectories()) {
                String name = central.getFileNameAsString();
                if (central.getCompressionMethod() != ZipEntry.STORED || name.endsWith("/")) {
                    continue;
                }
                var local = central.getLinkedFileHeader();
                if (local == null) {
                    return false;
                }
                long dataOffset = local.offset() + 30L + local.getFileNameLength() + local.getExtraFieldLength();
                if (dataOffset % storedAlignment(name) != 0) {
                    return false;
                }
            }
            return true;
        }
    }

    public static void realignApk(Path apkPath) throws IOException {
        if (verifyZipAlignment(apkPath)) {
            return;
        }
        Path sibling = Files.createTempFile(apkPath.getParent(), ".recaf-align-", ".apk");
        try {
            try (var channel = FileChannel.open(apkPath, StandardOpenOption.READ);
                 var arena = Arena.ofConfined();
                 var archive = ZipIO.readJvm(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena));
                 var out = new BufferedOutputStream(Files.newOutputStream(sibling), 65536)) {
                RawZipWriter writer = new RawZipWriter(out);
                for (var central : archive.getCentralDirectories()) {
                    writer.copy(central);
                }
                byte[] comment = archive.getEnd() != null && archive.getEnd().getZipCommentAsString() != null
                        ? archive.getEnd().getZipCommentAsString().getBytes(StandardCharsets.UTF_8)
                        : new byte[0];
                writer.finish(comment);
            }
            Files.move(sibling, apkPath, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(sibling);
        }
    }

    public void writeFile(String name, byte[] content, ZipEntry original) throws IOException {
        boolean hadExtendedTime = original != null && hasExtendedTimestampExtra(original.getExtra());
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
            copy(archive.getCentralDirectories().getFirst(), !hadExtendedTime);
        }
    }

    public void finish(byte[] comment) throws IOException {
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

    public static ZipEntry outputEntry(String name, ZipEntry original) {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(original == null ? ZipEntry.DEFLATED : original.getMethod());
        if (original != null) {
            entry.setComment(original.getComment());
            byte[] extra = original.getExtra();
            if (hasExtendedTimestampExtra(extra)) {
                if (original.getTime() >= 0) entry.setTime(original.getTime());
                if (original.getCreationTime() != null) entry.setCreationTime(original.getCreationTime());
                if (original.getLastModifiedTime() != null) entry.setLastModifiedTime(original.getLastModifiedTime());
                if (original.getLastAccessTime() != null) entry.setLastAccessTime(original.getLastAccessTime());
            } else {
                java.time.LocalDateTime localTime = null;
                try {
                    localTime = original.getTimeLocal();
                } catch (java.time.DateTimeException ignored) {
                    // Zeroed or out-of-range DOS timestamp in APK/ZIP header.
                }
                if (localTime != null && localTime.getYear() >= 1980 && localTime.getYear() <= 2107) {
                    entry.setTimeLocal(localTime);
                } else {
                    entry.setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0, 0));
                }
            }
            if (extra != null) {
                // ZIP64 sizes/offsets and prior zipalign padding belong to the old entry layout.
                byte[] cleaned = cleanExtraBytes(extra, 0, extra.length, true, false);
                if (cleaned.length > 0) {
                    entry.setExtra(cleaned);
                }
            }
        }
        return entry;
    }

    public static boolean hasExtendedTimestampExtra(byte[] extra) {
        if (extra == null || extra.length < 4) {
            return false;
        }
        for (int offset = 0; offset + 4 <= extra.length;) {
            int id = unsignedShort(extra, offset);
            int length = unsignedShort(extra, offset + 2);
            if (id == 0x5455 || id == 0x5855 || id == 0x000a) {
                return true;
            }
            if (offset + 4 + length > extra.length) {
                break;
            }
            offset += 4 + length;
        }
        return false;
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }

    private static boolean allZero(byte[] bytes, int start, int end) {
        for (int index = start; index < end; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] cleanExtraBytes(byte[] bytes,
                                          int start,
                                          int extraLength,
                                          boolean stripAlignment,
                                          boolean stripExtendedTimestamp) {
        int end = start + extraLength;
        var kept = new ByteArrayOutputStream();
        for (int offset = start; offset < end;) {
            if (offset + 4 > end) {
                if (!stripAlignment || !allZero(bytes, offset, end)) {
                    kept.write(bytes, offset, end - offset);
                }
                break;
            }
            int id = unsignedShort(bytes, offset);
            int length = unsignedShort(bytes, offset + 2);
            if (stripAlignment && id == 0 && length == 0 && allZero(bytes, offset, end)) {
                break;
            }
            if (offset + 4 + length > end) {
                if (!stripAlignment || !allZero(bytes, offset, end)) {
                    kept.write(bytes, offset, end - offset);
                }
                break;
            }
            boolean dropTimestamp = stripExtendedTimestamp && (id == 0x5455 || id == 0x5855 || id == 0x000a);
            if (id != 1 && !dropTimestamp && (!stripAlignment || id != ZIPALIGN_EXTRA_HEADER_ID)) {
                kept.write(bytes, offset, 4 + length);
            }
            offset += 4 + length;
        }
        return kept.toByteArray();
    }

    private static byte[] rebuildHeaderExtra(byte[] header,
                                             int fixed,
                                             int nameLength,
                                             int extraLength,
                                             int lengthOffset,
                                             boolean stripAlignment,
                                             boolean stripExtendedTimestamp,
                                             int alignment,
                                             long entryPosition) throws IOException {
        int start = fixed + nameLength;
        byte[] cleaned = cleanExtraBytes(header, start, extraLength, stripAlignment, stripExtendedTimestamp);
        byte[] finalExtra = cleaned;
        if (alignment > 1) {
            long unalignedDataStart = entryPosition + start + cleaned.length;
            int pad = (int) ((alignment - (unalignedDataStart % alignment)) % alignment);
            if (pad > 0) {
                if (cleaned.length + pad > 0xffff) {
                    throw new IOException("ZIP local extra field exceeds 65535 bytes during alignment");
                }
                finalExtra = new byte[cleaned.length + pad];
                System.arraycopy(cleaned, 0, finalExtra, 0, cleaned.length);
                if (pad >= 6) {
                    put(finalExtra, cleaned.length, ZIPALIGN_EXTRA_HEADER_ID, 2);
                    put(finalExtra, cleaned.length + 2, pad - 4, 2);
                    put(finalExtra, cleaned.length + 4, alignment, 2);
                }
            }
        }
        if (finalExtra.length == extraLength
                && java.util.Arrays.equals(
                        finalExtra,
                        0,
                        finalExtra.length,
                        header,
                        start,
                        start + extraLength
                )) {
            return header;
        }
        byte[] result = new byte[header.length - extraLength + finalExtra.length];
        System.arraycopy(header, 0, result, 0, start);
        System.arraycopy(finalExtra, 0, result, start, finalExtra.length);
        System.arraycopy(
                header,
                start + extraLength,
                result,
                start + finalExtra.length,
                header.length - start - extraLength
        );
        put(result, lengthOffset, finalExtra.length, 2);
        return result;
    }

    private void write(byte[] bytes) throws IOException { output.write(bytes); position += bytes.length; }

    private static void put(byte[] bytes, int offset, long value, int width) {
        for (int index = 0; index < width; index++) bytes[offset + index] = (byte) (value >>> (index * 8));
    }
}
