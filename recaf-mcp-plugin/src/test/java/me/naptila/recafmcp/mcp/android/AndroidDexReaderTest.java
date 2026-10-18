package me.naptila.recafmcp.mcp.android;

import me.darknet.dex.file.value.*;
import me.darknet.dex.io.Input;
import me.naptila.recafmcp.AndroidFidelityFixture;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AndroidDexReaderTest {
    @Test
    void decodesCompactSignedAndRightZeroExtendedValues() throws IOException {
        assertEquals(new IntValue(-515), value(0x24, 0xfd, 0xfd));
        assertEquals(new IntValue(-558), value(0x24, 0xd2, 0xfd));
        assertEquals(new IntValue(-790), value(0x24, 0xea, 0xfc));
        assertEquals(new ShortValue((short) -7), value(0x02, 0xf9));
        assertEquals(new LongValue(-515), value(0x26, 0xfd, 0xfd));
        assertEquals(new CharValue('\ufffe'), value(0x23, 0xfe, 0xff));
        assertEquals(new FloatValue(1.5f), value(0x30, 0xc0, 0x3f));
        assertEquals(new DoubleValue(-2.5), value(0x31, 0x04, 0xc0));
        assertEquals(new IntValue(128), value(0x24, 0x80, 0x00));
        assertEquals(new ArrayValue(List.of(new IntValue(-515), new FloatValue(1.5f))),
                value(0x1c, 0x02, 0x24, 0xfd, 0xfd, 0x30, 0xc0, 0x3f));
    }

    @Test
    void rejectsInvalidEncodedValueWidthsAndNesting() {
        assertThrows(IOException.class, () -> value(0x44, 0, 0)); // Truncated int.
        assertThrows(IOException.class, () -> value(0x42, 0, 0, 0)); // Short width > 2.
        byte[] nested = new byte[132];
        for (int index = 0; index < 130; index += 2) {
            nested[index] = 0x1c;
            nested[index + 1] = 1;
        }
        nested[130] = 4;
        assertThrows(IOException.class, () -> AndroidDexReader.readValue(Input.wrap(nested), null, 0));
    }

    @Test
    void importsIndependentDexWithCorrectConstantsAnnotationsAndDebugInformation() throws Exception {
        var info = AndroidDexReader.readClass(AndroidFidelityFixture.dex(), "sample/Fidelity");
        assertEquals(-515, info.getDeclaredField("NEG_A", "I").getDefaultValue());
        assertEquals(-558, info.getDeclaredField("NEG_B", "I").getDefaultValue());
        assertEquals(-790, info.getDeclaredField("NEG_C", "I").getDefaultValue());
        assertEquals(-515L, info.getDeclaredField("LONG", "J").getDefaultValue());
        assertEquals(1.5f, info.getDeclaredField("FLOAT", "F").getDefaultValue());
        assertEquals(-2.5, info.getDeclaredField("DOUBLE", "D").getDefaultValue());
        var code = info.getBackingDefinition().getMethod("mix", "(I)I").getCode();
        var debug = code.getDebugInfo();
        assertNotNull(debug);
        assertEquals(List.of("input"), debug.parameterNames());
        assertEquals(List.of(10, 11), debug.lineNumbers().stream().map(line -> line.line()).toList());
        assertEquals("delta", debug.locals().getFirst().name());
        assertTrue(info.getBackingDefinition().getAnnotations().toString().contains("-558"));
        assertTrue(info.getBackingDefinition().getAnnotations().toString().contains("1.5"));
    }

    private static Value value(int... octets) throws IOException {
        byte[] bytes = new byte[octets.length];
        for (int index = 0; index < octets.length; index++) bytes[index] = (byte) octets[index];
        return AndroidDexReader.readValue(Input.wrap(bytes), null, 0);
    }
}
