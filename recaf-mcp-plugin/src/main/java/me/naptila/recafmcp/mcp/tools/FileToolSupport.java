package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import software.coley.recaf.info.BinaryXmlFileInfo;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.TextFileInfo;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Shared primary-resource non-class file metadata helpers.
 */
final class FileToolSupport {
    private static final Comparator<Entry> ENTRY_ORDER = Comparator.comparing(Entry::path);

    private FileToolSupport() {
    }

    static List<Entry> entries(WorkspaceResource resource) {
        return resource.getFileBundle()
                .entrySet()
                .stream()
                .filter(entry -> !entry.getValue().isClass())
                .map(entry -> new Entry(entry.getKey(), entry.getValue()))
                .sorted(ENTRY_ORDER)
                .toList();
    }

    static Entry exact(WorkspaceResource resource, String path) {
        FileInfo fileInfo = resource.getFileBundle().get(path);
        if (fileInfo == null || fileInfo.isClass()) {
            return null;
        }
        return new Entry(path, fileInfo);
    }

    static void addSummary(JsonObject target, Entry entry) {
        FileInfo fileInfo = entry.fileInfo();
        target.addProperty("path", entry.path());
        target.addProperty("size_bytes", fileInfo.getRawContent().length);
        target.addProperty("kind", kind(fileInfo));
        target.addProperty("is_text", fileInfo.isTextFile());
        target.addProperty("text_available", isTextAvailable(fileInfo));
        addNullable(target, "extension", emptyToNull(fileInfo.getFileExtension()));
        addNullable(target, "directory", emptyToNull(fileInfo.getDirectoryName()));
        if (fileInfo.isTextFile()) {
            TextFileInfo textFileInfo = fileInfo.asTextFile();
            target.addProperty("charset", textFileInfo.getCharset().name());
        } else {
            target.add("charset", JsonNull.INSTANCE);
        }
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)
            );
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    static boolean isTextAvailable(FileInfo fileInfo) {
        return fileInfo.isTextFile() || fileInfo instanceof BinaryXmlFileInfo;
    }

    private static String kind(FileInfo fileInfo) {
        if (fileInfo instanceof BinaryXmlFileInfo) {
            return "android_binary_xml";
        }
        if (fileInfo.isTextFile()) {
            return "text";
        }
        if (fileInfo.isImageFile()) {
            return "image";
        }
        if (fileInfo.isAudioFile()) {
            return "audio";
        }
        if (fileInfo.isVideoFile()) {
            return "video";
        }
        if (fileInfo.isNativeLibraryFile()) {
            return "native_library";
        }
        if (fileInfo.isZipFile()) {
            return "zip";
        }
        return "binary";
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static void addNullable(JsonObject target, String name, String value) {
        if (value == null) {
            target.add(name, JsonNull.INSTANCE);
        } else {
            target.addProperty(name, value);
        }
    }

    record Entry(String path, FileInfo fileInfo) {
        Entry {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(fileInfo, "fileInfo");
        }
    }
}
