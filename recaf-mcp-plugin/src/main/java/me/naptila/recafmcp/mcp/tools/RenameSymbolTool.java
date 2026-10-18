package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
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
            "scope",
            "dex_name"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;
    private final RenameOperation renameOperation;

    public RenameSymbolTool(WorkspaceManager workspaceManager,
                            MappingApplierService mappingApplierService) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                fullOperation(mappingApplierService)
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
                        new TargetedMappingApplier(
                                searchService,
                                inheritanceGraphService,
                                mappingListeners,
                                aggregateMappingManager
                        )
                )
        );
    }

    RenameSymbolTool(BooleanSupplier hasWorkspace,
                     Supplier<Workspace> workspaceSupplier,
                     RenameOperation renameOperation) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.renameOperation = Objects.requireNonNull(renameOperation, "renameOperation");
    }

    private static RenameOperation fullOperation(MappingApplierService service) {
        return (workspace, request) -> {
            MappingsAdapter mappings = mappings(workspace, request);
            MappingResults results = service
                    .inWorkspace(workspace)
                    .applyToPrimaryResource(mappings);
            MappingMutationSupport.Summary summary =
                    MappingMutationSupport.summarize(workspace, results);
            ToolCancellation.check();
            results.apply();
            return summary;
        };
    }

    private static RenameOperation targetedOperation(
            MappingApplierService fullService,
            TargetedMappingApplier targetedApplier) {
        RenameOperation full = fullOperation(fullService);
        return (workspace, request) -> {
            if (!MappingMutationSupport.hasAndroidClasses(workspace)) {
                return full.run(workspace, request);
            }
            TargetedMappingApplier.Prepared prepared = targetedApplier.prepare(
                    workspace,
                    mappings(workspace, request)
            );
            ToolCancellation.check();
            prepared.apply();
            return prepared.summary();
        };
    }

    private static MappingsAdapter mappings(Workspace workspace,
                                            RenameRequest request) {
        MappingsAdapter mappings = new MappingsAdapter(true, true);
        mappings.enableClassLookup(workspace);
        switch (request.kind()) {
            case CLASS -> mappings.addClass(request.name(), request.newName());
            case FIELD -> mappings.addField(
                    request.owner(),
                    request.name(),
                    request.descriptor(),
                    request.newName()
            );
            case METHOD -> mappings.addMethod(
                    request.owner(),
                    request.name(),
                    request.descriptor(),
                    request.newName()
            );
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
        TargetResolution resolution = validateTarget(workspace, request);
        if (resolution.error() != null) {
            return error(resolution.error(), request);
        }
        request = request.withTarget(resolution.target());

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

        JsonObject structuredContent = targetMetadata(request);
        MappingMutationSupport.addSummary(structuredContent, summary);
        MappingMutationSupport.addPersistenceMetadata(structuredContent, workspace, true);
        String suffix = " in memory";
        return McpTool.successResult(
                "renamed " + request.kind().jsonName + " "
                        + request.displayName() + suffix,
                structuredContent
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
                selection,
                null
        );
    }

    private static TargetResolution validateTarget(Workspace workspace,
                                                   RenameRequest request) {
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
            ClassToolSupport.Selection collisionSelection =
                    new ClassToolSupport.Selection(target.scope(), null);
            if (!ClassToolSupport.exact(
                    resource,
                    collisionSelection,
                    request.newName()
            ).isEmpty()) {
                return TargetResolution.error(
                        "target class name already exists in primary "
                                + target.scope().value() + " resource"
                );
            }
            return TargetResolution.success(target);
        }

        ClassInfo owner = target.classInfo();
        if (request.kind() == SymbolKind.FIELD) {
            if (owner.getDeclaredField(request.name(), request.descriptor()) == null) {
                return TargetResolution.error(
                        "field with exact descriptor not found in owner class"
                );
            }
            if (owner.getDeclaredField(request.newName(), request.descriptor()) != null) {
                return TargetResolution.error(
                        "target field name and descriptor already exist in owner class"
                );
            }
        } else {
            if (owner.getDeclaredMethod(request.name(), request.descriptor()) == null) {
                return TargetResolution.error(
                        "method with exact descriptor not found in owner class"
                );
            }
            if (owner.getDeclaredMethod(request.newName(), request.descriptor()) != null) {
                return TargetResolution.error(
                        "target method name and descriptor already exist in owner class"
                );
            }
        }
        return TargetResolution.success(target);
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
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
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
                         ClassToolSupport.Selection selection,
                         ClassToolSupport.Entry target) {
        RenameRequest {
            Objects.requireNonNull(selection, "selection");
        }

        private RenameRequest withTarget(ClassToolSupport.Entry resolvedTarget) {
            return new RenameRequest(
                    kind,
                    owner,
                    name,
                    descriptor,
                    newName,
                    selection,
                    resolvedTarget
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

    record TargetResolution(ClassToolSupport.Entry target, String error) {
        private static TargetResolution success(ClassToolSupport.Entry target) {
            return new TargetResolution(Objects.requireNonNull(target), null);
        }

        private static TargetResolution error(String message) {
            return new TargetResolution(null, Objects.requireNonNull(message));
        }
    }

}
