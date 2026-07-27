package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.services.callgraph.CallGraph;
import software.coley.recaf.services.callgraph.CallGraphService;
import software.coley.recaf.services.callgraph.MethodRef;
import software.coley.recaf.services.callgraph.MethodVertex;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
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
    private static final Set<String> ARGUMENTS = Set.of(
            "owner",
            "name",
            "descriptor",
            "direction",
            "max_depth",
            "stop_at_external",
            "scope",
            "dex_name",
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
                    return buildGraph(graph, method, request);
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
        List<ClassToolSupport.Entry> matches = ClassToolSupport.exact(
                primary,
                request.selection(),
                request.owner()
        );
        if (matches.isEmpty()) {
            return error("owner class not found in requested primary scope", request);
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
                return error("method with exact descriptor not found in owner class", request);
            }
            rootIdentity = androidIdentity(method);
            outputScope = "primary_android_dex_graph";
            try {
                graph = buildAndroidGraph(primary, rootIdentity, request);
            } catch (Exception ex) {
                ToolCancellation.propagateIfRequested(ex);
                return error("Android call graph failed: " + exceptionMessage(ex), request);
            }
        } else {
            JvmClassInfo owner = rootEntry.classInfo().asJvmClass();
            MethodMember method = owner.getDeclaredMethod(
                    request.name(),
                    request.descriptor()
            );
            if (method == null) {
                return error("method with exact descriptor not found in owner class", request);
            }
            rootIdentity = new MethodIdentity(
                    request.owner(),
                    request.name(),
                    request.descriptor(),
                    true,
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
        }
        addPageMetadata(structured, request, ordered.size(), end - start);
        return McpTool.successResult(
                "found " + ordered.size() + " call graph edges",
                structured
        );
    }

    private static GraphData buildGraph(CallGraph graph,
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

        return walkGraph(root, request);
    }

    private static GraphData buildAndroidGraph(WorkspaceResource primary,
                                               MethodIdentity root,
                                               GraphRequest request) {
        List<ClassToolSupport.Entry> classes = ClassToolSupport.entries(
                primary,
                request.selection()
        );
        Map<MethodKey, List<MethodIdentity>> definitions = new HashMap<>();
        Map<MethodIdentity, AndroidInstructionSupport.MethodCode> methods =
                new HashMap<>();
        boolean truncated = false;
        for (ClassToolSupport.Entry entry : classes) {
            ToolCancellation.check();
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
            AndroidInstructionSupport.MethodCode rootMethod = AndroidInstructionSupport.exact(
                    ClassToolSupport.exact(
                            primary,
                            request.selection(),
                            root.owner()
                    ).stream()
                            .filter(entry -> Objects.equals(entry.dexName(), root.dexName()))
                            .findFirst()
                            .orElseThrow(),
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

        Map<MethodIdentity, Set<MethodIdentity>> calls = new HashMap<>();
        Map<MethodIdentity, Set<MethodIdentity>> callers = new HashMap<>();
        long instructionsScanned = 0;
        List<Map.Entry<MethodIdentity, AndroidInstructionSupport.MethodCode>> orderedMethods =
                new ArrayList<>(methods.entrySet());
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
                        definitions
                );
                calls.computeIfAbsent(caller, ignored -> new HashSet<>()).add(callee);
                callers.computeIfAbsent(callee, ignored -> new HashSet<>()).add(caller);
            }
        }
        GraphData walked = walkAndroidGraph(root, calls, callers, request);
        return new GraphData(
                walked.edges(),
                walked.verticesVisited(),
                walked.externalVerticesStopped(),
                truncated || walked.truncated(),
                classes.size(),
                methods.size(),
                instructionsScanned,
                "dex_invoke_instructions"
        );
    }

    private static GraphData walkAndroidGraph(
            MethodIdentity root,
            Map<MethodIdentity, Set<MethodIdentity>> calls,
            Map<MethodIdentity, Set<MethodIdentity>> callers,
            GraphRequest request) {
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
            if (request.direction() != Direction.CALLERS) {
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
            }
            if (truncated) {
                break;
            }
            if (request.direction() != Direction.CALLS) {
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
        return new GraphData(
                List.copyOf(edges.values()),
                visited.size(),
                externalVerticesStopped.size(),
                truncated
        );
    }

    private static MethodIdentity resolveAndroidReference(
            MethodIdentity caller,
            AndroidInstructionSupport.MethodReference reference,
            Map<MethodKey, List<MethodIdentity>> definitions) {
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
        return new MethodIdentity(
                reference.owner(),
                reference.name(),
                reference.descriptor(),
                false,
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
        return new MethodIdentity(
                method.owner().className(),
                method.name(),
                method.descriptor(),
                true,
                ClassToolSupport.Scope.ANDROID.value(),
                method.owner().dexName()
        );
    }

    private static MethodKey key(MethodIdentity method) {
        return new MethodKey(method.owner(), method.name(), method.descriptor());
    }

    static GraphData walkGraph(MethodVertex root, GraphRequest request) {
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
            if (request.direction() != Direction.CALLERS) {
                for (MethodVertex callee : current.vertex().getCalls()) {
                    if (addEdge(edges, current.vertex(), callee, current.depth() + 1)
                            && edges.size() >= MAX_COLLECTED_EDGES) {
                        truncated = true;
                        break;
                    }
                    if (stopAtExternal(request, callee)) {
                        externalVerticesStopped.add(callee.getMethod());
                    } else if (enqueue(pending, visited, callee, current.depth() + 1)) {
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
            if (request.direction() != Direction.CALLS) {
                for (MethodVertex caller : current.vertex().getCallers()) {
                    if (addEdge(edges, caller, current.vertex(), current.depth() + 1)
                            && edges.size() >= MAX_COLLECTED_EDGES) {
                        truncated = true;
                        break;
                    }
                    if (stopAtExternal(request, caller)) {
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
        return new GraphData(
                List.copyOf(edges.values()),
                visited.size(),
                externalVerticesStopped.size(),
                truncated
        );
    }

    private static boolean stopAtExternal(GraphRequest request, MethodVertex vertex) {
        return request.stopAtExternal() && vertex.getResolvedMethod() == null;
    }

    private static boolean addEdge(Map<EdgeKey, GraphEdge> edges,
                                   MethodVertex caller,
                                   MethodVertex callee,
                                   int depth) {
        MethodIdentity callerIdentity = identity(caller);
        MethodIdentity calleeIdentity = identity(callee);
        EdgeKey key = new EdgeKey(caller.getMethod(), callee.getMethod());
        GraphEdge existing = edges.get(key);
        if (existing == null || depth < existing.depth()) {
            edges.put(key, new GraphEdge(callerIdentity, calleeIdentity, depth));
            return existing == null;
        }
        return false;
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

    private static MethodIdentity identity(MethodVertex vertex) {
        MethodRef ref = vertex.getMethod();
        boolean resolved = vertex.getResolvedMethod() != null;
        return new MethodIdentity(
                ref.owner(),
                ref.name(),
                ref.desc(),
                resolved,
                resolved ? ClassToolSupport.Scope.JVM.value() : null,
                null
        );
    }

    private static GraphRequest parseRequest(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(
                arguments,
                false
        );
        String owner = ToolArguments.requiredString(arguments, "owner");
        String name = ToolArguments.requiredString(arguments, "name");
        String descriptor = ToolArguments.requiredString(arguments, "descriptor");
        String directionName = ToolArguments.optionalString(arguments, "direction");
        Direction direction = Direction.fromJsonName(directionName == null ? "both" : directionName);
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
                selection
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
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private static JsonObject createInputSchema() {
        JsonObject properties = new JsonObject();
        properties.add("owner", stringProperty(
                "Exact primary JVM or Android owner internal name."
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
                "Keep unresolved edges but do not expand their vertices."
        );
        properties.add("stop_at_external", stopAtExternal);
        ClassToolSupport.addScopeProperties(properties, false);
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
                        ClassToolSupport.Selection selection) {
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
                    new ClassToolSupport.Selection(ClassToolSupport.Scope.JVM, null)
            );
        }
    }

    record MethodIdentity(String owner,
                          String name,
                          String descriptor,
                          boolean resolved,
                          String classScope,
                          String dexName) {
        MethodIdentity(String owner,
                       String name,
                       String descriptor,
                       boolean resolved) {
            this(
                    owner,
                    name,
                    descriptor,
                    resolved,
                    resolved ? ClassToolSupport.Scope.JVM.value() : null,
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
                     String graphSource) {
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
                    "recaf_jvm_call_graph"
            );
        }
    }

    private record EdgeKey(MethodRef caller, MethodRef callee) {
    }

    private record MethodKey(String owner, String name, String descriptor) {
    }

    private record AndroidEdgeKey(MethodIdentity caller, MethodIdentity callee) {
    }

    private record AndroidVertexDepth(MethodIdentity method, int depth) {
    }

    private record VertexDepth(MethodVertex vertex, int depth) {
    }

    @FunctionalInterface
    interface GraphOperation {
        GraphData run(Workspace workspace, MethodMember method, GraphRequest request);
    }
}
