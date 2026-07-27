package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.JvmInstructionPathNode;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.path.ResourcePathNode;
import software.coley.recaf.services.search.SearchFeedback;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.search.match.StringPredicate;
import software.coley.recaf.services.search.match.StringPredicateProvider;
import software.coley.recaf.services.search.query.InstructionQuery;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Searches JVM and DEX instructions without decompiling classes.
 */
public final class SearchInstructionsTool implements McpTool {
    private static final int MAX_INSTRUCTION_LENGTH = 4096;
    private static final int MAX_COLLECTED_RESULTS = 20_000;
    private static final int MAX_FILTER_LENGTH = 500;
    private static final Set<String> ARGUMENTS = Set.of(
            "text", "regex", "filter", "scope", "dex_name", "offset", "limit"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();
    private static final Comparator<InstructionHit> HIT_ORDER = Comparator
            .comparing(InstructionHit::className)
            .thenComparing(InstructionHit::classScope)
            .thenComparing(InstructionHit::dexName,
                    Comparator.nullsFirst(String::compareTo))
            .thenComparing(InstructionHit::memberName)
            .thenComparing(InstructionHit::descriptor)
            .thenComparingInt(InstructionHit::instructionIndex)
            .thenComparing(InstructionHit::instruction);

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;
    private final ScopedSearchOperation searchOperation;

    public SearchInstructionsTool(WorkspaceManager workspaceManager,
                                  SearchService searchService,
                                  StringPredicateProvider predicateProvider) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                (workspace, request, filter, selection) -> runSearch(
                        workspace,
                        searchService,
                        predicateProvider,
                        request,
                        filter,
                        selection
                )
        );
    }

    SearchInstructionsTool(BooleanSupplier hasWorkspace,
                           Supplier<Workspace> workspaceSupplier,
                           ScopedSearchOperation searchOperation) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.searchOperation = Objects.requireNonNull(searchOperation, "searchOperation");
    }

    SearchInstructionsTool(BooleanSupplier hasWorkspace,
                           Supplier<Workspace> workspaceSupplier,
                           SearchOperation searchOperation) {
        this(
                hasWorkspace,
                workspaceSupplier,
                (workspace, request, filter, selection) -> searchOperation.run(
                        workspace,
                        request,
                        filter
                )
        );
    }

    SearchInstructionsTool(BooleanSupplier hasWorkspace,
                           Supplier<Workspace> workspaceSupplier,
                           SearchService searchService,
                           StringPredicateProvider predicateProvider) {
        this(
                hasWorkspace,
                workspaceSupplier,
                (workspace, request, filter, selection) -> runSearch(
                        workspace,
                        searchService,
                        predicateProvider,
                        request,
                        filter,
                        selection
                )
        );
    }

    @Override
    public String name() {
        return "search_instructions";
    }

    @Override
    public String description() {
        return "Search JVM assembler or Android DEX instructions without decompilation.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        SearchToolSupport.Request request = SearchToolSupport.parse(arguments, ARGUMENTS);
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(
                arguments,
                true
        );
        String filter = ToolArguments.optionalString(arguments, "filter");
        if (filter != null && filter.length() > MAX_FILTER_LENGTH) {
            throw new IllegalArgumentException("filter is too long");
        }
        if (!hasWorkspace.getAsBoolean()) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "workspace not open");
            return McpTool.errorResult("workspace not open", error);
        }

        Workspace workspace = workspaceSupplier.get();
        int classesSelected = (int) ClassToolSupport.entries(
                        workspace.getPrimaryResource(),
                        selection
                )
                .stream()
                .filter(entry -> filter == null || entry.className().contains(filter))
                .count();
        List<InstructionHit> hits = new ArrayList<>(searchOperation.run(
                workspace,
                request,
                filter,
                selection
        ));
        hits.sort(HIT_ORDER);
        JsonArray page = new JsonArray();
        for (int index = 0; index < hits.size(); index++) {
            if (SearchToolSupport.inPage(index, request)) {
                page.add(toJson(hits.get(index)));
            }
        }

        JsonObject structured = new JsonObject();
        structured.add("hits", page);
        structured.addProperty(
                "scope",
                ClassToolSupport.outputScope(selection.scope(), false) + "_instructions"
        );
        structured.addProperty("requested_scope", selection.scope().value());
        if (selection.dexName() == null) {
            structured.add("dex_name", JsonNull.INSTANCE);
        } else {
            structured.addProperty("dex_name", selection.dexName());
        }
        if (filter == null) {
            structured.add("filter", JsonNull.INSTANCE);
        } else {
            structured.addProperty("filter", filter);
        }
        structured.addProperty("classes_selected", classesSelected);
        structured.addProperty(
                "collection_truncated",
                hits.size() >= MAX_COLLECTED_RESULTS
        );
        SearchToolSupport.addPageMetadata(structured, request, hits.size(), page.size());
        return McpTool.successResult(
                "found " + hits.size() + " matching instruction locations",
                structured
        );
    }

    private static List<InstructionHit> runSearch(Workspace workspace,
                                                  SearchService searchService,
                                                  StringPredicateProvider provider,
                                                  SearchToolSupport.Request request,
                                                  String filter,
                                                  ClassToolSupport.Selection selection) {
        StringPredicate predicate = SearchToolSupport.predicate(
                provider,
                request.text(),
                request.regex()
        );
        InstructionQuery query = new InstructionQuery(List.of(predicate));
        WorkspaceResource primary = workspace.getPrimaryResource();
        List<ClassToolSupport.Entry> selectedEntries = ClassToolSupport.entries(
                        primary,
                        selection
                )
                .stream()
                .filter(entry -> filter == null || entry.className().contains(filter))
                .toList();
        Set<ClassInfo> selectedJvmClasses = Collections.newSetFromMap(
                new IdentityHashMap<>()
        );
        selectedEntries.stream()
                .filter(entry -> entry.scope() == ClassToolSupport.Scope.JVM)
                .forEach(entry -> selectedJvmClasses.add(entry.classInfo()));
        AtomicInteger accepted = new AtomicInteger();
        SearchFeedback feedback = new SearchFeedback() {
            @Override
            public boolean hasRequestedCancellation() {
                return ToolCancellation.requested()
                        || accepted.get() >= MAX_COLLECTED_RESULTS;
            }

            @Override
            public boolean doVisitClass(ClassInfo classInfo) {
                return selectedJvmClasses.contains(classInfo);
            }

            @Override
            public boolean doAcceptResult(Result<?> result) {
                while (true) {
                    int current = accepted.get();
                    if (current >= MAX_COLLECTED_RESULTS) {
                        return false;
                    }
                    if (accepted.compareAndSet(current, current + 1)) {
                        return true;
                    }
                }
            }
        };

        List<InstructionHit> hits = new ArrayList<>();
        if (!selectedJvmClasses.isEmpty()) {
            for (Result<?> result : searchService.search(workspace, query, feedback)) {
                ToolCancellation.check();
                if (!(result instanceof StringResult stringResult)) {
                    continue;
                }
                PathNode<?> path = result.getPath();
                ResourcePathNode resourcePath = findPath(path, ResourcePathNode.class);
                ClassPathNode classPath = findPath(path, ClassPathNode.class);
                ClassMemberPathNode memberPath = findPath(path, ClassMemberPathNode.class);
                JvmInstructionPathNode instructionPath = findPath(
                        path,
                        JvmInstructionPathNode.class
                );
                if (resourcePath == null
                        || resourcePath.getValue() != primary
                        || classPath == null
                        || !(classPath.getValue() instanceof JvmClassInfo classInfo)
                        || memberPath == null
                        || instructionPath == null) {
                    continue;
                }
                ClassToolSupport.Entry entry = ClassToolSupport.identify(primary, classInfo);
                if (entry == null || !selectedJvmClasses.contains(classInfo)) {
                    continue;
                }
                ClassMember member = memberPath.getValue();
                hits.add(new InstructionHit(
                        classInfo.getName(),
                        entry.scope().value(),
                        entry.dexName(),
                        member.getName(),
                        member.getDescriptor(),
                        instructionPath.getInstructionIndex(),
                        null,
                        stringResult.getValue()
                ));
            }
        }

        Predicate<String> matcher = SearchToolSupport.matcher(
                request.text(),
                request.regex()
        );
        for (ClassToolSupport.Entry entry : selectedEntries) {
            if (entry.scope() != ClassToolSupport.Scope.ANDROID) {
                continue;
            }
            for (AndroidInstructionSupport.MethodCode method
                    : AndroidInstructionSupport.methods(entry)) {
                for (AndroidInstructionSupport.InstructionLine instruction
                        : AndroidInstructionSupport.instructions(method)) {
                    ToolCancellation.check();
                    if (hits.size() >= MAX_COLLECTED_RESULTS) {
                        return hits;
                    }
                    if (!matcher.test(instruction.text())) {
                        continue;
                    }
                    hits.add(new InstructionHit(
                            entry.className(),
                            entry.scope().value(),
                            entry.dexName(),
                            method.name(),
                            method.descriptor(),
                            instruction.index(),
                            instruction.offset(),
                            instruction.text()
                    ));
                }
            }
        }
        return hits;
    }

    private static JsonObject toJson(InstructionHit hit) {
        JsonObject json = new JsonObject();
        json.addProperty("class_name", hit.className());
        json.addProperty("class_scope", hit.classScope());
        if (hit.dexName() == null) {
            json.add("dex_name", JsonNull.INSTANCE);
        } else {
            json.addProperty("dex_name", hit.dexName());
        }
        json.addProperty("member_name", hit.memberName());
        json.addProperty("descriptor", hit.descriptor());
        json.addProperty("instruction_index", hit.instructionIndex());
        if (hit.dexOffset() == null) {
            json.add("dex_offset", JsonNull.INSTANCE);
        } else {
            json.addProperty("dex_offset", hit.dexOffset());
        }
        boolean truncated = hit.instruction().length() > MAX_INSTRUCTION_LENGTH;
        json.addProperty(
                "instruction",
                truncated
                        ? hit.instruction().substring(0, MAX_INSTRUCTION_LENGTH)
                        : hit.instruction()
        );
        json.addProperty("instruction_truncated", truncated);
        return json;
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

    private static JsonObject createInputSchema() {
        JsonObject schema = SearchToolSupport.inputSchema(
                "Instruction literal or partial Java regex."
        );
        JsonObject filter = new JsonObject();
        filter.addProperty("type", "string");
        filter.addProperty("minLength", 1);
        filter.addProperty("maxLength", MAX_FILTER_LENGTH);
        filter.addProperty(
                "description",
                "Internal class-name substring or package prefix."
        );
        JsonObject properties = schema.getAsJsonObject("properties");
        properties.add("filter", filter);
        ClassToolSupport.addScopeProperties(properties, true);
        return schema;
    }

    @FunctionalInterface
    interface SearchOperation {
        List<InstructionHit> run(Workspace workspace,
                                 SearchToolSupport.Request request,
                                 String filter);
    }

    @FunctionalInterface
    interface ScopedSearchOperation {
        List<InstructionHit> run(Workspace workspace,
                                 SearchToolSupport.Request request,
                                 String filter,
                                 ClassToolSupport.Selection selection);
    }

    record InstructionHit(String className,
                          String classScope,
                          String dexName,
                          String memberName,
                          String descriptor,
                          int instructionIndex,
                          Integer dexOffset,
                          String instruction) {
        InstructionHit {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(classScope, "classScope");
            Objects.requireNonNull(memberName, "memberName");
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(instruction, "instruction");
        }

        InstructionHit(String className,
                       String memberName,
                       String descriptor,
                       int instructionIndex,
                       String instruction) {
            this(
                    className,
                    ClassToolSupport.Scope.JVM.value(),
                    null,
                    memberName,
                    descriptor,
                    instructionIndex,
                    null,
                    instruction
            );
        }
    }
}
