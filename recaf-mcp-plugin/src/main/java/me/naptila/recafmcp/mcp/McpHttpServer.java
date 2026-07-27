package me.naptila.recafmcp.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Authenticated loopback HTTP transport for one Recaf Worker.
 */
public final class McpHttpServer {
    static final int MAX_REQUEST_BYTES = 1024 * 1024;
    static final int MIN_TOKEN_LENGTH = 32;
    private static final int MAX_TOKEN_LENGTH = 512;
    private static final String ENDPOINT_PATH = "/jsonrpc";

    private final HttpServer server;
    private final ExecutorService executor;
    private final String expectedAuthorization;
    private final McpRequestHandler requestHandler;

    public McpHttpServer(int port,
                         String token,
                         McpRequestHandler requestHandler)
            throws IOException {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        if (!isValidToken(token)) {
            throw new IllegalArgumentException(
                    "token must contain 32 to 512 ASCII bearer-token characters"
            );
        }

        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        server = HttpServer.create(new InetSocketAddress(loopback, port), 0);
        expectedAuthorization = "Bearer " + token;
        this.requestHandler = Objects.requireNonNull(requestHandler, "requestHandler");
        executor = Executors.newFixedThreadPool(
                4,
                Thread.ofPlatform().daemon(true).name("recaf-mcp-http-", 0).factory()
        );
        server.setExecutor(executor);
        server.createContext(ENDPOINT_PATH, this::handleExchange);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    public InetAddress getAddress() {
        return server.getAddress().getAddress();
    }

    private void handleExchange(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!ENDPOINT_PATH.equals(exchange.getRequestURI().getPath())
                    || exchange.getRequestURI().getRawQuery() != null) {
                send(exchange, 404, "{\"error\":\"not found\"}");
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                send(exchange, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            if (!isAuthorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"recaf-mcp\"");
                send(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            if (!isJsonContentType(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                send(exchange, 415, "{\"error\":\"content type must be application/json\"}");
                return;
            }
            if (!isIdentityEncoding(exchange.getRequestHeaders().getFirst("Content-Encoding"))) {
                send(exchange, 415, "{\"error\":\"content encoding is not supported\"}");
                return;
            }
            if (declaredBodyIsTooLarge(exchange.getRequestHeaders().getFirst("Content-Length"))) {
                send(exchange, 413, "{\"error\":\"request too large\"}");
                return;
            }

            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) {
                send(exchange, 413, "{\"error\":\"request too large\"}");
                return;
            }

            String requestBody;
            try {
                requestBody = decodeUtf8(body);
            } catch (CharacterCodingException ex) {
                send(exchange, 400, "{\"error\":\"request body is not valid UTF-8\"}");
                return;
            }

            McpRequestHandler.DispatchResult response = requestHandler.dispatch(requestBody);
            send(exchange, 200, response.body());
            response.afterResponse().run();
        }
    }

    private static boolean isValidToken(String token) {
        if (token == null || token.length() < MIN_TOKEN_LENGTH || token.length() > MAX_TOKEN_LENGTH) {
            return false;
        }
        for (int index = 0; index < token.length(); index++) {
            char value = token.charAt(index);
            boolean valid = value >= 'a' && value <= 'z'
                    || value >= 'A' && value <= 'Z'
                    || value >= '0' && value <= '9'
                    || value == '-'
                    || value == '.'
                    || value == '_'
                    || value == '~'
                    || value == '+'
                    || value == '/'
                    || value == '=';
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    private static boolean isJsonContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.equals("application/json") || normalized.startsWith("application/json;");
    }

    private static boolean isIdentityEncoding(String contentEncoding) {
        return contentEncoding == null || "identity".equalsIgnoreCase(contentEncoding.trim());
    }

    private static boolean declaredBodyIsTooLarge(String contentLength) {
        if (contentLength == null) {
            return false;
        }
        try {
            long length = Long.parseLong(contentLength);
            return length < 0 || length > MAX_REQUEST_BYTES;
        } catch (NumberFormatException ex) {
            return true;
        }
    }

    private static String decodeUtf8(byte[] body) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString();
    }

    private boolean isAuthorized(String actualAuthorization) {
        if (actualAuthorization == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedAuthorization.getBytes(StandardCharsets.UTF_8),
                actualAuthorization.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
    }
}
