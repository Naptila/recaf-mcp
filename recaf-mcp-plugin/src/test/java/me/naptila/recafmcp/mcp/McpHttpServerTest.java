package me.naptila.recafmcp.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.naptila.recafmcp.mcp.internal.HealthHandler;
import me.naptila.recafmcp.mcp.internal.SaveWorkspaceHandler;
import me.naptila.recafmcp.mcp.internal.ShutdownHandler;
import me.naptila.recafmcp.mcp.tools.WorkspaceInfoTool;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpHttpServerTest {
    private static final String TOKEN = "m2.1-test-token-0123456789-abcdef";

    @Test
    void bindsLoopbackDynamicPortAndRequiresBearerToken() throws IOException, InterruptedException {
        WorkerHealth health = new WorkerHealth(
                () -> false, null, "1.0.0", "4.0.0-SNAPSHOT", "test-commit");
        McpHttpServer server = new McpHttpServer(0, TOKEN, testHandler(health));
        server.start();
        try {
            assertEquals("127.0.0.1", server.getAddress().getHostAddress());
            assertNotEquals(0, server.getPort());

            URI endpoint = URI.create("http://127.0.0.1:" + server.getPort() + "/jsonrpc");
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest unauthorized = HttpRequest.newBuilder(endpoint)
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            assertEquals(401, client.send(unauthorized, HttpResponse.BodyHandlers.ofString()).statusCode());

            HttpRequest authorized = HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"health\",\"params\":{}}"))
                    .build();
            HttpResponse<String> response = client.send(authorized, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(response.body().contains("\"workspace_open\":false"));
        } finally {
            server.stop();
        }
    }

    @Test
    void enforcesTransportPathMediaTypeEncodingAndBodyLimit() throws IOException, InterruptedException {
        WorkerHealth health = new WorkerHealth(
                () -> false, null, "1.0.0", "4.0.0-SNAPSHOT", "test-commit");
        McpHttpServer server = new McpHttpServer(0, TOKEN, testHandler(health));
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getPort() + "/jsonrpc");
            HttpClient client = HttpClient.newHttpClient();

            HttpRequest get = HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + TOKEN)
                    .GET()
                    .build();
            HttpResponse<String> getResponse = client.send(get, HttpResponse.BodyHandlers.ofString());
            assertEquals(405, getResponse.statusCode());
            assertEquals("POST", getResponse.headers().firstValue("Allow").orElseThrow());

            HttpRequest wrongPath = jsonRequest(endpoint.resolve("/jsonrpc/extra"), "{}")
                    .build();
            assertEquals(404, client.send(wrongPath, HttpResponse.BodyHandlers.ofString()).statusCode());

            HttpRequest missingContentType = HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + TOKEN)
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            assertEquals(415, client.send(
                    missingContentType, HttpResponse.BodyHandlers.ofString()).statusCode());

            HttpRequest encoded = jsonRequest(endpoint, "{}")
                    .header("Content-Encoding", "gzip")
                    .build();
            assertEquals(415, client.send(encoded, HttpResponse.BodyHandlers.ofString()).statusCode());

            HttpRequest invalidUtf8 = HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{(byte) 0xC3, 0x28}))
                    .build();
            assertEquals(400, client.send(
                    invalidUtf8, HttpResponse.BodyHandlers.ofString()).statusCode());

            String oversizedBody = "x".repeat(McpHttpServer.MAX_REQUEST_BYTES + 1);
            HttpRequest oversized = jsonRequest(endpoint, oversizedBody).build();
            assertEquals(413, client.send(oversized, HttpResponse.BodyHandlers.ofString()).statusCode());

            HttpRequest streamedOversized = HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(unknownLengthBody(oversizedBody))
                    .build();
            assertEquals(413, client.send(
                    streamedOversized, HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            server.stop();
        }
    }

    @Test
    void healthRemainsResponsiveWhileWorkspaceOperationIsBusy() throws Exception {
        AtomicBoolean workspaceOpen = new AtomicBoolean(true);
        WorkerHealth health = new WorkerHealth(
                workspaceOpen::get, "/input.jar", "1.0.0", "4.0.0", "commit");
        health.markWorkspaceReady("/input.jar");
        WorkerOperationCoordinator coordinator = new WorkerOperationCoordinator();
        CountDownLatch saveStarted = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        SaveWorkspaceHandler saveHandler = (destination, overwrite) -> {
            saveStarted.countDown();
            if (!releaseSave.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test save was not released");
            }
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            return result;
        };
        McpRequestHandler handler = testHandler(health, coordinator, saveHandler, () -> {});
        McpHttpServer server = new McpHttpServer(0, TOKEN, handler);
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getPort() + "/jsonrpc");
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest saveRequest = jsonRequest(endpoint, """
                    {"jsonrpc":"2.0","id":9,"method":"save_workspace","params":{"destination":"/output.jar","overwrite":true}}
                    """).build();
            CompletableFuture<HttpResponse<String>> saveFuture = client.sendAsync(
                    saveRequest, HttpResponse.BodyHandlers.ofString());
            assertTrue(saveStarted.await(5, TimeUnit.SECONDS));

            HttpRequest healthRequest = jsonRequest(endpoint, """
                    {"jsonrpc":"2.0","id":10,"method":"health","params":{}}
                    """).build();
            HttpResponse<String> healthResponse = client.send(
                    healthRequest, HttpResponse.BodyHandlers.ofString());
            JsonObject healthResult = JsonParser.parseString(healthResponse.body())
                    .getAsJsonObject()
                    .getAsJsonObject("result");
            assertTrue(healthResult.get("busy").getAsBoolean());
            assertFalse(healthResult.get("closing").getAsBoolean());

            releaseSave.countDown();
            assertEquals(200, saveFuture.get(5, TimeUnit.SECONDS).statusCode());
        } finally {
            releaseSave.countDown();
            server.stop();
        }
    }

    @Test
    void rejectsWeakOrMalformedBearerTokens() {
        WorkerHealth health = new WorkerHealth(
                () -> false, null, "1.0.0", "4.0.0-SNAPSHOT", "test-commit");

        assertThrows(IllegalArgumentException.class, () ->
                new McpHttpServer(0, "short-token", testHandler(health))
        );
        assertThrows(IllegalArgumentException.class, () ->
                new McpHttpServer(
                        0,
                        "valid-length-but contains-a-space-000",
                        testHandler(health)
                )
        );
    }

    private static HttpRequest.Builder jsonRequest(URI endpoint, String body) {
        return HttpRequest.newBuilder(endpoint)
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private static McpToolRegistry testRegistry() {
        return new McpToolRegistry(List.of(
                new WorkspaceInfoTool(() -> false, () -> null)
        ));
    }

    private static McpRequestHandler testHandler(WorkerHealth health) {
        return testHandler(
                health,
                new WorkerOperationCoordinator(),
                (destination, overwrite) -> new JsonObject(),
                () -> {}
        );
    }

    private static McpRequestHandler testHandler(WorkerHealth health,
                                                 WorkerOperationCoordinator coordinator,
                                                 SaveWorkspaceHandler saveHandler,
                                                 Runnable shutdownAction) {
        McpToolRegistry registry = testRegistry();
        return new McpRequestHandler(
                health,
                registry,
                coordinator,
                new HealthHandler(health, registry, coordinator),
                null,
                saveHandler,
                new ShutdownHandler(coordinator, shutdownAction)
        );
    }

    private static HttpRequest.BodyPublisher unknownLengthBody(String body) {
        HttpRequest.BodyPublisher delegate = HttpRequest.BodyPublishers.ofString(body);
        return new HttpRequest.BodyPublisher() {
            @Override
            public long contentLength() {
                return -1;
            }

            @Override
            public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
                delegate.subscribe(subscriber);
            }
        };
    }
}
