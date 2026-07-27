package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchCodeToolTest {
    private ExecutorService executor;

    @AfterEach
    void stopExecutor() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void usesRequestedDecompilerWithBoundedParallelism() {
        List<JvmClassInfo> classes = IntStream.range(0, 20)
                .mapToObj(index -> namedClass("example/C" + index))
                .toList();
        Workspace workspace = workspace(classes.toArray(JvmClassInfo[]::new));
        executor = Executors.newFixedThreadPool(16);
        CountDownLatch firstWindowStarted = new CountDownLatch(8);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        SearchCodeTool tool = new SearchCodeTool(
                () -> true,
                () -> workspace,
                requested -> {
                    assertEquals("vineflower", requested);
                    return new SearchCodeTool.SelectedDecompiler(
                            "Vineflower",
                            "1.11.1",
                            (currentWorkspace, classInfo) -> CompletableFuture.supplyAsync(
                                    () -> {
                                        int current = active.incrementAndGet();
                                        maximumActive.accumulateAndGet(current, Math::max);
                                        firstWindowStarted.countDown();
                                        try {
                                            if (!firstWindowStarted.await(5, TimeUnit.SECONDS)) {
                                                throw new IllegalStateException(
                                                        "parallel window did not fill"
                                                );
                                            }
                                            return new DecompileResult(
                                                    "String marker = \"needle\";",
                                                    0
                                            );
                                        } catch (InterruptedException ex) {
                                            Thread.currentThread().interrupt();
                                            throw new IllegalStateException(ex);
                                        } finally {
                                            active.decrementAndGet();
                                        }
                                    },
                                    executor
                            )
                    );
                }
        );
        JsonObject arguments = new JsonObject();
        arguments.addProperty("text", "needle");
        arguments.addProperty("decompiler", "vineflower");
        arguments.addProperty("limit", 20);

        JsonObject structured = tool.execute(arguments)
                .getAsJsonObject("structuredContent");

        assertEquals("Vineflower", structured.get("decompiler").getAsString());
        assertEquals("1.11.1", structured.get("decompiler_version").getAsString());
        assertEquals(8, structured.get("max_in_flight_decompilations").getAsInt());
        assertEquals(8, maximumActive.get());
        assertEquals(20, structured.get("classes_scanned").getAsInt());
        assertEquals(0, structured.get("classes_failed").getAsInt());
        assertEquals(20, structured.get("total").getAsInt());
        List<String> expectedOrder = classes.stream()
                .map(JvmClassInfo::getName)
                .sorted()
                .toList();
        List<String> actualOrder = structured.getAsJsonArray("hits").asList().stream()
                .map(hit -> hit.getAsJsonObject().get("class_name").getAsString())
                .toList();
        assertEquals(expectedOrder, actualOrder);
        JsonObject limit = tool.inputSchema().getAsJsonObject("properties")
                .getAsJsonObject("limit");
        assertEquals(10, limit.get("default").getAsInt());
        assertEquals(200, limit.get("maximum").getAsInt());
    }

    @Test
    void reportsUnavailableRequestedDecompiler() throws IOException {
        JvmClassInfo classInfo = readClass(SearchCodeToolTest.class);
        Workspace workspace = workspace(classInfo);
        SearchCodeTool tool = new SearchCodeTool(
                () -> true,
                () -> workspace,
                requested -> null,
                List.of(
                        new DecompilerToolSupport.Descriptor("CFR", "0.152", true),
                        new DecompilerToolSupport.Descriptor(
                                "Vineflower",
                                "1.11.1",
                                false
                        )
                )
        );
        JsonObject arguments = new JsonObject();
        arguments.addProperty("text", "needle");
        arguments.addProperty("decompiler", "missing");

        JsonObject result = tool.execute(arguments);

        assertTrue(result.get("isError").getAsBoolean());
        assertEquals("missing", result.getAsJsonObject("structuredContent")
                .get("requested_decompiler").getAsString());
        assertEquals("CFR", result.getAsJsonObject("structuredContent")
                .get("default_decompiler").getAsString());
        assertEquals("Vineflower", result.getAsJsonObject("structuredContent")
                .getAsJsonArray("available_decompilers")
                .get(1).getAsJsonObject().get("name").getAsString());
        assertTrue(tool.inputSchema().getAsJsonObject("properties")
                .has("decompiler"));
        assertEquals(List.of("CFR", "Vineflower"), tool.inputSchema()
                .getAsJsonObject("properties")
                .getAsJsonObject("decompiler")
                .getAsJsonArray("enum").asList().stream()
                .map(item -> item.getAsString())
                .toList());
    }

    @Test
    void filtersClassesAndPackagesBeforeDecompilation() throws IOException {
        JvmClassInfo selected = readClass(SearchCodeToolTest.class);
        JvmClassInfo excluded = readClass(SearchStringsToolTest.class);
        Workspace workspace = workspace(selected, excluded);
        List<String> decompiledClasses = new ArrayList<>();
        SearchCodeTool tool = new SearchCodeTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, classInfo) -> {
                    decompiledClasses.add(classInfo.getName());
                    return new DecompileResult("String marker = \"needle\";", 0);
                }
        );
        JsonObject arguments = new JsonObject();
        arguments.addProperty("text", "needle");
        arguments.addProperty("filter", selected.getName());

        JsonObject structured = tool.execute(arguments)
                .getAsJsonObject("structuredContent");

        assertEquals(List.of(selected.getName()), decompiledClasses);
        assertEquals(selected.getName(), structured.get("filter").getAsString());
        assertEquals(1, structured.get("classes_selected").getAsInt());
        assertEquals(1, structured.get("classes_scanned").getAsInt());
        assertEquals(1, structured.get("total").getAsInt());

        decompiledClasses.clear();
        arguments.addProperty("filter", "me/naptila/recafmcp/mcp/tools/");
        structured = tool.execute(arguments).getAsJsonObject("structuredContent");
        assertEquals(List.of(selected.getName(), excluded.getName()), decompiledClasses);
        assertEquals(2, structured.get("classes_selected").getAsInt());
        assertEquals(2, structured.get("classes_scanned").getAsInt());
        assertEquals(2, structured.get("total").getAsInt());

        JsonObject filterSchema = tool.inputSchema()
                .getAsJsonObject("properties")
                .getAsJsonObject("filter");
        assertEquals(500, filterSchema.get("maxLength").getAsInt());
    }

    @Test
    void requiresExplicitOptInBeforeLargeDecompilationScope() {
        List<JvmClassInfo> classes = IntStream.range(0, 201)
                .mapToObj(index -> namedClass("large/C" + index))
                .toList();
        Workspace workspace = workspace(classes.toArray(JvmClassInfo[]::new));
        AtomicInteger decompilations = new AtomicInteger();
        SearchCodeTool tool = new SearchCodeTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, classInfo) -> {
                    decompilations.incrementAndGet();
                    return new DecompileResult("class marker {}", 0);
                }
        );
        JsonObject arguments = new JsonObject();
        arguments.addProperty("text", "needle");

        JsonObject guarded = tool.execute(arguments);
        JsonObject guardedContent = guarded.getAsJsonObject("structuredContent");

        assertTrue(guarded.get("isError").getAsBoolean());
        assertEquals("large_decompilation_scope_requires_opt_in",
                guardedContent.get("error").getAsString());
        assertEquals(201, guardedContent.get("classes_available").getAsInt());
        assertEquals(201, guardedContent.get("classes_selected").getAsInt());
        assertEquals(200, guardedContent.get("implicit_scan_class_limit").getAsInt());
        assertEquals(3, guardedContent.getAsJsonArray("suggestions").size());
        assertEquals(0, decompilations.get());
        assertFalse(tool.inputSchema().getAsJsonObject("properties")
                .getAsJsonObject("allow_large_scan").get("default").getAsBoolean());

        arguments.addProperty("allow_large_scan", true);
        JsonObject allowed = tool.execute(arguments);
        JsonObject allowedContent = allowed.getAsJsonObject("structuredContent");

        assertFalse(allowed.get("isError").getAsBoolean());
        assertEquals(201, allowedContent.get("classes_scanned").getAsInt());
        assertTrue(allowedContent.get("allow_large_scan").getAsBoolean());
        assertEquals(201, decompilations.get());
    }

    @Test
    void searchesDecompiledLinesInStableClassOrderWithPagination() throws IOException {
        JvmClassInfo first = readClass(SearchCodeToolTest.class);
        JvmClassInfo second = readClass(SearchStringsToolTest.class);
        Workspace workspace = workspace(first, second);
        SearchCodeTool tool = new SearchCodeTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, classInfo) -> new DecompileResult(
                        "class " + classInfo.getName() + " {\n"
                                + "  String marker = \"needle\";\n"
                                + "}",
                        0
                )
        );
        JsonObject arguments = new JsonObject();
        arguments.addProperty("text", "needle");
        arguments.addProperty("offset", 1);
        arguments.addProperty("limit", 1);

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");
        JsonArray hits = structured.getAsJsonArray("hits");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals(2, structured.get("total").getAsInt());
        assertEquals(2, structured.get("classes_scanned").getAsInt());
        assertEquals(0, structured.get("classes_failed").getAsInt());
        assertEquals(1, hits.size());
        assertEquals(second.getName(), hits.get(0).getAsJsonObject()
                .get("class_name").getAsString());
        assertEquals(2, hits.get(0).getAsJsonObject().get("line_number").getAsInt());
        assertTrue(structured.get("next_offset").isJsonNull());
    }

    @Test
    void supportsPartialRegexAndCountsDecompilerFailures() throws IOException {
        JvmClassInfo classInfo = readClass(SearchCodeToolTest.class);
        Workspace workspace = workspace(classInfo);
        SearchCodeTool failing = new SearchCodeTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, currentClass) -> new DecompileResult(0)
        );
        JsonObject arguments = new JsonObject();
        arguments.addProperty("text", "need(le|ful)");
        arguments.addProperty("regex", true);
        JsonObject result = failing.execute(arguments);
        assertEquals(1, result.getAsJsonObject("structuredContent")
                .get("classes_failed").getAsInt());

        JsonObject invalidRegex = new JsonObject();
        invalidRegex.addProperty("text", "[");
        invalidRegex.addProperty("regex", true);
        assertThrows(IllegalArgumentException.class, () -> failing.execute(invalidRegex));
    }

    @Test
    void stopsCollectingAtTheGlobalResultCap() throws IOException {
        JvmClassInfo classInfo = readClass(SearchCodeToolTest.class);
        Workspace workspace = workspace(classInfo);
        String source = "needle\n".repeat(20_001);
        SearchCodeTool tool = new SearchCodeTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, currentClass) -> new DecompileResult(source, 0)
        );
        JsonObject arguments = new JsonObject();
        arguments.addProperty("text", "needle");

        JsonObject structured = tool.execute(arguments)
                .getAsJsonObject("structuredContent");

        assertEquals(20_000, structured.get("total").getAsInt());
        assertTrue(structured.get("collection_truncated").getAsBoolean());
        assertEquals(1, structured.get("classes_scanned").getAsInt());
    }

    private static Workspace workspace(JvmClassInfo... classes) {
        BasicJvmClassBundle bundle = new BasicJvmClassBundle();
        for (JvmClassInfo classInfo : classes) {
            bundle.put(classInfo);
        }
        WorkspaceResource primary = new WorkspaceResourceBuilder()
                .withJvmClassBundle(bundle)
                .build();
        return new BasicWorkspace(primary, List.of(), false);
    }

    private static JvmClassInfo readClass(Class<?> type) throws IOException {
        String resourceName = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("test class resource is missing");
            }
            return new JvmClassInfoBuilder(input.readAllBytes()).build();
        }
    }

    private static JvmClassInfo namedClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return new JvmClassInfoBuilder(writer.toByteArray()).build();
    }
}
