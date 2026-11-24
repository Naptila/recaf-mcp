package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import me.naptila.recafmcp.mcp.util.ToolErrors;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.services.callgraph.CallGraph;
import software.coley.recaf.services.callgraph.CallGraphService;
import software.coley.recaf.services.callgraph.MethodRef;
import software.coley.recaf.services.callgraph.MethodVertex;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Builds a bounded method call graph rooted at one exact JVM or Android method.
 */
public final class CallGraphTool implements McpTool {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_DEPTH = 5;
    private static final long GRAPH_READY_TIMEOUT_NANOS = 30_000_000_000L;
    private static final int MAX_VISITED_VERTICES = 10_000;
    private static final int MAX_COLLECTED_EDGES = 20_000;
    private static final int MAX_ANDROID_INDEXED_METHODS = 1_000_000;
    private static final long MAX_ANDROID_SCANNED_INSTRUCTIONS = 20_000_000L;
    private static final int MAX_ANDROID_GRAPH_CACHE_ENTRIES = 2;
    private static final Object ANDROID_CACHE_LOCK = new Object();
    private static final Map<AndroidGraphCacheKey, CachedAndroidReverseGraph> ANDROID_REVERSE_CACHE =
            new LinkedHashMap<>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<AndroidGraphCacheKey, CachedAndroidReverseGraph> eldest) {
                    return size() > MAX_ANDROID_GRAPH_CACHE_ENTRIES;
                }
            };
    private static final Set<String> GRAPH_DEX_SCOPES = Set.of("all", "root_dex");
    private static final Set<String> ARGUMENTS = Set.of(
            "owner",
            "name",
            "descriptor",
            "direction",
            "max_depth",
            "stop_at_external",
            "scope",
            "dex_name",
            "graph_dex_scope",
            ClassToolSupport.RESOURCE_ARGUMENT,
            "offset",
            "limit"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();
    private static final Comparator<GraphEdge> EDGE_ORDER = Comparator
            .comparingInt(GraphEdge::depth)
            .thenComparing(edge -> edge.caller().owner())
            .thenComparing(edge -> edge.caller().name())
            .thenComparing(edge -> edge.caller().descriptor())
            .thenComparing(edge -> edge.callee().owner())
            .thenComparing(edge -> edge.callee().name())
            .thenComparing(edge -> edge.callee().descriptor())
            .thenComparing(edge -> edge.caller().dexName(),
                    Comparator.nullsFirst(String::compareTo))
            .thenComparing(edge -> edge.callee().dexName(),
                    Comparator.nullsFirst(String::compareTo));
    private static final Comparator<MethodIdentity> METHOD_ORDER = Comparator
            .comparing(MethodIdentity::owner)
            .thenComparing(MethodIdentity::name)
            .thenComparing(MethodIdentity::descriptor)
            .thenComparing(MethodIdentity::classScope,
                    Comparator.nullsFirst(String::compareTo))
            .thenComparing(MethodIdentity::dexName,
                    Comparator.nullsFirst(String::compareTo));

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;
    private final GraphOperation graphOperation;

    public CallGraphTool(WorkspaceManager workspaceManager,
                         CallGraphService callGraphService) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                (workspace, method, request) -> {
                    CallGraph graph = callGraphService.getCurrentWorkspaceCallGraph();
                    if (graph == null) {
                        throw new IllegalStateException(
                                "Recaf has no call graph for the current workspace"
                        );
                    }
                    return buildGraph(workspace, graph, method, request);
                }
        );
    }

    CallGraphTool(BooleanSupplier hasWorkspace,
                  Supplier<Workspace> workspaceSupplier,
                  GraphOperation graphOperation) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.graphOperation = Objects.requireNonNull(graphOperation, "graphOperation");
    }

    @Override
    public String name() {
        return "call_graph";
    }

    @Override
    public String description() {
        return "Get callers and callees for one exact JVM or Android method.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        GraphRequest request = parseRequest(arguments);
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
        List<ClassToolSupport.Entry> matches = ClassToolSupport.exact(
                primary,
                request.selection(),
                request.owner()
        );
        if (matches.isEmpty()) {
            JsonObject err = error("owner class not found in requested scope", request);
            ClassToolSupport.addScopeDiagnostics(
                    err.getAsJsonObject("structuredContent"),
                    primary,
                    request.selection()
            );
            ClassToolSupport.addDidYouMeanClasses(
                    err.getAsJsonObject("structuredContent"),
                    primary,
                    request.selection(),
                    request.owner()
            );
            return err;
        }
        if (matches.size() > 1) {
            return error(
                    "owner class is ambiguous; specify dex_name",
                    request,
                    matches
            );
        }
        ClassToolSupport.Entry rootEntry = matches.getFirst();

        GraphData graph;
        MethodIdentity rootIdentity;
        String outputScope;
        if (rootEntry.scope() == ClassToolSupport.Scope.ANDROID) {
            AndroidInstructionSupport.MethodCode method = AndroidInstructionSupport.exact(
                    rootEntry,
                    request.name(),
                    request.descriptor()
            );
            if (method == null) {
                JsonObject err = error(
                        "method with exact descriptor not found in owner class",
                        request
                );
                ClassToolSupport.addDidYouMeanMembers(
                        err.getAsJsonObject("structuredContent"),
                        rootEntry.classInfo(),
                        request.name(),
                        request.descriptor(),
                        true
                );
                return err;
            }
            rootIdentity = androidIdentity(method);
            outputScope = request.selection().resourcePath() == null
                    ? "primary_android_dex_graph"
                    : "embedded_android_dex_graph";
            try {
                WorkspaceResource targetResource = rootEntry.resource() == null
                        ? primary
                        : rootEntry.resource();
                graph = buildAndroidGraph(targetResource, rootIdentity, request);
            } catch (Exception ex) {
                ToolCancellation.propagateIfRequested(ex);
                return error("Android call graph failed: " + exceptionMessage(ex), request);
            }
        } else {
            JvmClassInfo owner = rootEntry.classInfo().asJvmClass();
            MethodMember method = ClassToolSupport.findDeclaredMethod(
                    owner,
                    request.name(),
                    request.descriptor()
            );
            if (method == null) {
                JsonObject err = error(
                        "method with exact descriptor not found in owner class",
                        request
                );
                ClassToolSupport.addDidYouMeanMembers(
                        err.getAsJsonObject("structuredContent"),
                        owner,
                        request.name(),
                        request.descriptor(),
                        true
                );
                return err;
            }
            String origin = request.selection().resourcePath() == null
                    ? "primary"
                    : "selected_resource";
            rootIdentity = new MethodIdentity(
                    owner.getName(),
                    method.getName(),
                    method.getDescriptor(),
                    true,
                    origin,
                    ClassToolSupport.Scope.JVM.value(),
                    null
            );
            outputScope = "workspace_jvm_graph";
            try {
                graph = graphOperation.run(workspace, method, request);
            } catch (Exception ex) {
                ToolCancellation.propagateIfRequested(ex);
                return error("call graph failed: " + exceptionMessage(ex), request);
            }
        }

        List<GraphEdge> ordered = new ArrayList<>(graph.edges());
        ordered.sort(EDGE_ORDER);
        int start = Math.min(request.offset(), ordered.size());
        int end = Math.min(start + request.limit(), ordered.size());
        JsonArray edges = new JsonArray();
        ordered.subList(start, end).forEach(edge -> edges.add(toJson(edge)));

        JsonObject structured = requestMetadata(request);
        structured.add("root", methodJson(rootIdentity));
        structured.add("edges", edges);
        structured.addProperty("scope", outputScope);
        structured.addProperty("vertices_visited", graph.verticesVisited());
        structured.addProperty("external_vertices_stopped", graph.externalVerticesStopped());
        structured.addProperty("graph_truncated", graph.truncated());
        structured.addProperty("graph_source", graph.graphSource());
        if (graph.classesSelected() >= 0) {
            structured.addProperty("classes_selected", graph.classesSelected());
            structured.addProperty("methods_indexed", graph.methodsIndexed());
            structured.addProperty("instructions_scanned", graph.instructionsScanned());
            JsonArray indexedDexNames = new JsonArray();
            graph.indexedDexNames().forEach(indexedDexNames::add);
            structured.add("indexed_dex_names", indexedDexNames);
            structured.addProperty("graph_dex_scope", request.graphDexScope());
        }
        addPageMetadata(structured, request, ordered.size(), end - start);
        return McpTool.successResult(
                "found " + ordered.size() + " call graph edges",
                structured
        );
    }

    private static GraphData buildGraph(Workspace workspace,
                                        CallGraph graph,
                                        MethodMember rootMethod,
                                        GraphRequest request) {
        graph.initialize();
        long deadline = System.nanoTime() + GRAPH_READY_TIMEOUT_NANOS;
        while (!graph.isReady().getValue()) {
            ToolCancellation.check();
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Recaf call graph readiness timed out");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("call graph initialization was interrupted", ex);
            }
        }
        MethodVertex root = graph.getVertex(rootMethod);
        if (root == null) {
            throw new IllegalStateException("Recaf call graph has no root vertex");
        }
        WorkspaceResource primary = workspace.getPrimaryResource();
        WorkspaceResource targetResource = request.selection().resourcePath() == null
                ? primary
                : ClassToolSupport.resolveEmbeddedResource(
                        primary,
                        request.selection().resourcePath()
                );
        return walkGraph(workspace, targetResource, root, request);
    }

    private static GraphData buildAndroidGraph(WorkspaceResource targetResource,
                                               MethodIdentity root,
                                               GraphRequest request) {
        String indexDexName = "root_dex".equals(request.graphDexScope())
                ? root.dexName()
                : null;
        if (request.direction() == Direction.CALLS) {
            AndroidDeclarations decls = indexAndroidDeclarations(targetResource, indexDexName, root);
            LazyAndroidForwardOutcome lazy = walkAndroidCallsLazy(
                    root,
                    decls.definitions(),
                    decls.methods(),
                    decls.classesByName(),
                    request
            );
            return new GraphData(
                    List.copyOf(lazy.edges().values()),
                    lazy.visited().size(),
                    lazy.externalStopped().size(),
                    decls.truncated() || lazy.truncated(),
                    decls.classesSelected(),
                    decls.methods().size(),
                    lazy.instructionsScanned(),
                    "dex_invoke_instructions",
                    decls.indexedDexNames()
            );
        }

        CachedAndroidReverseGraph cached = getOrBuildAndroidReverseGraph(
                targetResource,
                indexDexName,
                root
        );
        GraphData walked = walkAndroidGraph(root, cached.calls(), cached.callers(), request);
        return new GraphData(
                walked.edges(),
                walked.verticesVisited(),
                walked.externalVerticesStopped(),
                cached.truncated() || walked.truncated(),
                cached.classesSelected(),
                cached.methodsIndexed(),
                cached.instructionsScanned(),
                "dex_invoke_instructions",
                cached.indexedDexNames()
        );
    }

    private static AndroidDeclarations indexAndroidDeclarations(WorkspaceResource targetResource,
                                                                String indexDexName,
                                                                MethodIdentity root) {
        ClassToolSupport.Selection indexSelection = new ClassToolSupport.Selection(
                ClassToolSupport.Scope.ANDROID,
                indexDexName,
                true,
                null,
                false
        );
        List<ClassToolSupport.Entry> classes = ClassToolSupport.entries(
                targetResource,
                indexSelection
        );
        Set<String> indexedDexNames = new TreeSet<>();
        classes.stream()
                .map(ClassToolSupport.Entry::dexName)
                .filter(Objects::nonNull)
                .forEach(indexedDexNames::add);
        if (root.dexName() != null) {
            indexedDexNames.add(root.dexName());
        }

        Map<MethodKey, List<MethodIdentity>> definitions = new HashMap<>();
        Map<MethodIdentity, AndroidInstructionSupport.MethodCode> methods = new HashMap<>();
        Map<String, software.coley.recaf.info.ClassInfo> classesByName = new HashMap<>();
        boolean truncated = false;
        for (ClassToolSupport.Entry entry : classes) {
            ToolCancellation.check();
            classesByName.putIfAbsent(entry.className(), entry.classInfo());
            for (AndroidInstructionSupport.MethodCode method
                    : AndroidInstructionSupport.methods(entry)) {
                if (methods.size() >= MAX_ANDROID_INDEXED_METHODS) {
                    truncated = true;
                    break;
                }
                MethodIdentity identity = androidIdentity(method);
                methods.put(identity, method);
                definitions.computeIfAbsent(
                        key(identity),
                        ignored -> new ArrayList<>()
                ).add(identity);
            }
            if (truncated) {
                break;
            }
        }
        definitions.values().forEach(values -> values.sort(METHOD_ORDER));
        if (!methods.containsKey(root)) {
            ClassToolSupport.Entry rootClassEntry = ClassToolSupport.exact(
                            targetResource,
                            new ClassToolSupport.Selection(
                                    ClassToolSupport.Scope.ANDROID,
                                    root.dexName()
                            ),
                            root.owner()
                    ).stream()
                    .findFirst()
                    .orElseThrow();
            AndroidInstructionSupport.MethodCode rootMethod = AndroidInstructionSupport.exact(
                    rootClassEntry,
                    root.name(),
                    root.descriptor()
            );
            if (rootMethod != null) {
                methods.put(root, rootMethod);
                definitions.computeIfAbsent(
                        key(root),
                        ignored -> new ArrayList<>()
                ).add(root);
            }
        }
        return new AndroidDeclarations(
                classes.size(),
                List.copyOf(indexedDexNames),
                definitions,
                methods,
                classesByName,
                truncated
        );
    }

    private static LazyAndroidForwardOutcome walkAndroidCallsLazy(
            MethodIdentity root,
            Map<MethodKey, List<MethodIdentity>> definitions,
            Map<MethodIdentity, AndroidInstructionSupport.MethodCode> methods,
            Map<String, software.coley.recaf.info.ClassInfo> classesByName,
            GraphRequest request) {
        Queue<AndroidVertexDepth> pending = new ArrayDeque<>();
        Map<MethodIdentity, Integer> visited = new HashMap<>();
        Map<AndroidEdgeKey, GraphEdge> edges = new LinkedHashMap<>();
        Set<MethodIdentity> externalVerticesStopped = new HashSet<>();
        Map<MethodIdentity, List<MethodIdentity>> decodedCalls = new HashMap<>();
        pending.add(new AndroidVertexDepth(root, 0));
        visited.put(root, 0);
        long instructionsScanned = 0;
        boolean truncated = false;

        walk:
        while (!pending.isEmpty()) {
            ToolCancellation.check();
            AndroidVertexDepth current = pending.remove();
            if (current.depth() >= request.maxDepth()) {
                continue;
            }
            List<MethodIdentity> callees = decodedCalls.get(current.method());
            if (callees == null) {
                AndroidInstructionSupport.MethodCode code = methods.get(current.method());
                Set<MethodIdentity> found = new HashSet<>();
                if (code != null) {
                    for (AndroidInstructionSupport.InstructionLine instruction
                            : AndroidInstructionSupport.instructions(code)) {
                        if (instructionsScanned >= MAX_ANDROID_SCANNED_INSTRUCTIONS) {
                            truncated = true;
                            break walk;
                        }
                        instructionsScanned++;
                        AndroidInstructionSupport.MethodReference reference =
                                AndroidInstructionSupport.invokedMethod(instruction.instruction());
                        if (reference != null) {
                            found.add(resolveAndroidReference(
                                    current.method(),
                                    reference,
                                    definitions,
                                    classesByName
                            ));
                        }
                    }
                }
                callees = new ArrayList<>(found);
                callees.sort(METHOD_ORDER);
                decodedCalls.put(current.method(), callees);
            }
            for (MethodIdentity callee : callees) {
                if (addAndroidEdge(
                        edges,
                        current.method(),
                        callee,
                        current.depth() + 1
                ) && edges.size() >= MAX_COLLECTED_EDGES) {
                    truncated = true;
                    break walk;
                }
                if (stopAtExternal(request, callee)) {
                    externalVerticesStopped.add(callee);
                } else if (enqueueAndroid(
                        pending,
                        visited,
                        callee,
                        current.depth() + 1
                ) && visited.size() >= MAX_VISITED_VERTICES) {
                    truncated = true;
                    break walk;
                }
            }
        }
        return new LazyAndroidForwardOutcome(
                edges,
                visited.keySet(),
                externalVerticesStopped,
                instructionsScanned,
                truncated
        );
    }

    private static CachedAndroidReverseGraph getOrBuildAndroidReverseGraph(
            WorkspaceResource targetResource,
            String indexDexName,
            MethodIdentity root) {
        String revision = androidResourceRevision(targetResource, indexDexName);
        AndroidGraphCacheKey cacheKey = new AndroidGraphCacheKey(
                System.identityHashCode(targetResource),
                indexDexName,
                revision
        );
        synchronized (ANDROID_CACHE_LOCK) {
            CachedAndroidReverseGraph hit = ANDROID_REVERSE_CACHE.get(cacheKey);
            if (hit != null && hit.resourceRef().get() == targetResource) {
                return hit;
            }
        }

        AndroidDeclarations decls = indexAndroidDeclarations(targetResource, indexDexName, root);
        Map<MethodIdentity, Set<MethodIdentity>> calls = new HashMap<>();
        Map<MethodIdentity, Set<MethodIdentity>> callers = new HashMap<>();
        long instructionsScanned = 0;
        boolean truncated = decls.truncated();
        List<Map.Entry<MethodIdentity, AndroidInstructionSupport.MethodCode>> orderedMethods =
                new ArrayList<>(decls.methods().entrySet());
        orderedMethods.sort(Map.Entry.comparingByKey(METHOD_ORDER));
        scan:
        for (Map.Entry<MethodIdentity, AndroidInstructionSupport.MethodCode> methodEntry
                : orderedMethods) {
            ToolCancellation.check();
            MethodIdentity caller = methodEntry.getKey();
            for (AndroidInstructionSupport.InstructionLine instruction
                    : AndroidInstructionSupport.instructions(methodEntry.getValue())) {
                if (instructionsScanned >= MAX_ANDROID_SCANNED_INSTRUCTIONS) {
                    truncated = true;
                    break scan;
                }
                instructionsScanned++;
                AndroidInstructionSupport.MethodReference reference =
                        AndroidInstructionSupport.invokedMethod(instruction.instruction());
                if (reference == null) {
                    continue;
                }
                MethodIdentity callee = resolveAndroidReference(
                        caller,
                        reference,
                        decls.definitions(),
                        decls.classesByName()
                );
                calls.computeIfAbsent(caller, ignored -> new HashSet<>()).add(callee);
                callers.computeIfAbsent(callee, ignored -> new HashSet<>()).add(caller);
            }
        }
        CachedAndroidReverseGraph built = new CachedAndroidReverseGraph(
                new java.lang.ref.WeakReference<>(targetResource),
                calls,
                callers,
                decls.classesSelected(),
                decls.methods().size(),
                instructionsScanned,
                truncated,
                decls.indexedDexNames()
        );
        synchronized (ANDROID_CACHE_LOCK) {
            ANDROID_REVERSE_CACHE.put(cacheKey, built);
        }
        return built;
    }

    private static String androidResourceRevision(WorkspaceResource resource, String indexDexName) {
        StringBuilder sb = new StringBuilder();
        List<String> dexNames = new ArrayList<>(resource.getAndroidClassBundles().keySet());
        dexNames.sort(String::compareTo);
        for (String dexName : dexNames) {
            if (indexDexName != null && !indexDexName.equals(dexName)) {
                continue;
            }
            var bundle = resource.getAndroidClassBundles().get(dexName);
            if (bundle == null) {
                continue;
            }
            sb.append(dexName)
                    .append(':')
                    .append(System.identityHashCode(bundle))
                    .append(':')
                    .append(bundle.size())
                    .append(':')
                    .append(bundle.getDirtyKeys().size())
                    .append(':')
                    .append(bundle.getRemovedKeys().size());
            if (!bundle.getDirtyKeys().isEmpty()) {
                List<String> dirtySorted = new ArrayList<>(bundle.getDirtyKeys());
                dirtySorted.sort(String::compareTo);
                for (String key : dirtySorted) {
                    sb.append(',').append(key).append('=').append(System.identityHashCode(bundle.get(key)));
                }
            }
            sb.append(';');
        }
        return sb.toString();
    }

    static GraphData walkAndroidGraph(
            MethodIdentity root,
            Map<MethodIdentity, Set<MethodIdentity>> calls,
            Map<MethodIdentity, Set<MethodIdentity>> callers,
            GraphRequest request) {
        if (request.direction() == Direction.BOTH) {
            AndroidWalkOutcome down = walkAndroidDirection(
                    root, calls, callers, request, Direction.CALLS
            );
            AndroidWalkOutcome up = walkAndroidDirection(
                    root, calls, callers, request, Direction.CALLERS
            );
            Map<AndroidEdgeKey, GraphEdge> mergedEdges = new LinkedHashMap<>(down.edges());
            for (Map.Entry<AndroidEdgeKey, GraphEdge> entry : up.edges().entrySet()) {
                GraphEdge existing = mergedEdges.get(entry.getKey());
                if (existing == null || entry.getValue().depth() < existing.depth()) {
                    mergedEdges.put(entry.getKey(), entry.getValue());
                }
            }
            Set<MethodIdentity> allVisited = new HashSet<>(down.visited());
            allVisited.addAll(up.visited());
            Set<MethodIdentity> allExternal = new HashSet<>(down.externalStopped());
            allExternal.addAll(up.externalStopped());
            boolean truncated = down.truncated()
                    || up.truncated()
                    || mergedEdges.size() > MAX_COLLECTED_EDGES
                    || allVisited.size() > MAX_VISITED_VERTICES;
            return new GraphData(
                    mergedEdges.values().stream().limit(MAX_COLLECTED_EDGES).toList(),
                    Math.min(allVisited.size(), MAX_VISITED_VERTICES),
                    allExternal.size(),
                    truncated
            );
        }
        AndroidWalkOutcome single = walkAndroidDirection(
                root, calls, callers, request, request.direction()
        );
        return new GraphData(
                List.copyOf(single.edges().values()),
                single.visited().size(),
                single.externalStopped().size(),
                single.truncated()
        );
    }

    private static AndroidWalkOutcome walkAndroidDirection(
            MethodIdentity root,
            Map<MethodIdentity, Set<MethodIdentity>> calls,
            Map<MethodIdentity, Set<MethodIdentity>> callers,
            GraphRequest request,
            Direction direction) {
        Queue<AndroidVertexDepth> pending = new ArrayDeque<>();
        Map<MethodIdentity, Integer> visited = new HashMap<>();
        Map<AndroidEdgeKey, GraphEdge> edges = new LinkedHashMap<>();
        Set<MethodIdentity> externalVerticesStopped = new HashSet<>();
        pending.add(new AndroidVertexDepth(root, 0));
        visited.put(root, 0);
        boolean truncated = false;

        while (!pending.isEmpty()) {
            ToolCancellation.check();
            AndroidVertexDepth current = pending.remove();
            if (current.depth() >= request.maxDepth()) {
                continue;
            }
            if (direction == Direction.CALLS) {
                List<MethodIdentity> callees = new ArrayList<>(
                        calls.getOrDefault(current.method(), Set.of())
                );
                callees.sort(METHOD_ORDER);
                for (MethodIdentity callee : callees) {
                    if (addAndroidEdge(
                            edges,
                            current.method(),
                            callee,
                            current.depth() + 1
                    ) && edges.size() >= MAX_COLLECTED_EDGES) {
                        truncated = true;
                        break;
                    }
                    if (stopAtExternal(request, callee)) {
                        externalVerticesStopped.add(callee);
                    } else if (enqueueAndroid(
                            pending,
                            visited,
                            callee,
                            current.depth() + 1
                    ) && visited.size() >= MAX_VISITED_VERTICES) {
                        truncated = true;
                        break;
                    }
                }
            } else {
                List<MethodIdentity> callerMethods = new ArrayList<>(
                        callers.getOrDefault(current.method(), Set.of())
                );
                callerMethods.sort(METHOD_ORDER);
                for (MethodIdentity caller : callerMethods) {
                    if (addAndroidEdge(
                            edges,
                            caller,
                            current.method(),
                            current.depth() + 1
                    ) && edges.size() >= MAX_COLLECTED_EDGES) {
                        truncated = true;
                        break;
                    }
                    if (stopAtExternal(request, caller)) {
                        externalVerticesStopped.add(caller);
                    } else if (enqueueAndroid(
                            pending,
                            visited,
                            caller,
                            current.depth() + 1
                    ) && visited.size() >= MAX_VISITED_VERTICES) {
                        truncated = true;
                        break;
                    }
                }
            }
            if (truncated) {
                break;
            }
        }
        return new AndroidWalkOutcome(
                edges,
                visited.keySet(),
                externalVerticesStopped,
                truncated
        );
    }

    private static MethodIdentity resolveAndroidReference(
            MethodIdentity caller,
            AndroidInstructionSupport.MethodReference reference,
            Map<MethodKey, List<MethodIdentity>> definitions,
            Map<String, software.coley.recaf.info.ClassInfo> classesByName) {
        List<MethodIdentity> candidates = definitions.getOrDefault(
                new MethodKey(
                        reference.owner(),
                        reference.name(),
                        reference.descriptor()
                ),
                List.of()
        );
        if (!candidates.isEmpty()) {
            for (MethodIdentity candidate : candidates) {
                if (Objects.equals(candidate.dexName(), caller.dexName())) {
                    return candidate;
                }
            }
            return candidates.getFirst();
        }
        String effectiveUnresolvedOwner = reference.owner();
        if (classesByName != null && !"<init>".equals(reference.name())
                && !"<clinit>".equals(reference.name())) {
            Queue<String> superQueue = new ArrayDeque<>();
            Set<String> visitedSupers = new HashSet<>();
            software.coley.recaf.info.ClassInfo startInfo = classesByName.get(reference.owner());
            if (startInfo != null) {
                visitedSupers.add(reference.owner());
                if (startInfo.getSuperName() != null && visitedSupers.add(startInfo.getSuperName())) {
                    superQueue.add(startInfo.getSuperName());
                }
                for (String iface : startInfo.getInterfaces()) {
                    if (iface != null && visitedSupers.add(iface)) {
                        superQueue.add(iface);
                    }
                }
            }
            String firstExternalSuper = null;
            while (!superQueue.isEmpty()) {
                String superOwner = superQueue.remove();
                List<MethodIdentity> inherited = definitions.getOrDefault(
                        new MethodKey(superOwner, reference.name(), reference.descriptor()),
                        List.of()
                );
                if (!inherited.isEmpty()) {
                    for (MethodIdentity candidate : inherited) {
                        if (Objects.equals(candidate.dexName(), caller.dexName())) {
                            return candidate;
                        }
                    }
                    return inherited.getFirst();
                }
                software.coley.recaf.info.ClassInfo superInfo = classesByName.get(superOwner);
                if (superInfo != null) {
                    if (superInfo.getSuperName() != null && visitedSupers.add(superInfo.getSuperName())) {
                        superQueue.add(superInfo.getSuperName());
                    }
                    for (String iface : superInfo.getInterfaces()) {
                        if (iface != null && visitedSupers.add(iface)) {
                            superQueue.add(iface);
                        }
                    }
                } else if (firstExternalSuper == null && !"java/lang/Object".equals(superOwner)) {
                    firstExternalSuper = superOwner;
                }
            }
            if (firstExternalSuper != null) {
                effectiveUnresolvedOwner = firstExternalSuper;
            }
        }
        return new MethodIdentity(
                effectiveUnresolvedOwner,
                reference.name(),
                reference.descriptor(),
                false,
                "unresolved",
                null,
                null
        );
    }

    private static boolean stopAtExternal(GraphRequest request,
                                          MethodIdentity method) {
        return request.stopAtExternal() && !method.resolved();
    }

    private static boolean addAndroidEdge(Map<AndroidEdgeKey, GraphEdge> edges,
                                          MethodIdentity caller,
                                          MethodIdentity callee,
                                          int depth) {
        AndroidEdgeKey key = new AndroidEdgeKey(caller, callee);
        GraphEdge existing = edges.get(key);
        if (existing == null || depth < existing.depth()) {
            edges.put(key, new GraphEdge(caller, callee, depth));
            return existing == null;
        }
        return false;
    }

    private static boolean enqueueAndroid(Queue<AndroidVertexDepth> pending,
                                          Map<MethodIdentity, Integer> visited,
                                          MethodIdentity method,
                                          int depth) {
        Integer priorDepth = visited.get(method);
        if (priorDepth != null && priorDepth <= depth) {
            return false;
        }
        visited.put(method, depth);
        pending.add(new AndroidVertexDepth(method, depth));
        return true;
    }

    private static MethodIdentity androidIdentity(
            AndroidInstructionSupport.MethodCode method) {
        String origin = method.owner().resourcePath() == null
                ? "primary"
                : "selected_resource";
        return new MethodIdentity(
                method.owner().className(),
                method.name(),
                method.descriptor(),
                true,
                origin,
                ClassToolSupport.Scope.ANDROID.value(),
                method.owner().dexName()
        );
    }

    private static MethodKey key(MethodIdentity method) {
        return new MethodKey(method.owner(), method.name(), method.descriptor());
    }

    static GraphData walkGraph(MethodVertex root, GraphRequest request) {
        return walkGraph(null, null, root, request);
    }

    static GraphData walkGraph(Workspace workspace,
                               WorkspaceResource targetResource,
                               MethodVertex root,
                               GraphRequest request) {
        if (request.direction() == Direction.BOTH) {
            JvmWalkOutcome down = walkJvmDirection(
                    workspace, targetResource, root, request, Direction.CALLS
            );
            JvmWalkOutcome up = walkJvmDirection(
                    workspace, targetResource, root, request, Direction.CALLERS
            );
            Map<EdgeKey, GraphEdge> mergedEdges = new LinkedHashMap<>(down.edges());
            for (Map.Entry<EdgeKey, GraphEdge> entry : up.edges().entrySet()) {
                GraphEdge existing = mergedEdges.get(entry.getKey());
                if (existing == null || entry.getValue().depth() < existing.depth()) {
                    mergedEdges.put(entry.getKey(), entry.getValue());
                }
            }
            Set<MethodRef> allVisited = new HashSet<>(down.visited());
            allVisited.addAll(up.visited());
            Set<MethodRef> allExternal = new HashSet<>(down.externalStopped());
            allExternal.addAll(up.externalStopped());
            boolean truncated = down.truncated()
                    || up.truncated()
                    || mergedEdges.size() > MAX_COLLECTED_EDGES
                    || allVisited.size() > MAX_VISITED_VERTICES;
            return new GraphData(
                    mergedEdges.values().stream().limit(MAX_COLLECTED_EDGES).toList(),
                    Math.min(allVisited.size(), MAX_VISITED_VERTICES),
                    allExternal.size(),
                    truncated
            );
        }
        JvmWalkOutcome single = walkJvmDirection(
                workspace, targetResource, root, request, request.direction()
        );
        return new GraphData(
                List.copyOf(single.edges().values()),
                single.visited().size(),
                single.externalStopped().size(),
                single.truncated()
        );
    }

    private static JvmWalkOutcome walkJvmDirection(Workspace workspace,
                                                   WorkspaceResource targetResource,
                                                   MethodVertex root,
                                                   GraphRequest request,
                                                   Direction direction) {
        Queue<VertexDepth> pending = new ArrayDeque<>();
        Map<MethodRef, Integer> visited = new HashMap<>();
        Map<EdgeKey, GraphEdge> edges = new LinkedHashMap<>();
        Set<MethodRef> externalVerticesStopped = new HashSet<>();
        pending.add(new VertexDepth(root, 0));
        visited.put(root.getMethod(), 0);
        boolean truncated = false;

        while (!pending.isEmpty()) {
            ToolCancellation.check();
            VertexDepth current = pending.remove();
            if (current.depth() >= request.maxDepth()) {
                continue;
            }
            if (direction == Direction.CALLS) {
                for (MethodVertex callee : current.vertex().getCalls()) {
                    if (addEdge(
                            workspace,
                            targetResource,
                            edges,
                            current.vertex(),
                            callee,
                            current.depth() + 1
                    ) && edges.size() >= MAX_COLLECTED_EDGES) {
                        truncated = true;
                        break;
                    }
                    if (stopAtExternal(targetResource, request, callee)) {
                        externalVerticesStopped.add(callee.getMethod());
                    } else if (enqueue(pending, visited, callee, current.depth() + 1)) {
                        if (visited.size() >= MAX_VISITED_VERTICES) {
                            truncated = true;
                            break;
                        }
                    }
                }
                if (!truncated) {
                    truncated = addUnresolvedJvmBytecodeCalls(
                            workspace,
                            targetResource,
                            current.vertex(),
                            current.depth() + 1,
                            request,
                            edges,
                            externalVerticesStopped
                    );
                }
            } else {
                for (MethodVertex caller : current.vertex().getCallers()) {
                    if (addEdge(
                            workspace,
                            targetResource,
                            edges,
                            caller,
                            current.vertex(),
                            current.depth() + 1
                    ) && edges.size() >= MAX_COLLECTED_EDGES) {
                        truncated = true;
                        break;
                    }
                    if (stopAtExternal(targetResource, request, caller)) {
                        externalVerticesStopped.add(caller.getMethod());
                    } else if (enqueue(pending, visited, caller, current.depth() + 1)) {
                        if (visited.size() >= MAX_VISITED_VERTICES) {
                            truncated = true;
                            break;
                        }
                    }
                }
            }
            if (truncated) {
                break;
            }
        }
        return new JvmWalkOutcome(
                edges,
                visited.keySet(),
                externalVerticesStopped,
                truncated
        );
    }

    private static boolean stopAtExternal(WorkspaceResource targetResource,
                                          GraphRequest request,
                                          MethodVertex vertex) {
        if (!request.stopAtExternal()) {
            return false;
        }
        if (vertex.getResolvedMethod() == null) {
            return true;
        }
        if (targetResource == null) {
            return false;
        }
        return !ownsJvmClass(targetResource, vertex.getMethod().owner());
    }

    private static boolean ownsJvmClass(WorkspaceResource resource, String internalName) {
        if (resource.getJvmClassBundle().containsKey(internalName)) {
            return true;
        }
        return resource.getVersionedJvmClassBundles().values().stream()
                .anyMatch(bundle -> bundle.containsKey(internalName));
    }

    private static boolean ownsJvmClassInEmbedded(WorkspaceResource resource,
                                                  String internalName) {
        for (WorkspaceFileResource embedded : resource.getEmbeddedResources().values()) {
            if (ownsJvmClass(embedded, internalName)
                    || ownsJvmClassInEmbedded(embedded, internalName)) {
                return true;
            }
        }
        return false;
    }

    private static String classifyJvmOrigin(Workspace workspace,
                                            WorkspaceResource targetResource,
                                            MethodVertex vertex) {
        if (vertex.getResolvedMethod() == null) {
            return "unresolved";
        }
        if (targetResource == null) {
            return "primary";
        }
        String owner = vertex.getMethod().owner();
        if (ownsJvmClass(targetResource, owner)) {
            return workspace != null && targetResource != workspace.getPrimaryResource()
                    ? "selected_resource"
                    : "primary";
        }
        if (workspace != null) {
            WorkspaceResource primary = workspace.getPrimaryResource();
            if (ownsJvmClass(primary, owner) || ownsJvmClassInEmbedded(primary, owner)) {
                return "embedded";
            }
        }
        return "supporting";
    }

    private static boolean addEdge(Workspace workspace,
                                   WorkspaceResource targetResource,
                                   Map<EdgeKey, GraphEdge> edges,
                                   MethodVertex caller,
                                   MethodVertex callee,
                                   int depth) {
        MethodIdentity callerIdentity = identity(workspace, targetResource, caller);
        MethodIdentity calleeIdentity = identity(workspace, targetResource, callee);
        EdgeKey key = new EdgeKey(caller.getMethod(), callee.getMethod());
        GraphEdge existing = edges.get(key);
        if (existing == null || depth < existing.depth()) {
            edges.put(key, new GraphEdge(callerIdentity, calleeIdentity, depth));
            return existing == null;
        }
        return false;
    }

    private static boolean addUnresolvedJvmBytecodeCalls(Workspace workspace,
                                                         WorkspaceResource targetResource,
                                                         MethodVertex callerVertex,
                                                         int depth,
                                                         GraphRequest request,
                                                         Map<EdgeKey, GraphEdge> edges,
                                                         Set<MethodRef> externalVerticesStopped) {
        JvmClassInfo ownerClass = findJvmClassForCalls(
                workspace,
                targetResource,
                callerVertex.getMethod().owner()
        );
        if (ownerClass == null) {
            return false;
        }
        Set<MethodRef> alreadyEmitted = new HashSet<>();
        for (MethodVertex known : callerVertex.getCalls()) {
            alreadyEmitted.add(known.getMethod());
        }
        MethodIdentity callerIdentity = identity(workspace, targetResource, callerVertex);
        org.objectweb.asm.tree.ClassNode classNode = new org.objectweb.asm.tree.ClassNode();
        try {
            new org.objectweb.asm.ClassReader(ownerClass.getBytecode()).accept(
                    classNode,
                    org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES
            );
        } catch (RuntimeException ignored) {
            return false;
        }
        for (org.objectweb.asm.tree.MethodNode methodNode : classNode.methods) {
            if (!callerVertex.getMethod().name().equals(methodNode.name)
                    || !callerVertex.getMethod().desc().equals(methodNode.desc)) {
                continue;
            }
            for (var insn = methodNode.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode methodInsn) {
                    if (emitUnresolvedJvmTarget(
                            workspace,
                            callerVertex.getMethod(),
                            callerIdentity,
                            methodInsn.owner,
                            methodInsn.name,
                            methodInsn.desc,
                            depth,
                            request,
                            alreadyEmitted,
                            edges,
                            externalVerticesStopped
                    )) {
                        return true;
                    }
                } else if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy
                        && indy.bsmArgs != null) {
                    for (Object bsmArg : indy.bsmArgs) {
                        if (bsmArg instanceof org.objectweb.asm.Handle handle
                                && handle.getTag() >= org.objectweb.asm.Opcodes.H_INVOKEVIRTUAL
                                && handle.getTag() <= org.objectweb.asm.Opcodes.H_INVOKEINTERFACE) {
                            if (emitUnresolvedJvmTarget(
                                    workspace,
                                    callerVertex.getMethod(),
                                    callerIdentity,
                                    handle.getOwner(),
                                    handle.getName(),
                                    handle.getDesc(),
                                    depth,
                                    request,
                                    alreadyEmitted,
                                    edges,
                                    externalVerticesStopped
                            )) {
                                return true;
                            }
                        }
                    }
                }
            }
            break;
        }
        return false;
    }

    private static boolean emitUnresolvedJvmTarget(Workspace workspace,
                                                   MethodRef callerRef,
                                                   MethodIdentity callerIdentity,
                                                   String rawOwner,
                                                   String name,
                                                   String desc,
                                                   int depth,
                                                   GraphRequest request,
                                                   Set<MethodRef> alreadyEmitted,
                                                   Map<EdgeKey, GraphEdge> edges,
                                                   Set<MethodRef> externalVerticesStopped) {
        if (rawOwner == null || rawOwner.startsWith("[")) {
            return false;
        }
        MethodRef calleeRef = new MethodRef(rawOwner, name, desc);
        if (!alreadyEmitted.add(calleeRef)) {
            return false;
        }
        if (workspace != null && workspace.findJvmClass(rawOwner) != null) {
            return false;
        }
        EdgeKey key = new EdgeKey(callerRef, calleeRef);
        MethodIdentity unresolvedCallee = new MethodIdentity(
                rawOwner,
                name,
                desc,
                false,
                "unresolved",
                null,
                null
        );
        GraphEdge existing = edges.get(key);
        boolean added = false;
        if (existing == null || depth < existing.depth()) {
            edges.put(key, new GraphEdge(callerIdentity, unresolvedCallee, depth));
            added = existing == null;
        }
        if (request.stopAtExternal()) {
            externalVerticesStopped.add(calleeRef);
        }
        return added && edges.size() >= MAX_COLLECTED_EDGES;
    }

    private static JvmClassInfo findJvmClassForCalls(Workspace workspace,
                                                     WorkspaceResource targetResource,
                                                     String owner) {
        if (targetResource != null) {
            JvmClassInfo direct = targetResource.getJvmClassBundle().get(owner);
            if (direct != null) {
                return direct;
            }
            for (var versioned : targetResource.getVersionedJvmClassBundles().values()) {
                JvmClassInfo vClass = versioned.get(owner);
                if (vClass != null) {
                    return vClass;
                }
            }
        }
        if (workspace != null) {
            var node = workspace.findJvmClass(owner);
            if (node != null && node.getValue().isJvmClass()) {
                return node.getValue().asJvmClass();
            }
        }
        return null;
    }

    private static boolean enqueue(Queue<VertexDepth> pending,
                                   Map<MethodRef, Integer> visited,
                                   MethodVertex vertex,
                                   int depth) {
        Integer priorDepth = visited.get(vertex.getMethod());
        if (priorDepth != null && priorDepth <= depth) {
            return false;
        }
        visited.put(vertex.getMethod(), depth);
        pending.add(new VertexDepth(vertex, depth));
        return true;
    }

    private static MethodIdentity identity(Workspace workspace,
                                           WorkspaceResource targetResource,
                                           MethodVertex vertex) {
        MethodRef ref = vertex.getMethod();
        boolean resolved = vertex.getResolvedMethod() != null;
        String origin = classifyJvmOrigin(workspace, targetResource, vertex);
        String resourcePath = resolveJvmResourcePath(workspace, targetResource, origin, ref.owner());
        return new MethodIdentity(
                ref.owner(),
                ref.name(),
                ref.desc(),
                resolved,
                origin,
                resolved ? ClassToolSupport.Scope.JVM.value() : null,
                null,
                resourcePath
        );
    }

    private static String resolveJvmResourcePath(Workspace workspace,
                                                 WorkspaceResource targetResource,
                                                 String origin,
                                                 String owner) {
        if (workspace == null
                || (!"embedded".equals(origin) && !"selected_resource".equals(origin))) {
            return null;
        }
        List<ClassToolSupport.ScopedResource> embedded =
                ClassToolSupport.embeddedResources(workspace.getPrimaryResource());
        if ("selected_resource".equals(origin) && targetResource != null) {
            for (ClassToolSupport.ScopedResource scoped : embedded) {
                if (scoped.resource() == targetResource) {
                    return scoped.path();
                }
            }
        }
        for (ClassToolSupport.ScopedResource scoped : embedded) {
            if (ownsJvmClass(scoped.resource(), owner)) {
                return scoped.path();
            }
        }
        return null;
    }

    private static GraphRequest parseRequest(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(
                arguments,
                false,
                false
        );
        String owner = ToolArguments.requiredString(arguments, "owner");
        String name = ToolArguments.requiredString(arguments, "name");
        String descriptor = ToolArguments.requiredString(arguments, "descriptor");
        String directionName = ToolArguments.optionalString(arguments, "direction");
        Direction direction = Direction.fromJsonName(directionName == null ? "both" : directionName);
        String graphDexScope = ToolArguments.optionalString(arguments, "graph_dex_scope");
        if (graphDexScope == null) {
            graphDexScope = "all";
        } else if (!GRAPH_DEX_SCOPES.contains(graphDexScope)) {
            throw new IllegalArgumentException("graph_dex_scope must be all or root_dex");
        }
        int maxDepth = ToolArguments.integer(arguments, "max_depth", 1, 1, MAX_DEPTH);
        boolean stopAtExternal = ToolArguments.bool(arguments, "stop_at_external", true);
        int offset = ToolArguments.integer(arguments, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = ToolArguments.integer(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        return new GraphRequest(
                owner,
                name,
                descriptor,
                direction,
                maxDepth,
                stopAtExternal,
                offset,
                limit,
                selection,
                graphDexScope
        );
    }

    private static JsonObject toJson(GraphEdge edge) {
        JsonObject json = new JsonObject();
        json.add("caller", methodJson(edge.caller()));
        json.add("callee", methodJson(edge.callee()));
        json.addProperty("depth", edge.depth());
        return json;
    }

    private static JsonObject methodJson(MethodIdentity method) {
        JsonObject json = new JsonObject();
        json.addProperty("owner", method.owner());
        json.addProperty("name", method.name());
        json.addProperty("descriptor", method.descriptor());
        json.addProperty("resolved", method.resolved());
        json.addProperty("origin", method.origin());
        if (method.classScope() == null) {
            json.add("class_scope", JsonNull.INSTANCE);
        } else {
            json.addProperty("class_scope", method.classScope());
        }
        if (method.dexName() == null) {
            json.add("dex_name", JsonNull.INSTANCE);
        } else {
            json.addProperty("dex_name", method.dexName());
        }
        if (method.resourcePath() != null) {
            json.addProperty("resource", method.resourcePath());
        }
        return json;
    }

    private static JsonObject error(String message, GraphRequest request) {
        return error(message, request, List.of());
    }

    private static JsonObject error(String message,
                                    GraphRequest request,
                                    List<ClassToolSupport.Entry> candidates) {
        JsonObject structured = requestMetadata(request);
        if (!candidates.isEmpty()) {
            structured.add("candidates", ClassToolSupport.candidates(candidates));
        }
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject requestMetadata(GraphRequest request) {
        JsonObject json = new JsonObject();
        json.addProperty("owner", request.owner());
        json.addProperty("name", request.name());
        json.addProperty("descriptor", request.descriptor());
        json.addProperty("direction", request.direction().jsonName);
        json.addProperty("max_depth", request.maxDepth());
        json.addProperty("stop_at_external", request.stopAtExternal());
        json.addProperty("requested_scope", request.selection().scope().value());
        if (request.selection().dexName() == null) {
            json.add("dex_name", JsonNull.INSTANCE);
        } else {
            json.addProperty("dex_name", request.selection().dexName());
        }
        ClassToolSupport.addSelectionMetadata(json, request.selection());
        return json;
    }

    private static void addPageMetadata(JsonObject target,
                                        GraphRequest request,
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

    private static String exceptionMessage(Throwable throwable) {
        return ToolErrors.message(throwable);
    }

    private static JsonObject createInputSchema() {
        JsonObject properties = new JsonObject();
        properties.add("owner", stringProperty(
                "Exact JVM or Android owner internal name."
        ));
        properties.add("name", stringProperty("Exact method name."));
        properties.add("descriptor", stringProperty("Exact JVM method descriptor."));

        JsonObject direction = new JsonObject();
        direction.addProperty("type", "string");
        JsonArray directions = new JsonArray();
        directions.add("calls");
        directions.add("callers");
        directions.add("both");
        direction.add("enum", directions);
        direction.addProperty("default", "both");
        properties.add("direction", direction);
        properties.add("max_depth", integerProperty(1, MAX_DEPTH, 1));
        JsonObject stopAtExternal = new JsonObject();
        stopAtExternal.addProperty("type", "boolean");
        stopAtExternal.addProperty("default", true);
        stopAtExternal.addProperty(
                "description",
                "Keep external/library boundary edges without expanding their vertices."
        );
        properties.add("stop_at_external", stopAtExternal);
        ClassToolSupport.addScopeProperties(properties, false, true, false);
        JsonObject graphDexScope = new JsonObject();
        graphDexScope.addProperty("type", "string");
        JsonArray dexScopes = new JsonArray();
        dexScopes.add("all");
        dexScopes.add("root_dex");
        graphDexScope.add("enum", dexScopes);
        graphDexScope.addProperty("default", "all");
        graphDexScope.addProperty(
                "description",
                "Android DEX indexing scope; all resolves cross-DEX calls even when dex_name disambiguates root."
        );
        properties.add("graph_dex_scope", graphDexScope);
        properties.add("offset", integerProperty(0, Integer.MAX_VALUE, 0));
        properties.add("limit", integerProperty(1, MAX_LIMIT, DEFAULT_LIMIT));

        JsonArray required = new JsonArray();
        required.add("owner");
        required.add("name");
        required.add("descriptor");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject stringProperty(String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("minLength", 1);
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

    enum Direction {
        CALLS("calls"),
        CALLERS("callers"),
        BOTH("both");

        private final String jsonName;

        Direction(String jsonName) {
            this.jsonName = jsonName;
        }

        private static Direction fromJsonName(String name) {
            for (Direction direction : values()) {
                if (direction.jsonName.equals(name)) {
                    return direction;
                }
            }
            throw new IllegalArgumentException("direction must be calls, callers, or both");
        }
    }

    record GraphRequest(String owner,
                        String name,
                        String descriptor,
                        Direction direction,
                        int maxDepth,
                        boolean stopAtExternal,
                        int offset,
                        int limit,
                        ClassToolSupport.Selection selection,
                        String graphDexScope) {
        GraphRequest(String owner,
                     String name,
                     String descriptor,
                     Direction direction,
                     int maxDepth,
                     boolean stopAtExternal,
                     int offset,
                     int limit) {
            this(
                    owner,
                    name,
                    descriptor,
                    direction,
                    maxDepth,
                    stopAtExternal,
                    offset,
                    limit,
                    new ClassToolSupport.Selection(ClassToolSupport.Scope.JVM, null),
                    "all"
            );
        }

        GraphRequest(String owner,
                     String name,
                     String descriptor,
                     Direction direction,
                     int maxDepth,
                     boolean stopAtExternal,
                     int offset,
                     int limit,
                     ClassToolSupport.Selection selection) {
            this(
                    owner,
                    name,
                    descriptor,
                    direction,
                    maxDepth,
                    stopAtExternal,
                    offset,
                    limit,
                    selection,
                    "all"
            );
        }
    }

    record MethodIdentity(String owner,
                          String name,
                          String descriptor,
                          boolean resolved,
                          String origin,
                          String classScope,
                          String dexName,
                          String resourcePath) {
        MethodIdentity(String owner,
                       String name,
                       String descriptor,
                       boolean resolved,
                       String origin,
                       String classScope,
                       String dexName) {
            this(owner, name, descriptor, resolved, origin, classScope, dexName, null);
        }

        MethodIdentity(String owner,
                       String name,
                       String descriptor,
                       boolean resolved) {
            this(
                    owner,
                    name,
                    descriptor,
                    resolved,
                    resolved ? "primary" : "unresolved",
                    resolved ? ClassToolSupport.Scope.JVM.value() : null,
                    null,
                    null
            );
        }

        MethodIdentity(String owner,
                       String name,
                       String descriptor,
                       boolean resolved,
                       String classScope,
                       String dexName) {
            this(
                    owner,
                    name,
                    descriptor,
                    resolved,
                    resolved ? "primary" : "unresolved",
                    classScope,
                    dexName,
                    null
            );
        }
    }

    record GraphEdge(MethodIdentity caller, MethodIdentity callee, int depth) {
    }

    record GraphData(List<GraphEdge> edges,
                     int verticesVisited,
                     int externalVerticesStopped,
                     boolean truncated,
                     int classesSelected,
                     int methodsIndexed,
                     long instructionsScanned,
                     String graphSource,
                     List<String> indexedDexNames) {
        GraphData(List<GraphEdge> edges,
                  int verticesVisited,
                  int externalVerticesStopped,
                  boolean truncated) {
            this(
                    edges,
                    verticesVisited,
                    externalVerticesStopped,
                    truncated,
                    -1,
                    -1,
                    -1,
                    "recaf_jvm_call_graph",
                    List.of()
            );
        }

        GraphData(List<GraphEdge> edges,
                  int verticesVisited,
                  int externalVerticesStopped,
                  boolean truncated,
                  int classesSelected,
                  int methodsIndexed,
                  long instructionsScanned,
                  String graphSource) {
            this(
                    edges,
                    verticesVisited,
                    externalVerticesStopped,
                    truncated,
                    classesSelected,
                    methodsIndexed,
                    instructionsScanned,
                    graphSource,
                    List.of()
            );
        }
    }

    private record JvmWalkOutcome(Map<EdgeKey, GraphEdge> edges,
                                  Set<MethodRef> visited,
                                  Set<MethodRef> externalStopped,
                                  boolean truncated) {
    }

    private record AndroidWalkOutcome(Map<AndroidEdgeKey, GraphEdge> edges,
                                      Set<MethodIdentity> visited,
                                      Set<MethodIdentity> externalStopped,
                                      boolean truncated) {
    }

    private record EdgeKey(MethodRef caller, MethodRef callee) {
    }

    private record MethodKey(String owner, String name, String descriptor) {
    }

    private record AndroidEdgeKey(MethodIdentity caller, MethodIdentity callee) {
    }

    private record AndroidVertexDepth(MethodIdentity method, int depth) {
    }

    private record AndroidDeclarations(int classesSelected,
                                       List<String> indexedDexNames,
                                       Map<MethodKey, List<MethodIdentity>> definitions,
                                       Map<MethodIdentity, AndroidInstructionSupport.MethodCode> methods,
                                       Map<String, software.coley.recaf.info.ClassInfo> classesByName,
                                       boolean truncated) {
    }

    private record LazyAndroidForwardOutcome(Map<AndroidEdgeKey, GraphEdge> edges,
                                             Set<MethodIdentity> visited,
                                             Set<MethodIdentity> externalStopped,
                                             long instructionsScanned,
                                             boolean truncated) {
    }

    private record AndroidGraphCacheKey(int resourceIdentity,
                                        String indexDexName,
                                        String revision) {
    }

    private record CachedAndroidReverseGraph(
            java.lang.ref.WeakReference<WorkspaceResource> resourceRef,
            Map<MethodIdentity, Set<MethodIdentity>> calls,
            Map<MethodIdentity, Set<MethodIdentity>> callers,
            int classesSelected,
            int methodsIndexed,
            long instructionsScanned,
            boolean truncated,
            List<String> indexedDexNames) {
    }

    private record VertexDepth(MethodVertex vertex, int depth) {
    }

    @FunctionalInterface
    interface GraphOperation {
        GraphData run(Workspace workspace, MethodMember method, GraphRequest request);
    }
}
