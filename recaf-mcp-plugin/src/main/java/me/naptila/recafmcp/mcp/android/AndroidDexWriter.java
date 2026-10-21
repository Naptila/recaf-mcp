package me.naptila.recafmcp.mcp.android;

import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.instructions.Format;
import me.darknet.dex.file.instructions.Format00opAAAABBBB;
import me.darknet.dex.tree.codec.definition.CodeCodec;
import me.darknet.dex.tree.codec.definition.InstructionContext;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.Instructions;
import me.darknet.dex.tree.definitions.instructions.MoveObjectInstruction;

import java.io.IOException;

/** Scoped compatibility for the pinned native writer's missing move-object/16 case. */
public final class AndroidDexWriter {
    private AndroidDexWriter() {}

    @FunctionalInterface
    public interface IOAction<T> {
        T run() throws IOException;
    }

    private static final Instruction.InstructionCodec<MoveObjectInstruction, Format> MOVE_OBJECT_16_CODEC =
            new Instruction.InstructionCodec<>() {
                @Override
                public MoveObjectInstruction map(Format format, InstructionContext<DexMap> context) {
                    return MoveObjectInstruction.CODEC.map(format, context);
                }

                @Override
                public Format unmap(MoveObjectInstruction instruction, InstructionContext<DexMapBuilder> context) {
                    return new Format00opAAAABBBB(Instruction.MOVE_OBJECT_16, instruction.to(), instruction.from());
                }
            };

    public static <T> T withCompatibility(IOAction<T> action) throws IOException {
        // The public opcode registry is mutable in the pinned dependency. Restore
        // it even on failure; the Worker's operation gate serializes tool writes.
        synchronized (CodeCodec.class) {
            var previous = Instructions.CODECS.put(Instruction.MOVE_OBJECT_16, MOVE_OBJECT_16_CODEC);
            try {
                return action.run();
            } finally {
                if (previous == null) Instructions.CODECS.remove(Instruction.MOVE_OBJECT_16);
                else Instructions.CODECS.put(Instruction.MOVE_OBJECT_16, previous);
            }
        }
    }
}
