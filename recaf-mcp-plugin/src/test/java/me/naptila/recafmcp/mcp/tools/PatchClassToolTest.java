package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.compile.JavacCompiler;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchClassToolTest {
    @Test
    void replacesValidatedPrimaryAndCompanionOutputs() throws IOException {
        JvmClassInfo primary = readClass(PatchClassToolTest.class);
        JvmClassInfo companion = readClass(FixtureCompanion.class);
        Workspace workspace = workspace(primary, companion);
        PatchClassTool tool = new PatchClassTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, classInfo, source, targetVersion) -> {
                    assertEquals(workspace, currentWorkspace);
                    assertEquals(primary, classInfo);
                    assertEquals("complete source", source);
                    assertEquals(22, targetVersion);
                    return new PatchClassTool.Compilation(
                            true,
                            Map.of(
                                    primary.getName(), primary.getBytecode(),
                                    companion.getName(), companion.getBytecode()
                            ),
                            List.of(new PatchClassTool.Diagnostic(
                                    "warning", 2, 3, 4, "fixture warning"
                            )),
                            null
                    );
                }
        );

        JsonObject result = tool.execute(arguments(primary.getName(), "complete source"));
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals(2, structured.get("updated_class_count").getAsInt());
        assertEquals(1, structured.get("generated_companion_count").getAsInt());
        assertEquals(22, structured.get("target_version").getAsInt());
        assertEquals(1, structured.get("diagnostic_count").getAsInt());
        assertEquals(primary.getName(), structured.getAsJsonArray("updated_classes")
                .get(0).getAsString());
        assertEquals(64, structured.get("patched_sha256").getAsString().length());
    }

    @Test
    void rejectsCompilerFailureAndUnrelatedTopLevelOutputWithoutMutation()
            throws IOException {
        JvmClassInfo primary = readClass(PatchClassToolTest.class);
        JvmClassInfo unrelated = readClass(WorkspaceInfoToolTest.class);
        Workspace workspace = workspace(primary, unrelated);

        PatchClassTool failing = new PatchClassTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, classInfo, source, targetVersion) ->
                        new PatchClassTool.Compilation(
                                false,
                                Map.of(),
                                List.of(new PatchClassTool.Diagnostic(
                                        "error", 1, 2, 5, "cannot compile"
                                )),
                                null
                        )
        );
        JsonObject failed = failing.execute(arguments(primary.getName(), "broken"));
        assertTrue(failed.get("isError").getAsBoolean());
        assertEquals("cannot compile", failed.getAsJsonObject("structuredContent")
                .getAsJsonArray("diagnostics").get(0).getAsJsonObject()
                .get("message").getAsString());

        PatchClassTool unrelatedOutput = new PatchClassTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, classInfo, source, targetVersion) ->
                        new PatchClassTool.Compilation(
                                true,
                                Map.of(
                                        primary.getName(), primary.getBytecode(),
                                        unrelated.getName(), unrelated.getBytecode()
                                ),
                                List.of(),
                                null
                        )
        );
        JsonObject rejected = unrelatedOutput.execute(
                arguments(primary.getName(), "two top levels")
        );
        assertTrue(rejected.get("isError").getAsBoolean());
        assertTrue(rejected.getAsJsonObject("structuredContent")
                .get("error").getAsString().contains("unrelated top-level"));
        assertEquals(primary.getBytecode().length, workspace.getPrimaryResource()
                .getJvmClassBundle().get(primary.getName()).getBytecode().length);
    }

    @Test
    void validatesWorkspaceTargetNameAndSourceBounds() throws IOException {
        JvmClassInfo primary = readClass(PatchClassToolTest.class);
        Workspace workspace = workspace(primary);
        PatchClassTool unavailable = new PatchClassTool(
                () -> false,
                () -> null,
                (currentWorkspace, classInfo, source, targetVersion) -> null
        );
        assertTrue(unavailable.execute(arguments(primary.getName(), "source"))
                .get("isError").getAsBoolean());

        PatchClassTool tool = new PatchClassTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, classInfo, source, targetVersion) -> {
                    throw new AssertionError("compile must not run");
                }
        );
        assertTrue(tool.execute(arguments("missing/Class", "source"))
                .get("isError").getAsBoolean());
        assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(arguments("invalid.Class", "source"))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(arguments(primary.getName(), "x".repeat(750_001)))
        );
        JsonObject invalidPolicy = arguments(primary.getName(), "source");
        invalidPolicy.addProperty("target_version_policy", "latest");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(invalidPolicy));
        assertEquals(
                "preserve",
                tool.inputSchema().getAsJsonObject("properties")
                        .getAsJsonObject("target_version_policy")
                        .get("default").getAsString()
        );
    }

    @Test
    void legacyClassRequiresExplicitVersionUpgrade() throws IOException {
        JvmClassInfo current = readClass(PatchClassToolTest.class);
        JvmClassInfo legacy = new JvmClassInfoBuilder(
                withClassfileVersion(current.getBytecode(), 50)
        ).build();
        Workspace workspace = workspace(legacy);
        PatchClassTool tool = new PatchClassTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, classInfo, source, targetVersion) -> {
                    throw new AssertionError("compile must not run without explicit upgrade");
                }
        );

        JsonObject result = tool.execute(arguments(legacy.getName(), "source"));
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertTrue(result.get("isError").getAsBoolean());
        assertEquals(50, structured.get("input_classfile_version").getAsInt());
        assertEquals(6, structured.get("input_target_version").getAsInt());
        assertEquals("preserve", structured.get("target_version_policy").getAsString());
        assertFalse(structured.get("classfile_version_upgraded").getAsBoolean());
        assertTrue(structured.get("error").getAsString()
                .contains("target_version_policy=\"minimum_supported\""));
    }

    @Test
    void explicitlyUpgradesLegacyClassToCompilerMinimum() throws IOException {
        JvmClassInfo current = readClass(PatchClassToolTest.class);
        JvmClassInfo legacy = new JvmClassInfoBuilder(
                withClassfileVersion(current.getBytecode(), 50)
        ).build();
        int minimumTarget = JavacCompiler.getMinTargetVersion();
        byte[] upgradedBytecode = withClassfileVersion(
                current.getBytecode(),
                minimumTarget + 44
        );
        Workspace workspace = workspace(legacy);
        PatchClassTool tool = new PatchClassTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, classInfo, source, targetVersion) -> {
                    assertEquals(minimumTarget, targetVersion);
                    return new PatchClassTool.Compilation(
                            true,
                            Map.of(legacy.getName(), upgradedBytecode),
                            List.of(),
                            null
                    );
                }
        );
        JsonObject arguments = arguments(legacy.getName(), "source");
        arguments.addProperty("target_version_policy", "minimum_supported");

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals(minimumTarget + 44,
                structured.get("target_classfile_version").getAsInt());
        assertEquals(minimumTarget, structured.get("target_version").getAsInt());
        assertEquals("minimum_supported",
                structured.get("target_version_policy").getAsString());
        assertTrue(structured.get("classfile_version_upgraded").getAsBoolean());
        assertEquals(minimumTarget + 44, workspace.getPrimaryResource()
                .getJvmClassBundle().get(legacy.getName()).getVersion());
    }

    private static JsonObject arguments(String className, String source) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("class_name", className);
        arguments.addProperty("source", source);
        return arguments;
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

    private static byte[] withClassfileVersion(byte[] bytecode, int version) {
        byte[] copy = Arrays.copyOf(bytecode, bytecode.length);
        copy[6] = (byte) (version >>> 8);
        copy[7] = (byte) version;
        return copy;
    }

    private static final class FixtureCompanion {
    }
}
