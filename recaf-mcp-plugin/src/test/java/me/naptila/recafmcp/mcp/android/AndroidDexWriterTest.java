package me.naptila.recafmcp.mcp.android;

import me.naptila.recafmcp.AndroidFidelityFixture;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.instruction.formats.Instruction32x;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.builder.AndroidClassInfoBuilder;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.Instructions;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class AndroidDexWriterTest {
    @Test
    void preservesMoveObject16OpcodeAndWideRegisters() throws Exception {
        var input = AndroidFidelityFixture.moveObject16Dex();
        var definition = AndroidDexReader.read(input).definitions().getFirst();
        var originalCodec = Instructions.CODECS.get(Instruction.MOVE_OBJECT_16);
        var output = AndroidDexReader.encodeClass(new AndroidClassInfoBuilder(definition).build(), 35);
        assertSame(originalCodec, Instructions.CODECS.get(Instruction.MOVE_OBJECT_16));
        var saved = new DexBackedDexFile(Opcodes.forDexVersion(35), output);
        var method = saved.getClasses().iterator().next().getMethods().iterator().next();
        var instruction = (Instruction32x) method.getImplementation().getInstructions().iterator().next();
        assertEquals(Opcode.MOVE_OBJECT_16, instruction.getOpcode());
        assertEquals(300, instruction.getRegisterA());
        assertEquals(599, instruction.getRegisterB());
    }

    @Test
    void restoresTheOpcodeRegistryAfterAnExportFailure() {
        var originalCodec = Instructions.CODECS.get(Instruction.MOVE_OBJECT_16);
        assertThrows(IOException.class, () -> AndroidDexWriter.withCompatibility(() -> {
            throw new IOException("test export failure");
        }));
        assertSame(originalCodec, Instructions.CODECS.get(Instruction.MOVE_OBJECT_16));
    }
}
