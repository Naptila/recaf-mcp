package me.naptila.recafmcp.mcp;

import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.internal.HealthHandler;
import me.naptila.recafmcp.mcp.tools.GetRecafScriptHelpTool;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecafRuntimeInfoTest {
    @Test
    void readsRuntimeConstantsInsteadOfInliningTheBuildRevision() {
        ClassWriter writer = new ClassWriter(0);
        String name = "software.coley.recaf.RecafBuildConfig";
        writer.visit(Opcodes.V22, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null,
                "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "VERSION", "Ljava/lang/String;", null, "runtime-test").visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "GIT_SHA", "Ljava/lang/String;", null, "different-runtime-revision").visitEnd();
        writer.visitEnd();
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
                if (requested.equals(name)) {
                    byte[] bytes = writer.toByteArray();
                    Class<?> loaded = findLoadedClass(requested);
                    return loaded != null ? loaded : defineClass(requested, bytes, 0, bytes.length);
                }
                return super.loadClass(requested, resolve);
            }
        };
        RecafRuntimeInfo runtime = RecafRuntimeInfo.detect(loader);
        assertEquals("runtime-test", runtime.version());
        assertEquals("different-runtime-revision", runtime.commit());
        assertNotEquals(RecafRuntimeInfo.BUILD_COMMIT, runtime.commit());
        assertTrue(runtime.compatible(), runtime.errors().toString());

        WorkerHealth health = new WorkerHealth(() -> false, null, "test", runtime);
        try (WorkerOperationCoordinator coordinator = new WorkerOperationCoordinator()) {
            JsonObject result = new HealthHandler(health,
                    new McpToolRegistry(List.of(new GetRecafScriptHelpTool())), coordinator).get();
            assertEquals(runtime.commit(), result.get("recaf_commit").getAsString());
            assertEquals(RecafRuntimeInfo.BUILD_COMMIT, result.get("recaf_build_commit").getAsString());
            assertTrue(result.get("recaf_compatible").getAsBoolean());
        }
    }

    @Test
    void missingApiPreventsReadinessAndReportsTheBoundary() {
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("me.darknet.assembler.error.Outcome")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        RecafRuntimeInfo runtime = RecafRuntimeInfo.detect(loader);
        assertFalse(runtime.compatible());
        WorkerHealth health = new WorkerHealth(() -> true, "/input.jar", "test", runtime);
        health.markWorkspaceReady("/input.jar");
        assertFalse(health.isReady());
        assertTrue(health.getLastError().contains("Jasm Outcome API"));
    }

    @Test
    void missingStructuralApiIsReportedBeforeWorkspaceReadiness() {
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.endsWith("ClassQueryBuilder")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        RecafRuntimeInfo runtime = RecafRuntimeInfo.detect(loader);
        assertFalse(runtime.compatible());
        assertTrue(runtime.errors().stream().anyMatch(error -> error.contains("structural query")));
    }

    @Test
    void missingAndroidConversionApiIsReportedBeforeWorkspaceReadiness() {
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("me.darknet.dex.convert.factory.IrOptimizerFactory")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        RecafRuntimeInfo runtime = RecafRuntimeInfo.detect(loader);
        assertFalse(runtime.compatible());
        assertTrue(runtime.errors().stream().anyMatch(error -> error.contains("DEX fidelity")));
    }

    @Test
    void missingAndroidLoweringPolicyIsReportedBeforeWorkspaceReadiness() {
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("me.darknet.dex.convert.ir.lowering.JvmLoweringPolicy")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        RecafRuntimeInfo runtime = RecafRuntimeInfo.detect(loader);
        assertFalse(runtime.compatible());
        assertTrue(runtime.errors().stream().anyMatch(error -> error.contains("DEX fidelity")));
    }

    @Test
    void scriptHelpReportsRuntimeAndCompilationSeparately() throws ReflectiveOperationException {
        JsonObject result = new GetRecafScriptHelpTool().execute(new JsonObject())
                .getAsJsonObject("structuredContent");
        Object actual = Class.forName("software.coley.recaf.RecafBuildConfig")
                .getField("GIT_SHA").get(null);
        assertEquals(actual, result.get("recaf_commit").getAsString());
        assertEquals(RecafRuntimeInfo.BUILD_COMMIT, result.get("recaf_build_commit").getAsString());
        assertTrue(result.get("recaf_compatible").getAsBoolean());
    }
}
