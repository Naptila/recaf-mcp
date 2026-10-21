package me.naptila.recafmcp.mcp.android;

import software.coley.recaf.info.DexFileInfo;
import software.coley.recaf.info.builder.AndroidClassInfoBuilder;
import software.coley.recaf.info.properties.builtin.InputFilePathProperty;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Prepares original Android trees before the Worker reports workspace readiness. */
public final class AndroidWorkspaceSupport {
    private AndroidWorkspaceSupport() {}

    public static void prepare(Workspace workspace) throws IOException {
        var resource = workspace.getPrimaryResource();
        if (resource instanceof AndroidInputResource android) {
            android.validateReady();
            return;
        }
        if (!(resource instanceof WorkspaceFileResource fileResource)) {
            if (!resource.getAndroidClassBundles().isEmpty()) {
                throw new IOException("Android primary resource is not file-backed");
            }
            return;
        }
        var bundles = resource.getAndroidClassBundles();
        var info = fileResource.getFileInfo();
        byte[] raw = info.getRawContent();
        var input = InputFilePathProperty.get(info);
        if (info instanceof DexFileInfo) {
            if (raw.length == 0 && input == null) {
                throw new IOException("original Android input bytes are unavailable");
            }
            if (bundles.size() != 1) {
                throw new IOException("standalone DEX has an unexpected bundle count");
            }
            replace(bundles.values().iterator().next(), raw.length == 0 ? Files.readAllBytes(input) : raw);
            return;
        }
        if (!info.isZipFile()) {
            if (!bundles.isEmpty()) {
                throw new IOException("original Android container is not a ZIP archive");
            }
            return;
        }
        if (raw.length == 0 && input == null) {
            throw new IOException("original Android input bytes are unavailable");
        }
        Set<String> pending = new HashSet<>(bundles.keySet());
        Set<String> seen = new HashSet<>();
        // Recaf may release a container's raw bytes after importing its entries.
        if (raw.length == 0) {
            // Read DEX entries directly; do not decompress every APK asset again.
            try (var zip = new ZipFile(input.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (!entry.isDirectory()) {
                        try (var stream = zip.getInputStream(entry)) {
                            prepareEntry(entry.getName(), stream, bundles, pending, seen);
                        }
                    }
                }
            }
        } else {
            try (var zip = new ZipInputStream(new ByteArrayInputStream(raw))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    if (!entry.isDirectory()) {
                        prepareEntry(entry.getName(), zip, bundles, pending, seen);
                    }
                }
            }
        }
        if (!pending.isEmpty()) {
            throw new IOException("original DEX entries are missing: " + pending);
        }
    }

    private static void prepareEntry(String name, InputStream stream,
                                     Map<String, AndroidClassBundle> bundles,
                                     Set<String> pending, Set<String> seen) throws IOException {
        byte[] prefix = stream.readNBytes(8);
        boolean dex = prefix.length == 8 && prefix[0] == 'd' && prefix[1] == 'e'
                && prefix[2] == 'x' && prefix[3] == '\n';
        // Inventory original entries even if Recaf dropped every Android bundle.
        if (!dex && !bundles.containsKey(name) && !name.matches("classes(?:[0-9]+)?\\.dex")) {
            return;
        }
        if (!seen.add(name)) {
            throw new IOException("duplicate DEX entry: " + name);
        }
        var bundle = bundles.get(name);
        if (bundle == null) {
            throw new IOException("incomplete Android import: original DEX was not imported: " + name);
        }
        var bytes = new ByteArrayOutputStream();
        bytes.write(prefix);
        stream.transferTo(bytes);
        try {
            replace(bundle, bytes.toByteArray());
        } catch (IOException ex) {
            throw new IOException("Android DEX preparation failed for " + name + ": " + ex.getMessage(), ex);
        }
        pending.remove(name);
    }

    private static void replace(AndroidClassBundle bundle, byte[] bytes) throws IOException {
        var file = AndroidDexReader.read(bytes);
        Set<String> names = new HashSet<>();
        for (var definition : file.definitions()) {
            if (!names.add(definition.getType().internalName())) {
                throw new IOException("duplicate class definition in original DEX");
            }
        }
        if (!names.equals(bundle.keySet())) {
            throw new IOException("corrected DEX class set differs from the imported class set");
        }
        for (var definition : file.definitions()) {
            // put notifies Recaf's graph/listeners, which may have seen its initial
            // import. The Worker's first usable state contains the repaired trees.
            var corrected = new AndroidClassInfoBuilder(definition).build();
            bundle.put(corrected);
            // Import repair is the baseline, not a user edit. Drop Recaf's old
            // trees so every DEX is not retained twice in the undo history.
            var history = bundle.getHistory(corrected.getName());
            history.clear();
            history.push(corrected);
        }
    }
}
