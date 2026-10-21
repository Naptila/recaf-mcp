package me.naptila.recafmcp.mcp.android;

import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.debug.DebugInformation;
import me.darknet.dex.tree.definitions.instructions.Instruction;
import me.darknet.dex.tree.definitions.instructions.Label;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Anchors debug positions on executable instructions when native decoding drops payload padding. */
final class AndroidDebugSupport {
    private AndroidDebugSupport() {}

    static Code repair(Code code) throws IOException {
        var debug = code.getDebugInfo();
        if (debug == null) return code;
        var wanted = Collections.newSetFromMap(new IdentityHashMap<Label, Boolean>());
        debug.lineNumbers().forEach(line -> wanted.add(line.label()));
        debug.locals().forEach(local -> wanted.add(local.start()));
        if (wanted.isEmpty()) return code;
        var instructions = code.getInstructions();
        var invalid = Collections.newSetFromMap(new IdentityHashMap<Label, Boolean>());
        for (int index = 0; index < instructions.size(); index++) {
            if (!(instructions.get(index) instanceof Label label) || !wanted.contains(label)) continue;
            int next = index + 1;
            while (next < instructions.size() && instructions.get(next) instanceof Label) next++;
            if (next == instructions.size() || !Objects.equals(code.offsetOf(instructions.get(next)), label.position())) {
                invalid.add(label);
            }
        }
        if (invalid.isEmpty()) return code;

        var real = new TreeMap<Integer, Integer>();
        Map<Integer, Label> labels = new HashMap<>();
        for (int index = 0; index < instructions.size(); index++) {
            Instruction instruction = instructions.get(index);
            if (instruction instanceof Label label) labels.putIfAbsent(label.position(), label);
            else if (code.offsetOf(instruction) != null) real.put(code.offsetOf(instruction), index);
        }
        var replacements = new IdentityHashMap<Label, Label>();
        Map<Integer, Label> inserted = new HashMap<>();
        for (Label label : invalid) {
            var previous = real.floorEntry(label.position());
            if (previous == null) throw new IOException("debug position has no preceding executable instruction");
            Label target = labels.get(previous.getKey());
            if (target == null) {
                target = new Label(previous.getValue(), previous.getKey());
                labels.put(previous.getKey(), target);
                inserted.put(previous.getValue(), target);
            }
            replacements.put(label, target);
        }
        var lines = debug.lineNumbers().stream().map(line -> {
            Label target = replacements.getOrDefault(line.label(), line.label());
            target.lineNumber(line.line());
            return new DebugInformation.LineNumber(target, line.line());
        }).toList();
        var locals = debug.locals().stream().map(local -> new DebugInformation.LocalVariable(local.register(),
                local.name(), local.type(), local.signature(), replacements.getOrDefault(local.start(), local.start()),
                local.end())).toList();
        var repaired = new Code(code.getIn(), code.getOut(), code.getRegisters());
        for (int index = 0; index < instructions.size(); index++) {
            if (inserted.containsKey(index)) repaired.addInstruction(inserted.get(index));
            Instruction instruction = instructions.get(index);
            repaired.addInstruction(instruction);
            Integer offset = code.offsetOf(instruction);
            if (offset != null) repaired.setInstructionOffset(instruction, offset);
        }
        code.tryCatch().forEach(repaired::addTryCatch);
        repaired.setDebugInfo(new DebugInformation(lines, debug.addressLines(), debug.parameterNames(), locals));
        return repaired;
    }
}
