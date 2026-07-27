package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.services.callgraph.CallEdge;
import software.coley.recaf.services.callgraph.MethodRef;
import software.coley.recaf.services.callgraph.MethodVertex;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallGraphToolTest {
    @Test
    void returnsStablePaginatedEdgesAndGraphMetadata() throws IOException {
        JvmClassInfo classInfo = readThisClass();
        Workspace workspace = workspace(classInfo);
        CallGraphTool.MethodIdentity root = method(
                classInfo.getName(),
                "fixtureMethod",
                "()V",
                true
        );
        CallGraphTool tool = new CallGraphTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, currentMethod, request) -> {
                    assertEquals(workspace, currentWorkspace);
                    assertEquals(CallGraphTool.Direction.CALLS, request.direction());
                    assertEquals(2, request.maxDepth());
                    assertTrue(request.stopAtExternal());
                    return new CallGraphTool.GraphData(
                            List.of(
                                    new CallGraphTool.GraphEdge(
                                            root,
                                            method("z/Z", "last", "()V", false),
                                            2
                                    ),
                                    new CallGraphTool.GraphEdge(
                                            root,
                                            method("a/A", "first", "()V", true),
                                            1
                                    )
                            ),
                            3,
                            1,
                            false
                    );
                }
        );
        JsonObject arguments = arguments(classInfo);
        arguments.addProperty("direction", "calls");
        arguments.addProperty("max_depth", 2);
        arguments.addProperty("offset", 1);
        arguments.addProperty("limit", 1);

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");
        JsonArray edges = structured.getAsJsonArray("edges");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals(2, structured.get("total").getAsInt());
        assertEquals(1, structured.get("returned").getAsInt());
        assertEquals(3, structured.get("vertices_visited").getAsInt());
        assertEquals(1, structured.get("external_vertices_stopped").getAsInt());
        assertTrue(structured.get("stop_at_external").getAsBoolean());
        assertFalse(structured.get("graph_truncated").getAsBoolean());
        assertEquals("z/Z", edges.get(0).getAsJsonObject()
                .getAsJsonObject("callee").get("owner").getAsString());
        assertTrue(structured.get("next_offset").isJsonNull());
        JsonObject limit = tool.inputSchema().getAsJsonObject("properties")
                .getAsJsonObject("limit");
        assertEquals(10, limit.get("default").getAsInt());
        assertEquals(200, limit.get("maximum").getAsInt());
    }

    @Test
    void validatesExactMethodAndBoundsBeforeGraphConstruction() throws IOException {
        JvmClassInfo classInfo = readThisClass();
        Workspace workspace = workspace(classInfo);
        CallGraphTool tool = new CallGraphTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, currentMethod, request) -> {
                    throw new AssertionError("graph must not run");
                }
        );

        JsonObject wrongDescriptor = arguments(classInfo);
        wrongDescriptor.addProperty("descriptor", "(I)V");
        assertTrue(tool.execute(wrongDescriptor).get("isError").getAsBoolean());

        JsonObject badDirection = arguments(classInfo);
        badDirection.addProperty("direction", "sideways");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(badDirection));

        JsonObject tooDeep = arguments(classInfo);
        tooDeep.addProperty("max_depth", 6);
        assertThrows(IllegalArgumentException.class, () -> tool.execute(tooDeep));
    }

    @Test
    void stopsAtUnresolvedBoundaryUnlessExplicitlyDisabled() throws IOException {
        JvmClassInfo classInfo = readThisClass();
        MethodMember resolvedMethod = classInfo.getDeclaredMethod("fixtureMethod", "()V");
        FakeVertex root = new FakeVertex(
                new MethodRef(classInfo.getName(), "fixtureMethod", "()V"),
                resolvedMethod
        );
        FakeVertex external = new FakeVertex(
                new MethodRef("java/util/List", "size", "()I"),
                null
        );
        FakeVertex downstream = new FakeVertex(
                new MethodRef("fixture/Unexpected", "fanout", "()V"),
                resolvedMethod
        );
        root.calls.add(external);
        external.calls.add(downstream);

        CallGraphTool.GraphData bounded = CallGraphTool.walkGraph(root, request(true));
        assertEquals(1, bounded.edges().size());
        assertEquals(1, bounded.verticesVisited());
        assertEquals(1, bounded.externalVerticesStopped());
        assertFalse(bounded.truncated());
        assertEquals("java/util/List", bounded.edges().get(0).callee().owner());
        assertFalse(bounded.edges().get(0).callee().resolved());

        CallGraphTool.GraphData expanded = CallGraphTool.walkGraph(root, request(false));
        assertEquals(2, expanded.edges().size());
        assertEquals(3, expanded.verticesVisited());
        assertEquals(0, expanded.externalVerticesStopped());
    }

    private static CallGraphTool.GraphRequest request(boolean stopAtExternal) {
        return new CallGraphTool.GraphRequest(
                "fixture/Root",
                "root",
                "()V",
                CallGraphTool.Direction.CALLS,
                3,
                stopAtExternal,
                0,
                100
        );
    }

    private static JsonObject arguments(JvmClassInfo classInfo) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("owner", classInfo.getName());
        arguments.addProperty("name", "fixtureMethod");
        arguments.addProperty("descriptor", "()V");
        return arguments;
    }

    private static CallGraphTool.MethodIdentity method(String owner,
                                                       String name,
                                                       String descriptor,
                                                       boolean resolved) {
        return new CallGraphTool.MethodIdentity(owner, name, descriptor, resolved);
    }

    private static Workspace workspace(JvmClassInfo classInfo) {
        BasicJvmClassBundle bundle = new BasicJvmClassBundle();
        bundle.put(classInfo);
        WorkspaceResource primary = new WorkspaceResourceBuilder()
                .withJvmClassBundle(bundle)
                .build();
        return new BasicWorkspace(primary, List.of(), false);
    }

    private static JvmClassInfo readThisClass() throws IOException {
        String resourceName = "/" + CallGraphToolTest.class.getName()
                .replace('.', '/') + ".class";
        try (InputStream input = CallGraphToolTest.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("test class resource is missing");
            }
            return new JvmClassInfoBuilder(input.readAllBytes()).build();
        }
    }

    @SuppressWarnings("unused")
    private static void fixtureMethod() {
    }

    private static final class FakeVertex implements MethodVertex {
        private final MethodRef method;
        private final MethodMember resolvedMethod;
        private final List<MethodVertex> callers = new ArrayList<>();
        private final List<MethodVertex> calls = new ArrayList<>();

        private FakeVertex(MethodRef method, MethodMember resolvedMethod) {
            this.method = method;
            this.resolvedMethod = resolvedMethod;
        }

        @Override
        public MethodRef getMethod() {
            return method;
        }

        @Override
        public MethodMember getResolvedMethod() {
            return resolvedMethod;
        }

        @Override
        public Collection<MethodVertex> getCallers() {
            return callers;
        }

        @Override
        public Collection<MethodVertex> getCalls() {
            return calls;
        }

        @Override
        public Collection<CallEdge> getCallerEdges() {
            return List.of();
        }

        @Override
        public Collection<CallEdge> getCallEdges() {
            return List.of();
        }
    }
}
