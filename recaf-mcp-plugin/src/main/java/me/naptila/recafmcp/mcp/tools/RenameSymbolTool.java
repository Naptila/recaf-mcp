package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import me.naptila.recafmcp.mcp.util.ToolErrors;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.MappingListeners;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.services.mapping.MappingsAdapter;
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Renames one exact primary-resource JVM or Android/DEX symbol.
 */
public final class RenameSymbolTool implements McpTool {
    private static final Set<String> ARGUMENTS = Set.of(
            "kind",
            "owner",
            "name",
            "descriptor",
            "new_name",
            "force",
            "scope",
            "dex_name"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;
    private final RenameOperation renameOperation;
    private final InheritanceGraphService inheritanceGraphService;

    public RenameSymbolTool(WorkspaceManager workspaceManager,
                            MappingApplierService mappingApplierService) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                fullOperation(mappingApplierService, null),
                null
        );
    }

    public RenameSymbolTool(WorkspaceManager workspaceManager,
                            MappingApplierService mappingApplierService,
                            SearchService searchService,
                            InheritanceGraphService inheritanceGraphService,
                            MappingListeners mappingListeners,
                            AggregateMappingManager aggregateMappingManager) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                targetedOperation(
                        mappingApplierService,
                        inheritanceGraphService,
                        new TargetedMappingApplier(
                                searchService,
                                inheritanceGraphService,
                                mappingListeners,
                                aggregateMappingManager
                        )
                ),
                inheritanceGraphService
        );
    }

    RenameSymbolTool(BooleanSupplier hasWorkspace,
                     Supplier<Workspace> workspaceSupplier,
                     RenameOperation renameOperation) {
        this(hasWorkspace, workspaceSupplier, renameOperation, null);
    }

    RenameSymbolTool(BooleanSupplier hasWorkspace,
                     Supplier<Workspace> workspaceSupplier,
                     RenameOperation renameOperation,
                     InheritanceGraphService inheritanceGraphService) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.renameOperation = Objects.requireNonNull(renameOperation, "renameOperation");
        this.inheritanceGraphService = inheritanceGraphService;
    }

    private static RenameOperation fullOperation(
            MappingApplierService service,
            InheritanceGraphService inheritanceGraphService) {
        return (workspace, request) -> {
            software.coley.recaf.services.inheritance.InheritanceGraph graph =
                    inheritanceGraphService != null
                            ? inheritanceGraphService.getOrCreateInheritanceGraph(workspace)
                            : new software.coley.recaf.services.inheritance.InheritanceGraph(workspace);
            MappingsAdapter mappings = mappings(workspace, request, graph);
            MappingResults results = service
                    .inWorkspace(workspace)
                    .applyToPrimaryResource(mappings);
            MappingMutationSupport.Summary summary =
                    MappingMutationSupport.summarize(workspace, results);
            ToolCancellation.check();
            MappingMutationSupport.preserveOriginalClassNames(null, results);
            results.apply();
            MappingMutationSupport.preserveOriginalClassNames(workspace, results);
            return summary;
        };
    }

    private static RenameOperation targetedOperation(
            MappingApplierService fullService,
            InheritanceGraphService inheritanceGraphService,
            TargetedMappingApplier targetedApplier) {
        RenameOperation full = fullOperation(fullService, inheritanceGraphService);
        return (workspace, request) -> {
            if (!MappingMutationSupport.hasAndroidClasses(workspace)) {
                return full.run(workspace, request);
            }
            software.coley.recaf.services.inheritance.InheritanceGraph graph =
                    inheritanceGraphService != null
                            ? inheritanceGraphService.getOrCreateInheritanceGraph(workspace)
                            : new software.coley.recaf.services.inheritance.InheritanceGraph(workspace);
            TargetedMappingApplier.Prepared prepared = targetedApplier.prepare(
                    workspace,
                    mappings(workspace, request, graph)
            );
            ToolCancellation.check();
            prepared.apply();
            return prepared.summary();
        };
    }

    static MappingsAdapter mappings(Workspace workspace,
                                    RenameRequest request,
                                    software.coley.recaf.services.inheritance.InheritanceGraph graph) {
        MappingsAdapter mappings = new MappingsAdapter(true, true);
        mappings.enableClassLookup(workspace);
        if (graph != null) {
            mappings.enableHierarchyLookup(graph);
        }
        switch (request.kind()) {
            case CLASS -> {
                mappings.addClass(request.name(), request.newName());
                String innerPrefix = request.name() + "$";
                for (ClassToolSupport.Entry entry : ClassToolSupport.entries(
                        workspace.getPrimaryResource(),
                        new ClassToolSupport.Selection(ClassToolSupport.Scope.ALL, null)
                )) {
                    if (entry.className().startsWith(innerPrefix)) {
                        String cascadedNew = request.newName()
                                + entry.className().substring(request.name().length());
                        mappings.addClass(entry.className(), cascadedNew);
                    }
                }
            }
            case FIELD -> mappings.addField(
                    request.owner(),
                    request.name(),
                    request.descriptor(),
                    request.newName()
            );
            case METHOD -> {
                List<String> owners = request.familyOwners().isEmpty()
                        ? List.of(request.owner())
                        : request.familyOwners();
                for (String declOwner : owners) {
                    mappings.addMethod(
                            declOwner,
                            request.name(),
                            request.descriptor(),
                            request.newName()
                    );
                }
            }
        }
        return mappings;
    }

    @Override
    public String name() {
        return "rename_symbol";
    }

    @Override
    public String description() {
        return "Rename one exact JVM or Android/DEX class, field, or method and rewrite references.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        RenameRequest request = parseRequest(arguments);
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", request);
        }

        Workspace workspace = workspaceSupplier.get();
        JsonObject selectionError = ClassToolSupport.validateSelection(
                workspace.getPrimaryResource(),
                request.selection()
        );
        if (selectionError != null) {
            JsonObject structured = selectionError.getAsJsonObject("structuredContent");
            structured.addProperty("kind", request.kind().jsonName);
            if (request.owner() != null) {
                structured.addProperty("owner", request.owner());
            }
            structured.addProperty("old_name", request.name());
            structured.addProperty("new_name", request.newName());
            return selectionError;
        }
        TargetResolution resolution = validateTarget(workspace, request, inheritanceGraphService);
        if (resolution.error() != null) {
            JsonObject err = error(resolution.error(), request);
            JsonObject structured = err.getAsJsonObject("structuredContent");
            if (!resolution.externalOverrides().isEmpty()) {
                JsonArray overrides = new JsonArray();
                resolution.externalOverrides().forEach(overrides::add);
                structured.add("overrides_external_methods", overrides);
            }
            if (!resolution.conflictingOwners().isEmpty()) {
                JsonArray conflicts = new JsonArray();
                resolution.conflictingOwners().forEach(conflicts::add);
                structured.add("conflicting_hierarchy_owners", conflicts);
            }
            if (resolution.error().contains("not found in requested primary scope")) {
                String lookupName = request.kind() == SymbolKind.CLASS
                        ? request.name()
                        : request.owner();
                ClassToolSupport.addMutationScopeDiagnostics(
                        structured,
                        workspace.getPrimaryResource(),
                        request.selection(),
                        lookupName
                );
                ClassToolSupport.addDidYouMeanClasses(
                        structured,
                        workspace.getPrimaryResource(),
                        request.selection(),
                        lookupName
                );
            } else if (resolution.target() != null
                    && resolution.error().contains("with exact descriptor not found")) {
                ClassToolSupport.addDidYouMeanMembers(
                        structured,
                        resolution.target().classInfo(),
                        request.name(),
                        request.descriptor(),
                        request.kind() == SymbolKind.METHOD
                );
            }
            return err;
        }
        request = resolution.resolvedRequest();

        MappingMutationSupport.Summary summary;
        try {
            summary = renameOperation.run(workspace, request);
        } catch (Exception ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error("rename failed: " + exceptionMessage(ex), request);
        }
        if (summary == null || summary.isEmpty()
                || summary.count(request.selection().scope()) == 0) {
            return error("Recaf mapping applier did not change any classes", request);
        }

        EntryPointMetadataSupport.SyncResult manifestSync = syncManifestsForRename(
                workspace,
                request,
                summary
        );
        JsonObject structuredContent = targetMetadata(request);
        structuredContent.add(
                "renamed_declarations",
                renamedDeclarationsJson(workspace, request, summary, resolution)
        );
        MappingMutationSupport.addSummary(structuredContent, summary);
        EntryPointMetadataSupport.addSyncMetadata(structuredContent, manifestSync);
        MappingMutationSupport.addPersistenceMetadata(structuredContent, workspace, true);
        String suffix = " in memory";
        return McpTool.successResult(
                "renamed " + request.kind().jsonName + " "
                        + request.displayName() + suffix,
                structuredContent
        );
    }

    private static EntryPointMetadataSupport.SyncResult syncManifestsForRename(
            Workspace workspace,
            RenameRequest request,
            MappingMutationSupport.Summary summary) {
        if (request.kind() != SymbolKind.CLASS) {
            return new EntryPointMetadataSupport.SyncResult(List.of(), List.of());
        }
        java.util.Map<String, String> jvmRenames = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> androidRenames = new java.util.LinkedHashMap<>();
        for (MappingMutationSupport.Change change : summary.changes()) {
            if (!change.renamed()) {
                continue;
            }
            if (change.scope() == ClassToolSupport.Scope.ANDROID) {
                androidRenames.put(change.oldName(), change.newName());
            } else {
                jvmRenames.put(change.oldName(), change.newName());
            }
        }
        if (jvmRenames.isEmpty() && androidRenames.isEmpty()) {
            if (request.target().scope() == ClassToolSupport.Scope.ANDROID) {
                androidRenames.put(request.name(), request.newName());
            } else {
                jvmRenames.put(request.name(), request.newName());
            }
        }
        return EntryPointMetadataSupport.syncClassRenames(
                workspace,
                jvmRenames,
                androidRenames,
                true
        );
    }

    private static RenameRequest parseRequest(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String kindName = ToolArguments.requiredString(arguments, "kind");
        SymbolKind kind = SymbolKind.fromJsonName(kindName);
        String owner = ToolArguments.optionalString(arguments, "owner");
        String name = ToolArguments.requiredString(arguments, "name");
        String descriptor = ToolArguments.optionalString(arguments, "descriptor");
        String newName = ToolArguments.requiredString(arguments, "new_name");
        boolean force = ToolArguments.bool(arguments, "force", false);
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(
                arguments,
                false
        );

        if (kind == SymbolKind.CLASS) {
            if (owner != null || descriptor != null) {
                throw new IllegalArgumentException(
                        "owner and descriptor are not allowed for a class rename"
                );
            }
            if (!isValidInternalClassName(name) || !isValidInternalClassName(newName)) {
                throw new IllegalArgumentException(
                        "class names must be valid JVM internal names"
                );
            }
        } else {
            if (owner == null || descriptor == null) {
                throw new IllegalArgumentException(
                        "owner and descriptor are required for a member rename"
                );
            }
            if (!isValidInternalClassName(owner)) {
                throw new IllegalArgumentException("owner must be a valid JVM internal name");
            }
            if (!isValidMemberName(newName, kind)) {
                throw new IllegalArgumentException("new_name is not a valid JVM member name");
            }
        }
        if (name.equals(newName)) {
            throw new IllegalArgumentException("new_name must differ from name");
        }
        return new RenameRequest(
                kind,
                owner,
                name,
                descriptor,
                newName,
                force,
                selection,
                null
        );
    }

    private static TargetResolution validateTarget(Workspace workspace,
                                                   RenameRequest request,
                                                   InheritanceGraphService inheritanceGraphService) {
        var resource = workspace.getPrimaryResource();
        String lookupName = request.kind() == SymbolKind.CLASS
                ? request.name()
                : request.owner();
        List<ClassToolSupport.Entry> matches = ClassToolSupport.exact(
                resource,
                request.selection(),
                lookupName
        );
        if (matches.isEmpty()) {
            return TargetResolution.error(
                    request.kind() == SymbolKind.CLASS
                            ? "class not found in requested primary scope"
                            : "owner class not found in requested primary scope"
            );
        }
        if (matches.size() > 1) {
            return TargetResolution.error(
                    "class identity is ambiguous; specify dex_name"
            );
        }
        ClassToolSupport.Entry target = matches.getFirst();
        List<ClassToolSupport.Entry> globalMatches = ClassToolSupport.exact(
                resource,
                new ClassToolSupport.Selection(ClassToolSupport.Scope.ALL, null),
                lookupName
        );
        if (globalMatches.size() > 1) {
            return TargetResolution.error(
                    "class identity occurs in multiple primary class domains or DEX "
                            + "bundles; Recaf mappings cannot rename only one duplicate definition"
            );
        }

        if (request.kind() == SymbolKind.CLASS) {
            String resolvedNewName = ClassToolSupport.normalizeUnicodeInput(request.newName());
            ClassToolSupport.Selection collisionSelection =
                    new ClassToolSupport.Selection(target.scope(), null);
            if (!ClassToolSupport.exact(
                    resource,
                    collisionSelection,
                    resolvedNewName
            ).isEmpty()) {
                return TargetResolution.error(
                        "target class name already exists in primary "
                                + target.scope().value() + " resource"
                );
            }
            return TargetResolution.success(
                    target,
                    request.withResolvedNames(null, target.className(), null, resolvedNewName, target),
                    List.of()
            );
        }

        ClassInfo owner = target.classInfo();
        String resolvedNewName = ClassToolSupport.normalizeUnicodeInput(request.newName());
        if (request.kind() == SymbolKind.FIELD) {
            var declaredField = ClassToolSupport.findDeclaredField(
                    owner,
                    request.name(),
                    request.descriptor()
            );
            if (declaredField == null) {
                return TargetResolution.memberError(
                        target,
                        "field with exact descriptor not found in owner class"
                );
            }
            if (owner.getDeclaredField(resolvedNewName, declaredField.getDescriptor()) != null) {
                return TargetResolution.error(
                        "target field name and descriptor already exist in owner class"
                );
            }
            return TargetResolution.success(
                    target,
                    request.withResolvedNames(
                            owner.getName(),
                            declaredField.getName(),
                            declaredField.getDescriptor(),
                            resolvedNewName,
                            target,
                            List.of(owner.getName())
                    ),
                    List.of(owner.getName())
            );
        }

        var declaredMethod = ClassToolSupport.findDeclaredMethod(
                owner,
                request.name(),
                request.descriptor()
        );
        if (declaredMethod == null) {
            return TargetResolution.memberError(
                    target,
                    "method with exact descriptor not found in owner class"
            );
        }
        String resolvedMethodName = declaredMethod.getName();
        String resolvedDesc = declaredMethod.getDescriptor();
        if (owner.getDeclaredMethod(resolvedNewName, resolvedDesc) != null) {
            return TargetResolution.error(
                    "target method name and descriptor already exist in owner class"
            );
        }
        if (!MethodHierarchySupport.isInheritable(declaredMethod)) {
            List<String> singleOwner = List.of(owner.getName());
            return TargetResolution.success(
                    target,
                    request.withResolvedNames(
                            owner.getName(),
                            resolvedMethodName,
                            resolvedDesc,
                            resolvedNewName,
                            target,
                            singleOwner
                    ),
                    singleOwner
            );
        }

        software.coley.recaf.services.inheritance.InheritanceGraph graph =
                inheritanceGraphService != null
                        ? inheritanceGraphService.getOrCreateInheritanceGraph(workspace)
                        : new software.coley.recaf.services.inheritance.InheritanceGraph(workspace);
        MethodHierarchySupport.Analysis hierarchy = MethodHierarchySupport.analyze(
                workspace,
                graph,
                owner,
                declaredMethod,
                resolvedNewName
        );

        if (!hierarchy.externalOverrides().isEmpty() && !request.force()) {
            return TargetResolution.externalOverrideError(
                    "method overrides or implements external/library declaration(s): "
                            + String.join(", ", hierarchy.externalOverrides())
                            + "; pass force=true to rename anyway",
                    hierarchy.externalOverrides()
            );
        }
        if (!hierarchy.conflictingOwners().isEmpty() && !request.force()) {
            return TargetResolution.hierarchyConflictError(
                    "target method signature already exists in inheritance hierarchy ("
                            + String.join(", ", hierarchy.conflictingOwners())
                            + "); pass force=true to rename anyway",
                    hierarchy.conflictingOwners()
            );
        }
        RenameRequest normalizedRequest = request.withResolvedNames(
                owner.getName(),
                resolvedMethodName,
                resolvedDesc,
                resolvedNewName,
                target,
                hierarchy.primaryDeclaringOwners()
        );
        return TargetResolution.success(
                target,
                normalizedRequest,
                hierarchy.primaryDeclaringOwners()
        );
    }

    private static JsonArray renamedDeclarationsJson(Workspace workspace,
                                                     RenameRequest request,
                                                     MappingMutationSupport.Summary summary,
                                                     TargetResolution resolution) {
        JsonArray array = new JsonArray();
        if (request.kind() == SymbolKind.CLASS) {
            boolean any = false;
            for (MappingMutationSupport.Change change : summary.changes()) {
                if (!change.renamed()) {
                    continue;
                }
                any = true;
                JsonObject item = new JsonObject();
                item.addProperty("kind", "class");
                item.addProperty("old_name", change.oldName());
                item.addProperty("new_name", change.newName());
                item.addProperty("class_scope", change.scope().value());
                if (change.dexName() == null) {
                    item.add("dex_name", JsonNull.INSTANCE);
                } else {
                    item.addProperty("dex_name", change.dexName());
                }
                array.add(item);
            }
            if (!any) {
                JsonObject item = new JsonObject();
                item.addProperty("kind", "class");
                item.addProperty("old_name", request.name());
                item.addProperty("new_name", request.newName());
                array.add(item);
            }
            return array;
        }
        List<String> plannedOwners = resolution.familyOwners().isEmpty()
                ? List.of(request.owner())
                : resolution.familyOwners();
        var primary = workspace.getPrimaryResource();
        List<String> verifiedOwners = new java.util.ArrayList<>();
        for (String declOwner : plannedOwners) {
            List<ClassToolSupport.Entry> postEntries = ClassToolSupport.exact(
                    primary,
                    new ClassToolSupport.Selection(ClassToolSupport.Scope.ALL, null),
                    declOwner
            );
            if (postEntries.isEmpty()) {
                verifiedOwners.add(declOwner);
                continue;
            }
            boolean renamedInBundle = postEntries.stream().anyMatch(entry -> {
                ClassInfo postInfo = entry.classInfo();
                if (request.kind() == SymbolKind.FIELD) {
                    return postInfo.getDeclaredField(request.newName(), request.descriptor()) != null
                            && postInfo.getDeclaredField(request.name(), request.descriptor()) == null;
                }
                return postInfo.getDeclaredMethod(request.newName(), request.descriptor()) != null
                        && postInfo.getDeclaredMethod(request.name(), request.descriptor()) == null;
            });
            if (renamedInBundle) {
                verifiedOwners.add(declOwner);
            }
        }
        List<String> ownersToReport = verifiedOwners.isEmpty() ? plannedOwners : verifiedOwners;
        for (String declOwner : ownersToReport) {
            JsonObject item = new JsonObject();
            item.addProperty("kind", request.kind().jsonName);
            item.addProperty("owner", declOwner);
            item.addProperty("old_name", request.name());
            item.addProperty("new_name", request.newName());
            item.addProperty("descriptor", request.descriptor());
            array.add(item);
        }
        return array;
    }

    private static boolean isValidInternalClassName(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.endsWith("/")) {
            return false;
        }
        String[] components = name.split("/", -1);
        for (String component : components) {
            if (!isValidUnqualifiedName(component, false)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidMemberName(String name, SymbolKind kind) {
        if (!isValidUnqualifiedName(name, kind == SymbolKind.METHOD)) {
            return false;
        }
        return !"<init>".equals(name) && !"<clinit>".equals(name);
    }

    private static boolean isValidUnqualifiedName(String name, boolean method) {
        if (name.isEmpty()) {
            return false;
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (character == '.' || character == ';' || character == '['
                    || character == '/' || Character.isISOControl(character)
                    || (method && (character == '<' || character == '>'))) {
                return false;
            }
        }
        return true;
    }

    private static JsonObject error(String message, RenameRequest request) {
        JsonObject structuredContent = targetMetadata(request);
        structuredContent.addProperty("error", message);
        return McpTool.errorResult(message, structuredContent);
    }

    private static JsonObject targetMetadata(RenameRequest request) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("kind", request.kind().jsonName);
        if (request.owner() != null) {
            metadata.addProperty("owner", request.owner());
        }
        metadata.addProperty("old_name", request.name());
        if (request.descriptor() != null) {
            metadata.addProperty("descriptor", request.descriptor());
        }
        metadata.addProperty("new_name", request.newName());
        ClassToolSupport.Entry target = request.target();
        ClassToolSupport.Scope classScope = target == null
                ? request.selection().scope()
                : target.scope();
        metadata.addProperty("class_scope", classScope.value());
        metadata.addProperty("scope", ClassToolSupport.outputScope(classScope, false));
        String dexName = target == null
                ? request.selection().dexName()
                : target.dexName();
        if (dexName == null) {
            metadata.add("dex_name", JsonNull.INSTANCE);
        } else {
            metadata.addProperty("dex_name", dexName);
        }
        return metadata;
    }

    private static String exceptionMessage(Throwable throwable) {
        return ToolErrors.message(throwable);
    }

    private static JsonObject createInputSchema() {
        JsonObject properties = new JsonObject();
        JsonObject kind = new JsonObject();
        kind.addProperty("type", "string");
        JsonArray kinds = new JsonArray();
        kinds.add("class");
        kinds.add("field");
        kinds.add("method");
        kind.add("enum", kinds);
        properties.add("kind", kind);

        JsonObject owner = new JsonObject();
        owner.addProperty("type", "string");
        owner.addProperty("minLength", 1);
        owner.addProperty("description", "Member owner's JVM internal class name.");
        properties.add("owner", owner);

        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("minLength", 1);
        name.addProperty("description", "Existing class internal name or member name.");
        properties.add("name", name);

        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("type", "string");
        descriptor.addProperty("minLength", 1);
        descriptor.addProperty("description", "Exact JVM field or method descriptor.");
        properties.add("descriptor", descriptor);

        JsonObject newName = new JsonObject();
        newName.addProperty("type", "string");
        newName.addProperty("minLength", 1);
        newName.addProperty("description", "New internal class name or unqualified member name.");
        properties.add("new_name", newName);

        JsonObject force = new JsonObject();
        force.addProperty("type", "boolean");
        force.addProperty("default", false);
        force.addProperty(
                "description",
                "Allow renaming methods that override external/library supertypes or collide in hierarchy."
        );
        properties.add("force", force);

        ClassToolSupport.addScopeProperties(properties, false);

        JsonArray required = new JsonArray();
        required.add("kind");
        required.add("name");
        required.add("new_name");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    enum SymbolKind {
        CLASS("class"),
        FIELD("field"),
        METHOD("method");

        private final String jsonName;

        SymbolKind(String jsonName) {
            this.jsonName = jsonName;
        }

        private static SymbolKind fromJsonName(String name) {
            for (SymbolKind kind : values()) {
                if (kind.jsonName.equals(name)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("kind must be class, field, or method");
        }
    }

    record RenameRequest(SymbolKind kind,
                         String owner,
                         String name,
                         String descriptor,
                         String newName,
                         boolean force,
                         ClassToolSupport.Selection selection,
                         ClassToolSupport.Entry target,
                         List<String> familyOwners) {
        RenameRequest {
            Objects.requireNonNull(selection, "selection");
            familyOwners = familyOwners == null ? List.of() : List.copyOf(familyOwners);
        }

        RenameRequest(SymbolKind kind,
                      String owner,
                      String name,
                      String descriptor,
                      String newName,
                      boolean force,
                      ClassToolSupport.Selection selection,
                      ClassToolSupport.Entry target) {
            this(kind, owner, name, descriptor, newName, force, selection, target, List.of());
        }

        private RenameRequest withResolvedNames(String resolvedOwner,
                                                String resolvedName,
                                                String resolvedDescriptor,
                                                String resolvedNewName,
                                                ClassToolSupport.Entry resolvedTarget) {
            return withResolvedNames(
                    resolvedOwner,
                    resolvedName,
                    resolvedDescriptor,
                    resolvedNewName,
                    resolvedTarget,
                    List.of()
            );
        }

        private RenameRequest withResolvedNames(String resolvedOwner,
                                                String resolvedName,
                                                String resolvedDescriptor,
                                                String resolvedNewName,
                                                ClassToolSupport.Entry resolvedTarget,
                                                List<String> resolvedFamilyOwners) {
            return new RenameRequest(
                    kind,
                    resolvedOwner,
                    resolvedName,
                    resolvedDescriptor,
                    resolvedNewName,
                    force,
                    selection,
                    resolvedTarget,
                    resolvedFamilyOwners
            );
        }

        private String displayName() {
            return owner == null ? name : owner + "." + name + descriptor;
        }
    }

    @FunctionalInterface
    interface RenameOperation {
        MappingMutationSupport.Summary run(
                Workspace workspace,
                RenameRequest request
        );
    }

    record TargetResolution(ClassToolSupport.Entry target,
                            RenameRequest resolvedRequest,
                            String error,
                            List<String> familyOwners,
                            List<String> externalOverrides,
                            List<String> conflictingOwners) {
        private static TargetResolution success(ClassToolSupport.Entry target,
                                                RenameRequest resolvedRequest,
                                                List<String> familyOwners) {
            return new TargetResolution(
                    Objects.requireNonNull(target),
                    Objects.requireNonNull(resolvedRequest),
                    null,
                    List.copyOf(familyOwners),
                    List.of(),
                    List.of()
            );
        }

        private static TargetResolution error(String message) {
            return new TargetResolution(
                    null,
                    null,
                    Objects.requireNonNull(message),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        private static TargetResolution memberError(ClassToolSupport.Entry target,
                                                    String message) {
            return new TargetResolution(
                    Objects.requireNonNull(target),
                    null,
                    Objects.requireNonNull(message),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        private static TargetResolution externalOverrideError(String message,
                                                              List<String> externalOverrides) {
            return new TargetResolution(
                    null,
                    null,
                    Objects.requireNonNull(message),
                    List.of(),
                    List.copyOf(externalOverrides),
                    List.of()
            );
        }

        private static TargetResolution hierarchyConflictError(String message,
                                                               List<String> conflictingOwners) {
            return new TargetResolution(
                    null,
                    null,
                    Objects.requireNonNull(message),
                    List.of(),
                    List.of(),
                    List.copyOf(conflictingOwners)
            );
        }
    }

}
