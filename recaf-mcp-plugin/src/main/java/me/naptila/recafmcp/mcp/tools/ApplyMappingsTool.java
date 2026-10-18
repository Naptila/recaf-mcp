package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.MappingListeners;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.services.mapping.Mappings;
import software.coley.recaf.services.mapping.UniqueKeyMappings;
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.mapping.format.MappingFileFormat;
import software.coley.recaf.services.mapping.format.MappingFormatManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.workspace.model.Workspace;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Parses and applies one Recaf mapping document to primary JVM and/or DEX classes.
 */
public final class ApplyMappingsTool implements McpTool {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final Set<String> ARGUMENTS = Set.of(
            "format", "mappings", "scope", "unique_keys", "dry_run", "offset", "limit"
    );

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;
    private final Function<String, MappingFileFormat> formatResolver;
    private final ApplyOperation applyOperation;
    private final List<MappingToolSupport.Descriptor> formats;
    private final JsonObject inputSchema;

    public ApplyMappingsTool(WorkspaceManager workspaceManager,
                             MappingFormatManager formatManager,
                             MappingApplierService mappingApplierService) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                formatManager::createFormatInstance,
                (workspace, mappings) -> prepare(
                        workspace,
                        mappings,
                        mappingApplierService
                ),
                MappingToolSupport.descriptors(formatManager)
        );
    }

    public ApplyMappingsTool(WorkspaceManager workspaceManager,
                             MappingFormatManager formatManager,
                             MappingApplierService mappingApplierService,
                             SearchService searchService,
                             InheritanceGraphService inheritanceGraphService,
                             MappingListeners mappingListeners,
                             AggregateMappingManager aggregateMappingManager) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                formatManager::createFormatInstance,
                (workspace, mappings) -> MappingMutationSupport.hasAndroidClasses(workspace)
                        ? prepareTargeted(
                                workspace,
                                mappings,
                                new TargetedMappingApplier(
                                        searchService,
                                        inheritanceGraphService,
                                        mappingListeners,
                                        aggregateMappingManager
                                )
                        )
                        : prepare(workspace, mappings, mappingApplierService),
                MappingToolSupport.descriptors(formatManager)
        );
    }

    ApplyMappingsTool(BooleanSupplier hasWorkspace,
                      Supplier<Workspace> workspaceSupplier,
                      Function<String, MappingFileFormat> formatResolver,
                      ApplyOperation applyOperation,
                      List<MappingToolSupport.Descriptor> formats) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.formatResolver = Objects.requireNonNull(formatResolver, "formatResolver");
        this.applyOperation = Objects.requireNonNull(applyOperation, "applyOperation");
        this.formats = List.copyOf(formats);
        inputSchema = createInputSchema(this.formats);
    }

    @Override
    public String name() {
        return "apply_mappings";
    }

    @Override
    public String description() {
        return "Parse and apply an installed Recaf mapping format to JVM or Android/DEX classes.";
    }

    @Override
    public JsonObject inputSchema() {
        return inputSchema.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        Request request = parseRequest(arguments);
        MappingToolSupport.Descriptor descriptor = MappingToolSupport.descriptor(
                formats,
                request.format()
        );
        if (descriptor == null) {
            throw new IllegalArgumentException("format is not an installed Recaf mapping format");
        }
        request = request.withFormat(descriptor.name());
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", request, descriptor, null, null);
        }

        Workspace workspace = workspaceSupplier.get();

        MappingFileFormat format = formatResolver.apply(descriptor.name());
        if (format == null) {
            return error(
                    "mapping format became unavailable after tool registration",
                    request,
                    descriptor,
                    null,
                    null
            );
        }

        IntermediateMappings parsed;
        MappingToolSupport.MappingCounts counts;
        try {
            parsed = format.parse(request.mappings());
            counts = MappingToolSupport.counts(parsed);
        } catch (Exception ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error(
                    "mapping parse failed: " + MappingToolSupport.exceptionMessage(ex),
                    request,
                    descriptor,
                    null,
                    null
            );
        }
        if (counts.isEmpty()) {
            return error(
                    "mapping document contains no class, field, method, or variable mappings",
                    request,
                    descriptor,
                    counts,
                    null
            );
        }

        Mappings mappings = request.uniqueKeys()
                ? new UniqueKeyMappings(parsed)
                : parsed;
        PreparedMapping prepared;
        try {
            ToolCancellation.check();
            prepared = applyOperation.prepare(workspace, mappings);
        } catch (Exception ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error(
                    "mapping preparation failed: " + MappingToolSupport.exceptionMessage(ex),
                    request,
                    descriptor,
                    counts,
                    null
            );
        }
        if (prepared.conflict() != null) {
            return error(prepared.conflict(), request, descriptor, counts, prepared);
        }
        if (prepared.classChanges().isEmpty()
                || selectedChanges(prepared.classChanges(), request.scope()) == 0) {
            return error(
                    "mappings did not match or change any primary "
                            + request.scope().value() + " classes",
                    request,
                    descriptor,
                    counts,
                    prepared
            );
        }

        boolean applied = false;
        if (!request.dryRun()) {
            try {
                ToolCancellation.check();
                prepared.apply();
                applied = true;
            } catch (Exception ex) {
                ToolCancellation.propagateIfRequested(ex);
                return error(
                        "mapping application failed: "
                                + MappingToolSupport.exceptionMessage(ex),
                        request,
                        descriptor,
                        counts,
                        prepared
                );
            }
        }

        JsonObject structured = metadata(request, descriptor);
        MappingToolSupport.addCounts(structured, counts);
        addChanges(structured, request, prepared);
        structured.addProperty("applied", applied);
        MappingMutationSupport.addPersistenceMetadata(structured, workspace, applied);
        String message = request.dryRun()
                ? "mapping dry run prepared " + prepared.classChanges().size()
                + " changed classes without applying them"
                : "applied mappings to " + prepared.classChanges().size()
                + " classes; save the workspace to persist changes";
        return McpTool.successResult(message, structured);
    }

    private static PreparedMapping prepare(Workspace workspace,
                                           Mappings mappings,
                                           MappingApplierService service) {
        MappingResults results = service.inWorkspace(workspace)
                .applyToPrimaryResource(mappings);
        List<ClassChange> changes = MappingMutationSupport.summarize(workspace, results)
                .changes().stream()
                .map(change -> new ClassChange(
                        change.oldName(),
                        change.newName(),
                        change.scope(),
                        change.dexName()
                ))
                .toList();
        List<ClassInfo> postClasses = results.getPostMappingPaths().values().stream()
                .map(path -> path.getValue())
                .toList();
        String conflict = validate(workspace, postClasses, changes);
        return new PreparedMapping(changes, conflict, results::apply);
    }

    private static PreparedMapping prepareTargeted(Workspace workspace,
                                                    Mappings mappings,
                                                    TargetedMappingApplier applier) {
        TargetedMappingApplier.Prepared prepared = applier.prepare(workspace, mappings);
        List<ClassChange> changes = prepared.summary().changes().stream()
                .map(change -> new ClassChange(
                        change.oldName(),
                        change.newName(),
                        change.scope(),
                        change.dexName()
                ))
                .toList();
        String conflict = validate(workspace, prepared.postClasses(), changes);
        return new PreparedMapping(changes, conflict, prepared::apply);
    }

    private static String validate(Workspace workspace,
                                   List<ClassInfo> postClasses,
                                   List<ClassChange> changes) {
        Set<String> targets = new HashSet<>();
        Set<String> existingJvmClasses = new HashSet<>();
        Set<String> existingAndroidClasses = new HashSet<>();
        workspace.getPrimaryResource().jvmAllClassBundleStreamRecursive()
                .forEach(bundle -> existingJvmClasses.addAll(bundle.keySet()));
        workspace.getPrimaryResource().androidClassBundleStreamRecursive()
                .forEach(bundle -> existingAndroidClasses.addAll(bundle.keySet()));
        for (ClassChange change : changes) {
            if (!change.renamed()) {
                continue;
            }
            String identity = change.scope().value() + '\0' + change.newName();
            if (!targets.add(identity)) {
                return "multiple classes would map to the same target name: "
                        + change.newName();
            }
            Set<String> existingClasses = change.scope() == ClassToolSupport.Scope.ANDROID
                    ? existingAndroidClasses
                    : existingJvmClasses;
            if (existingClasses.contains(change.newName())) {
                return "mapped class target already exists in the primary "
                        + change.scope().value() + " resource: "
                        + change.newName();
            }
        }
        for (ClassInfo classInfo : postClasses) {
            String duplicateField = duplicateMember(classInfo.getFields());
            if (duplicateField != null) {
                return "mapping would create a duplicate field in " + classInfo.getName()
                        + ": " + duplicateField;
            }
            String duplicateMethod = duplicateMember(classInfo.getMethods());
            if (duplicateMethod != null) {
                return "mapping would create a duplicate method in " + classInfo.getName()
                        + ": " + duplicateMethod;
            }
        }
        return null;
    }

    private static String duplicateMember(List<? extends ClassMember> members) {
        Set<String> identities = new HashSet<>();
        for (ClassMember member : members) {
            String identity = member.getName() + '\0' + member.getDescriptor();
            if (!identities.add(identity)) {
                return identity;
            }
        }
        return null;
    }

    private static void addChanges(JsonObject target,
                                   Request request,
                                   PreparedMapping prepared) {
        List<ClassChange> changes = prepared.classChanges();
        int start = Math.min(request.offset(), changes.size());
        int end = Math.min(start + request.limit(), changes.size());
        JsonArray page = new JsonArray();
        for (ClassChange change : changes.subList(start, end)) {
            JsonObject item = new JsonObject();
            item.addProperty("old_name", change.oldName());
            item.addProperty("new_name", change.newName());
            item.addProperty("renamed", change.renamed());
            item.addProperty("class_scope", change.scope().value());
            if (change.dexName() == null) {
                item.add("dex_name", JsonNull.INSTANCE);
            } else {
                item.addProperty("dex_name", change.dexName());
            }
            page.add(item);
        }
        long renamed = changes.stream().filter(ClassChange::renamed).count();
        long jvmChanges = changes.stream()
                .filter(change -> change.scope() == ClassToolSupport.Scope.JVM)
                .count();
        long androidChanges = changes.size() - jvmChanges;
        target.addProperty("changed_classes", changes.size());
        target.addProperty("selected_scope_changed_classes",
                selectedChanges(changes, request.scope()));
        target.addProperty("jvm_changed_classes", jvmChanges);
        target.addProperty("android_changed_classes", androidChanges);
        target.addProperty("renamed_classes", renamed);
        target.addProperty("reference_only_classes", changes.size() - renamed);
        target.addProperty("total", changes.size());
        target.addProperty("returned", end - start);
        target.addProperty("offset", request.offset());
        target.addProperty("limit", request.limit());
        target.add("class_changes", page);
        if (end < changes.size()) {
            target.addProperty("next_offset", end);
        } else {
            target.add("next_offset", JsonNull.INSTANCE);
        }
    }

    private JsonObject error(String message,
                             Request request,
                             MappingToolSupport.Descriptor descriptor,
                             MappingToolSupport.MappingCounts counts,
                             PreparedMapping prepared) {
        JsonObject structured = metadata(request, descriptor);
        if (counts != null) {
            MappingToolSupport.addCounts(structured, counts);
        }
        if (prepared != null) {
            addChanges(structured, request, prepared);
        }
        structured.addProperty("applied", false);
        structured.addProperty("save_required", false);
        structured.addProperty("workspace_mutated_in_memory", false);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private JsonObject metadata(Request request,
                                MappingToolSupport.Descriptor descriptor) {
        JsonObject structured = new JsonObject();
        MappingToolSupport.addFormatMetadata(structured, descriptor, formats);
        structured.addProperty(
                "scope",
                ClassToolSupport.outputScope(request.scope(), false)
        );
        structured.addProperty("requested_scope", request.scope().value());
        structured.addProperty("mapping_direction", "current_to_target");
        structured.addProperty("mapping_characters", request.mappings().length());
        structured.addProperty("mapping_sha256", MappingToolSupport.sha256(
                request.mappings()
        ));
        structured.addProperty("unique_keys", request.uniqueKeys());
        structured.addProperty("dry_run", request.dryRun());
        return structured;
    }

    private static Request parseRequest(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String format = ToolArguments.requiredString(arguments, "format");
        String mappings = ToolArguments.requiredString(arguments, "mappings");
        if (mappings.length() > MappingToolSupport.MAX_MAPPING_TEXT_LENGTH) {
            throw new IllegalArgumentException("mappings exceeds the maximum length");
        }
        String scopeValue = ToolArguments.optionalString(arguments, "scope");
        ClassToolSupport.Scope scope = scopeValue == null
                ? ClassToolSupport.Scope.JVM
                : ClassToolSupport.Scope.from(scopeValue);
        boolean uniqueKeys = ToolArguments.bool(arguments, "unique_keys", false);
        boolean dryRun = ToolArguments.bool(arguments, "dry_run", false);
        int offset = ToolArguments.integer(
                arguments,
                "offset",
                0,
                0,
                Integer.MAX_VALUE
        );
        int limit = ToolArguments.integer(
                arguments,
                "limit",
                DEFAULT_LIMIT,
                1,
                MAX_LIMIT
        );
        return new Request(format, mappings, scope, uniqueKeys, dryRun, offset, limit);
    }

    private static JsonObject createInputSchema(
            List<MappingToolSupport.Descriptor> descriptors) {
        JsonObject properties = new JsonObject();
        properties.add("format", MappingToolSupport.formatProperty(descriptors));

        JsonObject mappings = new JsonObject();
        mappings.addProperty("type", "string");
        mappings.addProperty("minLength", 1);
        mappings.addProperty("maxLength", MappingToolSupport.MAX_MAPPING_TEXT_LENGTH);
        mappings.addProperty(
                "description",
                "Inline source-to-destination mapping document."
        );
        properties.add("mappings", mappings);

        ClassToolSupport.addScopeProperty(properties, true);

        JsonObject uniqueKeys = new JsonObject();
        uniqueKeys.addProperty("type", "boolean");
        uniqueKeys.addProperty("default", false);
        uniqueKeys.addProperty(
                "description",
                "Enable Recaf UniqueKeyMappings for globally unique names."
        );
        properties.add("unique_keys", uniqueKeys);

        JsonObject dryRun = new JsonObject();
        dryRun.addProperty("type", "boolean");
        dryRun.addProperty("default", false);
        dryRun.addProperty(
                "description",
                "Validate class rewrites without mutation."
        );
        properties.add("dry_run", dryRun);
        properties.add("offset", integerProperty(0, Integer.MAX_VALUE, 0));
        properties.add("limit", integerProperty(1, MAX_LIMIT, DEFAULT_LIMIT));

        JsonArray required = new JsonArray();
        required.add("format");
        required.add("mappings");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty(
                "description",
                "Applies in memory; scope requires a matching-domain change but may "
                        + "rewrite cross-domain references. APK save is unsupported."
        );
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject integerProperty(int minimum,
                                              int maximum,
                                              int defaultValue) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("minimum", minimum);
        property.addProperty("maximum", maximum);
        property.addProperty("default", defaultValue);
        return property;
    }

    record Request(String format,
                   String mappings,
                   ClassToolSupport.Scope scope,
                   boolean uniqueKeys,
                   boolean dryRun,
                   int offset,
                   int limit) {
        private Request withFormat(String canonicalFormat) {
            return new Request(
                    canonicalFormat,
                    mappings,
                    scope,
                    uniqueKeys,
                    dryRun,
                    offset,
                    limit
            );
        }
    }

    record ClassChange(String oldName,
                       String newName,
                       ClassToolSupport.Scope scope,
                       String dexName) {
        ClassChange {
            Objects.requireNonNull(oldName, "oldName");
            Objects.requireNonNull(newName, "newName");
            Objects.requireNonNull(scope, "scope");
        }

        ClassChange(String oldName, String newName) {
            this(oldName, newName, ClassToolSupport.Scope.JVM, null);
        }

        boolean renamed() {
            return !oldName.equals(newName);
        }
    }

    record PreparedMapping(List<ClassChange> classChanges,
                           String conflict,
                           Runnable application) {
        PreparedMapping {
            classChanges = List.copyOf(classChanges);
            Objects.requireNonNull(application, "application");
        }

        void apply() {
            application.run();
        }
    }

    @FunctionalInterface
    interface ApplyOperation {
        PreparedMapping prepare(Workspace workspace, Mappings mappings);
    }

    private static int selectedChanges(List<ClassChange> changes,
                                       ClassToolSupport.Scope scope) {
        if (scope == ClassToolSupport.Scope.ALL) {
            return changes.size();
        }
        return (int) changes.stream()
                .filter(change -> change.scope() == scope)
                .count();
    }

}
