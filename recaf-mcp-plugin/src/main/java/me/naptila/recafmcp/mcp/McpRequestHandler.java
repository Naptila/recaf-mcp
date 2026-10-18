package me.naptila.recafmcp.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import me.naptila.recafmcp.mcp.WorkerOperationCoordinator.WorkerClosingException;
import me.naptila.recafmcp.mcp.WorkerOperationCoordinator.OperationCancelledException;
import me.naptila.recafmcp.mcp.WorkerOperationCoordinator.OperationTimeoutException;
import me.naptila.recafmcp.mcp.internal.HealthHandler;
import me.naptila.recafmcp.mcp.internal.HeadlessServicesProbe;
import me.naptila.recafmcp.mcp.internal.SaveWorkspaceHandler;
import me.naptila.recafmcp.mcp.internal.ShutdownHandler;

/**
 * JSON-RPC dispatcher for the Worker endpoint.
 */
public final class McpRequestHandler {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final int DEFAULT_TOOL_TIMEOUT_SECONDS = 60;

    private final WorkerHealth health;
    private final McpToolRegistry toolRegistry;
    private final WorkerOperationCoordinator operationCoordinator;
    private final HealthHandler healthHandler;
    private final HeadlessServicesProbe headlessServicesProbe;
    private final SaveWorkspaceHandler saveWorkspaceHandler;
    private final ShutdownHandler shutdownHandler;

    public McpRequestHandler(WorkerHealth health,
                             McpToolRegistry toolRegistry,
                             WorkerOperationCoordinator operationCoordinator,
                             HealthHandler healthHandler,
                             HeadlessServicesProbe headlessServicesProbe,
                             SaveWorkspaceHandler saveWorkspaceHandler,
                             ShutdownHandler shutdownHandler) {
        this.health = health;
        this.toolRegistry = toolRegistry;
        this.operationCoordinator = operationCoordinator;
        this.healthHandler = healthHandler;
        this.headlessServicesProbe = headlessServicesProbe;
        this.saveWorkspaceHandler = saveWorkspaceHandler;
        this.shutdownHandler = shutdownHandler;
    }

    public String handle(String requestBody) {
        return dispatch(requestBody).body();
    }

    DispatchResult dispatch(String requestBody) {
        JsonObject request;
        try {
            JsonElement parsed = JsonParser.parseString(requestBody);
            if (!parsed.isJsonObject()) {
                return immediate(error(null, -32600, "Invalid Request"));
            }
            request = parsed.getAsJsonObject();
        } catch (JsonParseException ex) {
            return immediate(error(null, -32700, "Parse error"));
        }

        JsonElement id = responseId(request.get("id"));
        if (!isValidEnvelope(request)) {
            return immediate(error(id, -32600, "Invalid Request"));
        }

        String method = request.get("method").getAsString();
        if ("shutdown".equals(method)) {
            return handleShutdown(id);
        }

        String response = switch (method) {
            case "tools/list" -> success(id, toolRegistry.catalog());
            case "tools/call" -> handleToolCall(id, request.get("params"));
            case "health" -> success(id, healthHandler.get());
            case "cancel_operation" -> handleCancelOperation(id, request.get("params"));
            case "probe/headless_services" -> handleHeadlessServicesProbe(id, request.get("params"));
            case "save_workspace" -> handleSaveWorkspace(id, request.get("params"));
            default -> error(id, -32601, "Method not found");
        };
        return immediate(response);
    }

    String schemaFingerprint() {
        return toolRegistry.schemaFingerprint();
    }

    private String handleToolCall(JsonElement id, JsonElement paramsElement) {
        if (paramsElement == null || !paramsElement.isJsonObject()) {
            return error(id, -32602, "Invalid params");
        }

        JsonObject params = paramsElement.getAsJsonObject();
        JsonElement nameElement = params.get("name");
        if (nameElement == null || !nameElement.isJsonPrimitive() || !nameElement.getAsJsonPrimitive().isString()) {
            return error(id, -32602, "Invalid params");
        }
        McpTool tool = toolRegistry.find(nameElement.getAsString());
        if (tool == null) {
            return error(id, -32602, "Unknown tool");
        }

        JsonElement argumentsElement = params.get("arguments");
        JsonObject arguments;
        if (argumentsElement == null) {
            arguments = new JsonObject();
        } else if (argumentsElement.isJsonObject()) {
            arguments = argumentsElement.getAsJsonObject();
        } else {
            return error(id, -32602, "Invalid params");
        }

        String operationId = optionalOperationId(params, id);
        if (operationId == null) {
            return error(id, -32602, "Invalid params");
        }
        try {
            return success(id, operationCoordinator.execute(
                    operationId,
                    timeoutSeconds(tool.name()),
                    java.util.concurrent.TimeUnit.SECONDS,
                    () -> tool.execute(arguments)
            ));
        } catch (WorkerClosingException ex) {
            return error(id, -32003, "Worker is shutting down");
        } catch (OperationTimeoutException ex) {
            return error(id, -32004, "Tool operation timed out");
        } catch (OperationCancelledException ex) {
            return error(id, -32005, "Tool operation was cancelled");
        } catch (IllegalArgumentException ex) {
            return error(id, -32602, "Invalid params: " + exceptionMessage(ex));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return error(id, -32603, "Operation interrupted");
        } catch (Exception ex) {
            return error(id, -32603, "Internal error");
        }
    }

    private String handleCancelOperation(JsonElement id, JsonElement paramsElement) {
        if (paramsElement == null || !paramsElement.isJsonObject()) {
            return error(id, -32602, "Invalid params");
        }
        String operationId = requiredString(paramsElement.getAsJsonObject(), "operation_id");
        if (operationId == null || operationId.length() > 128) {
            return error(id, -32602, "Invalid params");
        }
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("cancelled", operationCoordinator.cancel(operationId));
        return success(id, result);
    }

    private static String optionalOperationId(JsonObject params, JsonElement id) {
        JsonElement value = params.get("operation_id");
        if (value == null) {
            String fallback = "rpc-" + GSON.toJson(id);
            return fallback.length() <= 128 ? fallback : null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        String operationId = value.getAsString();
        return operationId.isBlank() || operationId.length() > 128 ? null : operationId;
    }

    static int timeoutSeconds(String toolName) {
        return switch (toolName) {
            case "workspace_info", "list_classes", "list_files", "get_class_bytecode",
                    "get_recaf_script_help" -> 15;
            case "decompile_class", "search_strings", "search_instructions",
                    "search_members", "search_structure", "rename_symbol", "get_method_assembly",
                    "read_file", "export_mappings" -> 60;
            case "search_code", "call_graph", "patch_instructions" -> 120;
            case "transform_workspace", "patch_class", "run_recaf_script",
                    "apply_mappings" -> 300;
            case "export_sources" -> 1800;
            default -> DEFAULT_TOOL_TIMEOUT_SECONDS;
        };
    }

    private DispatchResult handleShutdown(JsonElement id) {
        try {
            shutdownHandler.prepare();
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            return new DispatchResult(success(id, result), shutdownHandler::afterResponseSent);
        } catch (WorkerClosingException ex) {
            return immediate(error(id, -32003, "Worker is shutting down"));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return immediate(error(id, -32603, "Operation interrupted"));
        }
    }

    private String handleHeadlessServicesProbe(JsonElement id, JsonElement paramsElement) {
        if (headlessServicesProbe == null) {
            return error(id, -32601, "Method not found");
        }
        if (!health.isReady() || !health.isWorkspaceReady()) {
            return error(id, -32002, "Workspace not ready");
        }
        if (paramsElement == null || !paramsElement.isJsonObject()) {
            return error(id, -32602, "Invalid params");
        }

        JsonObject params = paramsElement.getAsJsonObject();
        String className = requiredString(params, "class_name");
        String searchText = requiredString(params, "search_text");
        if (className == null || searchText == null) {
            return error(id, -32602, "Invalid params");
        }

        try {
            return success(id, operationCoordinator.execute(
                    () -> headlessServicesProbe.run(className, searchText)
            ));
        } catch (WorkerClosingException ex) {
            return error(id, -32003, "Worker is shutting down");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return error(id, -32603, "Operation interrupted");
        } catch (Exception ex) {
            return error(id, -32010, "Headless services probe failed: " + ex.getClass().getSimpleName());
        }
    }

    private String handleSaveWorkspace(JsonElement id, JsonElement paramsElement) {
        if (!health.isReady() || !health.isWorkspaceReady()) {
            return error(id, -32002, "Workspace not ready");
        }
        if (paramsElement == null || !paramsElement.isJsonObject()) {
            return error(id, -32602, "Invalid params");
        }

        JsonObject params = paramsElement.getAsJsonObject();
        String destination = requiredString(params, "destination");
        JsonElement overwriteElement = params.get("overwrite");
        if (destination == null
                || overwriteElement == null
                || !overwriteElement.isJsonPrimitive()
                || !overwriteElement.getAsJsonPrimitive().isBoolean()) {
            return error(id, -32602, "Invalid params");
        }

        try {
            JsonObject result = operationCoordinator.execute(
                    () -> saveWorkspaceHandler.save(destination, overwriteElement.getAsBoolean())
            );
            health.clearOperationError();
            return success(id, result);
        } catch (WorkerClosingException ex) {
            return error(id, -32003, "Worker is shutting down");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return error(id, -32603, "Operation interrupted");
        } catch (Exception ex) {
            String message = "Workspace save failed: " + exceptionMessage(ex);
            health.recordOperationError(message);
            return error(id, -32011, message);
        }
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        String text = value.getAsString();
        return text.isBlank() ? null : text;
    }

    private static String exceptionMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        String singleLine = message.replace('\n', ' ').replace('\r', ' ').strip();
        return singleLine.length() <= 512 ? singleLine : singleLine.substring(0, 512);
    }

    private static boolean isValidEnvelope(JsonObject request) {
        JsonElement version = request.get("jsonrpc");
        JsonElement method = request.get("method");
        JsonElement id = request.get("id");
        JsonElement params = request.get("params");
        return version != null
                && version.isJsonPrimitive()
                && version.getAsJsonPrimitive().isString()
                && "2.0".equals(version.getAsString())
                && method != null
                && method.isJsonPrimitive()
                && method.getAsJsonPrimitive().isString()
                && id != null
                && isValidId(id)
                && (params == null || params.isJsonObject() || params.isJsonArray());
    }

    private static boolean isValidId(JsonElement id) {
        return id.isJsonNull()
                || id.isJsonPrimitive()
                && (id.getAsJsonPrimitive().isString() || id.getAsJsonPrimitive().isNumber());
    }

    private static JsonElement responseId(JsonElement id) {
        return id != null && isValidId(id) ? id : null;
    }

    private static String success(JsonElement id, JsonElement result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", copyId(id));
        response.add("result", result);
        return GSON.toJson(response);
    }

    private static String error(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);

        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", copyId(id));
        response.add("error", error);
        return GSON.toJson(response);
    }

    private static JsonElement copyId(JsonElement id) {
        if (id == null) {
            return JsonParser.parseString("null");
        }
        return id.deepCopy();
    }

    private static DispatchResult immediate(String body) {
        return new DispatchResult(body, () -> {});
    }

    record DispatchResult(String body, Runnable afterResponse) {}
}
