package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.AndroidInstructionPathNode;
import software.coley.recaf.path.JvmInstructionPathNode;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.path.ResourcePathNode;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.search.SearchFeedback;
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
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Searches primary-resource JVM and Android string constants with bounded pagination.
 */
public final class SearchStringsTool implements McpTool {
    private static final int MAX_VALUE_LENGTH = 4096;
    private static final int MAX_COLLECTED_RESULTS = 20_000;
    private static final Set<String> ARGUMENTS = Set.of(
            "text", "regex", "scope", "dex_name", "offset", "limit"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();
    private static final Comparator<StringHit> HIT_ORDER = Comparator
            .comparing(StringHit::className)
            .thenComparing(StringHit::classScope)
            .thenComparing(StringHit::dexName, Comparator.nullsFirst(String::compareTo))
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
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(arguments, true);
        if (!hasWorkspace.getAsBoolean()) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "workspace not open");
            return McpTool.errorResult("workspace not open", error);
        }

        List<StringHit> hits = new ArrayList<>(searchOperation.run(
                workspaceSupplier.get(),
                request,
                selection
        ));
        hits.sort(HIT_ORDER);
        JsonArray page = new JsonArray();
        for (int index = 0; index < hits.size(); index++) {
            if (SearchToolSupport.inPage(index, request)) {
                page.add(toJson(hits.get(index)));
            }
        }

        JsonObject structuredContent = new JsonObject();
        structuredContent.add("hits", page);
        structuredContent.addProperty(
                "scope",
                ClassToolSupport.outputScope(selection.scope(), false)
        );
        structuredContent.addProperty("requested_scope", selection.scope().value());
        if (selection.dexName() == null) {
            structuredContent.add("dex_name", JsonNull.INSTANCE);
        } else {
            structuredContent.addProperty("dex_name", selection.dexName());
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
        Set<ClassInfo> selectedClasses = Collections.newSetFromMap(new IdentityHashMap<>());
        ClassToolSupport.entries(primary, selection).forEach(entry ->
                selectedClasses.add(entry.classInfo()));
        List<StringHit> hits = new ArrayList<>();
        SearchFeedback feedback = new SearchFeedback() {
            private int accepted;

            @Override
            public boolean hasRequestedCancellation() {
                return ToolCancellation.requested() || accepted >= MAX_COLLECTED_RESULTS;
            }

            @Override
            public boolean doVisitClass(ClassInfo classInfo) {
                return selectedClasses.contains(classInfo);
            }

            @Override
            public boolean doAcceptResult(Result<?> result) {
                if (accepted >= MAX_COLLECTED_RESULTS) {
                    return false;
                }
                accepted++;
                return true;
            }
        };
        for (Result<?> result : searchService.search(workspace, query, feedback)) {
            ToolCancellation.check();
            if (!(result instanceof StringResult stringResult)) {
                continue;
            }
            PathNode<?> path = stringResult.getPath();
            ResourcePathNode resourcePath = findPath(path, ResourcePathNode.class);
            ClassPathNode classPath = findPath(path, ClassPathNode.class);
            if (resourcePath == null
                    || resourcePath.getValue() != primary
                    || classPath == null
                    || !(classPath.getValue() instanceof ClassInfo classInfo)) {
                continue;
            }
            ClassToolSupport.Entry classEntry = ClassToolSupport.identify(primary, classInfo);
            if (classEntry == null || !selectedClasses.contains(classInfo)) {
                continue;
            }
            ClassMemberPathNode memberPath = findPath(path, ClassMemberPathNode.class);
            ClassMember member = memberPath == null ? null : memberPath.getValue();
            JvmInstructionPathNode instructionPath = findPath(
                    path,
                    JvmInstructionPathNode.class
            );
            AndroidInstructionPathNode androidInstructionPath = findPath(
                    path,
                    AndroidInstructionPathNode.class
            );
            Integer instructionIndex = null;
            if (instructionPath != null) {
                instructionIndex = instructionPath.getInstructionIndex();
            } else if (androidInstructionPath != null) {
                instructionIndex = androidInstructionPath.getInstructionIndex();
            }
            hits.add(new StringHit(
                    classInfo.getName(),
                    classEntry.scope().value(),
                    classEntry.dexName(),
                    member == null ? null : member.isField() ? "field" : "method",
                    member == null ? null : member.getName(),
                    member == null ? null : member.getDescriptor(),
                    instructionIndex,
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
        addNullable(result, "member_kind", hit.memberKind());
        addNullable(result, "member_name", hit.memberName());
        addNullable(result, "descriptor", hit.descriptor());
        if (hit.instructionIndex() == null) {
            result.add("instruction_index", JsonNull.INSTANCE);
        } else {
            result.addProperty("instruction_index", hit.instructionIndex());
        }
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

    private static <T extends PathNode<?>> T findPath(PathNode<?> start, Class<T> type) {
        PathNode<?> current = start;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getParent();
        }
        return null;
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
                     String memberKind,
                     String memberName,
                     String descriptor,
                     Integer instructionIndex,
                     String value) {
        StringHit {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(classScope, "classScope");
            Objects.requireNonNull(value, "value");
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
                    memberKind,
                    memberName,
                    descriptor,
                    instructionIndex,
                    value
            );
        }
    }

    private static JsonObject createInputSchema() {
        JsonObject schema = SearchToolSupport.inputSchema(
                "String-constant literal or partial Java regex."
        );
        ClassToolSupport.addScopeProperties(schema.getAsJsonObject("properties"), true);
        return schema;
    }
}
