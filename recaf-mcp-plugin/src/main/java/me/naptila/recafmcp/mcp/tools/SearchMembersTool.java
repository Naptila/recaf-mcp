package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
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
import software.coley.recaf.services.search.match.StringPredicate;
import software.coley.recaf.services.search.match.StringPredicateProvider;
import software.coley.recaf.services.search.query.DeclarationQuery;
import software.coley.recaf.services.search.query.Query;
import software.coley.recaf.services.search.query.ReferenceQuery;
import software.coley.recaf.services.search.result.MemberDeclaration;
import software.coley.recaf.services.search.result.MemberDeclarationResult;
import software.coley.recaf.services.search.result.MemberReference;
import software.coley.recaf.services.search.result.MemberReferenceResult;
import software.coley.recaf.services.search.result.Result;
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
 * Searches Recaf member declarations and references in primary JVM and Android classes.
 */
public final class SearchMembersTool implements McpTool {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_SELECTOR_LENGTH = SearchToolSupport.MAX_QUERY_LENGTH;
    private static final int MAX_FILTER_LENGTH = 500;
    private static final int MAX_COLLECTED_RESULTS = 20_000;
    private static final Set<String> ARGUMENTS = Set.of(
            "owner", "name", "descriptor", "regex", "kind", "results",
            "filter", "scope", "dex_name", "offset", "limit"
    );
    private static final Set<String> MEMBER_KINDS = Set.of("any", "field", "method");
    private static final Set<String> RESULT_SCOPES = Set.of(
            "both", "references", "declarations"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();
    private static final Comparator<MemberHit> HIT_ORDER = Comparator
            .comparing(MemberHit::resultType)
            .thenComparing(MemberHit::memberKind)
            .thenComparing(MemberHit::owner)
            .thenComparing(MemberHit::name)
            .thenComparing(MemberHit::descriptor)
            .thenComparing(MemberHit::className)
            .thenComparing(MemberHit::classScope)
            .thenComparing(MemberHit::dexName, Comparator.nullsFirst(String::compareTo))
            .thenComparing(MemberHit::containingMemberKind,
                    Comparator.nullsFirst(String::compareTo))
            .thenComparing(MemberHit::containingMemberName,
                    Comparator.nullsFirst(String::compareTo))
            .thenComparing(MemberHit::containingMemberDescriptor,
                    Comparator.nullsFirst(String::compareTo))
            .thenComparing(MemberHit::instructionIndex,
                    Comparator.nullsFirst(Integer::compareTo));

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;
    private final SearchOperation searchOperation;

    public SearchMembersTool(WorkspaceManager workspaceManager,
                             SearchService searchService,
                             StringPredicateProvider predicateProvider) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                (workspace, request) -> runSearch(
                        workspace,
                        searchService,
                        predicateProvider,
                        request
                )
        );
    }

    SearchMembersTool(BooleanSupplier hasWorkspace,
                      Supplier<Workspace> workspaceSupplier,
                      SearchService searchService,
                      StringPredicateProvider predicateProvider) {
        this(
                hasWorkspace,
                workspaceSupplier,
                (workspace, request) -> runSearch(
                        workspace,
                        searchService,
                        predicateProvider,
                        request
                )
        );
    }

    SearchMembersTool(BooleanSupplier hasWorkspace,
                      Supplier<Workspace> workspaceSupplier,
                      SearchOperation searchOperation) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.searchOperation = Objects.requireNonNull(searchOperation, "searchOperation");
    }

    @Override
    public String name() {
        return "search_members";
    }

    @Override
    public String description() {
        return "Search JVM/Android member declarations and references.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        SearchRequest request = parseRequest(arguments);
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", request);
        }

        Workspace workspace = workspaceSupplier.get();
        int classesSelected = (int) ClassToolSupport.entries(
                        workspace.getPrimaryResource(),
                        request.selection()
                )
                .stream()
                .filter(entry -> request.filter() == null
                        || entry.className().contains(request.filter()))
                .count();
        SearchOutcome outcome;
        try {
            outcome = searchOperation.run(workspace, request);
        } catch (Exception ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error("member search failed: " + exceptionMessage(ex), request);
        }

        List<MemberHit> hits = new ArrayList<>(outcome.hits());
        Predicate<String> ownerMatches = SearchToolSupport.matcher(
                request.owner(),
                request.regex()
        );
        Predicate<String> nameMatches = SearchToolSupport.matcher(
                request.name(),
                request.regex()
        );
        Predicate<String> descriptorMatches = SearchToolSupport.matcher(
                request.descriptor(),
                request.regex()
        );
        hits.removeIf(hit -> !matchesRequest(
                hit,
                request,
                ownerMatches,
                nameMatches,
                descriptorMatches
        ));
        hits.sort(HIT_ORDER);
        int start = Math.min(request.offset(), hits.size());
        int end = Math.min(start + request.limit(), hits.size());
        JsonArray page = new JsonArray();
        hits.subList(start, end).forEach(hit -> page.add(toJson(hit)));

        JsonObject structured = requestMetadata(request);
        structured.add("hits", page);
        structured.addProperty(
                "scope",
                ClassToolSupport.outputScope(request.selection().scope(), false)
        );
        structured.addProperty("classes_selected", classesSelected);
        structured.addProperty("collection_truncated", outcome.collectionTruncated());
        addPageMetadata(structured, request, hits.size(), end - start);
        return McpTool.successResult(
                "found " + hits.size() + " matching member results",
                structured
        );
    }

    static SearchOutcome runSearch(Workspace workspace,
                                   SearchService searchService,
                                   StringPredicateProvider provider,
                                   SearchRequest request) {
        StringPredicate owner = SearchToolSupport.predicate(
                provider,
                request.owner(),
                request.regex()
        );
        StringPredicate name = SearchToolSupport.predicate(
                provider,
                request.name(),
                request.regex()
        );
        StringPredicate descriptor = SearchToolSupport.predicate(
                provider,
                request.descriptor(),
                request.regex()
        );
        List<Query> queries = new ArrayList<>(2);
        if (!request.results().equals("references")) {
            queries.add(new DeclarationQuery(owner, name, descriptor));
        }
        if (!request.results().equals("declarations")) {
            queries.add(new ReferenceQuery(owner, name, descriptor));
        }

        WorkspaceResource primary = workspace.getPrimaryResource();
        Set<ClassInfo> selectedClasses = Collections.newSetFromMap(new IdentityHashMap<>());
        ClassToolSupport.entries(primary, request.selection()).stream()
                .filter(entry -> request.filter() == null
                        || entry.className().contains(request.filter()))
                .forEach(entry -> selectedClasses.add(entry.classInfo()));
        AtomicInteger accepted = new AtomicInteger();
        SearchFeedback feedback = new SearchFeedback() {
            @Override
            public boolean hasRequestedCancellation() {
                return ToolCancellation.requested()
                        || accepted.get() >= MAX_COLLECTED_RESULTS;
            }

            @Override
            public boolean doVisitClass(ClassInfo classInfo) {
                return selectedClasses.contains(classInfo);
            }

            @Override
            public boolean doAcceptResult(Result<?> result) {
                if (!matchesKind(result, request.kind())) {
                    return false;
                }
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

        List<MemberHit> hits = new ArrayList<>();
        for (Result<?> result : searchService.search(workspace, queries, feedback)) {
            ToolCancellation.check();
            MemberIdentity identity = identity(result);
            if (identity == null) {
                continue;
            }
            PathNode<?> path = result.getPath();
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
            ClassMember containingMember = memberPath == null ? null : memberPath.getValue();
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
            hits.add(new MemberHit(
                    identity.resultType(),
                    identity.memberKind(),
                    identity.owner(),
                    identity.name(),
                    identity.descriptor(),
                    identity.access(),
                    classInfo.getName(),
                    classEntry.scope().value(),
                    classEntry.dexName(),
                    containingMember == null
                            ? null
                            : containingMember.isField() ? "field" : "method",
                    containingMember == null ? null : containingMember.getName(),
                    containingMember == null ? null : containingMember.getDescriptor(),
                    instructionIndex
            ));
        }
        return new SearchOutcome(
                hits,
                accepted.get() >= MAX_COLLECTED_RESULTS
        );
    }

    private static SearchRequest parseRequest(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(
                arguments,
                true
        );
        String owner = boundedOptionalString(arguments, "owner", MAX_SELECTOR_LENGTH);
        String name = boundedOptionalString(arguments, "name", MAX_SELECTOR_LENGTH);
        String descriptor = boundedOptionalString(
                arguments,
                "descriptor",
                MAX_SELECTOR_LENGTH
        );
        if (owner == null && name == null && descriptor == null) {
            throw new IllegalArgumentException(
                    "at least one of owner, name, or descriptor is required"
            );
        }
        boolean regex = ToolArguments.bool(arguments, "regex", false);
        String kind = enumArgument(arguments, "kind", "any", MEMBER_KINDS);
        String results = enumArgument(arguments, "results", "both", RESULT_SCOPES);
        String filter = boundedOptionalString(arguments, "filter", MAX_FILTER_LENGTH);
        if (regex) {
            SearchToolSupport.validateRegex(owner, "owner");
            SearchToolSupport.validateRegex(name, "name");
            SearchToolSupport.validateRegex(descriptor, "descriptor");
        }
        return new SearchRequest(
                owner,
                name,
                descriptor,
                regex,
                kind,
                results,
                filter,
                selection,
                ToolArguments.integer(arguments, "offset", 0, 0, Integer.MAX_VALUE),
                ToolArguments.integer(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT)
        );
    }

    private static String boundedOptionalString(JsonObject arguments,
                                                String name,
                                                int maximumLength) {
        String value = ToolArguments.optionalString(arguments, name);
        if (value != null && value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return value;
    }

    private static String enumArgument(JsonObject arguments,
                                       String name,
                                       String defaultValue,
                                       Set<String> allowed) {
        String value = ToolArguments.optionalString(arguments, name);
        if (value == null) {
            return defaultValue;
        }
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(name + " has an unsupported value");
        }
        return value;
    }

    private static boolean matchesKind(Result<?> result, String kind) {
        if (result instanceof MemberDeclarationResult declarationResult) {
            MemberDeclaration declaration = declarationResult.getValue();
            return kind.equals("any")
                    || kind.equals("field") && declaration.isFieldDeclaration()
                    || kind.equals("method") && declaration.isMethodDeclaration();
        }
        if (result instanceof MemberReferenceResult referenceResult) {
            MemberReference reference = referenceResult.getValue();
            return kind.equals("any")
                    || kind.equals("field") && reference.isFieldReference()
                    || kind.equals("method") && reference.isMethodReference();
        }
        return false;
    }

    private static boolean matchesRequest(MemberHit hit,
                                          SearchRequest request,
                                          Predicate<String> ownerMatches,
                                          Predicate<String> nameMatches,
                                          Predicate<String> descriptorMatches) {
        boolean kindMatches = request.kind().equals("any")
                || request.kind().equals(hit.memberKind());
        boolean resultMatches = request.results().equals("both")
                || request.results().equals(hit.resultType() + "s");
        boolean filterMatches = request.filter() == null
                || hit.className().contains(request.filter());
        return kindMatches
                && resultMatches
                && filterMatches
                && ownerMatches.test(hit.owner())
                && nameMatches.test(hit.name())
                && descriptorMatches.test(hit.descriptor());
    }

    private static MemberIdentity identity(Result<?> result) {
        if (result instanceof MemberDeclarationResult declarationResult) {
            MemberDeclaration declaration = declarationResult.getValue();
            String kind = declaration.isFieldDeclaration() ? "field"
                    : declaration.isMethodDeclaration() ? "method" : null;
            if (kind == null) {
                return null;
            }
            return new MemberIdentity(
                    "declaration",
                    kind,
                    declaration.owner(),
                    declaration.name(),
                    declaration.desc(),
                    declaration.access()
            );
        }
        if (result instanceof MemberReferenceResult referenceResult) {
            MemberReference reference = referenceResult.getValue();
            String kind = reference.isFieldReference() ? "field"
                    : reference.isMethodReference() ? "method" : null;
            if (kind == null) {
                return null;
            }
            return new MemberIdentity(
                    "reference",
                    kind,
                    reference.owner(),
                    reference.name(),
                    reference.desc(),
                    null
            );
        }
        return null;
    }

    private static JsonObject toJson(MemberHit hit) {
        JsonObject json = new JsonObject();
        json.addProperty("result_type", hit.resultType());
        json.addProperty("member_kind", hit.memberKind());
        json.addProperty("owner", hit.owner());
        json.addProperty("name", hit.name());
        json.addProperty("descriptor", hit.descriptor());
        if (hit.access() == null) {
            json.add("access", JsonNull.INSTANCE);
        } else {
            json.addProperty("access", hit.access());
        }
        json.addProperty("class_name", hit.className());
        json.addProperty("class_scope", hit.classScope());
        addNullable(json, "dex_name", hit.dexName());
        addNullable(json, "containing_member_kind", hit.containingMemberKind());
        addNullable(json, "containing_member_name", hit.containingMemberName());
        addNullable(
                json,
                "containing_member_descriptor",
                hit.containingMemberDescriptor()
        );
        if (hit.instructionIndex() == null) {
            json.add("instruction_index", JsonNull.INSTANCE);
        } else {
            json.addProperty("instruction_index", hit.instructionIndex());
        }
        return json;
    }

    private static JsonObject error(String message, SearchRequest request) {
        JsonObject structured = requestMetadata(request);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject requestMetadata(SearchRequest request) {
        JsonObject json = new JsonObject();
        addNullable(json, "owner", request.owner());
        addNullable(json, "name", request.name());
        addNullable(json, "descriptor", request.descriptor());
        json.addProperty("regex", request.regex());
        json.addProperty("kind", request.kind());
        json.addProperty("results", request.results());
        addNullable(json, "filter", request.filter());
        json.addProperty("requested_scope", request.selection().scope().value());
        addNullable(json, "dex_name", request.selection().dexName());
        return json;
    }

    private static void addPageMetadata(JsonObject target,
                                        SearchRequest request,
                                        int total,
                                        int returned) {
        target.addProperty("offset", request.offset());
        target.addProperty("limit", request.limit());
        target.addProperty("total", total);
        target.addProperty("returned", returned);
        long next = (long) request.offset() + returned;
        if (next < total) {
            target.addProperty("next_offset", next);
        } else {
            target.add("next_offset", JsonNull.INSTANCE);
        }
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

    private static String exceptionMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private static JsonObject createInputSchema() {
        JsonObject properties = new JsonObject();
        properties.add("owner", selectorProperty(
                "Declaring-class selector."
        ));
        properties.add("name", selectorProperty("Member-name selector."));
        properties.add("descriptor", selectorProperty(
                "JVM descriptor selector."
        ));
        JsonObject regex = new JsonObject();
        regex.addProperty("type", "boolean");
        regex.addProperty("default", false);
        regex.addProperty(
                "description",
                "Apply a partial Java regex to supplied selectors."
        );
        properties.add("regex", regex);
        properties.add("kind", enumProperty(
                "Member kind.",
                MEMBER_KINDS,
                "any"
        ));
        properties.add("results", enumProperty(
                "Result kind.",
                RESULT_SCOPES,
                "both"
        ));
        JsonObject filter = selectorProperty(
                "Class-name substring limiting scanned classes."
        );
        filter.addProperty("maxLength", MAX_FILTER_LENGTH);
        properties.add("filter", filter);
        ClassToolSupport.addScopeProperties(properties, true);
        properties.add("offset", integerProperty(0, Integer.MAX_VALUE, 0));
        properties.add("limit", integerProperty(1, MAX_LIMIT, DEFAULT_LIMIT));

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty(
                "description",
                "Requires owner, name, or descriptor."
        );
        schema.add("properties", properties);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject selectorProperty(String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("minLength", 1);
        property.addProperty("maxLength", MAX_SELECTOR_LENGTH);
        property.addProperty("description", description);
        return property;
    }

    private static JsonObject enumProperty(String description,
                                           Set<String> values,
                                           String defaultValue) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        JsonArray choices = new JsonArray();
        values.stream().sorted().forEach(choices::add);
        property.add("enum", choices);
        property.addProperty("default", defaultValue);
        property.addProperty("description", description);
        return property;
    }

    private static JsonObject integerProperty(int minimum, int maximum, int defaultValue) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("minimum", minimum);
        property.addProperty("maximum", maximum);
        property.addProperty("default", defaultValue);
        return property;
    }

    record SearchRequest(String owner,
                         String name,
                         String descriptor,
                         boolean regex,
                         String kind,
                         String results,
                         String filter,
                         ClassToolSupport.Selection selection,
                         int offset,
                         int limit) {
    }

    record MemberHit(String resultType,
                     String memberKind,
                     String owner,
                     String name,
                     String descriptor,
                     Integer access,
                     String className,
                     String classScope,
                     String dexName,
                     String containingMemberKind,
                     String containingMemberName,
                     String containingMemberDescriptor,
                     Integer instructionIndex) {
        MemberHit {
            Objects.requireNonNull(resultType, "resultType");
            Objects.requireNonNull(memberKind, "memberKind");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(classScope, "classScope");
        }

        MemberHit(String resultType,
                  String memberKind,
                  String owner,
                  String name,
                  String descriptor,
                  Integer access,
                  String className,
                  String containingMemberKind,
                  String containingMemberName,
                  String containingMemberDescriptor,
                  Integer instructionIndex) {
            this(
                    resultType,
                    memberKind,
                    owner,
                    name,
                    descriptor,
                    access,
                    className,
                    ClassToolSupport.Scope.JVM.value(),
                    null,
                    containingMemberKind,
                    containingMemberName,
                    containingMemberDescriptor,
                    instructionIndex
            );
        }
    }

    record SearchOutcome(List<MemberHit> hits, boolean collectionTruncated) {
        SearchOutcome {
            hits = List.copyOf(hits);
        }
    }

    private record MemberIdentity(String resultType,
                                  String memberKind,
                                  String owner,
                                  String name,
                                  String descriptor,
                                  Integer access) {
    }

    @FunctionalInterface
    interface SearchOperation {
        SearchOutcome run(Workspace workspace, SearchRequest request);
    }
}
