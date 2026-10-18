package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonObject;
import me.naptila.recafmcp.AndroidFixture;
import me.naptila.recafmcp.AndroidFidelityFixture;
import me.naptila.recafmcp.mcp.android.AndroidDexReader;
import software.coley.recaf.info.builder.AndroidClassInfoBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.services.assembler.AndroidAssemblerPipelineConfig;
import software.coley.recaf.services.assembler.AssemblerPipelineGeneralConfig;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.assembler.JvmAssemblerPipelineConfig;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.inheritance.InheritanceGraphServiceConfig;
import software.coley.recaf.services.mapping.MappingListeners;
import software.coley.recaf.services.mapping.MappingListenersConfig;
import software.coley.recaf.services.workspace.BasicWorkspaceManager;
import software.coley.recaf.services.workspace.WorkspaceManagerConfig;
import software.coley.recaf.util.android.DexIOUtil;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.bundle.BasicAndroidClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AndroidPatchInstructionsToolTest {
    private static final String OWNER = "sample/Greeter";
    private Workspace workspace;
    private GetMethodAssemblyTool readTool;
    private PatchInstructionsTool patchTool;

    @BeforeEach
    void setup() throws IOException {
        var first = new BasicAndroidClassBundle(35, new byte[0]);
        first.put(DexIOUtil.read(AndroidFixture.dex(OWNER, "before")).get(OWNER));
        var second = new BasicAndroidClassBundle(35, new byte[0]);
        second.put(DexIOUtil.read(AndroidFixture.dex("sample/Other", "untouched")).get("sample/Other"));
        Map<String, AndroidClassBundle> bundles = new LinkedHashMap<>();
        bundles.put("classes.dex", first);
        bundles.put("classes2.dex", second);
        workspace = new BasicWorkspace(new WorkspaceResourceBuilder()
                .withAndroidClassBundles(bundles).build(), List.of(), false);
        var manager = new BasicWorkspaceManager(new WorkspaceManagerConfig());
        var graph = new InheritanceGraphService(manager,
                new MappingListeners(new MappingListenersConfig()), new InheritanceGraphServiceConfig());
        var pipelines = new AssemblerPipelineManager(manager, graph,
                new AssemblerPipelineGeneralConfig(), new AndroidAssemblerPipelineConfig(),
                new JvmAssemblerPipelineConfig());
        var assembler = new MethodAssemblySupport.RecafMethodAssembler(pipelines);
        readTool = new GetMethodAssemblyTool(() -> true, () -> workspace, assembler);
        patchTool = new PatchInstructionsTool(() -> true, () -> workspace, assembler, pipelines);
    }

    @AfterEach
    void close() throws IOException {
        if (workspace != null) {
            workspace.close();
        }
    }

    @Test
    void editsDebugBearingMethodAndRebindsDebugRangesWithoutChangingOtherMethods() throws IOException {
        var bundle = workspace.getPrimaryResource().getAndroidClassBundles().get("classes.dex");
        for (var definition : AndroidDexReader.read(AndroidFidelityFixture.dex()).definitions()) {
            bundle.put(new AndroidClassInfoBuilder(definition).build());
        }
        var info = bundle.get("sample/Fidelity");
        var request = new JsonObject();
        request.addProperty("scope", "android");
        request.addProperty("dex_name", "classes.dex");
        request.addProperty("owner", "sample/Fidelity");
        request.addProperty("name", "mix");
        request.addProperty("descriptor", "(I)I");
        var read = readTool.execute(request).getAsJsonObject("structuredContent");
        String assembly = read.get("assembly").getAsString();
        assertFalse(assembly.contains("line 10"));
        assertFalse(assembly.contains("delta"));
        assertFalse(assembly.contains("parameters:"));
        String other = info.getBackingDefinition().getMethod("unnamed", "(II)I").getCode().getDebugInfo().toString();
        request.addProperty("expected_assembly_sha256", read.get("assembly_sha256").getAsString());
        request.addProperty("assembly", assembly.replace("17", "18").replace("return v1", "nop\n        return v1"));
        var result = patchTool.execute(request);
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        var updated = bundle.get("sample/Fidelity");
        var debug = updated.getBackingDefinition().getMethod("mix", "(I)I").getCode().getDebugInfo();
        assertEquals(List.of("input"), debug.parameterNames());
        assertEquals(List.of(10, 11), debug.lineNumbers().stream().map(line -> line.line()).toList());
        assertEquals("delta", debug.locals().getFirst().name());
        AndroidAssemblySupport.bytes(updated, 35); // Resolve new instruction positions.
        assertEquals(5, debug.locals().getFirst().end().position());
        assertEquals(other, updated.getBackingDefinition().getMethod("unnamed", "(II)I").getCode().getDebugInfo().toString());

        String current = AndroidAssemblySupport.disassemble(updated, "mix", "(I)I");
        request.addProperty("expected_assembly_sha256", MethodAssemblySupport.sha256(current));
        byte[] before = AndroidAssemblySupport.bytes(updated, 35);
        request.addProperty("assembly", current.replace("A:", "RenamedA:"));
        assertTrue(patchTool.execute(request).get("isError").getAsBoolean());
        assertArrayEquals(before, AndroidAssemblySupport.bytes(bundle.get("sample/Fidelity"), 35));
    }

    @Test
    void realDalvikPatchRoundTripsAndPreservesOtherMethodsAndDexClasses() throws IOException {
        JsonObject original = read();
        String keptMethod = AndroidAssemblySupport.disassemble(owner(), "keep", "()V");
        byte[] other = AndroidAssemblySupport.bytes(workspace.getPrimaryResource()
                .getAndroidClassBundles().get("classes2.dex").get("sample/Other"), 35);
        JsonObject patched = patchTool.execute(patch(original, "after"));
        assertFalse(patched.get("isError").getAsBoolean(), patched.toString());
        var metadata = patched.getAsJsonObject("structuredContent");
        assertTrue(metadata.get("applied").getAsBoolean());
        assertTrue(metadata.get("changed").getAsBoolean());
        assertEquals("classes.dex", metadata.get("dex_name").getAsString());
        assertEquals("recaf_jasm_dalvik_method", metadata.get("assembly_format").getAsString());
        assertTrue(read().get("assembly").getAsString().contains("after"));
        assertEquals(keptMethod, AndroidAssemblySupport.disassemble(owner(), "keep", "()V"));
        assertArrayEquals(other, AndroidAssemblySupport.bytes(workspace.getPrimaryResource()
                .getAndroidClassBundles().get("classes2.dex").get("sample/Other"), 35));
        AndroidClassInfo reopened = DexIOUtil.read(AndroidAssemblySupport.bytes(owner(), 35)).get(OWNER);
        assertTrue(AndroidAssemblySupport.disassemble(reopened, "greet", "()V").contains("after"));
    }

    @Test
    void dryRunAndNoopLeaveTheLiveTreeUntouched() throws IOException {
        JsonObject original = read();
        byte[] before = AndroidAssemblySupport.bytes(owner(), 35);
        JsonObject arguments = patch(original, "after");
        arguments.addProperty("dry_run", true);
        JsonObject result = patchTool.execute(arguments);
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        assertFalse(result.getAsJsonObject("structuredContent").get("applied").getAsBoolean());
        assertTrue(result.getAsJsonObject("structuredContent").get("changed").getAsBoolean());
        assertArrayEquals(before, AndroidAssemblySupport.bytes(owner(), 35));
        JsonObject noop = patchTool.execute(patch(original, "before"));
        assertFalse(noop.get("isError").getAsBoolean(), noop.toString());
        assertFalse(noop.getAsJsonObject("structuredContent").get("changed").getAsBoolean());
        assertArrayEquals(before, AndroidAssemblySupport.bytes(owner(), 35));
    }

    @Test
    void rejectsStaleHashesInvalidAssemblyAndDeclarationChangesWithoutWrites() throws IOException {
        JsonObject original = read();
        byte[] before = AndroidAssemblySupport.bytes(owner(), 35);
        JsonObject stale = patch(original, "after");
        stale.addProperty("expected_assembly_sha256", "0".repeat(64));
        assertRejected(stale, before);
        for (String invalid : List.of("\"unterminated", ".method public static greet ()V { code: { bad_opcode } }",
                original.get("assembly").getAsString().replace("greet", "renamed"),
                original.get("assembly").getAsString().replace("public static", "private static"))) {
            JsonObject arguments = patch(original, "after");
            arguments.addProperty("assembly", invalid);
            assertRejected(arguments, before);
        }
    }

    @Test
    void requiresDexSelectionWhenOwnersAreAmbiguous() throws IOException {
        var second = workspace.getPrimaryResource().getAndroidClassBundles().get("classes2.dex");
        second.put(DexIOUtil.read(AndroidFixture.dex(OWNER, "second")).get(OWNER));
        JsonObject original = read();
        JsonObject arguments = patch(original, "after");
        arguments.remove("dex_name");
        JsonObject ambiguous = patchTool.execute(arguments);
        assertTrue(ambiguous.get("isError").getAsBoolean(), ambiguous.toString());
        assertEquals(2, ambiguous.getAsJsonObject("structuredContent").getAsJsonArray("candidates").size());
        arguments.addProperty("dex_name", "classes.dex");
        JsonObject result = patchTool.execute(arguments);
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        assertTrue(AndroidAssemblySupport.disassemble(second.get(OWNER), "greet", "()V").contains("second"));
    }

    private void assertRejected(JsonObject arguments, byte[] before) throws IOException {
        JsonObject result = patchTool.execute(arguments);
        assertTrue(result.get("isError").getAsBoolean(), result.toString());
        assertFalse(result.getAsJsonObject("structuredContent").get("applied").getAsBoolean());
        assertArrayEquals(before, AndroidAssemblySupport.bytes(owner(), 35));
    }

    private AndroidClassInfo owner() {
        return workspace.getPrimaryResource().getAndroidClassBundles().get("classes.dex").get(OWNER);
    }

    private JsonObject read() {
        JsonObject result = readTool.execute(identity());
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        return result.getAsJsonObject("structuredContent");
    }

    private static JsonObject patch(JsonObject original, String text) {
        JsonObject arguments = identity();
        arguments.addProperty("assembly", original.get("assembly").getAsString().replace("before", text));
        arguments.addProperty("expected_assembly_sha256", original.get("assembly_sha256").getAsString());
        return arguments;
    }

    private static JsonObject identity() {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("scope", "android");
        arguments.addProperty("dex_name", "classes.dex");
        arguments.addProperty("owner", OWNER);
        arguments.addProperty("name", "greet");
        arguments.addProperty("descriptor", "()V");
        return arguments;
    }
}
