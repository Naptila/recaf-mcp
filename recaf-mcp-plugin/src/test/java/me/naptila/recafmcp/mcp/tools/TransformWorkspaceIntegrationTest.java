package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.deobfuscation.transform.generic.DeadCodeRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.FrameRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.LongAnnotationRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.OpaqueConstantFoldingTransformer;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.inheritance.InheritanceGraphServiceConfig;
import software.coley.recaf.services.mapping.MappingApplierConfig;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.MappingListeners;
import software.coley.recaf.services.mapping.MappingListenersConfig;
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManagerConfig;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.services.transform.TransformationApplierConfig;
import software.coley.recaf.services.transform.TransformationApplierService;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.services.transform.TransformationManager;
import software.coley.recaf.services.workspace.BasicWorkspaceManager;
import software.coley.recaf.services.workspace.WorkspaceManagerConfig;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class TransformWorkspaceIntegrationTest {
    private static final String OWNER = "sample/Obfuscated";
    private static final String PLAN = """
            {"class_prefix":"sample/","phases":[
              {"id":"cleanup","transformers":["long_annotations"],
                "options":{"long_annotations":{"max_length":40}}},
              {"id":"fold","transformers":["opaque_constants"]},
              {"id":"finish","transformers":["dead_code"]}]}
            """;
    private Workspace workspace;
    private BasicWorkspaceManager manager;
    private TransformationManager transformers;
    private TransformWorkspaceTool tool;

    @BeforeEach
    void setup() {
        var bundle = new BasicJvmClassBundle();
        bundle.put(fixture(OWNER));
        bundle.put(fixture("other/Untouched"));
        var library = new BasicJvmClassBundle();
        library.put(fixture("library/Untouched"));
        workspace = new BasicWorkspace(new WorkspaceResourceBuilder().withJvmClassBundle(bundle).build(),
                List.of(new WorkspaceResourceBuilder().withJvmClassBundle(library).build()), true);
        manager = new BasicWorkspaceManager(new WorkspaceManagerConfig());
        var listeners = new MappingListeners(new MappingListenersConfig());
        var graph = new InheritanceGraphService(manager, listeners, new InheritanceGraphServiceConfig());
        var aggregate = new AggregateMappingManager(new AggregateMappingManagerConfig(), manager);
        var mappings = new MappingApplierService(new MappingApplierConfig(), graph, aggregate, listeners, manager);
        Map<Class<? extends JvmClassTransformer>, Supplier<JvmClassTransformer>> suppliers = new LinkedHashMap<>();
        suppliers.put(LongAnnotationRemovingTransformer.class, LongAnnotationRemovingTransformer::new);
        suppliers.put(OpaqueConstantFoldingTransformer.class, () -> new OpaqueConstantFoldingTransformer(graph));
        suppliers.put(DeadCodeRemovingTransformer.class, DeadCodeRemovingTransformer::new);
        suppliers.put(FrameRemovingTransformer.class, FrameRemovingTransformer::new);
        transformers = new TransformationManager(suppliers);
        var config = new TransformationApplierConfig();
        config.doParallelize().setValue(false);
        var service = new TransformationApplierService(transformers, graph, mappings, manager, config);
        manager.setCurrentIgnoringConditions(workspace);
        tool = new TransformWorkspaceTool(manager, service);
    }

    @AfterEach
    void close() {
        manager.setCurrentIgnoringConditions(null);
    }

    @Test
    void runsNativePhasesOnDetachedClassesAndKeepsDryRunUnchanged() {
        byte[] before = owner().getBytecode().clone();
        JsonObject arguments = json(PLAN);
        arguments.addProperty("dry_run", true);
        JsonObject result = tool.execute(arguments);
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        var summary = result.getAsJsonObject("structuredContent");
        assertFalse(summary.get("applied").getAsBoolean());
        assertEquals(1, summary.get("modified_class_count").getAsInt());
        assertEquals(3, summary.getAsJsonArray("phase_results").size());
        assertTrue(summary.getAsJsonArray("phase_results").get(0).getAsJsonObject()
                .get("modified_class_count").getAsInt() > 0);
        assertArrayEquals(before, owner().getBytecode());
        assertEquals(1, owner().getAnnotations().size());
        assertEquals(1, workspace.getSupportingResources().getFirst().getJvmClassBundle().size());
    }

    @Test
    void appliesCleanupAndConstantFoldingOnlyAfterAllPhasesSucceed() throws ReflectiveOperationException {
        byte[] other = workspace.getPrimaryResource().getJvmClassBundle().get("other/Untouched").getBytecode().clone();
        JsonObject result = tool.execute(json(PLAN));
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        assertTrue(result.getAsJsonObject("structuredContent").get("applied").getAsBoolean());
        assertTrue(owner().getAnnotations().isEmpty());
        ClassNode node = new ClassNode();
        owner().getClassReader().accept(node, 0);
        assertTrue(node.methods.getFirst().instructions.iterator().hasNext());
        for (var instruction : node.methods.getFirst().instructions) {
            assertNotEquals(Opcodes.IADD, instruction.getOpcode());
        }
        byte[] code = owner().getBytecode();
        Class<?> loaded = new ClassLoader() {
            Class<?> define() { return defineClass("sample.Obfuscated", code, 0, code.length); }
        }.define();
        assertEquals(5, loaded.getMethod("value").invoke(null));
        assertArrayEquals(other, workspace.getPrimaryResource().getJvmClassBundle().get("other/Untouched").getBytecode());
        assertEquals(1, workspace.getSupportingResources().getFirst().getJvmClassBundle().size());
    }

    @Test
    void laterPhaseFailureDiscardsEarlierCleanupAndSkipsFollowingPhases() {
        transformers.registerJvmClassTransformer(FrameRemovingTransformer.class, () -> new FrameRemovingTransformer() {
            @Override
            public void transform(JvmTransformerContext context, Workspace current, WorkspaceResource resource,
                                  JvmClassBundle bundle, JvmClassInfo info) throws TransformationException {
                throw new TransformationException("injected later-phase failure");
            }
        });
        byte[] before = owner().getBytecode().clone();
        JsonObject result = tool.execute(json(PLAN.replace("opaque_constants", "frame_removal")));
        assertTrue(result.get("isError").getAsBoolean(), result.toString());
        var summary = result.getAsJsonObject("structuredContent");
        assertFalse(summary.get("applied").getAsBoolean());
        assertEquals("failed", summary.getAsJsonArray("phase_results").get(1).getAsJsonObject().get("status").getAsString());
        assertEquals("skipped", summary.getAsJsonArray("phase_results").get(2).getAsJsonObject().get("status").getAsString());
        assertArrayEquals(before, owner().getBytecode());
        assertEquals(1, owner().getAnnotations().size());
    }

    private JvmClassInfo owner() {
        return workspace.getPrimaryResource().getJvmClassBundle().get(OWNER);
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static JvmClassInfo fixture(String name) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V22, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitAnnotation("Lsample/" + "A".repeat(80) + ";", true).visitEnd();
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()I", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_2);
        method.visitInsn(Opcodes.ICONST_3);
        method.visitInsn(Opcodes.IADD);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(2, 0);
        method.visitEnd();
        writer.visitEnd();
        return new JvmClassInfoBuilder(writer.toByteArray()).build();
    }
}
