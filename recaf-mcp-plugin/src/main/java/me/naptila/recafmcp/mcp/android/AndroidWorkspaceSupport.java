package me.naptila.recafmcp.mcp.android;

import software.coley.recaf.info.DexFileInfo;
import software.coley.recaf.info.builder.AndroidClassInfoBuilder;
import software.coley.recaf.info.properties.builtin.InputFilePathProperty;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipInputStream;

/** Prepares original Android trees before the Worker reports workspace readiness. */
public final class AndroidWorkspaceSupport {
    private AndroidWorkspaceSupport() {}

    public static void prepare(Workspace workspace) throws IOException {
        var resource = workspace.getPrimaryResource();
        if (resource.getAndroidClassBundles().isEmpty()) {
            return;
        }
        if (!(resource instanceof WorkspaceFileResource fileResource)) {
            throw new IOException("Android primary resource is not file-backed");
        }
        var bundles = resource.getAndroidClassBundles();
        var info = fileResource.getFileInfo();
        byte[] raw = info.getRawContent();
        var input = InputFilePathProperty.get(info);
        if (raw.length == 0 && input == null) {
            throw new IOException("original Android input bytes are unavailable");
        }
        if (info instanceof DexFileInfo) {
            if (bundles.size() != 1) {
                throw new IOException("standalone DEX has an unexpected bundle count");
            }
            replace(bundles.values().iterator().next(), raw.length == 0 ? Files.readAllBytes(input) : raw);
            return;
        }
        Set<String> pending = new HashSet<>(bundles.keySet());
        Set<String> seen = new HashSet<>();
        // Recaf may release a container's raw bytes after importing its entries.
        try (var zip = new ZipInputStream(raw.length == 0
                ? Files.newInputStream(input) : new ByteArrayInputStream(raw))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                var bundle = bundles.get(entry.getName());
                if (bundle != null) {
                    if (!seen.add(entry.getName())) {
                        throw new IOException("duplicate DEX entry: " + entry.getName());
                    }
                    replace(bundle, zip.readAllBytes());
                    pending.remove(entry.getName());
                }
            }
        }
        if (!pending.isEmpty()) {
            throw new IOException("original DEX entries are missing: " + pending);
        }
    }

    private static void replace(AndroidClassBundle bundle, byte[] bytes) throws IOException {
        var file = AndroidDexReader.read(bytes);
        Set<String> names = new HashSet<>();
        file.definitions().forEach(definition -> names.add(definition.getType().internalName()));
        if (!names.equals(bundle.keySet())) {
            throw new IOException("corrected DEX class set differs from the imported class set");
        }
        for (var definition : file.definitions()) {
            // put notifies Recaf's graph/listeners, which may have seen its initial
            // import. The Worker's first usable state contains the repaired trees.
            bundle.put(new AndroidClassInfoBuilder(definition).build());
        }
    }
}
