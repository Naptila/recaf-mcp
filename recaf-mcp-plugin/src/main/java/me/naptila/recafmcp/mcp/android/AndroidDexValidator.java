package me.naptila.recafmcp.mcp.android;

import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.constant.Constant;
import me.darknet.dex.tree.definitions.constant.DoubleConstant;
import me.darknet.dex.tree.definitions.constant.FloatConstant;
import me.darknet.dex.tree.definitions.debug.DebugInformation;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Rejects an export that loses class/member declarations, field values or debug tables. */
public final class AndroidDexValidator {
    private AndroidDexValidator() {}

    public static void validate(AndroidClassBundle expected, byte[] bytes) throws IOException {
        var actual = new HashMap<String, ClassDefinition>();
        for (var definition : AndroidDexReader.read(bytes).definitions()) {
            actual.put(definition.getType().internalName(), definition);
        }
        if (!actual.keySet().equals(expected.keySet())) {
            throw new IOException("DEX export changed the class set");
        }
        for (var info : expected.values()) {
            ClassDefinition before = info.getBackingDefinition();
            ClassDefinition after = actual.get(info.getName());
            if (!fields(before).equals(fields(after))) {
                throw new IOException("DEX export changed field declarations or values: " + info.getName());
            }
            if (!before.getMethods().keySet().equals(after.getMethods().keySet())) {
                throw new IOException("DEX export changed method declarations: " + info.getName());
            }
            for (var entry : before.getMethods().entrySet()) {
                var original = entry.getValue().getCode();
                var reloaded = after.getMethods().get(entry.getKey()).getCode();
                if ((original == null) != (reloaded == null)
                        || original != null && !Objects.equals(debug(original.getDebugInfo()),
                        debug(reloaded.getDebugInfo()))) {
                    throw new IOException("DEX export changed debug information: "
                            + info.getName() + "." + entry.getKey());
                }
            }
        }
    }

    private static Map<String, Object> fields(ClassDefinition definition) {
        Map<String, Object> values = new HashMap<>();
        definition.getFields().forEach((id, field) -> {
            Constant constant = field.getStaticValue();
            Object value = switch (constant) {
                case FloatConstant f -> Float.floatToRawIntBits(f.value());
                case DoubleConstant d -> Double.doubleToRawLongBits(d.value());
                case null -> "<no static value>";
                default -> constant;
            };
            values.put(id.toString(), List.of(field.getAccess(), value));
        });
        return values;
    }

    private static Object debug(DebugInformation information) {
        if (information == null) return null;
        var lines = information.lineNumbers().stream()
                .map(line -> List.of(line.label().position(), line.line())).toList();
        var locals = information.locals().stream().map(local -> List.of(
                local.register(), Objects.toString(local.name(), ""),
                Objects.toString(local.type(), ""), Objects.toString(local.signature(), ""),
                local.start().position(), local.end().position()))
                .sorted(Comparator.comparing(Object::toString)).toList();
        return java.util.Arrays.asList(information.parameterNames(), lines, locals);
    }
}
