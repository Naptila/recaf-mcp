package me.naptila.recafmcp.mcp.tools;

import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.bundle.BasicVersionedJvmClassBundle;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.bundle.VersionedJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Detached JVM bundles for transactional, multi-phase transformation plans. */
final class TransformationWorkspaceSupport implements AutoCloseable {
    private static final long MAX_SNAPSHOT_BYTES = 256L * 1024 * 1024;
    private final Map<JvmClassBundle, Snapshot> snapshots = new IdentityHashMap<>();
    private final Workspace detached;
    private long bytes;

    TransformationWorkspaceSupport(Workspace original) {
        WorkspaceResource primary = copy(original.getPrimaryResource(), true);
        List<WorkspaceResource> libraries = original.getSupportingResources().stream()
                .map(resource -> copy(resource, false)).toList();
        detached = new BasicWorkspace(primary, libraries, false) {
            @Override
            public List<WorkspaceResource> getInternalSupportingResources() {
                return original.getInternalSupportingResources();
            }
        };
    }

    Workspace workspace() {
        return detached;
    }

    boolean isPrimaryBundle(Object bundle) {
        return snapshots.values().stream().anyMatch(snapshot -> snapshot.copy() == bundle);
    }

    private WorkspaceResource copy(WorkspaceResource source, boolean primary) {
        ToolCancellation.check();
        JvmClassBundle base = copyBundle(source.getJvmClassBundle(), new BasicJvmClassBundle(), primary);
        var versioned = new TreeMap<Integer, VersionedJvmClassBundle>();
        source.getVersionedJvmClassBundles().forEach((version, bundle) -> versioned.put(version,
                (VersionedJvmClassBundle) copyBundle(bundle, new BasicVersionedJvmClassBundle(version), primary)));
        var embedded = new TreeMap<String, WorkspaceFileResource>();
        source.getEmbeddedResources().forEach((name, resource) -> embedded.put(name,
                (WorkspaceFileResource) copy(resource, false)));
        var builder = new WorkspaceResourceBuilder().withJvmClassBundle(base)
                .withVersionedJvmClassBundles(versioned).withEmbeddedResources(embedded);
        if (source instanceof WorkspaceFileResource file) {
            builder = builder.withFileInfo(file.getFileInfo());
        }
        // Every resource owns its copied bundles; closing this workspace never closes live resources.
        return builder.build();
    }

    private JvmClassBundle copyBundle(JvmClassBundle source, JvmClassBundle target, boolean primary) {
        Map<String, byte[]> originalBytes = new TreeMap<>();
        Map<String, JvmClassInfo> originalClasses = new TreeMap<>();
        for (JvmClassInfo info : source.values()) {
            ToolCancellation.check();
            byte[] code = info.getBytecode().clone();
            bytes += code.length;
            if (bytes > MAX_SNAPSHOT_BYTES) {
                throw new IllegalArgumentException("JVM transformation snapshot exceeds 256 MiB; use a smaller workspace");
            }
            target.put(new JvmClassInfoBuilder(code, info.getClassReaderFlags()).build());
            if (primary) {
                originalBytes.put(info.getName(), code.clone());
                originalClasses.put(info.getName(), info);
            }
        }
        if (primary) {
            snapshots.put(source, new Snapshot(target, originalBytes, originalClasses));
        }
        return target;
    }

    List<String> modifiedClasses(String prefix) {
        TreeSet<String> modified = new TreeSet<>();
        snapshots.forEach((live, snapshot) -> snapshot.copy().forEach(info -> {
            ToolCancellation.check();
            if (!Arrays.equals(snapshot.bytes().get(info.getName()), info.getBytecode())) {
                requireScope(info.getName(), prefix);
                modified.add(info.getName());
            }
        }));
        return List.copyOf(modified);
    }

    List<String> removedClasses(String prefix) {
        TreeSet<String> removed = new TreeSet<>();
        snapshots.forEach((live, snapshot) -> snapshot.bytes().keySet().forEach(name -> {
            ToolCancellation.check();
            if (!snapshot.copy().containsKey(name)) {
                requireScope(name, prefix);
                removed.add(name);
            }
        }));
        return List.copyOf(removed);
    }

    void commit(String prefix) {
        // Finish all validation and cancellation checks before the first live write.
        modifiedClasses(prefix);
        removedClasses(prefix);
        snapshots.forEach((live, snapshot) -> {
            ToolCancellation.check();
            if (!live.keySet().equals(snapshot.bytes().keySet()) || live.values().stream()
                    .anyMatch(info -> !Arrays.equals(snapshot.bytes().get(info.getName()), info.getBytecode()))) {
                throw new IllegalStateException("workspace changed while preparing transformations; retry");
            }
        });
        ToolCancellation.check();
        List<JvmClassBundle> written = new ArrayList<>();
        try {
            snapshots.forEach((live, snapshot) -> {
                written.add(live);
                for (String name : snapshot.bytes().keySet()) {
                    if (!snapshot.copy().containsKey(name)) {
                        live.remove(name);
                    }
                }
                for (JvmClassInfo info : snapshot.copy()) {
                    if (!Arrays.equals(snapshot.bytes().get(info.getName()), info.getBytecode())) {
                        live.put(info);
                    }
                }
            });
        } catch (RuntimeException | Error failure) {
            for (JvmClassBundle live : written) {
                Snapshot snapshot = snapshots.get(live);
                try {
                    for (String name : new ArrayList<>(live.keySet())) {
                        if (!snapshot.classes().containsKey(name)) {
                            live.remove(name);
                        }
                    }
                    snapshot.classes().values().forEach(live::put);
                } catch (RuntimeException | Error rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    private static void requireScope(String name, String prefix) {
        if (prefix != null && !name.startsWith(prefix)) {
            throw new IllegalStateException("transformer changed a class outside class_prefix: " + name);
        }
    }

    @Override
    public void close() {
        detached.close();
    }

    private record Snapshot(JvmClassBundle copy, Map<String, byte[]> bytes, Map<String, JvmClassInfo> classes) {}
}
