package me.naptila.recafmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import software.coley.recaf.RecafBuildConfig;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Runtime identity and checks for the API boundaries used by the Worker. */
public record RecafRuntimeInfo(String version, String commit, List<String> errors) {
    // These constants intentionally describe compilation, never the loaded runtime.
    public static final String BUILD_VERSION = RecafBuildConfig.VERSION;
    public static final String BUILD_COMMIT = RecafBuildConfig.GIT_SHA;

    public RecafRuntimeInfo {
        errors = List.copyOf(errors);
    }

    public static RecafRuntimeInfo current() {
        return Current.VALUE;
    }

    static RecafRuntimeInfo detect(ClassLoader loader) {
        List<String> errors = new ArrayList<>();
        String version = "unknown";
        String commit = "unknown";
        try {
            Class<?> build = Class.forName("software.coley.recaf.RecafBuildConfig", true, loader);
            // Reflection prevents javac from substituting the compile-time constants.
            version = (String) build.getField("VERSION").get(null);
            commit = (String) build.getField("GIT_SHA").get(null);
            if (version == null || version.isBlank() || commit == null || commit.isBlank()) {
                throw new IllegalStateException("empty runtime identity");
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            errors.add("Recaf runtime build metadata is unavailable");
        }
        check(errors, "Jasm Outcome API", () -> {
            Class<?> outcome = Class.forName("me.darknet.assembler.error.Outcome", false, loader);
            requireReturn(outcome.getMethod("isSuccess"), boolean.class);
            outcome.getMethod("value");
            outcome.getMethod("requireValue");
            outcome.getMethod("errors");
            outcome.getMethod("warnings");
            Class<?> pipeline = Class.forName(
                    "software.coley.recaf.services.assembler.JvmAssemblerPipeline", false, loader);
            Class<?> memberPath = Class.forName("software.coley.recaf.path.ClassMemberPathNode", false, loader);
            requireReturn(pipeline.getMethod("disassemble", memberPath), outcome);
        });
        check(errors, "DEX constructor and code-unit API", () -> {
            Class.forName("me.darknet.dex.tree.DexFile", false, loader)
                    .getConstructor(int.class, List.class);
            Class<?> instruction = Class.forName(
                    "me.darknet.dex.tree.definitions.instructions.Instruction", false, loader);
            requireReturn(instruction.getMethod("unitSize"), int.class);
        });
        check(errors, "DEX fidelity and Android conversion API", () -> {
            Class<?> codeCodec = Class.forName("me.darknet.dex.tree.codec.definition.CodeCodec", false, loader);
            if (codeCodec.getField("readDebug").getType() != boolean.class) {
                throw new IllegalStateException("incompatible DEX debug flag");
            }
            Class<?> conversion = Class.forName("me.darknet.dex.convert.DexConversionIr", false, loader);
            Class<?> factory = Class.forName("me.darknet.dex.convert.factory.IrOptimizerFactory", false, loader);
            requireReturn(conversion.getMethod("getOptimizerFactory"), factory);
            conversion.getMethod("setOptimizerFactory", factory);
            Class<?> method = Class.forName("me.darknet.dex.convert.ir.IrMethod", false, loader);
            requireReturn(method.getMethod("blocks"), List.class);
            Class<?> policy = Class.forName("me.darknet.dex.convert.ir.lowering.JvmLoweringPolicy", false, loader);
            conversion.getMethod("setJvmLoweringPolicy", policy);
            policy.getField("DETERMINISTIC_LOCAL");
            policy.getField("AGGRESSIVE_OPTIMIZED");
            Class<?> code = Class.forName("me.darknet.dex.tree.definitions.code.Code", false, loader);
            Class<?> instruction = Class.forName("me.darknet.dex.tree.definitions.instructions.Instruction", false, loader);
            code.getMethod("offsetOf", instruction);
            code.getMethod("addInstructions", List.class);
            Class.forName("software.coley.recaf.workspace.model.bundle.AndroidClassBundle", false, loader)
                    .getMethod("getDexMetadata");
        });
        check(errors, "Recaf structural query API", () -> {
            Class<?> query = Class.forName("software.coley.recaf.services.search.query.structure.ClassQuery", false, loader);
            Class<?> builder = Class.forName("software.coley.recaf.services.search.query.structure.ClassQueryBuilder", false, loader);
            requireReturn(builder.getMethod("build"), query);
            for (String backend : List.of("jvm.JvmClassStructureMatcher", "android.AndroidClassStructureMatcher")) {
                Class<?> matcher = Class.forName("software.coley.recaf.services.search.query.structure." + backend, false, loader);
                Class<?> info = Class.forName("software.coley.recaf.info." + (backend.startsWith("jvm")
                        ? "JvmClassInfo" : "AndroidClassInfo"), false, loader);
                requireReturn(matcher.getMethod("matches", query, info), boolean.class);
            }
        });
        check(errors, "Recaf transformation plan and parameter API", () -> {
            Class<?> plan = Class.forName("software.coley.recaf.services.transform.TransformationPlan", false, loader);
            plan.getConstructor(List.class);
            Class<?> parameters = Class.forName("software.coley.recaf.services.transform.TransformationParameters", false, loader);
            parameters.getConstructor(java.util.Map.class);
            Class<?> phase = Class.forName("software.coley.recaf.services.transform.TransformationPhase", false, loader);
            phase.getConstructor(String.class, int.class, List.class, parameters);
            Class<?> applier = Class.forName("software.coley.recaf.services.transform.TransformationApplier", false, loader);
            Class<?> feedback = Class.forName("software.coley.recaf.services.transform.TransformationFeedback", false, loader);
            Class<?> result = Class.forName("software.coley.recaf.services.transform.TransformationPlanResult", false, loader);
            requireReturn(applier.getMethod("transformJvm", plan, feedback), result);
        });
        return new RecafRuntimeInfo(version, commit, errors);
    }

    public boolean compatible() {
        return errors.isEmpty();
    }

    public void addTo(JsonObject result) {
        result.addProperty("recaf_version", version);
        result.addProperty("recaf_commit", commit);
        result.addProperty("recaf_build_version", BUILD_VERSION);
        result.addProperty("recaf_build_commit", BUILD_COMMIT);
        result.addProperty("recaf_compatible", compatible());
        JsonArray failures = new JsonArray();
        errors.forEach(failures::add);
        result.add("recaf_compatibility_errors", failures);
    }

    private static void requireReturn(Method method, Class<?> expected) {
        if (method.getReturnType() != expected) {
            throw new IllegalStateException("incompatible method return type");
        }
    }

    private static void check(List<String> errors, String api, ApiCheck check) {
        try {
            check.run();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            errors.add("Missing or incompatible " + api);
        }
    }

    private interface ApiCheck {
        void run() throws ReflectiveOperationException;
    }

    private static final class Current {
        private static final RecafRuntimeInfo VALUE = detect(RecafBuildConfig.class.getClassLoader());
    }
}
