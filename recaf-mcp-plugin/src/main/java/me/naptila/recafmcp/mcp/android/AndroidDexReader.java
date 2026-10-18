package me.naptila.recafmcp.mcp.android;

import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.DexMap;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.file.annotation.AnnotationElement;
import me.darknet.dex.file.annotation.EncodedAnnotation;
import me.darknet.dex.file.items.AnnotationSetItem;
import me.darknet.dex.file.items.AnnotationsDirectoryItem;
import me.darknet.dex.file.value.*;
import me.darknet.dex.io.Input;
import me.darknet.dex.io.GrowingOutput;
import me.darknet.dex.tree.DexFile;
import me.darknet.dex.tree.definitions.InnerClass;
import me.darknet.dex.tree.codec.definition.CodeCodec;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.builder.AndroidClassInfoBuilder;

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Reads DEX trees without the pinned library's encoded-value extension bug or
 * Recaf's global debug-info opt-out. Only detached low-level items are repaired;
 * no runtime classes, codecs or original input bytes are replaced.
 */
public final class AndroidDexReader {
    private AndroidDexReader() {}

    public static DexFile read(byte[] bytes) throws IOException {
        try {
            DexHeader header = DexHeader.CODEC.read(Input.wrap(bytes));
            repairValues(bytes, header.map());
            // Recaf disables this flag in BasicInfoImporter. Restore it after
            // our detached read so unrelated Recaf operations retain their policy.
            synchronized (CodeCodec.class) {
                boolean previous = CodeCodec.readDebug;
                try {
                    CodeCodec.readDebug = true;
                    DexFile file = DexFile.CODEC.map(header, header.map());
                    restoreInnerClassFlags(header, file);
                    return file;
                } finally {
                    CodeCodec.readDebug = previous;
                }
            }
        } catch (RuntimeException ex) {
            throw new IOException("DEX tree could not be read without losing data", ex);
        }
    }

    public static AndroidClassInfo readClass(byte[] bytes, String name) throws IOException {
        return read(bytes).definitions().stream()
                .filter(definition -> definition.getType().internalName().equals(name))
                .map(definition -> new AndroidClassInfoBuilder(definition).build())
                .findFirst().orElseThrow(() -> new IOException("DEX class is missing: " + name));
    }

    public static byte[] encodeClass(AndroidClassInfo info, int version) throws IOException {
        var header = DexFile.CODEC.unmap(new DexFile(version, List.of(info.getBackingDefinition())), new DexMapBuilder());
        var output = new GrowingOutput();
        DexHeader.CODEC.write(header, output);
        var buffer = output.buffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static void restoreInnerClassFlags(DexHeader header, DexFile file) {
        var accessByName = new HashMap<String, Integer>();
        for (var encodedClass : header.map().classes()) {
            if (encodedClass.directory() == null) continue;
            for (var entry : encodedClass.directory().classAnnotations().entries()) {
                var annotation = entry.item().annotation();
                if (!annotation.type().descriptor().string().equals("Ldalvik/annotation/InnerClass;")) continue;
                Integer access = null;
                for (var element : annotation.elements()) {
                    if (element.name().string().equals("accessFlags") && element.value() instanceof IntValue value) {
                        access = value.value();
                    }
                }
                if (access == null) continue;
                String descriptor = encodedClass.type().descriptor().string();
                String name = descriptor.substring(1, descriptor.length() - 1);
                accessByName.put(name, access);
            }
        }
        for (var definition : file.definitions()) {
            var innerClasses = definition.getInnerClasses();
            for (int index = 0; index < innerClasses.size(); index++) {
                var inner = innerClasses.get(index);
                Integer access = accessByName.get(inner.innerClassName());
                if (access != null) {
                    innerClasses.set(index, new InnerClass(inner.innerClassName(),
                            inner.outerClassName(), inner.innerName(), access));
                }
            }
        }
    }

    private static void repairValues(byte[] bytes, DexMap map) throws IOException {
        Input input = littleEndian(bytes);
        input.position(96); // Standard DEX header: class_defs_size/off.
        int count = input.readInt();
        int offset = input.readInt();
        requireCount(count, map.classes().size());
        boundedCount(count, (bytes.length - offset) / 32);
        List<Runnable> updates = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            input.position(offset + index * 32 + 20);
            int directoryOffset = input.readInt();
            input.readInt(); // class_data_off
            int valuesOffset = input.readInt();
            var definition = map.classes().get(index);
            if (valuesOffset != 0) {
                stage(updates, definition.staticValues().values(),
                        readArray(littleEndian(bytes).position(valuesOffset), map, 0));
            }
            if (directoryOffset != 0) {
                repairAnnotations(bytes, directoryOffset, definition.directory(), map, updates);
            }
        }
        // Call-site arguments are encoded arrays too. Their raw offsets are in
        // the call_site_id_item section, which has no standard header slot.
        input.position(52);
        input.position(input.readInt());
        int sections = boundedCount(input.readInt(), (input.size() - input.position()) / 12);
        for (int section = 0; section < sections; section++) {
            int type = input.readUnsignedShort();
            input.readUnsignedShort();
            int size = input.readInt();
            int sectionOffset = input.readInt();
            if (type == 0x0007) {
                requireCount(size, map.callSites().size());
                Input sites = littleEndian(bytes).position(sectionOffset);
                for (int index = 0; index < size; index++) {
                    var values = readArray(littleEndian(bytes).position(sites.readInt()), map, 0);
                    if (values.size() < 3) {
                        throw new IOException("DEX call site has fewer than three values");
                    }
                    stage(updates, map.callSites().get(index).data().arguments(),
                            values.subList(3, values.size()));
                }
            }
        }
        // Repair actual references by file offset, not by value equality: two
        // different compact values can collapse to the same buggy decoded value.
        // No pool hash lookups are made after these detached lists are updated.
        updates.forEach(Runnable::run);
    }

    private static void repairAnnotations(byte[] bytes, int offset, AnnotationsDirectoryItem directory,
                                          DexMap map, List<Runnable> updates) throws IOException {
        Input input = littleEndian(bytes).position(offset);
        int classOffset = input.readInt();
        requireCount(input.readInt(), directory.fieldAnnotations().size());
        requireCount(input.readInt(), directory.methodAnnotations().size());
        requireCount(input.readInt(), directory.parameterAnnotations().size());
        repairSet(bytes, classOffset, directory.classAnnotations(), map, updates);
        for (var field : directory.fieldAnnotations()) {
            input.readInt(); // field_idx
            repairSet(bytes, input.readInt(), field.annotations(), map, updates);
        }
        for (var method : directory.methodAnnotations()) {
            input.readInt(); // method_idx
            repairSet(bytes, input.readInt(), method.annotations(), map, updates);
        }
        for (var parameter : directory.parameterAnnotations()) {
            input.readInt(); // method_idx
            Input parameters = littleEndian(bytes).position(input.readInt());
            requireCount(parameters.readInt(), parameter.annotations().items().size());
            for (var set : parameter.annotations().items()) {
                repairSet(bytes, parameters.readInt(), set, map, updates);
            }
        }
    }

    private static void repairSet(byte[] bytes, int offset, AnnotationSetItem set,
                                  DexMap map, List<Runnable> updates) throws IOException {
        if (offset == 0) {
            if (set != null && !set.isEmpty()) {
                throw new IOException("DEX annotation set does not match its file offset");
            }
            return;
        }
        Input input = littleEndian(bytes).position(offset);
        requireCount(input.readInt(), set.entries().size());
        for (var entry : set.entries()) {
            Input annotation = littleEndian(bytes).position(input.readInt());
            annotation.readByte(); // visibility
            stage(updates, entry.item().annotation().elements(),
                    readAnnotation(annotation, map, 0).elements());
        }
    }

    private static <T> void stage(List<Runnable> updates, List<T> target, List<T> corrected)
            throws IOException {
        updates.add(() -> {
            target.clear();
            target.addAll(corrected);
        });
    }

    static Value readValue(Input input, DexMap map, int depth) throws IOException {
        if (depth > 64) {
            throw new IOException("DEX encoded-value nesting exceeds 64");
        }
        int start = input.position();
        int header = input.readUnsignedByte();
        int type = header & 31;
        int width = (header >>> 5) + 1;
        return switch (type) {
            case 0x00 -> new ByteValue((byte) signed(input, width, 1));
            case 0x02 -> new ShortValue((short) signed(input, width, 2));
            case 0x03 -> new CharValue((char) unsigned(input, width, 2));
            case 0x04 -> new IntValue((int) signed(input, width, 4));
            case 0x06 -> new LongValue(signed(input, width, 8));
            case 0x10 -> new FloatValue(Float.intBitsToFloat(
                    (int) (unsigned(input, width, 4) << ((4 - width) * 8))));
            case 0x11 -> new DoubleValue(Double.longBitsToDouble(
                    unsigned(input, width, 8) << ((8 - width) * 8)));
            case 0x1c -> {
                requireWidth(width, 1);
                yield new ArrayValue(readArray(input, map, depth + 1));
            }
            case 0x1d -> {
                requireWidth(width, 1);
                yield new AnnotationValue(readAnnotation(input, map, depth + 1));
            }
            default -> {
                if (type >= 0x15 && type <= 0x1b) {
                    requireWidth(width, 4); // Unsigned reference index.
                } else if (type == 0x1e) {
                    requireWidth(width, 1);
                } else if (type == 0x1f) {
                    requireWidth(width, 2); // value_arg itself is the boolean.
                } else {
                    throw new IOException("unknown DEX encoded-value type: " + type);
                }
                input.position(start);
                yield Value.CODEC.read(input, map);
            }
        };
    }

    private static List<Value> readArray(Input input, DexMap map, int depth) throws IOException {
        int count = boundedCount(input.readULeb128(), input.size() - input.position());
        List<Value> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(readValue(input, map, depth));
        }
        return values;
    }

    private static EncodedAnnotation readAnnotation(Input input, DexMap map, int depth)
            throws IOException {
        var type = map.types().get(input.readULeb128());
        int count = boundedCount(input.readULeb128(), (input.size() - input.position()) / 2);
        List<AnnotationElement> elements = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            var name = map.strings().get(input.readULeb128());
            elements.add(new AnnotationElement(name, readValue(input, map, depth)));
        }
        return new EncodedAnnotation(type, elements);
    }

    private static long unsigned(Input input, int width, int maximum) throws IOException {
        requireWidth(width, maximum);
        long result = 0;
        for (int index = 0; index < width; index++) {
            result |= (long) input.readUnsignedByte() << (index * 8);
        }
        return result;
    }

    private static long signed(Input input, int width, int maximum) throws IOException {
        long result = unsigned(input, width, maximum);
        int shift = (8 - width) * 8;
        return (result << shift) >> shift;
    }

    private static void requireWidth(int width, int maximum) throws IOException {
        if (width > maximum) {
            throw new IOException("invalid DEX encoded-value width: " + width);
        }
    }

    private static int boundedCount(int count, int maximum) throws IOException {
        if (count < 0 || count > maximum) {
            throw new IOException("invalid DEX item count: " + count);
        }
        return count;
    }

    private static void requireCount(int count, int expected) throws IOException {
        if (count != expected) {
            throw new IOException("DEX item count " + count + " differs from decoded count " + expected);
        }
    }

    private static Input littleEndian(byte[] bytes) {
        return Input.wrap(bytes, ByteOrder.LITTLE_ENDIAN);
    }
}
