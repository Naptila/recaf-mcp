package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import me.naptila.recafmcp.mcp.util.ToolErrors;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.MethodMember;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Searches Recaf member declarations and references in primary or embedded JVM and Android classes.
 */
public final class SearchMembersTool implements McpTool {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_SELECTOR_LENGTH = SearchToolSupport.MAX_QUERY_LENGTH;
    private static final int MAX_FILTER_LENGTH = 500;
    private static final int MAX_COLLECTED_RESULTS = 20_000;
    private static final Set<String> ARGUMENTS = Set.of(
            "owner",
            "name",
            "descriptor",
            "match",
            "descriptor_match",
            "regex",
            "kind",
            "results",
            "filter",
            "scope",
            "dex_name",
            ClassToolSupport.RESOURCE_ARGUMENT,
            ClassToolSupport.INCLUDE_EMBEDDED_ARGUMENT,
            "offset",
            "limit"
    );
    private static final Set<String> MATCH_MODES = Set.of("exact", "contains", "regex");
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
            .thenComparing(MemberHit::resourcePath, Comparator.nullsFirst(String::compareTo))
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
    private final SelectedEntriesSearchOperation searchOperation;
    private final boolean usesDefaultRecafSearch;

    public SearchMembersTool(WorkspaceManager workspaceManager,
                             SearchService searchService,
                             StringPredicateProvider predicateProvider) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                (workspace, request, selectedEntries) -> runSearch(
                        workspace,
                        searchService,
                        predicateProvider,
                        request,
                        selectedEntries
                ),
                true
        );
    }

    SearchMembersTool(BooleanSupplier hasWorkspace,
                      Supplier<Workspace> workspaceSupplier,
                      SearchService searchService,
                      StringPredicateProvider predicateProvider) {
        this(
                hasWorkspace,
                workspaceSupplier,
                (workspace, request, selectedEntries) -> runSearch(
                        workspace,
                        searchService,
                        predicateProvider,
                        request,
                        selectedEntries
                ),
                true
        );
    }

    SearchMembersTool(BooleanSupplier hasWorkspace,
                      Supplier<Workspace> workspaceSupplier,
                      SearchOperation searchOperation) {
        this(
                hasWorkspace,
                workspaceSupplier,
                (workspace, request, selectedEntries) -> searchOperation.run(workspace, request),
                false
        );
    }

    SearchMembersTool(BooleanSupplier hasWorkspace,
                      Supplier<Workspace> workspaceSupplier,
                      SelectedEntriesSearchOperation searchOperation) {
        this(hasWorkspace, workspaceSupplier, searchOperation, false);
    }

    private SearchMembersTool(BooleanSupplier hasWorkspace,
                              Supplier<Workspace> workspaceSupplier,
                              SelectedEntriesSearchOperation searchOperation,
                              boolean usesDefaultRecafSearch) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.searchOperation = Objects.requireNonNull(searchOperation, "searchOperation");
        this.usesDefaultRecafSearch = usesDefaultRecafSearch;
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
        WorkspaceResource primary = workspace.getPrimaryResource();
        JsonObject selectionError = ClassToolSupport.validateSelection(
                primary,
                request.selection()
        );
        if (selectionError != null) {
            return selectionError;
        }

        Predicate<String> ownerMatches = SearchToolSupport.matcher(
                request.owner(),
                request.matchMode()
        );
        Predicate<String> nameMatches = SearchToolSupport.matcher(
                request.name(),
                request.matchMode()
        );
        Predicate<String> descriptorMatches = SearchToolSupport.matcher(
                request.descriptor(),
                request.descriptorMatchMode()
        );

        Predicate<String> filterMatches = ClassToolSupport.classFilterMatcher(
                request.filter(),
                false
        );

        int classesSelected;
        SearchOutcome outcome;
        if (usesDefaultRecafSearch && request.results().equals("declarations")) {
            List<ClassToolSupport.Entry> candidateEntries = selectDeclarationEntries(
                    primary,
                    request,
                    filterMatches
            );
            classesSelected = candidateEntries.size();
            outcome = scanDirectDeclarations(
                    candidateEntries,
                    request,
                    ownerMatches,
                    nameMatches,
                    descriptorMatches
            );
        } else {
            List<ClassToolSupport.Entry> selectedEntries = ClassToolSupport.entries(
                            primary,
                            request.selection()
                    )
                    .stream()
                    .filter(entry -> filterMatches.test(entry.className()))
                    .toList();
            classesSelected = selectedEntries.size();
            try {
                outcome = searchOperation.run(workspace, request, selectedEntries);
            } catch (Exception ex) {
                ToolCancellation.propagateIfRequested(ex);
                return error("member search failed: " + ToolErrors.message(ex), request);
            }
        }

        List<MemberHit> hits = new ArrayList<>(outcome.hits());
        hits.removeIf(hit -> !matchesRequest(
                hit,
                request,
                filterMatches,
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
                ClassToolSupport.outputScope(
                        request.selection().scope(),
                        false,
                        request.selection()
                )
        );
        ClassToolSupport.addScopeDiagnostics(structured, primary, request.selection());
        structured.addProperty("classes_selected", classesSelected);
        structured.addProperty("collection_truncated", outcome.collectionTruncated());
        addPageMetadata(structured, request, hits.size(), end - start);
        return McpTool.successResult(
                "found " + hits.size() + " matching member results",
                structured
        );
    }

    private static List<ClassToolSupport.Entry> selectDeclarationEntries(
            WorkspaceResource primary,
            SearchRequest request,
            Predicate<String> filterMatches) {
        if (request.matchMode() == SearchToolSupport.MatchMode.EXACT
                && request.owner() != null) {
            return ClassToolSupport.exact(
                            primary,
                            request.selection(),
                            request.owner()
                    )
                    .stream()
                    .filter(entry -> filterMatches.test(entry.className()))
                    .toList();
        }
        Predicate<String> ownerMatches = SearchToolSupport.matcher(
                request.owner(),
                request.matchMode()
        );
        return ClassToolSupport.entries(primary, request.selection())
                .stream()
                .filter(entry -> filterMatches.test(entry.className()))
                .filter(entry -> ownerMatches.test(entry.className()))
                .toList();
    }

    private static SearchOutcome scanDirectDeclarations(
            List<ClassToolSupport.Entry> entries,
            SearchRequest request,
            Predicate<String> ownerMatches,
            Predicate<String> nameMatches,
            Predicate<String> descriptorMatches) {
        List<MemberHit> hits = new ArrayList<>();
        boolean truncated = false;
        scan:
        for (ClassToolSupport.Entry entry : entries) {
            ToolCancellation.check();
            ClassInfo classInfo = entry.classInfo();
            if (!ownerMatches.test(classInfo.getName())) {
                continue;
            }
            if (!request.kind().equals("method")) {
                for (FieldMember field : classInfo.getFields()) {
                    if (!nameMatches.test(field.getName())
                            || !descriptorMatches.test(field.getDescriptor())) {
                        continue;
                    }
                    if (hits.size() >= MAX_COLLECTED_RESULTS) {
                        truncated = true;
                        break scan;
                    }
                    hits.add(new MemberHit(
                            "declaration",
                            "field",
                            classInfo.getName(),
                            field.getName(),
                            field.getDescriptor(),
                            field.getAccess(),
                            classInfo.getName(),
                            entry.scope().value(),
                            entry.dexName(),
                            entry.resourcePath(),
                            null,
                            null,
                            null,
                            null
                    ));
                }
            }
            if (!request.kind().equals("field")) {
                for (MethodMember method : classInfo.getMethods()) {
                    if (!nameMatches.test(method.getName())
                            || !descriptorMatches.test(method.getDescriptor())) {
                        continue;
                    }
                    if (hits.size() >= MAX_COLLECTED_RESULTS) {
                        truncated = true;
                        break scan;
                    }
                    hits.add(new MemberHit(
                            "declaration",
                            "method",
                            classInfo.getName(),
                            method.getName(),
                            method.getDescriptor(),
                            method.getAccess(),
                            classInfo.getName(),
                            entry.scope().value(),
                            entry.dexName(),
                            entry.resourcePath(),
                            null,
                            null,
                            null,
                            null
                    ));
                }
            }
        }
        return new SearchOutcome(hits, truncated);
    }

    static SearchOutcome runSearch(Workspace workspace,
                                   SearchService searchService,
                                   StringPredicateProvider provider,
                                   SearchRequest request) {
        Predicate<String> filterMatches = ClassToolSupport.classFilterMatcher(
                request.filter(),
                false
        );
        List<ClassToolSupport.Entry> selectedEntries = ClassToolSupport.entries(
                        workspace.getPrimaryResource(),
                        request.selection()
                )
                .stream()
                .filter(entry -> filterMatches.test(entry.className()))
                .toList();
        return runSearch(workspace, searchService, provider, request, selectedEntries);
    }

    private static SearchOutcome runSearch(Workspace workspace,
                                           SearchService searchService,
                                           StringPredicateProvider provider,
                                           SearchRequest request,
                                           List<ClassToolSupport.Entry> selectedEntries) {
        StringPredicate owner = SearchToolSupport.predicate(
                provider,
                request.owner(),
                request.matchMode()
        );
        StringPredicate name = SearchToolSupport.predicate(
                provider,
                request.name(),
                request.matchMode()
        );
        StringPredicate descriptor = SearchToolSupport.predicate(
                provider,
                request.descriptor(),
                request.descriptorMatchMode()
        );
        List<Query> queries = new ArrayList<>(2);
        if (!request.results().equals("references")) {
            queries.add(new DeclarationQuery(owner, name, descriptor));
        }
        if (!request.results().equals("declarations")) {
            queries.add(new ReferenceQuery(owner, name, descriptor));
        }

        WorkspaceResource primary = workspace.getPrimaryResource();
        Set<WorkspaceResource> allowedResources = Collections.newSetFromMap(
                new IdentityHashMap<>()
        );
        ClassToolSupport.targetResources(primary, request.selection())
                .forEach(item -> allowedResources.add(item.resource()));
        Map<ClassInfo, ClassToolSupport.Entry> selectedByClass = new IdentityHashMap<>();
        Set<ClassInfo> selectedClasses = Collections.newSetFromMap(new IdentityHashMap<>());
        selectedEntries.forEach(entry -> {
            selectedClasses.add(entry.classInfo());
            selectedByClass.put(entry.classInfo(), entry);
        });
        if (selectedClasses.isEmpty()) {
            return new SearchOutcome(List.of(), false);
        }
        AtomicInteger accepted = new AtomicInteger();
        SearchFeedback feedback = SearchToolSupport.classOnlyFeedback(
                selectedClasses,
                MAX_COLLECTED_RESULTS,
                accepted,
                result -> matchesKind(result, request.kind())
        );

        List<MemberHit> hits = new ArrayList<>();
        for (Result<?> result : searchService.search(workspace, queries, feedback)) {
            ToolCancellation.check();
            MemberIdentity identity = identity(result);
            if (identity == null) {
                continue;
            }
            PathNode<?> path = result.getPath();
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
            boolean isReference = "reference".equals(identity.resultType());
            ClassMemberPathNode memberPath = isReference
                    ? SearchToolSupport.findPath(path, ClassMemberPathNode.class)
                    : null;
            ClassMember containingMember = memberPath == null ? null : memberPath.getValue();
            JvmInstructionPathNode instructionPath = isReference
                    ? SearchToolSupport.findPath(path, JvmInstructionPathNode.class)
                    : null;
            AndroidInstructionPathNode androidInstructionPath = isReference
                    ? SearchToolSupport.findPath(path, AndroidInstructionPathNode.class)
                    : null;
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
                    classEntry.resourcePath(),
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
                true,
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
        boolean regexFlag = ToolArguments.bool(arguments, "regex", false);
        String matchValue = ToolArguments.optionalString(arguments, "match");
        SearchToolSupport.MatchMode matchMode;
        if (matchValue == null) {
            matchMode = regexFlag
                    ? SearchToolSupport.MatchMode.REGEX
                    : SearchToolSupport.MatchMode.EXACT;
        } else {
            matchMode = SearchToolSupport.MatchMode.from(matchValue);
            if (regexFlag && matchMode != SearchToolSupport.MatchMode.REGEX) {
                throw new IllegalArgumentException(
                        "regex=true cannot be combined with match=" + matchValue
                );
            }
        }
        boolean regex = matchMode == SearchToolSupport.MatchMode.REGEX;
        String descMatchValue = ToolArguments.optionalString(arguments, "descriptor_match");
        SearchToolSupport.MatchMode descriptorMatchMode;
        if (descMatchValue != null) {
            descriptorMatchMode = SearchToolSupport.MatchMode.from(descMatchValue);
        } else if (regex && descriptor != null && !containsDescriptorRegexMetachar(descriptor)) {
            descriptorMatchMode = SearchToolSupport.MatchMode.EXACT;
        } else {
            descriptorMatchMode = matchMode;
        }
        String kind = enumArgument(arguments, "kind", "any", MEMBER_KINDS);
        String results = enumArgument(arguments, "results", "both", RESULT_SCOPES);
        String filter = boundedOptionalString(arguments, "filter", MAX_FILTER_LENGTH);
        if (regex) {
            SearchToolSupport.validateRegex(owner, "owner");
            SearchToolSupport.validateRegex(name, "name");
        } else {
            if (owner != null) {
                owner = ClassToolSupport.normalizeClassNameInput(owner);
            }
            if (name != null && name.contains("\\u")) {
                name = JasmEscapeSupport.unescapeSafeIdentifierUnicode(name);
            }
        }
        if (descriptorMatchMode == SearchToolSupport.MatchMode.REGEX) {
            SearchToolSupport.validateRegex(descriptor, "descriptor");
        } else if (descriptor != null && descriptor.contains("\\u")) {
            descriptor = JasmEscapeSupport.unescapeSafeIdentifierUnicode(descriptor);
        }
        return new SearchRequest(
                owner,
                name,
                descriptor,
                matchMode,
                descriptorMatchMode,
                regex,
                kind,
                results,
                filter,
                selection,
                ToolArguments.integer(arguments, "offset", 0, 0, Integer.MAX_VALUE),
                ToolArguments.integer(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT)
        );
    }

    private static boolean containsDescriptorRegexMetachar(String descriptor) {
        for (int index = 0; index < descriptor.length(); index++) {
            char ch = descriptor.charAt(index);
            if (ch == '^' || ch == '$' && (index == descriptor.length() - 1)
                    || ch == '*' || ch == '+' || ch == '?' || ch == '.'
                    || ch == '|' || ch == '\\' || ch == '{' || ch == '}') {
                return true;
            }
        }
        return false;
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
                                          Predicate<String> filterPredicate,
                                          Predicate<String> ownerMatches,
                                          Predicate<String> nameMatches,
                                          Predicate<String> descriptorMatches) {
        boolean kindMatches = request.kind().equals("any")
                || request.kind().equals(hit.memberKind());
        boolean resultMatches = request.results().equals("both")
                || request.results().equals(hit.resultType() + "s");
        boolean filterMatches = filterPredicate.test(hit.className());
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
            int rawAccess = hit.access();
            boolean isJvm = "jvm".equals(hit.classScope());
            json.addProperty("access", isJvm ? (rawAccess & 0xFFFF) : rawAccess);
            if (isJvm && (rawAccess & org.objectweb.asm.Opcodes.ACC_DEPRECATED) != 0) {
                json.addProperty("deprecated", true);
            }
        }
        json.addProperty("class_name", hit.className());
        json.addProperty("class_scope", hit.classScope());
        addNullable(json, "dex_name", hit.dexName());
        if (hit.resourcePath() != null) {
            json.addProperty("resource", hit.resourcePath());
        }
        boolean isReference = "reference".equals(hit.resultType());
        addNullable(json, "containing_member_kind", isReference ? hit.containingMemberKind() : null);
        addNullable(json, "containing_member_name", isReference ? hit.containingMemberName() : null);
        addNullable(
                json,
                "containing_member_descriptor",
                isReference ? hit.containingMemberDescriptor() : null
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
        json.addProperty("match", request.matchMode().jsonName());
        json.addProperty("regex", request.regex());
        json.addProperty("kind", request.kind());
        json.addProperty("results", request.results());
        addNullable(json, "filter", request.filter());
        json.addProperty("requested_scope", request.selection().scope().value());
        addNullable(json, "dex_name", request.selection().dexName());
        ClassToolSupport.addSelectionMetadata(json, request.selection());
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

    private static JsonObject createInputSchema() {
        JsonObject properties = new JsonObject();
        properties.add("owner", selectorProperty(
                "Declaring-class selector."
        ));
        properties.add("name", selectorProperty("Member-name selector."));
        properties.add("descriptor", selectorProperty(
                "JVM descriptor selector."
        ));
        properties.add("match", enumProperty(
                "Selector matching mode: exact (default), contains (substring), or regex.",
                MATCH_MODES,
                "exact"
        ));
        JsonObject descriptorMatch = new JsonObject();
        descriptorMatch.addProperty("type", "string");
        JsonArray descMatchValues = new JsonArray();
        MATCH_MODES.stream().sorted().forEach(descMatchValues::add);
        descriptorMatch.add("enum", descMatchValues);
        descriptorMatch.addProperty(
                "description",
                "Optional override for descriptor matching (literal JVM descriptors like '()V' "
                        + "default to exact even when match='regex' unless regex metacharacters are used)."
        );
        properties.add("descriptor_match", descriptorMatch);
        JsonObject regex = new JsonObject();
        regex.addProperty("type", "boolean");
        regex.addProperty("default", false);
        regex.addProperty(
                "description",
                "Shorthand for match=\"regex\"; applies a partial Java regex to supplied selectors."
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
        ClassToolSupport.addScopeProperties(properties, true, true, true);
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
                         SearchToolSupport.MatchMode matchMode,
                         SearchToolSupport.MatchMode descriptorMatchMode,
                         boolean regex,
                         String kind,
                         String results,
                         String filter,
                         ClassToolSupport.Selection selection,
                         int offset,
                         int limit) {
        SearchRequest(String owner,
                      String name,
                      String descriptor,
                      SearchToolSupport.MatchMode matchMode,
                      boolean regex,
                      String kind,
                      String results,
                      String filter,
                      ClassToolSupport.Selection selection,
                      int offset,
                      int limit) {
            this(
                    owner,
                    name,
                    descriptor,
                    matchMode,
                    matchMode,
                    regex,
                    kind,
                    results,
                    filter,
                    selection,
                    offset,
                    limit
            );
        }

        SearchRequest(String owner,
                      String name,
                      String descriptor,
                      boolean regex,
                      String kind,
                      String results,
                      String filter,
                      ClassToolSupport.Selection selection,
                      int offset,
                      int limit) {
            this(
                    owner,
                    name,
                    descriptor,
                    regex
                            ? SearchToolSupport.MatchMode.REGEX
                            : SearchToolSupport.MatchMode.EXACT,
                    regex
                            ? SearchToolSupport.MatchMode.REGEX
                            : SearchToolSupport.MatchMode.EXACT,
                    regex,
                    kind,
                    results,
                    filter,
                    selection,
                    offset,
                    limit
            );
        }
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
                     String resourcePath,
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
                  String classScope,
                  String dexName,
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
                    classScope,
                    dexName,
                    null,
                    containingMemberKind,
                    containingMemberName,
                    containingMemberDescriptor,
                    instructionIndex
            );
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

    @FunctionalInterface
    interface SelectedEntriesSearchOperation {
        SearchOutcome run(Workspace workspace,
                          SearchRequest request,
                          List<ClassToolSupport.Entry> selectedEntries);
    }
}
