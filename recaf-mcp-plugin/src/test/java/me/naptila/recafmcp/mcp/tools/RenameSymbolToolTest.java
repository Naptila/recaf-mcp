package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonObject;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.code.CodeBuilder;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.AndroidClassInfoBuilder;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.bundle.BasicAndroidClassBundle;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenameSymbolToolTest {
    private static final String ANDROID_CLASS = "sample/android/Greeter";
    private static final String DEX_NAME = "classes2.dex";

    @Test
    void renamesExactDescriptorAwareMethod() throws IOException {
        JvmClassInfo classInfo = readThisClass();
        Workspace workspace = workspace(classInfo);
        AtomicReference<RenameSymbolTool.RenameRequest> applied = new AtomicReference<>();
        RenameSymbolTool tool = new RenameSymbolTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, request) -> {
                    assertEquals(workspace, currentWorkspace);
                    applied.set(request);
                    return new MappingMutationSupport.Summary(List.of(
                            change(classInfo.getName(), classInfo.getName()),
                            change("example/Caller", "example/Caller"),
                            change("example/OtherCaller", "example/OtherCaller")
                    ));
                }
        );
        JsonObject arguments = memberArguments(
                classInfo.getName(),
                "fixtureMethod",
                "(I)Ljava/lang/String;",
                "renamedFixtureMethod"
        );

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals(RenameSymbolTool.SymbolKind.METHOD, applied.get().kind());
        assertEquals("(I)Ljava/lang/String;", applied.get().descriptor());
        assertEquals(3, structured.get("changed_classes").getAsInt());
        assertEquals("method", structured.get("kind").getAsString());
        assertEquals("primary_jvm", structured.get("scope").getAsString());
        assertEquals("jvm", structured.get("class_scope").getAsString());
        assertTrue(structured.get("save_required").getAsBoolean());
        assertFalse(structured.get("in_memory_only").getAsBoolean());
    }

    @Test
    void rejectsWrongDescriptorAndDoesNotApplyMapping() throws IOException {
        JvmClassInfo classInfo = readThisClass();
        Workspace workspace = workspace(classInfo);
        RenameSymbolTool tool = new RenameSymbolTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, request) -> {
                    throw new AssertionError("mapping must not run");
                }
        );

        JsonObject result = tool.execute(memberArguments(
                classInfo.getName(),
                "fixtureMethod",
                "(J)Ljava/lang/String;",
                "renamedFixtureMethod"
        ));

        assertTrue(result.get("isError").getAsBoolean());
        assertTrue(result.getAsJsonObject("structuredContent")
                .get("error").getAsString().contains("exact descriptor"));
    }

    @Test
    void validatesClassTargetsAndArgumentShapes() throws IOException {
        JvmClassInfo classInfo = readThisClass();
        Workspace workspace = workspace(classInfo);
        RenameSymbolTool tool = new RenameSymbolTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, request) -> summary(request)
        );

        JsonObject classRename = new JsonObject();
        classRename.addProperty("kind", "class");
        classRename.addProperty("name", classInfo.getName());
        classRename.addProperty("new_name", classInfo.getName() + "Renamed");
        assertFalse(tool.execute(classRename).get("isError").getAsBoolean());

        JsonObject missingOwner = new JsonObject();
        missingOwner.addProperty("kind", "field");
        missingOwner.addProperty("name", "value");
        missingOwner.addProperty("new_name", "renamedValue");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(missingOwner));

        JsonObject invalidClass = new JsonObject();
        invalidClass.addProperty("kind", "class");
        invalidClass.addProperty("name", classInfo.getName());
        invalidClass.addProperty("new_name", "invalid.class");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(invalidClass));

        JsonObject sameName = new JsonObject();
        sameName.addProperty("kind", "class");
        sameName.addProperty("name", classInfo.getName());
        sameName.addProperty("new_name", classInfo.getName());
        assertThrows(IllegalArgumentException.class, () -> tool.execute(sameName));
    }

    @Test
    void reportsMissingWorkspaceAndMappingFailureAsToolErrors() throws IOException {
        JvmClassInfo classInfo = readThisClass();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("kind", "class");
        arguments.addProperty("name", classInfo.getName());
        arguments.addProperty("new_name", classInfo.getName() + "Renamed");

        RenameSymbolTool unavailable = new RenameSymbolTool(
                () -> false,
                () -> null,
                (workspace, request) -> summary(request)
        );
        assertTrue(unavailable.execute(arguments).get("isError").getAsBoolean());

        Workspace workspace = workspace(classInfo);
        RenameSymbolTool failing = new RenameSymbolTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, request) -> {
                    throw new IllegalStateException("mapping exploded");
                }
        );
        JsonObject result = failing.execute(arguments);
        assertTrue(result.get("isError").getAsBoolean());
        assertTrue(result.getAsJsonObject("structuredContent")
                .get("error").getAsString().contains("mapping exploded"));
    }

    @Test
    void renamesAndroidMethodWithStableDexIdentityInMemory() {
        AndroidClassInfo classInfo = androidClass(ANDROID_CLASS);
        Workspace workspace = androidWorkspace(classInfo, DEX_NAME);
        AtomicReference<RenameSymbolTool.RenameRequest> applied = new AtomicReference<>();
        RenameSymbolTool tool = new RenameSymbolTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, request) -> {
                    assertEquals(workspace, currentWorkspace);
                    assertEquals(ClassToolSupport.Scope.ANDROID,
                            request.selection().scope());
                    assertEquals(DEX_NAME, request.target().dexName());
                    assertEquals(classInfo, request.target().classInfo());
                    applied.set(request);
                    return new MappingMutationSupport.Summary(List.of(
                            new MappingMutationSupport.Change(
                                    ANDROID_CLASS,
                                    ANDROID_CLASS,
                                    ClassToolSupport.Scope.ANDROID,
                                    DEX_NAME
                            )
                    ));
                }
        );
        JsonObject arguments = memberArguments(
                ANDROID_CLASS,
                "greet",
                "()V",
                "renamedGreet"
        );
        arguments.addProperty("scope", "android");
        arguments.addProperty("dex_name", DEX_NAME);

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        assertEquals(RenameSymbolTool.SymbolKind.METHOD, applied.get().kind());
        assertEquals("android", structured.get("class_scope").getAsString());
        assertEquals("primary_android", structured.get("scope").getAsString());
        assertEquals(DEX_NAME, structured.get("dex_name").getAsString());
        assertEquals(1, structured.get("android_changed_classes").getAsInt());
        assertEquals(0, structured.get("jvm_changed_classes").getAsInt());
        assertEquals(DEX_NAME, structured.getAsJsonArray("dex_bundles_changed")
                .get(0).getAsString());
        assertTrue(structured.get("workspace_mutated_in_memory").getAsBoolean());
        assertTrue(structured.get("in_memory_only").getAsBoolean());
        assertFalse(structured.get("workspace_archive_export_supported").getAsBoolean());
        assertFalse(structured.get("save_required").getAsBoolean());

        JsonObject properties = tool.inputSchema().getAsJsonObject("properties");
        assertTrue(properties.has("scope"));
        assertTrue(properties.has("dex_name"));
    }

    @Test
    void rejectsAmbiguousAndroidClassIdentityAcrossDexBundles() {
        AndroidClassInfo first = androidClass(ANDROID_CLASS);
        AndroidClassInfo second = androidClass(ANDROID_CLASS);
        Map<String, AndroidClassBundle> bundles = new LinkedHashMap<>();
        BasicAndroidClassBundle firstBundle = new BasicAndroidClassBundle(39, new byte[0]);
        firstBundle.put(first);
        bundles.put("classes.dex", firstBundle);
        BasicAndroidClassBundle secondBundle = new BasicAndroidClassBundle(39, new byte[0]);
        secondBundle.put(second);
        bundles.put(DEX_NAME, secondBundle);
        Workspace workspace = androidWorkspace(bundles);
        RenameSymbolTool tool = new RenameSymbolTool(
                () -> true,
                () -> workspace,
                (currentWorkspace, request) -> {
                    throw new AssertionError("ambiguous mapping must not run");
                }
        );
        JsonObject arguments = memberArguments(
                ANDROID_CLASS,
                "greet",
                "()V",
                "renamedGreet"
        );
        arguments.addProperty("scope", "android");
        arguments.addProperty("dex_name", DEX_NAME);

        JsonObject result = tool.execute(arguments);

        assertTrue(result.get("isError").getAsBoolean());
        assertTrue(result.getAsJsonObject("structuredContent")
                .get("error").getAsString().contains("multiple primary class domains"));
    }

    private static JsonObject memberArguments(String owner,
                                              String name,
                                              String descriptor,
                                              String newName) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("kind", "method");
        arguments.addProperty("owner", owner);
        arguments.addProperty("name", name);
        arguments.addProperty("descriptor", descriptor);
        arguments.addProperty("new_name", newName);
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

    private static Workspace androidWorkspace(AndroidClassInfo classInfo,
                                              String dexName) {
        BasicAndroidClassBundle bundle = new BasicAndroidClassBundle(39, new byte[0]);
        bundle.put(classInfo);
        Map<String, AndroidClassBundle> bundles = new LinkedHashMap<>();
        bundles.put(dexName, bundle);
        return androidWorkspace(bundles);
    }

    private static Workspace androidWorkspace(Map<String, AndroidClassBundle> bundles) {
        WorkspaceResource primary = new WorkspaceResourceBuilder()
                .withAndroidClassBundles(bundles)
                .build();
        return new BasicWorkspace(primary, List.of(), false);
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
                .registers(0)
                .return_void()
                .build());
        definition.putMethod(method);
        return new AndroidClassInfoBuilder(definition).build();
    }

    private static MappingMutationSupport.Summary summary(
            RenameSymbolTool.RenameRequest request) {
        String oldClassName = request.kind() == RenameSymbolTool.SymbolKind.CLASS
                ? request.name()
                : request.owner();
        String newClassName = request.kind() == RenameSymbolTool.SymbolKind.CLASS
                ? request.newName()
                : oldClassName;
        return new MappingMutationSupport.Summary(List.of(
                change(oldClassName, newClassName)
        ));
    }

    private static MappingMutationSupport.Change change(String oldName,
                                                        String newName) {
        return new MappingMutationSupport.Change(
                oldName,
                newName,
                ClassToolSupport.Scope.JVM,
                null
        );
    }

    private static JvmClassInfo readThisClass() throws IOException {
        String resourceName = "/" + RenameSymbolToolTest.class.getName()
                .replace('.', '/') + ".class";
        try (InputStream input = RenameSymbolToolTest.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("test class resource is missing");
            }
            return new JvmClassInfoBuilder(input.readAllBytes()).build();
        }
    }

    @SuppressWarnings("unused")
    private static String fixtureMethod(int value) {
        return Integer.toString(value);
    }
}
