package me.naptila.recafmcp.mcp.android;

import software.coley.recaf.info.*;
import software.coley.recaf.info.builder.FileInfoBuilder;
import software.coley.recaf.info.properties.builtin.*;
import software.coley.recaf.services.workspace.io.InfoImporter;
import software.coley.recaf.util.io.ByteSources;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;

/** A file's header supplies listing metadata; full bytes are loaded only on an explicit read. */
public final class DeferredFileInfo extends BasicFileInfo {
    private final ArchiveBacking backing;
    private final ZipEntry entry;
    private final InfoImporter importer;
    private final FileInfo preview;
    private volatile FileInfo resolved;
    private byte[] originalHash;
    private final List<?> originalProperties;

    DeferredFileInfo(ArchiveBacking backing, ZipEntry entry, InfoImporter importer, byte[] prefix) throws IOException {
        super(new FileInfoBuilder<>().withName(entry.getName()).withRawContent(new byte[0]));
        this.backing = backing;
        this.entry = entry;
        this.importer = importer;
        Info hint = importer.readInfo(entry.getName(), ByteSources.wrap(prefix));
        preview = hint.isFile() ? hint.asFile()
                : new FileInfoBuilder<>().withName(entry.getName()).withRawContent(prefix).build();
        copyEntryProperties(this, entry);
        originalProperties = entryProperties(this);
    }

    public long size() { return entry.getSize(); }
    public FileInfo preview() { return preview; }
    public boolean isMaterialized() { return resolved != null; }

    public synchronized FileInfo resolve() {
        if (resolved == null) {
            try {
                byte[] bytes = backing.read(entry);
                Info loaded = importer.readInfo(getName(), ByteSources.wrap(bytes));
                FileInfo file = loaded.isFile() ? loaded.asFile()
                        : new FileInfoBuilder<>().withName(getName()).withRawContent(bytes).build();
                getProperties().values().forEach(file::setProperty);
                originalHash = hash(bytes);
                resolved = file;
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
        return resolved;
    }

    boolean isOriginal(FileInfo current) {
        if (current != this && current != resolved) return false;
        if (!originalProperties.equals(entryProperties(current))) return false;
        FileInfo loaded = resolved;
        return loaded == null || Arrays.equals(originalHash, hash(loaded.getRawContent()));
    }

    static void copyEntryProperties(Info info, ZipEntry entry) {
        ZipCompressionProperty.set(info, entry.getMethod());
        if (entry.getComment() != null) ZipCommentProperty.set(info, entry.getComment());
        if (entry.getCreationTime() != null) ZipCreationTimeProperty.set(info, entry.getCreationTime().toMillis());
        if (entry.getLastModifiedTime() != null) ZipModificationTimeProperty.set(info, entry.getLastModifiedTime().toMillis());
        if (entry.getLastAccessTime() != null) ZipAccessTimeProperty.set(info, entry.getLastAccessTime().toMillis());
    }

    private static List<?> entryProperties(Info info) {
        return Arrays.asList(ZipCompressionProperty.get(info), ZipCommentProperty.get(info), ZipCreationTimeProperty.get(info),
                ZipModificationTimeProperty.get(info), ZipAccessTimeProperty.get(info));
    }

    private static byte[] hash(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    @Override public byte[] getRawContent() { return resolve().getRawContent(); }
    @Override public FileInfoBuilder<?> toFileBuilder() { return resolve().toFileBuilder(); }
    @Override public boolean isTextFile() { return resolve().isTextFile(); }
    @Override public boolean isImageFile() { return resolve().isImageFile(); }
    @Override public boolean isAudioFile() { return resolve().isAudioFile(); }
    @Override public boolean isVideoFile() { return resolve().isVideoFile(); }
    @Override public boolean isNativeLibraryFile() { return resolve().isNativeLibraryFile(); }
    @Override public boolean isZipFile() { return resolve().isZipFile(); }
    @Override public TextFileInfo asTextFile() { return resolve().asTextFile(); }
    @Override public ImageFileInfo asImageFile() { return resolve().asImageFile(); }
    @Override public AudioFileInfo asAudioFile() { return resolve().asAudioFile(); }
    @Override public VideoFileInfo asVideoFile() { return resolve().asVideoFile(); }
    @Override public NativeLibraryFileInfo asNativeLibraryFile() { return resolve().asNativeLibraryFile(); }
    @Override public ZipFileInfo asZipFile() { return resolve().asZipFile(); }
}
