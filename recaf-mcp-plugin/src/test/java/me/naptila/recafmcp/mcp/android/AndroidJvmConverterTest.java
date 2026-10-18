package me.naptila.recafmcp.mcp.android;

import me.naptila.recafmcp.AndroidFidelityFixture;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AndroidJvmConverterTest {
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
