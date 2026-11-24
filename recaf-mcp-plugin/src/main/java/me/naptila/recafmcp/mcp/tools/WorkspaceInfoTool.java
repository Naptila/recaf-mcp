package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import software.coley.recaf.info.properties.builtin.InputFilePathProperty;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Returns a bounded summary of the current Recaf workspace.
 */
public final class WorkspaceInfoTool implements McpTool {
    private static final int MAX_EMBEDDED_SUMMARY_ENTRIES = 200;
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public WorkspaceInfoTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    public WorkspaceInfoTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "workspace_info";
    }

    @Override
    public String description() {
        return "Summarize the open Recaf workspace, including Android DEX bundles and embedded JARs.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        if (!arguments.isEmpty()) {
            throw new IllegalArgumentException("workspace_info does not accept arguments");
        }
        if (!hasWorkspace.getAsBoolean()) {
            JsonObject error = new JsonObject();
            error.addProperty("workspace_open", false);
            error.addProperty("error", "workspace not open");
            return McpTool.errorResult("workspace not open", error);
        }

        Workspace workspace = workspaceSupplier.get();
        WorkspaceResource primary = workspace.getPrimaryResource();
        int jvmClasses = primary.getJvmClassBundle().size();
        int versionedJvmClasses = primary.getVersionedJvmClassBundles().values().stream()
                .mapToInt(Map::size)
                .sum();
        int androidClasses = primary.getAndroidClassBundles().values().stream()
                .mapToInt(Map::size)
                .sum();
        int files = primary.getFileBundle().size();
        int classCount = jvmClasses + versionedJvmClasses + androidClasses;

        JsonObject primaryResult = new JsonObject();
        primaryResult.addProperty("kind", primary instanceof WorkspaceFileResource ? "file" : "resource");
        addPrimaryNameAndPath(primaryResult, primary);
        primaryResult.addProperty("jvm_class_count", jvmClasses);
        primaryResult.addProperty("versioned_jvm_class_count", versionedJvmClasses);
        primaryResult.addProperty("android_class_count", androidClasses);
        primaryResult.addProperty("class_count", classCount);
        primaryResult.addProperty("file_count", files);
        primaryResult.add("android_bundles", androidBundlesJson(primary));
        List<ClassToolSupport.ScopedResource> embeddedList = ClassToolSupport.embeddedResources(primary);
        primaryResult.addProperty("embedded_resource_count", embeddedList.size());
        primaryResult.add("embedded_resources", embeddedResourcesJson(embeddedList));
        primaryResult.addProperty(
                "embedded_resources_truncated",
                embeddedList.size() > MAX_EMBEDDED_SUMMARY_ENTRIES
        );
        primaryResult.addProperty("dirty_item_count", countDirtyItems(primary));
        primaryResult.addProperty("removed_item_count", countRemovedItems(primary));

        JsonObject structuredContent = new JsonObject();
        structuredContent.addProperty("workspace_open", true);
        structuredContent.add("primary_resource", primaryResult);
        structuredContent.addProperty(
                "supporting_resource_count",
                workspace.getSupportingResources().size()
        );
        structuredContent.addProperty(
                "internal_supporting_resource_count",
                workspace.getInternalSupportingResources().size()
        );

        String text = "workspace open: " + classCount + " classes, " + files + " files";
        return McpTool.successResult(text, structuredContent);
    }

    private static JsonArray androidBundlesJson(WorkspaceResource primary) {
        JsonArray bundles = new JsonArray();
        List<Map.Entry<String, AndroidClassBundle>> sorted = new ArrayList<>(
                primary.getAndroidClassBundles().entrySet()
        );
        sorted.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, AndroidClassBundle> entry : sorted) {
            AndroidClassBundle bundle = entry.getValue();
            JsonObject item = new JsonObject();
            item.addProperty("dex_name", entry.getKey());
            item.addProperty("class_count", bundle.size());
            item.addProperty("dex_version", bundle.getVersion());
            item.addProperty("dirty_count", bundle.getDirtyKeys().size());
            bundles.add(item);
        }
        return bundles;
    }

    private static JsonArray embeddedResourcesJson(
            List<ClassToolSupport.ScopedResource> embeddedResources) {
        JsonArray array = new JsonArray();
        embeddedResources.stream()
                .sorted(Comparator.comparing(ClassToolSupport.ScopedResource::path))
                .limit(MAX_EMBEDDED_SUMMARY_ENTRIES)
                .forEach(item -> {
                    WorkspaceResource resource = item.resource();
                    int jvm = resource.getJvmClassBundle().size();
                    int versioned = resource.getVersionedJvmClassBundles().values().stream()
                            .mapToInt(Map::size)
                            .sum();
                    int android = resource.getAndroidClassBundles().values().stream()
                            .mapToInt(Map::size)
                            .sum();
                    int fileCount = resource.getFileBundle().size();
                    JsonObject obj = new JsonObject();
                    obj.addProperty("path", item.path());
                    obj.addProperty("jvm_class_count", jvm);
                    obj.addProperty("versioned_jvm_class_count", versioned);
                    obj.addProperty("android_class_count", android);
                    obj.addProperty("class_count", jvm + versioned + android);
                    obj.addProperty("file_count", fileCount);
                    array.add(obj);
                });
        return array;
    }

    private static void addPrimaryNameAndPath(JsonObject result, WorkspaceResource primary) {
        if (primary instanceof WorkspaceFileResource fileResource) {
            result.addProperty("name", fileResource.getFileInfo().getName());
            Path input = InputFilePathProperty.get(fileResource.getFileInfo());
            if (input == null) {
                result.add("input_path", JsonNull.INSTANCE);
            } else {
                result.addProperty("input_path", canonicalize(input).toString());
            }
        } else {
            result.add("name", JsonNull.INSTANCE);
            result.add("input_path", JsonNull.INSTANCE);
        }
    }

    static int countDirtyItems(WorkspaceResource resource) {
        return resource.classBundleStreamRecursive()
                .mapToInt(bundle -> bundle.getDirtyKeys().size())
                .sum()
                + resource.fileBundleStreamRecursive()
                .mapToInt(bundle -> bundle.getDirtyKeys().size())
                .sum();
    }

    static int countRemovedItems(WorkspaceResource resource) {
        return resource.classBundleStreamRecursive()
                .mapToInt(bundle -> bundle.getRemovedKeys().size())
                .sum()
                + resource.fileBundleStreamRecursive()
                .mapToInt(bundle -> bundle.getRemovedKeys().size())
                .sum();
    }

    private static Path canonicalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException ex) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static JsonObject createInputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        schema.addProperty("additionalProperties", false);
        return schema;
    }
}
