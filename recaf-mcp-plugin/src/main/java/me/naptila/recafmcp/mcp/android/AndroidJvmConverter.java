package me.naptila.recafmcp.mcp.android;

import me.darknet.dex.convert.DexConversionIr;
import me.darknet.dex.convert.ir.lowering.JvmLoweringPolicy;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;

import java.io.IOException;

/** Validated Android-to-JVM views on detached trees, used by every source tool. */
public final class AndroidJvmConverter {
    private AndroidJvmConverter() {}

    public record ConversionOutcome(JvmClassInfo classInfo, boolean verified, String policy) {
    }

    public static JvmClassInfo convert(AndroidClassInfo info) throws IOException {
        return convert(info, AndroidJvmConverter::convertDefinition);
    }

    public static ConversionOutcome convertForDecompilation(AndroidClassInfo info) throws IOException {
        return convertForDecompilation(info, AndroidJvmConverter::convertDefinition);
    }

    static ConversionOutcome convertForDecompilation(AndroidClassInfo info, Conversion conversion)
            throws IOException {
        if (!info.canMapToJvmClass()) {
            throw new IOException("Android class cannot be mapped to JVM bytecode");
        }
        byte[] snapshot = AndroidDexReader.encodeClass(info, 39);
        JvmClassInfo unverifiedCandidate = null;
        String unverifiedPolicy = null;
        Throwable primaryFailure = null;
        try {
            var definition = AndroidDexReader.readClass(snapshot, info.getName()).getBackingDefinition();
            byte[] deterministicBytes = conversion.convert(definition, JvmLoweringPolicy.DETERMINISTIC_LOCAL);
            try {
                return new ConversionOutcome(validated(deterministicBytes), true, "deterministic_local");
            } catch (AnalyzerException verifierEx) {
                primaryFailure = verifierEx;
                unverifiedCandidate = buildUnverified(deterministicBytes);
                unverifiedPolicy = "deterministic_local_unverified";
            }
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            primaryFailure = ex;
        }

        Throwable fallbackFailure = null;
        try {
            var definition = AndroidDexReader.readClass(snapshot, info.getName()).getBackingDefinition();
            byte[] aggressiveBytes = conversion.convert(definition, JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED);
            try {
                return new ConversionOutcome(validated(aggressiveBytes), true, "aggressive_optimized");
            } catch (AnalyzerException verifierEx) {
                fallbackFailure = verifierEx;
                if (unverifiedCandidate == null) {
                    unverifiedCandidate = buildUnverified(aggressiveBytes);
                    unverifiedPolicy = "aggressive_optimized_unverified";
                }
            }
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            fallbackFailure = ex;
        }

        if (unverifiedCandidate != null) {
            return new ConversionOutcome(unverifiedCandidate, false, unverifiedPolicy);
        }
        Throwable root = primaryFailure != null ? primaryFailure : fallbackFailure;
        var failure = new IOException(
                "Android-to-JVM conversion failed: " + info.getName()
                        + " (" + describeFailure(root) + ")",
                root
        );
        if (fallbackFailure != null && fallbackFailure != root) {
            failure.addSuppressed(fallbackFailure);
        }
        throw failure;
    }

    static JvmClassInfo convert(AndroidClassInfo info, Conversion conversion) throws IOException {
        if (!info.canMapToJvmClass()) {
            throw new IOException("Android class cannot be mapped to JVM bytecode");
        }
        byte[] snapshot = AndroidDexReader.encodeClass(info, 39);
        try {
            var definition = AndroidDexReader.readClass(snapshot, info.getName()).getBackingDefinition();
            return validated(conversion.convert(definition, JvmLoweringPolicy.DETERMINISTIC_LOCAL));
        } catch (RuntimeException | AnalyzerException primary) {
            ToolCancellation.propagateIfRequested(primary);
            try {
                // Use a fresh snapshot: the failed converter may have changed
                // metadata in its private tree while preparing the source view.
                var definition = AndroidDexReader.readClass(snapshot, info.getName()).getBackingDefinition();
                return validated(conversion.convert(definition, JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED));
            } catch (RuntimeException | AnalyzerException fallback) {
                ToolCancellation.propagateIfRequested(fallback);
                var failure = new IOException(
                        "Android-to-JVM conversion failed verification: " + info.getName()
                                + " (" + describeFailure(primary) + ")",
                        primary
                );
                if (fallback != primary) failure.addSuppressed(fallback);
                throw failure;
            }
        }
    }

    static byte[] convertDefinition(ClassDefinition definition, JvmLoweringPolicy policy) {
        var conversion = new DexConversionIr();
        conversion.setJvmLoweringPolicy(policy);
        conversion.setOptimizerFactory(context -> new AndroidConversionOptimizer());
        return conversion.toJavaClass(definition);
    }

    @FunctionalInterface
    interface Conversion {
        byte[] convert(ClassDefinition definition, JvmLoweringPolicy policy);
    }

    private static JvmClassInfo validated(byte[] bytes) throws AnalyzerException {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (var method : node.methods) {
            if (method.instructions.size() > 0) {
                try {
                    new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
                } catch (AnalyzerException ex) {
                    throw new AnalyzerException(
                            ex.node,
                            "method " + method.name + method.desc + ": " + ex.getMessage(),
                            ex
                    );
                }
            }
        }
        return new JvmClassInfoBuilder(new ClassReader(bytes)).build();
    }

    private static String describeFailure(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root
                && !(root instanceof AnalyzerException)) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        if (msg == null || msg.isBlank()) {
            return root.getClass().getSimpleName();
        }
        String clean = msg.replace('\n', ' ').replace('\r', ' ').strip();
        if (clean.length() > 240) {
            clean = clean.substring(0, 240);
        }
        return root.getClass().getSimpleName() + ": " + clean;
    }

    private static JvmClassInfo buildUnverified(byte[] bytes) {
        try {
            return new JvmClassInfoBuilder(new ClassReader(bytes)).build();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
