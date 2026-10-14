package me.naptila.recafmcp.mcp.tools;

import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.constant.Handle;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.InvokeCustomInstruction;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.type.MethodType;
import software.coley.recaf.info.AndroidClassInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Read-only helpers over Recaf's backing DEX class tree.
 */
final class AndroidInstructionSupport {
    private static final Comparator<MethodCode> METHOD_ORDER = Comparator
            .comparing(MethodCode::name)
            .thenComparing(MethodCode::descriptor);

    private AndroidInstructionSupport() {
    }

    static List<MethodCode> methods(ClassToolSupport.Entry entry) {
        AndroidClassInfo classInfo = entry.classInfo().asAndroidClass();
        List<MethodCode> methods = new ArrayList<>();
        for (MethodMember method
                : classInfo.getBackingDefinition().getMethods().values()) {
            methods.add(new MethodCode(
                    entry,
                    method.getName(),
                    method.getType().descriptor(),
                    method.getAccess(),
                    method.getCode()
            ));
        }
        methods.sort(METHOD_ORDER);
        return methods;
    }

    static MethodCode exact(ClassToolSupport.Entry entry,
                            String name,
                            String descriptor) {
        MethodMember method = entry.classInfo()
                .asAndroidClass()
                .getBackingDefinition()
                .getMethod(name, descriptor);
        if (method == null) {
            return null;
        }
        return new MethodCode(
                entry,
                method.getName(),
                method.getType().descriptor(),
                method.getAccess(),
                method.getCode()
        );
    }

    static List<InstructionLine> instructions(MethodCode method) {
        Code code = method.code();
        if (code == null) {
            return List.of();
        }
        List<InstructionLine> lines = new ArrayList<>();
        int fallbackOffset = 0;
        List<Instruction> instructions = code.getInstructions();
        for (int index = 0; index < instructions.size(); index++) {
            Instruction instruction = instructions.get(index);
            Integer recordedOffset = code.offsetOf(instruction);
            int offset = recordedOffset == null ? fallbackOffset : recordedOffset;
            lines.add(new InstructionLine(
                    index,
                    offset,
                    instruction.toString(),
                    instruction
            ));
            fallbackOffset = Math.max(
                    fallbackOffset,
                    offset + Math.max(0, instruction.unitSize())
            );
        }
        return lines;
    }

    static MethodReference invokedMethod(Instruction instruction) {
        if (instruction instanceof InvokeInstruction invoke) {
            return new MethodReference(
                    invoke.owner().internalName(),
                    invoke.name(),
                    invoke.type().descriptor()
            );
        }
        if (instruction instanceof InvokeCustomInstruction invokeCustom) {
            Handle handle = invokeCustom.handle();
            if (handle.type() instanceof MethodType methodType) {
                return new MethodReference(
                        handle.owner().internalName(),
                        handle.name(),
                        methodType.descriptor()
                );
            }
        }
        return null;
    }

    record MethodCode(ClassToolSupport.Entry owner,
                      String name,
                      String descriptor,
                      int access,
                      Code code) {
        MethodCode {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    record InstructionLine(int index,
                           int offset,
                           String text,
                           Instruction instruction) {
        InstructionLine {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(instruction, "instruction");
        }
    }

    record MethodReference(String owner, String name, String descriptor) {
        MethodReference {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }
}
