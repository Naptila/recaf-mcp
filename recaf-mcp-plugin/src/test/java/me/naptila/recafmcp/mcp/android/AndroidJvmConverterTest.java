package me.naptila.recafmcp.mcp.android;

import me.naptila.recafmcp.AndroidFidelityFixture;
import me.darknet.dex.convert.ir.lowering.JvmLoweringPolicy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;
import java.util.List;
import java.util.ArrayList;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class AndroidJvmConverterTest {
    @Test
    void retriesWithAFreshTreeAndExecutesTheOptimizedFallback() throws Exception {
        var info = AndroidDexReader.readClass(AndroidFidelityFixture.arrayDex("B", 1, List.of(-1, 2)),
                "sample/ArrayPayload");
        var original = info.getBackingDefinition();
        int methodCount = original.getMethods().size();
        List<JvmLoweringPolicy> attempts = new ArrayList<>();
        byte[] bytecode = AndroidJvmConverter.convert(info, (definition, policy) -> {
            attempts.add(policy);
            assertNotSame(original, definition);
            if (policy == JvmLoweringPolicy.DETERMINISTIC_LOCAL) {
                definition.getMethods().clear();
                throw new IllegalStateException("first conversion changed its private tree");
            }
            assertEquals(methodCount, definition.getMethods().size());
            return AndroidJvmConverter.convertDefinition(definition, policy);
        }).getBytecode();
        Class<?> target = new ClassLoader() {
            Class<?> define() { return defineClass("sample.ArrayPayload", bytecode, 0, bytecode.length); }
        }.define();
        assertArrayEquals(new byte[]{-1, 2}, (byte[]) target.getMethod("values").invoke(null));
        assertEquals(List.of(JvmLoweringPolicy.DETERMINISTIC_LOCAL, JvmLoweringPolicy.AGGRESSIVE_OPTIMIZED), attempts);
        assertEquals(methodCount, original.getMethods().size());
    }

    @Test
    void reportsBothConversionFailures() throws Exception {
        var info = AndroidDexReader.readClass(AndroidFidelityFixture.arrayDex("I", 4, List.of(1)),
                "sample/ArrayPayload");
        var error = assertThrows(IOException.class, () -> AndroidJvmConverter.convert(info, (definition, policy) -> {
            throw new IllegalStateException(policy.name());
        }));
        assertEquals("DETERMINISTIC_LOCAL", error.getCause().getMessage());
        assertEquals(1, error.getSuppressed().length);
        assertEquals("AGGRESSIVE_OPTIMIZED", error.getSuppressed()[0].getMessage());
    }

    @Test
    void convertsAndExecutesEveryPrimitiveArrayPayloadWithoutChangingTheDexTree() throws Exception {
        array("B", 1, List.of(-1, 2), List.of((byte) -1, (byte) 2));
        array("Z", 1, List.of(0, 1), List.of(false, true));
        array("C", 2, List.of(65, -2), List.of('A', '\ufffe'));
        array("S", 2, List.of(-7, 258), List.of((short) -7, (short) 258));
        array("I", 4, List.of(-515, 123456), List.of(-515, 123456));
        array("J", 8, List.of(-515L, 12345678900L), List.of(-515L, 12345678900L));
        array("F", 4, List.of(Float.floatToRawIntBits(1.5f), Float.floatToRawIntBits(-2.5f)), List.of(1.5f, -2.5f));
        array("D", 8, List.of(Double.doubleToRawLongBits(1.5), Double.doubleToRawLongBits(-2.5)), List.of(1.5, -2.5));
    }

    private static void array(String component, int width, List<Number> encoded, List<?> expected) throws Exception {
        byte[] dex = AndroidFidelityFixture.arrayDex(component, width, encoded);
        var info = AndroidDexReader.readClass(dex, "sample/ArrayPayload");
        String before = info.getBackingDefinition().getMethod("values", "()[" + component).getCode().getInstructions().toString();
        byte[] bytecode = AndroidJvmConverter.convert(info).getBytecode();
        Class<?> target = new ClassLoader() {
            Class<?> define() { return defineClass("sample.ArrayPayload", bytecode, 0, bytecode.length); }
        }.define();
        Object array = target.getMethod("values").invoke(null);
        assertEquals(expected.size(), Array.getLength(array), component);
        for (int index = 0; index < expected.size(); index++) {
            assertEquals(expected.get(index), Array.get(array, index), component + ":" + index);
        }
        assertEquals(before, info.getBackingDefinition().getMethod("values", "()[" + component).getCode().getInstructions().toString());
    }
}
