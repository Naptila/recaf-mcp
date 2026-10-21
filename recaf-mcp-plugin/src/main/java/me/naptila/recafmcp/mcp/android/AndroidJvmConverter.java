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

    public static JvmClassInfo convert(AndroidClassInfo info) throws IOException {
        return convert(info, AndroidJvmConverter::convertDefinition);
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
                var failure = new IOException("Android-to-JVM conversion failed verification: " + info.getName(), primary);
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
                new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
            }
        }
        return new JvmClassInfoBuilder(new ClassReader(bytes)).build();
    }
}
