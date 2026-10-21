package me.naptila.recafmcp.mcp.android;

import software.coley.recaf.info.FileInfo;
import software.coley.recaf.workspace.model.resource.BasicWorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResourceBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** An Android resource whose DEX trees have already been read with fidelity repairs. */
public final class AndroidInputResource extends BasicWorkspaceFileResource {
    private final Path input;
    private final BasicFileAttributes original;
    private final ArchiveBacking archive;
    private final Map<String, Set<String>> classes = new LinkedHashMap<>();
    private final Map<String, DeferredFileInfo> originalFiles;
    private final Map<String, String> jvmPaths;

    AndroidInputResource(WorkspaceFileResourceBuilder builder, Path input, ArchiveBacking archive,
                         Map<String, DeferredFileInfo> originalFiles, Map<String, String> jvmPaths) throws IOException {
        super(builder);
        this.input = input.toRealPath();
        this.original = Files.readAttributes(this.input, BasicFileAttributes.class);
        this.archive = archive;
        this.originalFiles = Map.copyOf(originalFiles);
        this.jvmPaths = Map.copyOf(jvmPaths);
        getAndroidClassBundles().forEach((name, bundle) -> classes.put(name, Set.copyOf(bundle.keySet())));
    }

    public ArchiveBacking archive() { return archive; }
    public Map<String, String> originalJvmPaths() { return jvmPaths; }

    public boolean isOriginalFile(String name, FileInfo current) {
        DeferredFileInfo file = originalFiles.get(name);
        return file != null && file.isOriginal(current);
    }

    public void validateReady() throws IOException {
        checkInput();
        if (!classes.keySet().equals(getAndroidClassBundles().keySet())) {
            throw new IOException("DEX entry set changed during import");
        }
        for (var entry : classes.entrySet()) {
            if (!entry.getValue().equals(getAndroidClassBundles().get(entry.getKey()).keySet())) {
                throw new IOException("DEX class set changed during import: " + entry.getKey());
            }
        }
    }

    public void checkInput() throws IOException {
        if (archive != null) {
            archive.checkUnchanged();
            return;
        }
        var current = Files.readAttributes(input, BasicFileAttributes.class);
        if (current.size() != original.size() || !current.lastModifiedTime().equals(original.lastModifiedTime())
                || !Objects.equals(current.fileKey(), original.fileKey())) {
            throw new IOException("original Android input changed since opening");
        }
    }

    @Override
    public void close() {
        try {
            super.close();
        } finally {
            if (archive != null) {
                try { archive.close(); }
                catch (IOException error) { throw new UncheckedIOException(error); }
            }
        }
    }
}
