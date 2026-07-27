package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.code.CodeBuilder;
import me.darknet.dex.tree.definitions.instructions.Invoke;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.AndroidClassInfoBuilder;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.search.SearchServiceConfig;
import software.coley.recaf.services.search.match.StringPredicateProvider;
import software.coley.recaf.util.android.DexIOUtil;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.bundle.BasicAndroidClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AndroidClassToolsTest {
    private static final String CLASS_NAME = "sample/android/Greeter";
    private static final String STRING_VALUE = "android-search-needle";
    private static final String DEX_NAME = "classes2.dex";

    @Test
    void listsAndroidClassesWithStableDexIdentity() {
        Workspace workspace = workspace(androidClass(CLASS_NAME), DEX_NAME);
        ListClassesTool tool = new ListClassesTool(() -> true, () -> workspace);
        JsonObject arguments = androidArguments();

        JsonObject structured = tool.execute(arguments)
                .getAsJsonObject("structuredContent");

        assertEquals("primary_android", structured.get("scope").getAsString());
        assertEquals(1, structured.get("total").getAsInt());
        assertEquals(CLASS_NAME, structured.getAsJsonArray("classes").get(0).getAsString());
        JsonObject entry = structured.getAsJsonArray("class_entries")
                .get(0).getAsJsonObject();
        assertEquals("android", entry.get("class_scope").getAsString());
        assertEquals(DEX_NAME, entry.get("dex_name").getAsString());
    }

    @Test
    void decompilesAndroidThroughExplicitRecafJvmConversion() {
        AndroidClassInfo androidClass = androidClass(CLASS_NAME);
        Workspace workspace = workspace(androidClass, DEX_NAME);
        DecompileClassTool tool = new DecompileClassTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, convertedClass, requested) -> {
                    assertEquals(CLASS_NAME, convertedClass.getName());
                    assertTrue(convertedClass.getBytecode().length > 0);
                    return new DecompileClassTool.Decompilation(
                            "CFR",
                            "0.152",
                            new DecompileResult("class Greeter { String value = \""
                                    + STRING_VALUE + "\"; }", 0)
                    );
                }
        );
        JsonObject arguments = androidArguments();
        arguments.addProperty("class_name", CLASS_NAME);

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals("recaf_android_to_jvm",
                structured.get("decompilation_input").getAsString());
        assertEquals(DEX_NAME, structured.get("dex_name").getAsString());
        assertTrue(structured.get("source").getAsString().contains(STRING_VALUE));
    }

    @Test
    void returnsParseableReconstructedSingleClassDex() throws IOException {
        Workspace workspace = workspace(androidClass(CLASS_NAME), DEX_NAME);
        GetClassBytecodeTool tool = new GetClassBytecodeTool(
                () -> true,
                () -> workspace
        );
        JsonObject arguments = androidArguments();
        arguments.addProperty("class_name", CLASS_NAME);

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");
        byte[] bytes = Base64.getDecoder().decode(
                structured.get("bytecode_base64").getAsString()
        );

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals("dex", structured.get("format").getAsString());
        assertEquals("reconstructed_single_class_dex",
                structured.get("representation").getAsString());
        assertFalse(structured.get("original_bytes").getAsBoolean());
        assertTrue(new String(bytes, 0, 4).equals("dex\n"));
        assertNotNull(DexIOUtil.read(bytes).get(CLASS_NAME));

        arguments.addProperty(
                ConditionalReadSupport.ARGUMENT,
                structured.get("sha256").getAsString()
        );
        JsonObject unchanged = tool.execute(arguments)
                .getAsJsonObject("structuredContent");
        assertTrue(unchanged.get("unchanged").getAsBoolean());
        assertFalse(unchanged.has("bytecode_base64"));
        assertEquals("dex", unchanged.get("format").getAsString());
    }

    @Test
    void searchesAndroidSourceStringsAndMemberDeclarations() {
        Workspace workspace = workspace(androidClass(CLASS_NAME), DEX_NAME);
        SearchService searchService = new SearchService(new SearchServiceConfig());
        StringPredicateProvider predicates = new StringPredicateProvider();

        SearchCodeTool codeTool = new SearchCodeTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, convertedClass) -> new DecompileResult(
                        "String value = \"" + STRING_VALUE + "\";",
                        0
                )
        );
        JsonObject codeArguments = searchArguments(STRING_VALUE);
        JsonObject code = codeTool.execute(codeArguments)
                .getAsJsonObject("structuredContent");
        assertEquals(1, code.get("total").getAsInt());
        assertAndroidHit(code.getAsJsonArray("hits"));
        assertEquals("recaf_android_to_jvm",
                code.get("android_decompilation_input").getAsString());

        SearchStringsTool stringsTool = new SearchStringsTool(
                () -> true,
                () -> workspace,
                searchService,
                predicates
        );
        JsonObject strings = stringsTool.execute(searchArguments(STRING_VALUE))
                .getAsJsonObject("structuredContent");
        assertEquals(1, strings.get("total").getAsInt());
        assertAndroidHit(strings.getAsJsonArray("hits"));

        SearchMembersTool membersTool = new SearchMembersTool(
                () -> true,
                () -> workspace,
                searchService,
                predicates
        );
        JsonObject memberArguments = androidArguments();
        memberArguments.addProperty("name", "greet");
        memberArguments.addProperty("kind", "method");
        memberArguments.addProperty("results", "declarations");
        JsonObject memberResult = membersTool.execute(memberArguments);
        assertFalse(memberResult.get("isError").getAsBoolean(), memberResult.toString());
        JsonObject members = memberResult.getAsJsonObject("structuredContent");
        assertEquals(1, members.get("total").getAsInt());
        JsonObject memberHit = members.getAsJsonArray("hits").get(0).getAsJsonObject();
        assertEquals("declaration", memberHit.get("result_type").getAsString());
        assertEquals("android", memberHit.get("class_scope").getAsString());
        assertEquals(DEX_NAME, memberHit.get("dex_name").getAsString());

        JsonArray memberScopes = membersTool.inputSchema()
                .getAsJsonObject("properties")
                .getAsJsonObject("scope")
                .getAsJsonArray("enum");
        assertTrue(memberScopes.contains(new com.google.gson.JsonPrimitive("android")));
    }

    @Test
    void searchesDisassemblesAndGraphsNativeAndroidInstructions() {
        Workspace workspace = workspace(androidClass(CLASS_NAME), DEX_NAME);
        SearchService searchService = new SearchService(new SearchServiceConfig());
        StringPredicateProvider predicates = new StringPredicateProvider();

        SearchInstructionsTool searchTool = new SearchInstructionsTool(
                () -> true,
                () -> workspace,
                searchService,
                predicates
        );
        JsonObject search = searchTool.execute(searchArguments(STRING_VALUE));
        assertFalse(search.get("isError").getAsBoolean(), search.toString());
        JsonObject searchStructured = search.getAsJsonObject("structuredContent");
        assertEquals("primary_android_instructions",
                searchStructured.get("scope").getAsString());
        assertEquals(1, searchStructured.get("classes_selected").getAsInt());
        JsonObject instructionHit = searchStructured.getAsJsonArray("hits")
                .get(0).getAsJsonObject();
        assertEquals("android", instructionHit.get("class_scope").getAsString());
        assertEquals(DEX_NAME, instructionHit.get("dex_name").getAsString());
        assertTrue(instructionHit.get("dex_offset").getAsInt() >= 0);

        GetMethodAssemblyTool assemblyTool = new GetMethodAssemblyTool(
                () -> true,
                () -> workspace,
                unusedAssembler()
        );
        JsonObject assemblyArguments = androidArguments();
        assemblyArguments.addProperty("owner", CLASS_NAME);
        assemblyArguments.addProperty("name", "greet");
        assemblyArguments.addProperty("descriptor", "()V");
        JsonObject assembly = assemblyTool.execute(assemblyArguments);
        assertFalse(assembly.get("isError").getAsBoolean(), assembly.toString());
        JsonObject assemblyStructured = assembly.getAsJsonObject("structuredContent");
        assertEquals("android_dex_method_disassembly",
                assemblyStructured.get("assembly_format").getAsString());
        assertFalse(assemblyStructured.get("editable").getAsBoolean());
        assertFalse(assemblyStructured.get("patch_supported").getAsBoolean());
        assertTrue(assemblyStructured.get("assembly").getAsString()
                .contains(STRING_VALUE));
        assemblyArguments.addProperty(
                ConditionalReadSupport.ARGUMENT,
                assemblyStructured.get("assembly_sha256").getAsString()
        );
        JsonObject unchangedAssembly = assemblyTool.execute(assemblyArguments)
                .getAsJsonObject("structuredContent");
        assertTrue(unchangedAssembly.get("unchanged").getAsBoolean());
        assertFalse(unchangedAssembly.has("assembly"));
        assertEquals(
                "android_dex_method_disassembly",
                unchangedAssembly.get("assembly_format").getAsString()
        );

        CallGraphTool graphTool = new CallGraphTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, currentMethod, request) -> {
                    throw new AssertionError("JVM graph must not run for Android scope");
                }
        );
        JsonObject graphArguments = androidArguments();
        graphArguments.addProperty("owner", CLASS_NAME);
        graphArguments.addProperty("name", "callGreet");
        graphArguments.addProperty("descriptor", "()V");
        graphArguments.addProperty("direction", "calls");
        JsonObject graph = graphTool.execute(graphArguments);
        assertFalse(graph.get("isError").getAsBoolean(), graph.toString());
        JsonObject graphStructured = graph.getAsJsonObject("structuredContent");
        assertEquals("dex_invoke_instructions",
                graphStructured.get("graph_source").getAsString());
        assertEquals(1, graphStructured.get("classes_selected").getAsInt());
        assertEquals(1, graphStructured.get("total").getAsInt());
        JsonObject edge = graphStructured.getAsJsonArray("edges")
                .get(0).getAsJsonObject();
        assertEquals("greet", edge.getAsJsonObject("callee")
                .get("name").getAsString());
        assertEquals("android", edge.getAsJsonObject("callee")
                .get("class_scope").getAsString());

        graphArguments.addProperty("name", "greet");
        graphArguments.addProperty("direction", "callers");
        JsonObject callers = graphTool.execute(graphArguments)
                .getAsJsonObject("structuredContent");
        assertEquals(1, callers.get("total").getAsInt());
        assertEquals("callGreet", callers.getAsJsonArray("edges")
                .get(0).getAsJsonObject()
                .getAsJsonObject("caller")
                .get("name").getAsString());
    }

    private static void assertAndroidHit(JsonArray hits) {
        assertEquals(1, hits.size());
        JsonObject hit = hits.get(0).getAsJsonObject();
        assertEquals(CLASS_NAME, hit.get("class_name").getAsString());
        assertEquals("android", hit.get("class_scope").getAsString());
        assertEquals(DEX_NAME, hit.get("dex_name").getAsString());
    }

    private static JsonObject androidArguments() {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("scope", "android");
        arguments.addProperty("dex_name", DEX_NAME);
        return arguments;
    }

    private static JsonObject searchArguments(String text) {
        JsonObject arguments = androidArguments();
        arguments.addProperty("text", text);
        return arguments;
    }

    private static AndroidClassInfo androidClass(String name) {
        ClassDefinition definition = new ClassDefinition(
                Types.instanceTypeFromInternalName(name),
                Types.OBJECT,
                Opcodes.ACC_PUBLIC
        );
        MethodMember method = new MethodMember(
                "greet",
                Types.methodTypeFromDescriptor("()V"),
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC
        );
        method.setCode(new CodeBuilder()
                .registers(1)
                .const_string(0, STRING_VALUE)
                .return_void()
                .build());
        definition.putMethod(method);
        MethodMember caller = new MethodMember(
                "callGreet",
                Types.methodTypeFromDescriptor("()V"),
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC
        );
        caller.setCode(new CodeBuilder()
                .registers(0)
                .invoke(
                        Invoke.STATIC,
                        Types.instanceTypeFromInternalName(name),
                        "greet",
                        Types.methodTypeFromDescriptor("()V")
                )
                .return_void()
                .build());
        definition.putMethod(caller);
        return new AndroidClassInfoBuilder(definition).build();
    }

    private static MethodAssemblySupport.MethodAssembler unusedAssembler() {
        return new MethodAssemblySupport.MethodAssembler() {
            @Override
            public MethodAssemblySupport.OperationResult<String> disassemble(
                    Workspace workspace,
                    MethodAssemblySupport.Target target) {
                throw new AssertionError("JVM assembler must not run for Android scope");
            }

            @Override
            public MethodAssemblySupport.OperationResult<JvmClassInfo> assemble(
                    Workspace workspace,
                    MethodAssemblySupport.Target target,
                    String assembly) {
                throw new AssertionError("JVM assembler must not run for Android scope");
            }
        };
    }

    private static Workspace workspace(AndroidClassInfo classInfo, String dexName) {
        BasicAndroidClassBundle bundle = new BasicAndroidClassBundle(39, new byte[0]);
        bundle.put(classInfo);
        Map<String, AndroidClassBundle> bundles = new LinkedHashMap<>();
        bundles.put(dexName, bundle);
        WorkspaceResource primary = new WorkspaceResourceBuilder()
                .withAndroidClassBundles(bundles)
                .build();
        return new BasicWorkspace(primary, List.of(), false);
    }
}
