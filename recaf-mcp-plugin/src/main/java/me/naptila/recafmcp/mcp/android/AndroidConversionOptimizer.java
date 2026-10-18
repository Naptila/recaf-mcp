package me.naptila.recafmcp.mcp.android;

import me.darknet.dex.convert.ir.IrMethod;
import me.darknet.dex.convert.ir.optimize.BaseIrOptimizer;
import me.darknet.dex.convert.ir.optimize.IrOptimizationContext;
import me.darknet.dex.convert.ir.statement.IrEffect;
import me.darknet.dex.convert.ir.statement.IrEffectKind;
import me.darknet.dex.convert.ir.statement.IrOp;
import me.darknet.dex.convert.ir.statement.IrStmt;
import me.darknet.dex.convert.ir.value.IrConstant;
import me.darknet.dex.convert.ir.value.IrPhi;
import me.darknet.dex.convert.ir.value.IrValue;
import me.darknet.dex.tree.definitions.instructions.ArrayInstruction;
import me.darknet.dex.tree.definitions.instructions.FillArrayDataInstruction;
import me.darknet.dex.tree.type.ArrayType;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.Types;
import me.naptila.recafmcp.mcp.util.ToolCancellation;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

/** Corrects array effect contracts in the detached IR used for Java source views. */
public final class AndroidConversionOptimizer extends BaseIrOptimizer {
    @Override
    public void optimizeMethod(IrOptimizationContext context, IrMethod method) {
        int id = nextId(method);
        for (var block : method.blocks()) {
            ToolCancellation.check();
            List<IrStmt> statements = new ArrayList<>();
            for (var statement : block.statements()) {
                if (!(statement instanceof IrEffect effect)) {
                    statements.add(statement);
                    continue;
                }
                var inputs = effect.inputs().stream().map(IrValue::canonical).toList();
                if (effect.kind() == IrEffectKind.ARRAY_PUT) {
                    // The upstream contract was frozen before type inference.
                    statements.add(new IrEffect(effect.kind(), inputs, effect.payload()));
                } else if (effect.payload() instanceof FillArrayDataInstruction fill
                        && inputs.getFirst().type() instanceof ArrayType array) {
                    // FILL_ARRAY_DATA's native contract always says Object, which
                    // makes its emitter read char/short payloads as int[] data.
                    ClassType element = array.componentType();
                    int width = width(element);
                    if (width != fill.elementSize() || fill.data().length % width != 0) {
                        throw new IllegalStateException("array payload width does not match its inferred type");
                    }
                    if (fill.data().length / width > 8192) {
                        throw new IllegalStateException("array payload exceeds the Java source conversion limit");
                    }
                    var data = ByteBuffer.wrap(fill.data()).order(ByteOrder.LITTLE_ENDIAN);
                    for (int index = 0; data.hasRemaining(); index++) {
                        var position = new IrConstant(id++, Types.INT, index, false);
                        var value = new IrConstant(id++, element, read(data, element), false);
                        statements.add(new IrEffect(IrEffectKind.ARRAY_PUT,
                                List.of(inputs.getFirst(), position, value),
                                new ArrayInstruction(putKind(element), 0, fill.array(), 0)));
                    }
                } else {
                    statements.add(statement);
                }
            }
            block.statements().clear();
            block.statements().addAll(statements);
        }
        super.optimizeMethod(context, method);
    }

    private static int nextId(IrMethod method) {
        var pending = new ArrayDeque<IrValue>();
        for (var block : method.blocks()) {
            pending.addAll(block.phis());
            for (var statement : block.statements()) {
                if (statement instanceof IrOp operation) {
                    pending.add(operation);
                    pending.addAll(operation.inputs());
                } else if (statement instanceof IrEffect effect) {
                    pending.addAll(effect.inputs());
                }
            }
            if (block.terminator() != null) pending.addAll(block.terminator().inputs());
        }
        var seen = Collections.newSetFromMap(new IdentityHashMap<IrValue, Boolean>());
        int maximum = 0;
        while (!pending.isEmpty()) {
            var value = pending.removeFirst();
            if (!seen.add(value)) continue;
            maximum = Math.max(maximum, value.id());
            if (value.canonical() != value) pending.add(value.canonical());
            if (value instanceof IrOp operation) pending.addAll(operation.inputs());
            if (value instanceof IrPhi phi) pending.addAll(phi.operands().values());
        }
        return Math.incrementExact(maximum);
    }

    private static int width(ClassType element) {
        return switch (element.descriptor()) {
            case "Z", "B" -> 1;
            case "C", "S" -> 2;
            case "I", "F" -> 4;
            case "J", "D" -> 8;
            default -> throw new IllegalStateException("array payload has a non-primitive element type");
        };
    }

    private static Object read(ByteBuffer data, ClassType element) {
        return switch (element.descriptor()) {
            case "Z" -> data.get() == 0 ? 0 : 1;
            case "B" -> (int) data.get();
            case "C" -> (int) data.getChar();
            case "S" -> (int) data.getShort();
            case "I" -> data.getInt();
            case "F" -> data.getFloat();
            case "J" -> data.getLong();
            case "D" -> data.getDouble();
            default -> throw new IllegalStateException("unsupported array payload");
        };
    }

    private static int putKind(ClassType element) {
        return switch (element.descriptor()) {
            case "J", "D" -> 8;
            case "Z" -> 10;
            case "B" -> 11;
            case "C" -> 12;
            case "S" -> 13;
            default -> 7;
        };
    }
}
