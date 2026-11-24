package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.android.DeferredFileInfo;
import me.naptila.recafmcp.mcp.util.Hashing;
import software.coley.recaf.info.ArscFileInfo;
import software.coley.recaf.info.BinaryXmlFileInfo;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.TextFileInfo;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Shared primary and embedded resource non-class file metadata helpers.
 */
final class FileToolSupport {
    private static final Comparator<Entry> ENTRY_ORDER = Comparator
            .comparing(Entry::path)
            .thenComparing(Entry::resourcePath, Comparator.nullsFirst(String::compareTo));

    private FileToolSupport() {
    }

    static List<Entry> entries(WorkspaceResource resource) {
        return entries(resource, null, false);
    }

    static List<Entry> entries(WorkspaceResource primary,
                               String resourcePath,
                               boolean includeEmbedded) {
        List<Entry> results = new ArrayList<>();
        if (resourcePath != null) {
            WorkspaceResource resolved = ClassToolSupport.resolveEmbeddedResource(
                    primary,
                    resourcePath
            );
            if (resolved != null) {
                collectEntries(resolved, resourcePath, results);
            }
        } else {
            collectEntries(primary, null, results);
            if (includeEmbedded) {
                for (ClassToolSupport.ScopedResource scoped
                        : ClassToolSupport.embeddedResources(primary)) {
                    collectEntries(scoped.resource(), scoped.path(), results);
                }
            }
        }
        results.sort(ENTRY_ORDER);
        return results;
    }

    private static void collectEntries(WorkspaceResource resource,
                                       String resourcePath,
                                       List<Entry> target) {
        resource.getFileBundle()
                .entrySet()
                .stream()
                .filter(entry -> !entry.getValue().isClass())
                .map(entry -> new Entry(
                        entry.getKey(),
                        resourcePath,
                        resource,
                        entry.getValue()
                ))
                .forEach(target::add);
    }

    static Entry exact(WorkspaceResource resource, String path) {
        return exact(resource, null, path);
    }

    static Entry exact(WorkspaceResource primary, String resourcePath, String path) {
        WorkspaceResource target = resourcePath == null
                ? primary
                : ClassToolSupport.resolveEmbeddedResource(primary, resourcePath);
        if (target == null) {
            return null;
        }
        FileInfo fileInfo = target.getFileBundle().get(path);
        if (fileInfo == null || fileInfo.isClass()) {
            return null;
        }
        return new Entry(path, resourcePath, target, fileInfo);
    }

    static void addSummary(JsonObject target, Entry entry) {
        FileInfo fileInfo = entry.fileInfo();
        target.addProperty("path", entry.path());
        if (entry.resourcePath() != null) {
            target.addProperty("resource", entry.resourcePath());
        }
        target.addProperty("size_bytes", fileInfo instanceof DeferredFileInfo deferred
                ? deferred.size() : fileInfo.getRawContent().length);
        if (fileInfo instanceof DeferredFileInfo deferred) fileInfo = deferred.preview();
        target.addProperty("kind", kind(fileInfo));
        target.addProperty("is_text", fileInfo.isTextFile());
        target.addProperty("text_available", isTextAvailable(fileInfo));
        addNullable(target, "extension", extension(entry.path()));
        addNullable(target, "directory", emptyToNull(fileInfo.getDirectoryName()));
        if (fileInfo.isTextFile()) {
            TextFileInfo textFileInfo = fileInfo.asTextFile();
            target.addProperty("charset", textFileInfo.getCharset().name());
        } else {
            target.add("charset", JsonNull.INSTANCE);
        }
    }

    static String extension(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        String fileName = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1);
    }

    static String sha256(byte[] content) {
        return Hashing.sha256(content);
    }

    static boolean isTextAvailable(FileInfo fileInfo) {
        return fileInfo.isTextFile()
                || fileInfo instanceof BinaryXmlFileInfo
                || fileInfo instanceof ArscFileInfo;
    }

    private static String kind(FileInfo fileInfo) {
        if (fileInfo instanceof BinaryXmlFileInfo) {
            return "android_binary_xml";
        }
        if (fileInfo instanceof ArscFileInfo) {
            return "android_arsc";
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

    record Entry(String path,
                 String resourcePath,
                 WorkspaceResource resource,
                 FileInfo fileInfo) {
        Entry {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(fileInfo, "fileInfo");
        }

        Entry(String path, FileInfo fileInfo) {
            this(path, null, null, fileInfo);
        }
    }
}
