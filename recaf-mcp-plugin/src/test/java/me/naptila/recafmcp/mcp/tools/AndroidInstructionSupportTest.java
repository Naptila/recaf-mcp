package me.naptila.recafmcp.mcp.tools;

import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.code.CodeBuilder;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.builder.AndroidClassInfoBuilder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AndroidInstructionSupportTest {
    @Test
    void fallbackOffsetsUseDexCodeUnitsAndRespectRecordedOffsets() {
        var info = new AndroidClassInfoBuilder(new ClassDefinition(
                Types.instanceTypeFromInternalName("sample/Offsets"), Types.OBJECT, 1)).build();
        var owner = new ClassToolSupport.Entry(info.getName(), ClassToolSupport.Scope.ANDROID, "classes.dex", info);
        var code = new CodeBuilder().registers(1).const_string(0, "hello").return_void().build();
        var method = new AndroidInstructionSupport.MethodCode(owner, "test", "()V", 9, code);

        assertEquals(List.of(0, 2), AndroidInstructionSupport.instructions(method).stream()
                .map(AndroidInstructionSupport.InstructionLine::offset).toList());

        code.setInstructionOffset(code.getInstructions().getFirst(), 10);
        assertEquals(List.of(10, 12), AndroidInstructionSupport.instructions(method).stream()
                .map(AndroidInstructionSupport.InstructionLine::offset).toList());
        code.setInstructionOffset(code.getInstructions().get(1), 20);
        assertEquals(List.of(10, 20), AndroidInstructionSupport.instructions(method).stream()
                .map(AndroidInstructionSupport.InstructionLine::offset).toList());
    }
}
