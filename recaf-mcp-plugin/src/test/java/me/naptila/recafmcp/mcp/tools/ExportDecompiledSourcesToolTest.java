package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.FileInfoBuilder;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.info.properties.builtin.InputFilePathProperty;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResourceBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportDecompiledSourcesToolTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exportsEverySelectedClassAndManifestAtomically() throws Exception {
        JvmClassInfo first = readClass(ExportDecompiledSourcesToolTest.class);
        JvmClassInfo second = readClass(me.naptila.recafmcp.mcp.util.ToolArguments.class);
        Workspace workspace = workspace(first, second);
        ExportDecompiledSourcesTool tool = tool(
                workspace,
                classInfo -> new DecompileResult(
                        "// " + classInfo.getName() + "\nclass Exported {}",
                        0
                )
        );
        Path output = temporaryDirectory.resolve("sources.zip").toAbsolutePath();
        JsonObject arguments = arguments(output);
        arguments.addProperty("decompiler", "vineflower");

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals(output.toRealPath().toString(), structured.get("path").getAsString());
        assertEquals("Vineflower", structured.get("decompiler").getAsString());
        assertEquals(2, structured.get("classes_selected").getAsInt());
        assertEquals(2, structured.get("classes_exported").getAsInt());
        assertEquals(0, structured.get("classes_failed").getAsInt());
        assertTrue(structured.get("complete").getAsBoolean());
        assertEquals(3, structured.get("zip_entries").getAsInt());
        assertEquals(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(output))),
                structured.get("sha256").getAsString()
        );

        try (ZipFile zip = new ZipFile(output.toFile())) {
            String firstPath = "jvm/" + first.getName() + ".java";
            String secondPath = "jvm/" + second.getName() + ".java";
            assertTrue(new String(
                    zip.getInputStream(zip.getEntry(firstPath)).readAllBytes(),
                    StandardCharsets.UTF_8
            ).contains(first.getName()));
            assertTrue(new String(
                    zip.getInputStream(zip.getEntry(secondPath)).readAllBytes(),
                    StandardCharsets.UTF_8
            ).contains(second.getName()));
            JsonObject manifest = JsonParser.parseString(new String(
                    zip.getInputStream(zip.getEntry(
                            ExportDecompiledSourcesTool.MANIFEST_PATH
                    )).readAllBytes(),
                    StandardCharsets.UTF_8
            )).getAsJsonObject();
            assertEquals(1, manifest.get("format_version").getAsInt());
            assertEquals(2, manifest.getAsJsonArray("classes").size());
            assertTrue(manifest.get("complete").getAsBoolean());
        }

        JsonObject duplicate = tool.execute(arguments);
        assertTrue(duplicate.get("isError").getAsBoolean());
        assertTrue(duplicate.getAsJsonObject("structuredContent")
                .get("error").getAsString().contains("already exists"));

        arguments.addProperty("overwrite", true);
        assertFalse(tool.execute(arguments).get("isError").getAsBoolean());

        JsonObject schema = tool.inputSchema();
        assertEquals(List.of("output_path"), schema.getAsJsonArray("required")
                .asList().stream().map(item -> item.getAsString()).toList());
        assertEquals(List.of("jvm", "android", "all"), schema
                .getAsJsonObject("properties")
                .getAsJsonObject("scope")
                .getAsJsonArray("enum")
                .asList().stream().map(item -> item.getAsString()).toList());
        assertEquals("CFR", schema.getAsJsonObject("properties")
                .getAsJsonObject("decompiler").get("default").getAsString());
    }

    @Test
    void appliesRegexFilterAndKeepsPartialFailuresInManifest() throws IOException {
        JvmClassInfo first = readClass(ExportDecompiledSourcesToolTest.class);
        JvmClassInfo second = readClass(me.naptila.recafmcp.mcp.util.ToolArguments.class);
        Workspace workspace = workspace(first, second);
        ExportDecompiledSourcesTool tool = tool(
                workspace,
                classInfo -> new DecompileResult(new IOException("broken class"), 0)
        );
        Path output = temporaryDirectory.resolve("failed.zip").toAbsolutePath();
        JsonObject arguments = arguments(output);
        arguments.addProperty("filter", "mcp/util/ToolArg.*");
        arguments.addProperty("regex", true);

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals(1, structured.get("classes_selected").getAsInt());
        assertEquals(0, structured.get("classes_exported").getAsInt());
        assertEquals(1, structured.get("classes_failed").getAsInt());
        assertFalse(structured.get("complete").getAsBoolean());
        assertEquals("broken class", structured.getAsJsonArray("failures")
                .get(0).getAsJsonObject().get("error").getAsString());
        assertEquals(1, structured.get("zip_entries").getAsInt());

        try (ZipFile zip = new ZipFile(output.toFile())) {
            JsonObject manifest = JsonParser.parseString(new String(
                    zip.getInputStream(zip.getEntry(
                            ExportDecompiledSourcesTool.MANIFEST_PATH
                    )).readAllBytes(),
                    StandardCharsets.UTF_8
            )).getAsJsonObject();
            assertEquals(1, manifest.get("classes_failed").getAsInt());
            assertFalse(manifest.get("complete").getAsBoolean());
            assertEquals("failed", manifest.getAsJsonArray("classes")
                    .get(0).getAsJsonObject().get("status").getAsString());
        }
    }

    @Test
    void rejectsUnsafeArgumentsAndEscapesArchiveSegments() throws IOException {
        JvmClassInfo classInfo = readClass(ExportDecompiledSourcesToolTest.class);
        Workspace workspace = workspace(classInfo);
        ExportDecompiledSourcesTool tool = tool(
                workspace,
                current -> new DecompileResult("class Exported {}", 0)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(arguments(Path.of("relative.zip")))
        );
        JsonObject regexWithoutFilter = arguments(
                temporaryDirectory.resolve("regex.zip").toAbsolutePath()
        );
        regexWithoutFilter.addProperty("regex", true);
        assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(regexWithoutFilter)
        );

        JsonObject noMatches = arguments(
                temporaryDirectory.resolve("empty.zip").toAbsolutePath()
        );
        noMatches.addProperty("filter", "missing/package");
        JsonObject result = tool.execute(noMatches);
        assertTrue(result.get("isError").getAsBoolean());
        assertFalse(Files.exists(temporaryDirectory.resolve("empty.zip")));

        ClassToolSupport.Entry unusual = new ClassToolSupport.Entry(
                "../bad\\name",
                ClassToolSupport.Scope.JVM,
                null,
                classInfo
        );
        assertEquals(
                "jvm/%2E%2E/bad%5Cname.java",
                ExportDecompiledSourcesTool.sourceEntryPath(unusual)
        );
        assertEquals(
                "classes%3A2.dex",
                ExportDecompiledSourcesTool.escapePathSegment("classes:2.dex")
        );
    }

    @Test
    void disablesRecafDecompilationCacheOnlyForTheBulkOperation() throws IOException {
        JvmClassInfo classInfo = readClass(ExportDecompiledSourcesToolTest.class);
        Workspace workspace = workspace(classInfo);
        AtomicBoolean cacheEnabled = new AtomicBoolean(true);
        ExportDecompiledSourcesTool tool = new ExportDecompiledSourcesTool(
                () -> true,
                () -> workspace,
                requested -> new ExportDecompiledSourcesTool.SelectedDecompiler(
                        "CFR",
                        "0.152",
                        (currentWorkspace, currentClass) -> {
                            assertFalse(cacheEnabled.get());
                            return CompletableFuture.completedFuture(
                                    new DecompileResult("class Exported {}", 0)
                            );
                        }
                ),
                new ExportDecompiledSourcesTool.CacheControl(
                        cacheEnabled::get,
                        cacheEnabled::set
                ),
                List.of(new DecompilerToolSupport.Descriptor(
                        "CFR",
                        "0.152",
                        true
                ))
        );

        JsonObject result = tool.execute(arguments(
                temporaryDirectory.resolve("cache.zip").toAbsolutePath()
        ));

        assertFalse(result.get("isError").getAsBoolean());
        assertTrue(cacheEnabled.get());
        assertTrue(result.getAsJsonObject("structuredContent")
                .get("decompilation_cache_temporarily_disabled").getAsBoolean());
        assertFalse(result.getAsJsonObject("structuredContent")
                .get("decompilation_results_cached").getAsBoolean());
    }

    private ExportDecompiledSourcesTool tool(
            Workspace workspace,
            TestDecompileOperation operation) {
        return new ExportDecompiledSourcesTool(
                () -> true,
                () -> workspace,
                requested -> {
                    if (requested != null
                            && !requested.equalsIgnoreCase("CFR")
                            && !requested.equalsIgnoreCase("Vineflower")) {
                        return null;
                    }
                    boolean vineflower = requested != null
                            && requested.equalsIgnoreCase("Vineflower");
                    return new ExportDecompiledSourcesTool.SelectedDecompiler(
                            vineflower ? "Vineflower" : "CFR",
                            vineflower ? "1.11.1" : "0.152",
                            (currentWorkspace, classInfo) ->
                                    CompletableFuture.completedFuture(
                                            operation.run(classInfo)
                                    )
                    );
                },
                List.of(
                        new DecompilerToolSupport.Descriptor("CFR", "0.152", true),
                        new DecompilerToolSupport.Descriptor(
                                "Vineflower",
                                "1.11.1",
                                false
                        )
                )
        );
    }

    private Workspace workspace(JvmClassInfo... classInfos) throws IOException {
        Path input = temporaryDirectory.resolve("input.jar");
        Files.write(input, new byte[]{1, 2, 3});
        BasicJvmClassBundle classes = new BasicJvmClassBundle();
        for (JvmClassInfo classInfo : classInfos) {
            classes.put(classInfo);
        }
        FileInfo fileInfo = new FileInfoBuilder<>()
                .withName("input.jar")
                .withRawContent(new byte[]{1, 2, 3})
                .build();
        InputFilePathProperty.set(fileInfo, input);
        WorkspaceFileResource primary = new WorkspaceFileResourceBuilder()
                .withFileInfo(fileInfo)
                .withJvmClassBundle(classes)
                .build();
        return new BasicWorkspace(primary, List.of(), false);
    }

    private static JsonObject arguments(Path output) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("output_path", output.toString());
        return arguments;
    }

    private static JvmClassInfo readClass(Class<?> type) throws IOException {
        String resourceName = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("test class resource is missing: " + type.getName());
            }
            return new JvmClassInfoBuilder(input.readAllBytes()).build();
        }
    }

    @FunctionalInterface
    private interface TestDecompileOperation {
        DecompileResult run(JvmClassInfo classInfo);
    }
}
