package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunRecafScriptToolTest {
    @Test
    void defaultsToNonExecutingCompileOnlyPreflight() {
        AtomicBoolean called = new AtomicBoolean();
        RunRecafScriptTool tool = tool((workspace, request) -> {
            called.set(true);
            assertTrue(request.compileOnly());
            assertFalse(request.acknowledgeFullWorkerAccess());
            return execution(true, false, null, "", List.of(), List.of(), List.of());
        });
        JsonObject arguments = new JsonObject();
        arguments.addProperty("script", "System.out.println(\"not run\");");

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertTrue(called.get());
        assertFalse(result.get("isError").getAsBoolean());
        assertTrue(structured.get("compiled").getAsBoolean());
        assertFalse(structured.get("executed").getAsBoolean());
        assertEquals("shorthand", structured.get("script_form").getAsString());
        assertEquals(64, structured.get("script_sha256").getAsString().length());
    }

    @Test
    void executionRequiresExplicitFullAccessAcknowledgement() {
        RunRecafScriptTool tool = tool((workspace, request) ->
                execution(true, true, null, "", List.of(), List.of(), List.of()));
        JsonObject arguments = new JsonObject();
        arguments.addProperty("script", "System.out.println(1);");
        arguments.addProperty("compile_only", false);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(arguments)
        );
        assertTrue(error.getMessage().contains("acknowledge_full_worker_access"));

        JsonObject schema = tool.inputSchema();
        assertTrue(schema.get("description").getAsString().contains("not sandboxed"));
        assertTrue(schema.getAsJsonObject("properties")
                .getAsJsonObject("acknowledge_full_worker_access")
                .get("description")
                .getAsString()
                .contains("filesystem"));
    }

    @Test
    void returnsParsedOutputAndPaginatedObservedChanges() {
        RunRecafScriptTool tool = tool((workspace, request) -> execution(
                true,
                true,
                null,
                "progress\nMCP_RESULT {\"matches\":39}\n",
                List.of("sample/C", "sample/A"),
                List.of("sample/B"),
                List.of("sample/D")
        ));
        JsonObject arguments = runtimeArguments();
        arguments.addProperty("offset", 1);
        arguments.addProperty("limit", 2);

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean());
        assertTrue(structured.get("executed").getAsBoolean());
        assertEquals(39, structured.getAsJsonObject("script_result")
                .get("matches").getAsInt());
        assertEquals(4, structured.get("changed_class_count").getAsInt());
        assertEquals("[\"sample/B\",\"sample/C\"]",
                structured.getAsJsonArray("changed_classes").toString());
        assertEquals(3, structured.get("next_offset").getAsInt());
        assertFalse(structured.get("atomic").getAsBoolean());
        assertTrue(structured.get("save_required").getAsBoolean());
    }

    @Test
    void compilerAndRuntimeFailuresAreBoundedToolErrors() {
        RunRecafScriptTool compileFailure = tool((workspace, request) ->
                new RunRecafScriptTool.ScriptExecution(
                        false,
                        false,
                        false,
                        List.of(new RunRecafScriptTool.ScriptDiagnostic(
                                2, 4, 1, "error", "cannot find symbol"
                        )),
                        101,
                        null,
                        null,
                        "",
                        0,
                        false,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        false,
                        false,
                        null
                ));
        JsonObject compileResult = compileFailure.execute(scriptArguments());
        assertTrue(compileResult.get("isError").getAsBoolean());
        assertEquals(101, compileResult.getAsJsonObject("structuredContent")
                .get("diagnostic_count").getAsInt());
        assertTrue(compileResult.getAsJsonObject("structuredContent")
                .get("diagnostics_truncated").getAsBoolean());

        RunRecafScriptTool runtimeFailure = tool((workspace, request) ->
                execution(
                        true,
                        true,
                        new IllegalStateException("bad target"),
                        "",
                        List.of(),
                        List.of("sample/PartiallyChanged"),
                        List.of()
                ));
        JsonObject runtimeResult = runtimeFailure.execute(runtimeArguments());
        JsonObject runtimeStructured = runtimeResult.getAsJsonObject("structuredContent");
        assertTrue(runtimeResult.get("isError").getAsBoolean());
        assertTrue(runtimeStructured.get("error").getAsString().contains("partial"));
        assertEquals("java.lang.IllegalStateException",
                runtimeStructured.get("runtime_error_type").getAsString());
        assertEquals(1, runtimeStructured.get("modified_class_count").getAsInt());
    }

    @Test
    void reportsNoWorkspaceWithoutInvokingScriptOperation() {
        RunRecafScriptTool tool = new RunRecafScriptTool(
                () -> false,
                () -> null,
                (workspace, request) -> {
                    throw new AssertionError("must not run");
                }
        );

        JsonObject result = tool.execute(scriptArguments());
        assertTrue(result.get("isError").getAsBoolean());
        assertEquals("workspace not open", result.getAsJsonObject("structuredContent")
                .get("error").getAsString());
    }

    private static RunRecafScriptTool tool(RunRecafScriptTool.ScriptOperation operation) {
        return new RunRecafScriptTool(() -> true, () -> null, operation);
    }

    private static JsonObject scriptArguments() {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("script", "System.out.println(\"hello\");");
        return arguments;
    }

    private static JsonObject runtimeArguments() {
        JsonObject arguments = scriptArguments();
        arguments.addProperty("compile_only", false);
        arguments.addProperty("acknowledge_full_worker_access", true);
        return arguments;
    }

    private static RunRecafScriptTool.ScriptExecution execution(
            boolean compiled,
            boolean executed,
            Throwable runtimeFailure,
            String stdout,
            List<String> added,
            List<String> modified,
            List<String> removed
    ) {
        return new RunRecafScriptTool.ScriptExecution(
                compiled,
                executed,
                false,
                List.of(),
                0,
                runtimeFailure == null ? null : runtimeFailure.getClass().getName(),
                runtimeFailure == null ? null : runtimeFailure.getMessage(),
                stdout,
                stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                false,
                stdout.contains("MCP_RESULT ")
                        ? JsonParser.parseString("{\"matches\":39}")
                        : null,
                null,
                added,
                modified,
                removed,
                false,
                false,
                null
        );
    }
}
