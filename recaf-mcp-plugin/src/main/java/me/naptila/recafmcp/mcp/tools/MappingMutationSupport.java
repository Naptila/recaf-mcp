package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Shared JVM/Android mapping-result classification and response metadata.
 */
final class MappingMutationSupport {
    private MappingMutationSupport() {
    }

    static Summary summarize(Workspace workspace, MappingResults results) {
        WorkspaceResource resource = workspace.getPrimaryResource();
        List<Change> changes = results.getMappedClasses().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> {
                    var prePath = results.getPreMappingPaths().get(entry.getKey());
                    ClassInfo classInfo = prePath == null ? null : prePath.getValue();
                    ClassToolSupport.Scope scope = classInfo != null
                            && classInfo.isAndroidClass()
                            ? ClassToolSupport.Scope.ANDROID
                            : ClassToolSupport.Scope.JVM;
                    String dexName = null;
                    if (classInfo != null) {
                        ClassToolSupport.Entry identity = ClassToolSupport.identify(
                                resource,
                                classInfo
                        );
                        if (identity != null) {
                            scope = identity.scope();
                            dexName = identity.dexName();
                        }
                    }
                    return new Change(
                            entry.getKey(),
                            entry.getValue(),
                            scope,
                            dexName
                    );
                })
                .toList();
        return new Summary(changes);
    }

    static boolean hasAndroidClasses(Workspace workspace) {
        return workspace.getPrimaryResource()
                .androidClassBundleStreamRecursive()
                .anyMatch(bundle -> !bundle.isEmpty());
    }

    static void addPersistenceMetadata(JsonObject target, Workspace workspace, boolean applied) {
        boolean supported = me.naptila.recafmcp.mcp.internal.RecafSaveWorkspaceHandler
                .supportsArchiveExport(workspace);
        target.addProperty("workspace_mutated_in_memory", applied);
        target.addProperty("workspace_archive_export_supported", supported);
        target.addProperty("save_required", applied && supported);
        target.addProperty("in_memory_only", applied && !supported);
        if (!supported) {
            target.addProperty("persistence_note", "This workspace is not a supported file-backed input; "
                    + "export_mappings can preserve its symbol mappings.");
        }
    }

    static void addSummary(JsonObject target, Summary summary) {
        target.addProperty("changed_classes", summary.changes().size());
        target.addProperty("jvm_changed_classes", summary.jvmChanges());
        target.addProperty("android_changed_classes", summary.androidChanges());
        JsonArray dexBundles = new JsonArray();
        summary.dexBundles().forEach(dexBundles::add);
        target.add("dex_bundles_changed", dexBundles);
        target.addProperty("dex_bundle_count", summary.dexBundles().size());
    }

    record Change(String oldName,
                  String newName,
                  ClassToolSupport.Scope scope,
                  String dexName) {
        Change {
            Objects.requireNonNull(oldName, "oldName");
            Objects.requireNonNull(newName, "newName");
            Objects.requireNonNull(scope, "scope");
        }

        boolean renamed() {
            return !oldName.equals(newName);
        }
    }

    record Summary(List<Change> changes) {
        Summary {
            changes = List.copyOf(changes);
        }

        int jvmChanges() {
            return count(ClassToolSupport.Scope.JVM);
        }

        int androidChanges() {
            return count(ClassToolSupport.Scope.ANDROID);
        }

        int count(ClassToolSupport.Scope scope) {
            if (scope == ClassToolSupport.Scope.ALL) {
                return changes.size();
            }
            return (int) changes.stream()
                    .filter(change -> change.scope() == scope)
                    .count();
        }

        Set<String> dexBundles() {
            Set<String> names = new TreeSet<>();
            changes.stream()
                    .map(Change::dexName)
                    .filter(Objects::nonNull)
                    .forEach(names::add);
            return names;
        }

        boolean isEmpty() {
            return changes.isEmpty();
        }
    }
}
