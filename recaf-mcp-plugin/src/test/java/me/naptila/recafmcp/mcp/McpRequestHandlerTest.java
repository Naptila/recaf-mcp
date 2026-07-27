package me.naptila.recafmcp.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.naptila.recafmcp.mcp.internal.HealthHandler;
import me.naptila.recafmcp.mcp.internal.HeadlessServicesProbe;
import me.naptila.recafmcp.mcp.internal.SaveWorkspaceHandler;
import me.naptila.recafmcp.mcp.internal.ShutdownHandler;
import me.naptila.recafmcp.mcp.tools.WorkspaceInfoTool;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpRequestHandlerTest {
    private final WorkerHealth health = new WorkerHealth(
            () -> false, null, "1.0.0", "4.0.0-SNAPSHOT", "test-commit");
    private final McpToolRegistry toolRegistry = new McpToolRegistry(List.of(
            new WorkspaceInfoTool(() -> false, () -> null)
    ));
    private final WorkerOperationCoordinator operationCoordinator = new WorkerOperationCoordinator();
    private final AtomicBoolean shutdownRequested = new AtomicBoolean();
    private final McpRequestHandler handler = newHandler(
            health,
            operationCoordinator,
            null,
            (destination, overwrite) -> new JsonObject(),
            () -> shutdownRequested.set(true)
    );

    @Test
    void toolsListIsStableAndContainsOnlyPublicSchema() {
        String first = handler.handle("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """);
        String second = handler.handle("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """);

        JsonObject firstResult = parse(first).getAsJsonObject("result");
        JsonObject secondResult = parse(second).getAsJsonObject("result");
        assertEquals(firstResult, secondResult);
        assertEquals("workspace_info", firstResult.getAsJsonArray("tools")
                .get(0).getAsJsonObject().get("name").getAsString());

        String catalog = firstResult.toString();
        assertFalse(catalog.contains("database"));
        assertFalse(catalog.contains("health"));
        assertFalse(catalog.contains("save_workspace"));
        assertFalse(catalog.contains("cancel_operation"));
        assertFalse(catalog.contains("shutdown"));
        assertFalse(catalog.contains("probe/headless_services"));
        assertFalse(catalog.contains("probe/atomic_export"));
    }

    @Test
    void workspaceToolReturnsExplicitErrorWithoutWorkspace() {
        JsonObject response = parse(handler.handle("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"workspace_info","arguments":{}}}
                """));

        JsonObject result = response.getAsJsonObject("result");
        assertTrue(result.get("isError").getAsBoolean());
        assertFalse(result.getAsJsonObject("structuredContent")
                .get("workspace_open").getAsBoolean());
        assertEquals("workspace not open", result.getAsJsonObject("structuredContent")
                .get("error").getAsString());
    }

    @Test
    void toolArgumentErrorsPreserveSafeDiagnosticMessages() {
        JsonObject response = parse(handler.handle("""
                {"jsonrpc":"2.0","id":15,"method":"tools/call","params":{"name":"workspace_info","arguments":{"unexpected":true}}}
                """));

        JsonObject error = response.getAsJsonObject("error");
        assertEquals(-32602, error.get("code").getAsInt());
        assertEquals(
                "Invalid params: workspace_info does not accept arguments",
                error.get("message").getAsString()
        );
    }

    @Test
    void healthIsInternalAndReportsSchemaState() {
        JsonObject response = parse(handler.handle("""
                {"jsonrpc":"2.0","id":4,"method":"health","params":{}}
                """));

        JsonObject result = response.getAsJsonObject("result");
        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(ProcessHandle.current().pid(), result.get("pid").getAsLong());
        assertTrue(result.get("ready").getAsBoolean());
        assertFalse(result.get("workspace_open").getAsBoolean());
        assertFalse(result.get("workspace_ready").getAsBoolean());
        assertFalse(result.get("busy").getAsBoolean());
        assertFalse(result.get("closing").getAsBoolean());
        assertTrue(result.get("expected_input_path").isJsonNull());
        assertTrue(result.get("input_path").isJsonNull());
        assertEquals("1.0.0", result.get("plugin_build").getAsString());
        assertEquals("4.0.0-SNAPSHOT", result.get("recaf_version").getAsString());
        assertEquals("test-commit", result.get("recaf_commit").getAsString());
        assertEquals(handler.schemaFingerprint(),
                result.get("schema_fingerprint").getAsString());
        assertTrue(result.get("last_error").isJsonNull());
    }

    @Test
    void headlessServicesProbeIsInternalAndRequiresReadyWorkspace() {
        AtomicBoolean workspaceOpen = new AtomicBoolean(true);
        WorkerHealth readyHealth = new WorkerHealth(
                workspaceOpen::get, "/input.jar", "1.0.0", "4.0.0", "commit");
        readyHealth.markWorkspaceReady("/input.jar");
        HeadlessServicesProbe probe = (className, searchText) -> {
            JsonObject result = new JsonObject();
            result.addProperty("ok", "example/Foo".equals(className) && "needle".equals(searchText));
            return result;
        };
        McpRequestHandler readyHandler = newHandler(
                readyHealth,
                new WorkerOperationCoordinator(),
                probe,
                (destination, overwrite) -> new JsonObject(),
                () -> {}
        );

        JsonObject response = parse(readyHandler.handle("""
                {"jsonrpc":"2.0","id":5,"method":"probe/headless_services","params":{"class_name":"example/Foo","search_text":"needle"}}
                """));

        assertTrue(response.getAsJsonObject("result").get("ok").getAsBoolean());

        JsonObject notReadyResponse = parse(handler.handle("""
                {"jsonrpc":"2.0","id":6,"method":"probe/headless_services","params":{"class_name":"example/Foo","search_text":"needle"}}
                """));
        assertEquals(-32601, notReadyResponse.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void saveWorkspaceIsInternalAndRequiresReadyWorkspace() {
        AtomicBoolean workspaceOpen = new AtomicBoolean(true);
        WorkerHealth readyHealth = new WorkerHealth(
                workspaceOpen::get, "/input.jar", "1.0.0", "4.0.0", "commit");
        readyHealth.markWorkspaceReady("/input.jar");
        SaveWorkspaceHandler saveHandler = (destination, overwrite) -> {
            JsonObject result = new JsonObject();
            result.addProperty("ok", "/output.jar".equals(destination) && overwrite);
            return result;
        };
        McpRequestHandler readyHandler = newHandler(
                readyHealth,
                new WorkerOperationCoordinator(),
                null,
                saveHandler,
                () -> {}
        );

        JsonObject response = parse(readyHandler.handle("""
                {"jsonrpc":"2.0","id":7,"method":"save_workspace","params":{"destination":"/output.jar","overwrite":true}}
                """));

        assertTrue(response.getAsJsonObject("result").get("ok").getAsBoolean());

        JsonObject notReadyResponse = parse(handler.handle("""
                {"jsonrpc":"2.0","id":8,"method":"save_workspace","params":{"destination":"/output.jar","overwrite":true}}
                """));
        assertEquals(-32002, notReadyResponse.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void saveFailureIsActionableAndCanBeRetried() {
        AtomicBoolean workspaceOpen = new AtomicBoolean(true);
        WorkerHealth readyHealth = new WorkerHealth(
                workspaceOpen::get, "/input.jar", "1.0.0", "4.0.0", "commit");
        readyHealth.markWorkspaceReady("/input.jar");
        AtomicBoolean fail = new AtomicBoolean(true);
        SaveWorkspaceHandler saveHandler = (destination, overwrite) -> {
            if (fail.getAndSet(false)) {
                throw new IOException("destination is read-only");
            }
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            return result;
        };
        McpRequestHandler readyHandler = newHandler(
                readyHealth,
                new WorkerOperationCoordinator(),
                null,
                saveHandler,
                () -> {}
        );
        String request = """
                {"jsonrpc":"2.0","id":9,"method":"save_workspace","params":{"destination":"/output.jar","overwrite":true}}
                """;

        JsonObject failed = parse(readyHandler.handle(request));
        assertEquals(-32011, failed.getAsJsonObject("error").get("code").getAsInt());
        assertTrue(failed.getAsJsonObject("error").get("message").getAsString()
                .contains("destination is read-only"));
        assertTrue(readyHealth.isReady());
        assertTrue(readyHealth.getLastError().contains("destination is read-only"));

        JsonObject retried = parse(readyHandler.handle(request));
        assertTrue(retried.getAsJsonObject("result").get("ok").getAsBoolean());
        assertTrue(readyHealth.isReady());
        assertNull(readyHealth.getLastError());
    }

    @Test
    void shutdownRunsOnlyAfterResponseAndClosesNewOperations() {
        McpRequestHandler.DispatchResult response = handler.dispatch("""
                {"jsonrpc":"2.0","id":10,"method":"shutdown","params":{}}
                """);

        assertTrue(parse(response.body()).getAsJsonObject("result").get("ok").getAsBoolean());
        assertFalse(shutdownRequested.get());
        assertTrue(operationCoordinator.isClosing());

        JsonObject rejected = parse(handler.handle("""
                {"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"workspace_info","arguments":{}}}
                """));
        assertEquals(-32003, rejected.getAsJsonObject("error").get("code").getAsInt());

        response.afterResponse().run();
        assertTrue(shutdownRequested.get());
    }

    @Test
    void exposesOnlyInternalCancellationAndUsesBoundedToolTimeouts() {
        JsonObject response = parse(handler.handle("""
                {"jsonrpc":"2.0","id":12,"method":"cancel_operation","params":{"operation_id":"missing"}}
                """));

        assertTrue(response.getAsJsonObject("result").get("ok").getAsBoolean());
        assertFalse(response.getAsJsonObject("result").get("cancelled").getAsBoolean());
        assertEquals(15, McpRequestHandler.timeoutSeconds("workspace_info"));
        assertEquals(15, McpRequestHandler.timeoutSeconds("list_files"));
        assertEquals(60, McpRequestHandler.timeoutSeconds("read_file"));
        assertEquals(60, McpRequestHandler.timeoutSeconds("search_instructions"));
        assertEquals(60, McpRequestHandler.timeoutSeconds("search_members"));
        assertEquals(60, McpRequestHandler.timeoutSeconds("rename_symbol"));
        assertEquals(120, McpRequestHandler.timeoutSeconds("call_graph"));
        assertEquals(300, McpRequestHandler.timeoutSeconds("patch_class"));
        assertEquals(60, McpRequestHandler.timeoutSeconds("get_method_assembly"));
        assertEquals(120, McpRequestHandler.timeoutSeconds("patch_instructions"));
        assertEquals(15, McpRequestHandler.timeoutSeconds("get_recaf_script_help"));
        assertEquals(300, McpRequestHandler.timeoutSeconds("run_recaf_script"));
        assertEquals(300, McpRequestHandler.timeoutSeconds("apply_mappings"));
        assertEquals(60, McpRequestHandler.timeoutSeconds("export_mappings"));
        assertEquals(1800, McpRequestHandler.timeoutSeconds("export_sources"));
        assertEquals(60, McpRequestHandler.timeoutSeconds("future_tool"));
    }

    @Test
    void internalCancellationInterruptsAnActiveNamedToolCall() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        McpTool blocking = new McpTool() {
            @Override
            public String name() {
                return "blocking_tool";
            }

            @Override
            public String description() {
                return "Block until the test cancels this operation.";
            }

            @Override
            public JsonObject inputSchema() {
                JsonObject schema = new JsonObject();
                schema.addProperty("type", "object");
                schema.add("properties", new JsonObject());
                schema.addProperty("additionalProperties", false);
                return schema;
            }

            @Override
            public JsonObject execute(JsonObject arguments) {
                started.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("cancelled");
                }
                return McpTool.successResult("unexpected", new JsonObject());
            }
        };
        McpToolRegistry registry = new McpToolRegistry(List.of(blocking));
        WorkerOperationCoordinator coordinator = new WorkerOperationCoordinator();
        McpRequestHandler concurrentHandler = new McpRequestHandler(
                health,
                registry,
                coordinator,
                new HealthHandler(health, registry, coordinator),
                null,
                (destination, overwrite) -> new JsonObject(),
                new ShutdownHandler(coordinator, () -> {})
        );
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> call = executor.submit(() -> concurrentHandler.handle("""
                    {"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"blocking_tool","operation_id":"active-operation","arguments":{}}}
                    """));
            assertTrue(started.await(5, TimeUnit.SECONDS));

            JsonObject cancelled = parse(concurrentHandler.handle("""
                    {"jsonrpc":"2.0","id":14,"method":"cancel_operation","params":{"operation_id":"active-operation"}}
                    """));

            assertTrue(cancelled.getAsJsonObject("result")
                    .get("cancelled").getAsBoolean());
            JsonObject callResponse = parse(call.get(5, TimeUnit.SECONDS));
            assertEquals(-32005, callResponse.getAsJsonObject("error")
                    .get("code").getAsInt());
            for (int attempt = 0; attempt < 100 && coordinator.isBusy(); attempt++) {
                Thread.sleep(10);
            }
            assertFalse(coordinator.isBusy());
        } finally {
            coordinator.close();
            executor.shutdownNow();
        }
    }

    @Test
    void validatesJsonRpcEnvelopeAndResponseIds() {
        assertRpcError("{", -32700, true);
        assertRpcError("[]", -32600, true);
        assertRpcError("{\"jsonrpc\":\"2.0\",\"method\":\"health\"}", -32600, true);
        assertRpcError(
                "{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"health\"}",
                -32600,
                true
        );
        assertRpcError(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"health\",\"params\":1}",
                -32600,
                false
        );
        assertRpcError(
                "{\"jsonrpc\":2,\"id\":1,\"method\":\"health\"}",
                -32600,
                false
        );

        JsonObject unknown = parse(handler.handle("""
                {"jsonrpc":"2.0","id":"request-id","method":"unknown","params":{}}
                """));
        assertEquals("request-id", unknown.get("id").getAsString());
        assertEquals(-32601, unknown.getAsJsonObject("error").get("code").getAsInt());
    }

    private void assertRpcError(String request, int expectedCode, boolean nullId) {
        JsonObject response = parse(handler.handle(request));
        assertEquals("2.0", response.get("jsonrpc").getAsString());
        assertEquals(expectedCode, response.getAsJsonObject("error").get("code").getAsInt());
        assertEquals(nullId, response.get("id").isJsonNull());
    }

    private McpRequestHandler newHandler(WorkerHealth workerHealth,
                                         WorkerOperationCoordinator coordinator,
                                         HeadlessServicesProbe headlessProbe,
                                         SaveWorkspaceHandler saveHandler,
                                         Runnable shutdownAction) {
        return new McpRequestHandler(
                workerHealth,
                toolRegistry,
                coordinator,
                new HealthHandler(workerHealth, toolRegistry, coordinator),
                headlessProbe,
                saveHandler,
                new ShutdownHandler(coordinator, shutdownAction)
        );
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
