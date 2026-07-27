package me.naptila.recafmcp.mcp.tools;

import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.MappingApplier;
import software.coley.recaf.services.mapping.MappingListeners;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.services.mapping.Mappings;
import software.coley.recaf.services.mapping.MappingsAdapter;
import software.coley.recaf.services.mapping.UniqueKeyMappings;
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.mapping.data.FieldMapping;
import software.coley.recaf.services.mapping.data.MethodMapping;
import software.coley.recaf.services.mapping.data.VariableMapping;
import software.coley.recaf.services.search.SearchFeedback;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.search.match.StringPredicate;
import software.coley.recaf.services.search.query.DeclarationQuery;
import software.coley.recaf.services.search.query.Query;
import software.coley.recaf.services.search.query.ReferenceQuery;
import software.coley.recaf.services.search.result.Result;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Plans mapping work with Recaf search and remaps only declarations and referrers.
 *
 * <p>Recaf's primary-resource applier remaps every DEX class concurrently, which can
 * exhaust a headless Worker's heap on large APKs even for a one-symbol mapping. This
 * planner keeps Recaf's own search, hierarchy enrichment, remappers, listeners, and
 * aggregate mapping manager while bounding prepared class copies to actual candidates.</p>
 */
final class TargetedMappingApplier {
    private final SearchService searchService;
    private final InheritanceGraphService inheritanceGraphService;
    private final MappingListeners mappingListeners;
    private final AggregateMappingManager aggregateMappingManager;

    TargetedMappingApplier(SearchService searchService,
                           InheritanceGraphService inheritanceGraphService,
                           MappingListeners mappingListeners,
                           AggregateMappingManager aggregateMappingManager) {
        this.searchService = Objects.requireNonNull(searchService, "searchService");
        this.inheritanceGraphService = Objects.requireNonNull(
                inheritanceGraphService,
                "inheritanceGraphService"
        );
        this.mappingListeners = Objects.requireNonNull(
                mappingListeners,
                "mappingListeners"
        );
        this.aggregateMappingManager = Objects.requireNonNull(
                aggregateMappingManager,
                "aggregateMappingManager"
        );
    }

    Prepared prepare(Workspace workspace, Mappings requestedMappings) {
        InheritanceGraph graph = inheritanceGraphService
                .getOrCreateInheritanceGraph(workspace);
        Mappings mappings = enrich(workspace, requestedMappings, graph);
        WorkspaceResource resource = workspace.getPrimaryResource();
        Set<ClassInfo> candidates = candidates(workspace, resource, mappings, graph);
        MappingApplier applier = new MappingApplier(
                workspace,
                graph,
                mappingListeners,
                null
        );
        List<MappingResults> results = new ArrayList<>();

        List<JvmClassInfo> jvmCandidates = candidates.stream()
                .filter(ClassInfo::isJvmClass)
                .map(ClassInfo::asJvmClass)
                .filter(classInfo -> resource.getJvmClassBundle()
                        .get(classInfo.getName()) == classInfo)
                .toList();
        if (!jvmCandidates.isEmpty()) {
            results.add(applier.applyToClasses(
                    mappings,
                    resource,
                    resource.getJvmClassBundle(),
                    jvmCandidates
            ));
        }

        for (AndroidClassBundle bundle : resource.getAndroidClassBundles().values()) {
            List<AndroidClassInfo> androidCandidates = bundle.values().stream()
                    .filter(candidates::contains)
                    .toList();
            if (!androidCandidates.isEmpty()) {
                results.add(applier.applyToClasses(
                        mappings,
                        resource,
                        bundle,
                        androidCandidates
                ));
            }
        }

        List<MappingMutationSupport.Change> changes = results.stream()
                .flatMap(result -> MappingMutationSupport.summarize(workspace, result)
                        .changes().stream())
                .sorted(java.util.Comparator
                        .comparing(MappingMutationSupport.Change::oldName)
                        .thenComparing(change -> change.scope().value())
                        .thenComparing(
                                MappingMutationSupport.Change::dexName,
                                java.util.Comparator.nullsFirst(String::compareTo)
                        ))
                .toList();
        List<ClassInfo> postClasses = results.stream()
                .flatMap(result -> result.getPostMappingPaths().values().stream())
                .map(ClassPathNode::getValue)
                .toList();
        return new Prepared(
                new MappingMutationSupport.Summary(changes),
                postClasses,
                () -> {
                    for (MappingResults result : results) {
                        ToolCancellation.check();
                        result.apply();
                    }
                    aggregateMappingManager.updateAggregateMappings(mappings);
                }
        );
    }

    private Set<ClassInfo> candidates(Workspace workspace,
                                      WorkspaceResource resource,
                                      Mappings mappings,
                                      InheritanceGraph graph) {
        List<ClassToolSupport.Entry> entries = ClassToolSupport.entries(
                resource,
                new ClassToolSupport.Selection(ClassToolSupport.Scope.ALL, null)
        );
        Set<ClassInfo> eligible = Collections.synchronizedSet(
                Collections.newSetFromMap(new IdentityHashMap<>())
        );
        entries.forEach(entry -> eligible.add(entry.classInfo()));
        Set<ClassInfo> candidates = Collections.synchronizedSet(
                Collections.newSetFromMap(new IdentityHashMap<>())
        );
        IntermediateMappings intermediate = mappings.exportIntermediate();
        boolean uniqueKeys = mappings instanceof UniqueKeyMappings;
        List<Query> queries = new ArrayList<>();

        Set<String> mappedClassNames = new LinkedHashSet<>(
                intermediate.getClasses().keySet()
        );
        for (ClassToolSupport.Entry entry : entries) {
            for (String mappedClassName : intermediate.getClasses().keySet()) {
                if (entry.className().equals(mappedClassName)
                        || entry.className().startsWith(mappedClassName + '$')) {
                    mappedClassNames.add(entry.className());
                    candidates.add(entry.classInfo());
                    break;
                }
            }
        }
        mappedClassNames.forEach(className ->
                queries.add(new ReferenceQuery(exact(className))));

        for (List<FieldMapping> fields : intermediate.getFields().values()) {
            for (FieldMapping field : fields) {
                addOwner(entries, candidates, field.getOwnerName());
                if (uniqueKeys) {
                    queries.add(new DeclarationQuery(
                            null,
                            exact(field.getOldName()),
                            null
                    ));
                    queries.add(new ReferenceQuery(
                            null,
                            exact(field.getOldName()),
                            null
                    ));
                } else {
                    queries.add(new ReferenceQuery(
                            ownerFamily(graph, field.getOwnerName()),
                            exact(field.getOldName()),
                            nullableExact(field.getDesc())
                    ));
                }
            }
        }
        for (List<MethodMapping> methods : intermediate.getMethods().values()) {
            for (MethodMapping method : methods) {
                addOwner(entries, candidates, method.getOwnerName());
                if (uniqueKeys) {
                    queries.add(new DeclarationQuery(
                            null,
                            exact(method.getOldName()),
                            null
                    ));
                    queries.add(new ReferenceQuery(
                            null,
                            exact(method.getOldName()),
                            null
                    ));
                } else {
                    queries.add(new ReferenceQuery(
                            ownerFamily(graph, method.getOwnerName()),
                            exact(method.getOldName()),
                            exact(method.getDesc())
                    ));
                }
            }
        }
        for (List<VariableMapping> variables : intermediate.getVariables().values()) {
            for (VariableMapping variable : variables) {
                addOwner(entries, candidates, variable.getOwnerName());
            }
        }
        if (uniqueKeys && !intermediate.getVariables().isEmpty()) {
            entries.stream()
                    .filter(entry -> entry.scope() == ClassToolSupport.Scope.JVM)
                    .forEach(entry -> candidates.add(entry.classInfo()));
        }

        if (!queries.isEmpty()) {
            SearchFeedback feedback = new SearchFeedback() {
                @Override
                public boolean hasRequestedCancellation() {
                    return ToolCancellation.requested();
                }

                @Override
                public boolean doVisitClass(ClassInfo classInfo) {
                    return eligible.contains(classInfo);
                }

                @Override
                public boolean doAcceptResult(Result<?> result) {
                    ClassPathNode classPath = findPath(
                            result.getPath(),
                            ClassPathNode.class
                    );
                    if (classPath != null && eligible.contains(classPath.getValue())) {
                        candidates.add(classPath.getValue());
                    }
                    // The candidate identity is all we need. Avoid retaining every
                    // matching instruction/result in Recaf's result collection.
                    return false;
                }
            };
            searchService.search(
                    workspace,
                    queries,
                    feedback
            );
            ToolCancellation.check();
        }
        return candidates;
    }

    private static Mappings enrich(Workspace workspace,
                                   Mappings mappings,
                                   InheritanceGraph graph) {
        if (mappings instanceof IntermediateMappings intermediate) {
            MappingsAdapter adapter = new MappingsAdapter(
                    mappings.doesSupportFieldTypeDifferentiation(),
                    mappings.doesSupportVariableTypeDifferentiation()
            );
            adapter.importIntermediate(intermediate);
            adapter.infillMethodFamilies(intermediate, graph);
            adapter.enableClassLookup(workspace);
            adapter.enableHierarchyLookup(graph);
            return adapter;
        }
        if (mappings instanceof MappingsAdapter adapter) {
            adapter.enableClassLookup(workspace);
            adapter.enableHierarchyLookup(graph);
        }
        return mappings;
    }

    private static void addOwner(List<ClassToolSupport.Entry> entries,
                                 Set<ClassInfo> candidates,
                                 String owner) {
        entries.stream()
                .filter(entry -> entry.className().equals(owner))
                .forEach(entry -> candidates.add(entry.classInfo()));
    }

    private static StringPredicate exact(String expected) {
        return new StringPredicate("recaf-mcp-exact", expected::equals);
    }

    private static StringPredicate nullableExact(String expected) {
        return expected == null ? null : exact(expected);
    }

    private static StringPredicate ownerFamily(InheritanceGraph graph,
                                               String owner) {
        Set<String> familyNames = new LinkedHashSet<>();
        familyNames.add(owner);
        graph.getVertexFamily(owner, false).stream()
                .map(vertex -> vertex.getValue().getName())
                .forEach(familyNames::add);
        return new StringPredicate(
                "recaf-mcp-owner-family",
                familyNames::contains
        );
    }

    private static <T extends PathNode<?>> T findPath(PathNode<?> start,
                                                       Class<T> type) {
        PathNode<?> current = start;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getParent();
        }
        return null;
    }

    record Prepared(MappingMutationSupport.Summary summary,
                    List<ClassInfo> postClasses,
                    Runnable application) {
        Prepared {
            Objects.requireNonNull(summary, "summary");
            postClasses = List.copyOf(postClasses);
            Objects.requireNonNull(application, "application");
        }

        void apply() {
            application.run();
        }
    }
}
