package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.path.AndroidInstructionPathNode;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.JvmInstructionPathNode;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.path.ResourcePathNode;
import software.coley.recaf.services.search.SearchFeedback;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.search.match.StringPredicateProvider;
import software.coley.recaf.services.search.query.StringQuery;
import software.coley.recaf.services.search.result.Result;
import software.coley.recaf.services.search.result.StringResult;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Searches primary or embedded JVM and Android string constants with bounded pagination.
 */
public final class SearchStringsTool implements McpTool {
    private static final int MAX_VALUE_LENGTH = 4096;
    private static final int MAX_FILTER_LENGTH = 500;
    private static final int MAX_COLLECTED_RESULTS = 20_000;
    private static final Set<String> ARGUMENTS = Set.of(
            "text",
            "regex",
            "filter",
            "scope",
            "dex_name",
            ClassToolSupport.RESOURCE_ARGUMENT,
            ClassToolSupport.INCLUDE_EMBEDDED_ARGUMENT,
            "offset",
            "limit"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();
    private static final Comparator<StringHit> HIT_ORDER = Comparator
            .comparing(StringHit::className)
            .thenComparing(StringHit::classScope)
            .thenComparing(StringHit::dexName, Comparator.nullsFirst(String::compareTo))
            .thenComparing(StringHit::resourcePath, Comparator.nullsFirst(String::compareTo))
            .thenComparing(StringHit::memberKind, Comparator.nullsFirst(String::compareTo))
            .thenComparing(StringHit::memberName, Comparator.nullsFirst(String::compareTo))
            .thenComparing(StringHit::descriptor, Comparator.nullsFirst(String::compareTo))
            .thenComparing(StringHit::instructionIndex, Comparator.nullsFirst(Integer::compareTo))
            .thenComparing(StringHit::value);

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;
    private final SearchOperation searchOperation;

    public SearchStringsTool(WorkspaceManager workspaceManager,
                             SearchService searchService,
                             StringPredicateProvider predicateProvider) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                (workspace, request, selection) -> runSearch(
                        workspace,
                        searchService,
                        predicateProvider,
                        request,
                        selection
                )
        );
    }

    SearchStringsTool(BooleanSupplier hasWorkspace,
                      Supplier<Workspace> workspaceSupplier,
                      SearchOperation searchOperation) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.searchOperation = Objects.requireNonNull(searchOperation, "searchOperation");
    }

    SearchStringsTool(BooleanSupplier hasWorkspace,
                      Supplier<Workspace> workspaceSupplier,
                      LegacySearchOperation searchOperation) {
        this(
                hasWorkspace,
                workspaceSupplier,
                (workspace, request, selection) -> searchOperation.run(workspace, request)
        );
    }

    SearchStringsTool(BooleanSupplier hasWorkspace,
                      Supplier<Workspace> workspaceSupplier,
                      SearchService searchService,
                      StringPredicateProvider predicateProvider) {
        this(
                hasWorkspace,
                workspaceSupplier,
                (workspace, request, selection) -> runSearch(
                        workspace,
                        searchService,
                        predicateProvider,
                        request,
                        selection
                )
        );
    }

    @Override
    public String name() {
        return "search_strings";
    }

    @Override
    public String description() {
        return "Search JVM and/or Android string constants.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        SearchToolSupport.Request request = SearchToolSupport.parse(arguments, ARGUMENTS);
        String filter = me.naptila.recafmcp.mcp.util.ToolArguments.optionalString(arguments, "filter");
        if (filter != null && filter.length() > MAX_FILTER_LENGTH) {
            throw new IllegalArgumentException("filter is too long");
        }
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(
                arguments,
                true,
                true
        );
        if (!hasWorkspace.getAsBoolean()) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "workspace not open");
            return McpTool.errorResult("workspace not open", error);
        }

        Workspace workspace = workspaceSupplier.get();
        WorkspaceResource primary = workspace == null ? null : workspace.getPrimaryResource();
        if (primary != null) {
            JsonObject selectionError = ClassToolSupport.validateSelection(primary, selection);
            if (selectionError != null) {
                return selectionError;
            }
        }

        List<StringHit> hits = new ArrayList<>(searchOperation.run(
                workspace,
                request,
                selection
        ));
        if (filter != null) {
            hits.removeIf(hit -> !hit.className().contains(filter));
        }
        hits.sort(HIT_ORDER);
        JsonArray page = new JsonArray();
        for (int index = 0; index < hits.size(); index++) {
            if (SearchToolSupport.inPage(index, request)) {
                page.add(toJson(hits.get(index)));
            }
        }

        JsonObject structuredContent = new JsonObject();
        structuredContent.add("hits", page);
        addNullable(structuredContent, "filter", filter);
        structuredContent.addProperty(
                "scope",
                ClassToolSupport.outputScope(selection.scope(), false, selection)
        );
        structuredContent.addProperty("requested_scope", selection.scope().value());
        if (selection.dexName() == null) {
            structuredContent.add("dex_name", JsonNull.INSTANCE);
        } else {
            structuredContent.addProperty("dex_name", selection.dexName());
        }
        ClassToolSupport.addSelectionMetadata(structuredContent, selection);
        if (primary != null) {
            ClassToolSupport.addScopeDiagnostics(structuredContent, primary, selection);
        }
        structuredContent.addProperty(
                "collection_truncated",
                hits.size() >= MAX_COLLECTED_RESULTS
        );
        SearchToolSupport.addPageMetadata(
                structuredContent,
                request,
                hits.size(),
                page.size()
        );
        return McpTool.successResult(
                "found " + hits.size() + " matching string constant locations",
                structuredContent
        );
    }

    private static List<StringHit> runSearch(Workspace workspace,
                                             SearchService searchService,
                                             StringPredicateProvider provider,
                                             SearchToolSupport.Request request,
                                             ClassToolSupport.Selection selection) {
        StringQuery query = new StringQuery(
                SearchToolSupport.predicate(provider, request.text(), request.regex())
        );
        WorkspaceResource primary = workspace.getPrimaryResource();
        List<ClassToolSupport.ScopedResource> targets =
                ClassToolSupport.targetResources(primary, selection);
        Map<ClassInfo, ClassToolSupport.Entry> selectedByClass = new IdentityHashMap<>();
        ClassToolSupport.entries(primary, selection).forEach(entry ->
                selectedByClass.put(entry.classInfo(), entry));
        Set<ClassInfo> selectedClasses = Collections.newSetFromMap(new IdentityHashMap<>());
        selectedClasses.addAll(selectedByClass.keySet());
        Set<WorkspaceResource> allowedResources = Collections.newSetFromMap(
                new IdentityHashMap<>()
        );
        targets.forEach(item -> allowedResources.add(item.resource()));

        AtomicInteger accepted = new AtomicInteger();
        SearchFeedback feedback = SearchToolSupport.classOnlyFeedback(
                selectedClasses,
                MAX_COLLECTED_RESULTS,
                accepted,
                null
        );
        List<StringHit> hits = new ArrayList<>();
        if (selectedClasses.isEmpty()) {
            return hits;
        }
        for (Result<?> result : searchService.search(workspace, query, feedback)) {
            ToolCancellation.check();
            if (!(result instanceof StringResult stringResult)) {
                continue;
            }
            PathNode<?> path = stringResult.getPath();
            ResourcePathNode resourcePath = SearchToolSupport.findPath(path, ResourcePathNode.class);
            ClassPathNode classPath = SearchToolSupport.findPath(path, ClassPathNode.class);
            if (resourcePath == null
                    || !allowedResources.contains(resourcePath.getValue())
                    || classPath == null
                    || !(classPath.getValue() instanceof ClassInfo classInfo)) {
                continue;
            }
            ClassToolSupport.Entry classEntry = selectedByClass.get(classInfo);
            if (classEntry == null) {
                continue;
            }
            ClassMemberPathNode memberPath = SearchToolSupport.findPath(path, ClassMemberPathNode.class);
            ClassMember member = memberPath == null ? null : memberPath.getValue();
            JvmInstructionPathNode instructionPath = SearchToolSupport.findPath(
                    path,
                    JvmInstructionPathNode.class
            );
            AndroidInstructionPathNode androidInstructionPath = SearchToolSupport.findPath(
                    path,
                    AndroidInstructionPathNode.class
            );
            Integer instructionIndex = null;
            if (instructionPath != null) {
                instructionIndex = instructionPath.getInstructionIndex();
            } else if (androidInstructionPath != null) {
                instructionIndex = androidInstructionPath.getInstructionIndex();
            }
            boolean inAnnotation = SearchToolSupport.findPath(
                    path,
                    software.coley.recaf.path.AnnotationPathNode.class
            ) != null;
            String locationKind;
            if (instructionIndex != null) {
                locationKind = "instruction";
            } else if (inAnnotation) {
                locationKind = "annotation";
            } else if (member != null && member.isField()) {
                locationKind = "field_constant";
            } else if (member != null) {
                locationKind = "member_metadata";
            } else {
                locationKind = "class_metadata";
            }
            hits.add(new StringHit(
                    classInfo.getName(),
                    classEntry.scope().value(),
                    classEntry.dexName(),
                    classEntry.resourcePath(),
                    member == null ? null : member.isField() ? "field" : "method",
                    member == null ? null : member.getName(),
                    member == null ? null : member.getDescriptor(),
                    instructionIndex,
                    locationKind,
                    stringResult.getValue()
            ));
        }
        return hits;
    }

    private static JsonObject toJson(StringHit hit) {
        JsonObject result = new JsonObject();
        result.addProperty("class_name", hit.className());
        result.addProperty("class_scope", hit.classScope());
        addNullable(result, "dex_name", hit.dexName());
        if (hit.resourcePath() != null) {
            result.addProperty("resource", hit.resourcePath());
        }
        addNullable(result, "member_kind", hit.memberKind());
        addNullable(result, "member_name", hit.memberName());
        addNullable(result, "descriptor", hit.descriptor());
        if (hit.instructionIndex() == null) {
            result.add("instruction_index", JsonNull.INSTANCE);
        } else {
            result.addProperty("instruction_index", hit.instructionIndex());
        }
        result.addProperty("location_kind", hit.locationKind());
        boolean truncated = hit.value().length() > MAX_VALUE_LENGTH;
        result.addProperty(
                "value",
                truncated ? hit.value().substring(0, MAX_VALUE_LENGTH) : hit.value()
        );
        result.addProperty("value_truncated", truncated);
        return result;
    }

    private static void addNullable(JsonObject target, String name, String value) {
        if (value == null) {
            target.add(name, JsonNull.INSTANCE);
        } else {
            target.addProperty(name, value);
        }
    }

    @FunctionalInterface
    interface SearchOperation {
        List<StringHit> run(Workspace workspace,
                            SearchToolSupport.Request request,
                            ClassToolSupport.Selection selection);
    }

    @FunctionalInterface
    interface LegacySearchOperation {
        List<StringHit> run(Workspace workspace, SearchToolSupport.Request request);
    }

    record StringHit(String className,
                     String classScope,
                     String dexName,
                     String resourcePath,
                     String memberKind,
                     String memberName,
                     String descriptor,
                     Integer instructionIndex,
                     String locationKind,
                     String value) {
        StringHit {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(classScope, "classScope");
            Objects.requireNonNull(locationKind, "locationKind");
            Objects.requireNonNull(value, "value");
        }

        StringHit(String className,
                  String classScope,
                  String dexName,
                  String resourcePath,
                  String memberKind,
                  String memberName,
                  String descriptor,
                  Integer instructionIndex,
                  String value) {
            this(
                    className,
                    classScope,
                    dexName,
                    resourcePath,
                    memberKind,
                    memberName,
                    descriptor,
                    instructionIndex,
                    defaultLocationKind(memberKind, instructionIndex),
                    value
            );
        }

        StringHit(String className,
                  String classScope,
                  String dexName,
                  String memberKind,
                  String memberName,
                  String descriptor,
                  Integer instructionIndex,
                  String value) {
            this(
                    className,
                    classScope,
                    dexName,
                    null,
                    memberKind,
                    memberName,
                    descriptor,
                    instructionIndex,
                    defaultLocationKind(memberKind, instructionIndex),
                    value
            );
        }

        StringHit(String className,
                  String memberKind,
                  String memberName,
                  String descriptor,
                  Integer instructionIndex,
                  String value) {
            this(
                    className,
                    ClassToolSupport.Scope.JVM.value(),
                    null,
                    null,
                    memberKind,
                    memberName,
                    descriptor,
                    instructionIndex,
                    defaultLocationKind(memberKind, instructionIndex),
                    value
            );
        }

        private static String defaultLocationKind(String memberKind, Integer instructionIndex) {
            if (instructionIndex != null) {
                return "instruction";
            }
            if ("field".equals(memberKind)) {
                return "field_constant";
            }
            if (memberKind != null) {
                return "member_metadata";
            }
            return "class_metadata";
        }
    }

    private static JsonObject createInputSchema() {
        JsonObject schema = SearchToolSupport.inputSchema(
                "String-constant literal or partial Java regex."
        );
        JsonObject filter = new JsonObject();
        filter.addProperty("type", "string");
        filter.addProperty("minLength", 1);
        filter.addProperty("maxLength", MAX_FILTER_LENGTH);
        filter.addProperty(
                "description",
                "Class-name substring limiting scanned classes."
        );
        schema.getAsJsonObject("properties").add("filter", filter);
        ClassToolSupport.addScopeProperties(
                schema.getAsJsonObject("properties"),
                true,
                true,
                true
        );
        return schema;
    }
}
