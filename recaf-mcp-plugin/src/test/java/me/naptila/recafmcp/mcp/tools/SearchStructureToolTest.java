package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.naptila.recafmcp.AndroidFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.util.android.DexIOUtil;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.bundle.BasicAndroidClassBundle;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SearchStructureToolTest {
    private Workspace workspace;
    private SearchStructureTool tool;

    @BeforeEach
    void setup() throws IOException {
        var jvm = new BasicJvmClassBundle();
        jvm.put(jvm("sample/Greeter", true));
        jvm.put(jvm("sample/Other", false));
        Map<String, AndroidClassBundle> bundles = new LinkedHashMap<>();
        for (String dexName : List.of("classes.dex", "classes2.dex")) {
            var bundle = new BasicAndroidClassBundle(35, new byte[0]);
            bundle.put(DexIOUtil.read(AndroidFixture.dex("sample/Greeter", "hello")).get("sample/Greeter"));
            bundles.put(dexName, bundle);
        }
        workspace = new BasicWorkspace(new WorkspaceResourceBuilder().withJvmClassBundle(jvm)
                .withAndroidClassBundles(bundles).build(), List.of(), false);
        tool = new SearchStructureTool(() -> true, () -> workspace);
    }

    @AfterEach
    void close() throws IOException {
        workspace.close();
    }

    @Test
    void combinesClassFieldMethodAccessAndJvmOpcodes() {
        JsonObject result = search("""
                {"query":{"name":"sample/","regex":true,"access_required":1,
                  "fields":[{"name":"value","descriptor":"I","access_required":1}],
                  "methods":[{"name":"greet","descriptor":"()V","access_required":9,
                    "access_forbidden":2,"opcodes":[18,177]}]}}
                """);
        assertEquals(1, result.get("total").getAsInt());
        assertEquals("sample/Greeter", result.getAsJsonArray("hits").get(0).getAsJsonObject()
                .get("class_name").getAsString());
        assertEquals("recaf_class_query", result.get("search_backend").getAsString());
        assertEquals(0, search("""
                {"query":{"methods":[{"name":"greet","descriptor":"(I)V"}]}}
                """).get("total").getAsInt());
        assertEquals(0, search("""
                {"query":{"methods":[{"opcodes":[18,18]}]}}
                """).get("total").getAsInt());
    }

    @Test
    void matchesNativeDalvikInstructionsAndSelectsAnExactDex() {
        JsonObject result = search("""
                {"scope":"android","dex_name":"classes2.dex",
                 "query":{"name":"sample/Greeter","methods":[
                   {"name":"greet","descriptor":"()V","opcodes":[26,14]}]}}
                """);
        assertEquals(1, result.get("total").getAsInt());
        var hit = result.getAsJsonArray("hits").get(0).getAsJsonObject();
        assertEquals("android", hit.get("class_scope").getAsString());
        assertEquals("classes2.dex", hit.get("dex_name").getAsString());
        assertEquals(0, search("""
                {"scope":"android","query":{"methods":[{"opcodes":[177]}]}}
                """).get("total").getAsInt());
    }

    @Test
    void preservesJvmAndDuplicateAndroidIdentitiesAcrossStablePages() {
        JsonObject first = search("""
                {"scope":"all","query":{"name":"sample/Greeter"},"limit":1}
                """);
        assertEquals(3, first.get("total").getAsInt());
        assertEquals(1, first.get("next_offset").getAsInt());
        assertFalse(first.get("collection_truncated").getAsBoolean());
        assertEquals("classes.dex", first.getAsJsonArray("hits").get(0).getAsJsonObject()
                .get("dex_name").getAsString());
        JsonObject last = search("""
                {"scope":"all","query":{"name":"sample/Greeter"},"offset":2,"limit":1}
                """);
        assertEquals("jvm", last.getAsJsonArray("hits").get(0).getAsJsonObject()
                .get("class_scope").getAsString());
        assertTrue(last.get("next_offset").isJsonNull());
        assertEquals(0, search("""
                {"scope":"all","query":{"name":"sample/Greeter"},"offset":2147483647}
                """).get("returned").getAsInt());
    }

    @Test
    void emptyListsRequireAbsenceAndMemberMatchersUseDistinctAssignments() {
        assertEquals(1, search("""
                {"query":{"fields":[]}}
                """).get("total").getAsInt());
        assertEquals(0, search("""
                {"query":{"methods":[]}}
                """).get("total").getAsInt());
        assertEquals(0, search("""
                {"query":{"methods":[{"name":"greet"},{"name":"greet"}]}}
                """).get("total").getAsInt());
    }

    @Test
    void rejectsUnknownMalformedUnboundedAndContradictoryConstraints() {
        for (String arguments : List.of(
                "{}", "{\"query\":{}}", "{\"query\":{\"unknown\":1}}",
                "{\"query\":{\"access_required\":1,\"access_forbidden\":1}}",
                "{\"query\":{\"name\":\"[\",\"regex\":true}}",
                "{\"query\":{\"regex\":true}}",
                "{\"query\":{\"fields\":[{\"descriptor\":\"IJ\"}]}}",
                "{\"query\":{\"fields\":[{\"descriptor\":\"[V\"}]}}",
                "{\"query\":{\"methods\":[{\"descriptor\":\"(V)V\"}]}}",
                "{\"query\":{\"methods\":[{\"opcodes\":[1.5]}]}}",
                "{\"query\":{\"methods\":[{\"opcodes\":[256]}]}}",
                "{\"query\":{\"methods\":[1]}}",
                "{\"query\":{\"methods\":[{},{},{},{},{},{},{},{},{}]}}",
                "{\"dex_name\":\"classes.dex\",\"query\":{\"name\":\"X\"}}")) {
            assertThrows(IllegalArgumentException.class, () -> tool.execute(json(arguments)), arguments);
        }
    }

    @Test
    void reportsMissingWorkspaceAndAdvertisesNestedValidation() {
        var unavailable = new SearchStructureTool(() -> false, () -> null);
        assertTrue(unavailable.execute(json("{\"query\":{\"name\":\"X\"}}"))
                .get("isError").getAsBoolean());
        JsonObject schema = tool.inputSchema();
        assertEquals("query", schema.getAsJsonArray("required").get(0).getAsString());
        JsonObject querySchema = schema.getAsJsonObject("properties").getAsJsonObject("query");
        assertFalse(querySchema.get("additionalProperties").getAsBoolean());
        assertEquals(8, querySchema.getAsJsonObject("properties").getAsJsonObject("methods")
                .get("maxItems").getAsInt());
    }

    private JsonObject search(String arguments) {
        JsonObject result = tool.execute(json(arguments));
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        return result.getAsJsonObject("structuredContent");
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static software.coley.recaf.info.JvmClassInfo jvm(String name, boolean field) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V22, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        if (field) {
            writer.visitField(Opcodes.ACC_PUBLIC, "value", "I", null, null).visitEnd();
        }
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "greet", "()V", null, null);
        method.visitCode();
        method.visitLdcInsn("hello");
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return new JvmClassInfoBuilder(writer.toByteArray()).build();
    }
}
