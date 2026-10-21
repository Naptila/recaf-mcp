package me.naptila.recafmcp;

import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.debug.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.immutable.value.*;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Independent encoder, including compact values, debug tables and owner-only callers. */
public final class AndroidFidelityFixture {
    public static final String TARGET = "Lsample/Fidelity;";

    public static byte[] dex() throws IOException {
        var annotation = new ImmutableAnnotation(1, "Lsample/Values;", Set.of(
                new ImmutableAnnotationElement("nested", new ImmutableArrayEncodedValue(List.of(
                        new ImmutableIntEncodedValue(-558), new ImmutableFloatEncodedValue(1.5f))))));
        var method = new ImmutableMethod(TARGET, "mix",
                List.of(new ImmutableMethodParameter("I", Set.of(), "input")), "I", 9,
                Set.of(annotation), Set.of(), new ImmutableMethodImplementation(2, List.of(
                        new ImmutableInstruction21s(Opcode.CONST_16, 0, 17),
                        new ImmutableInstruction12x(Opcode.ADD_INT_2ADDR, 1, 0),
                        new ImmutableInstruction11x(Opcode.RETURN, 1)), List.of(), List.of(
                        new ImmutableLineNumber(0, 10),
                        new ImmutableStartLocal(2, 0, "delta", "I", null),
                        new ImmutableLineNumber(3, 11),
                        new ImmutableEndLocal(4, 0))));
        var fields = List.of(
                field("NEG_A", "I", new ImmutableIntEncodedValue(-515)),
                field("NEG_B", "I", new ImmutableIntEncodedValue(-558)),
                field("NEG_C", "I", new ImmutableIntEncodedValue(-790)),
                field("SHORT", "S", new ImmutableShortEncodedValue((short) -7)),
                field("LONG", "J", new ImmutableLongEncodedValue(-515)),
                field("FLOAT", "F", new ImmutableFloatEncodedValue(1.5f)),
                field("DOUBLE", "D", new ImmutableDoubleEncodedValue(-2.5)),
                field("CHAR", "C", new ImmutableCharEncodedValue('\ufffe')));
        var unnamed = new ImmutableMethod(TARGET, "unnamed", List.of(
                new ImmutableMethodParameter("I", Set.of(), "known"),
                new ImmutableMethodParameter("I", Set.of(), null)), "I", 9, Set.of(), Set.of(),
                new ImmutableMethodImplementation(2, List.of(new ImmutableInstruction11x(Opcode.RETURN, 0)),
                        List.of(), List.of(new ImmutableLineNumber(0, 20))));
        var target = new ImmutableClassDef(TARGET, 1, "Ljava/lang/Object;", List.of(),
                "Fidelity.java", Set.of(annotation), fields, List.of(method, unnamed));
        var outer = new ImmutableClassDef("Lsample/Outer;", 1, "Ljava/lang/Object;", List.of(), null,
                Set.of(), List.of(), List.of());
        var inner = new ImmutableClassDef("Lsample/Outer$Inner;", 1, "Ljava/lang/Object;", List.of(), null,
                Set.of(new ImmutableAnnotation(2, "Ldalvik/annotation/EnclosingClass;", Set.of(
                        new ImmutableAnnotationElement("value", new ImmutableTypeEncodedValue("Lsample/Outer;")))),
                        new ImmutableAnnotation(2, "Ldalvik/annotation/InnerClass;", Set.of(
                                new ImmutableAnnotationElement("name", new ImmutableStringEncodedValue("Inner")),
                                new ImmutableAnnotationElement("accessFlags", new ImmutableIntEncodedValue(9))))),
                List.of(), List.of());
        return encode(List.of(target, caller("Lsample/Caller;"), outer, inner));
    }

    public static byte[] secondDex() throws IOException {
        return encode(List.of(caller("Lsample/OtherCaller;")));
    }

    public static byte[] moveObject16Dex() throws IOException {
        String owner = "Lsample/WideRegisters;";
        var method = new ImmutableMethod(owner, "copy",
                List.of(new ImmutableMethodParameter("Ljava/lang/Object;", Set.of(), "input")),
                "Ljava/lang/Object;", 9, Set.of(), Set.of(), new ImmutableMethodImplementation(600, List.of(
                        new ImmutableInstruction32x(Opcode.MOVE_OBJECT_16, 300, 599),
                        new ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 0, 300),
                        new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), List.of(),
                        List.of(new ImmutableLineNumber(0, 10), new ImmutableLineNumber(3, 11))));
        return encode(List.of(new ImmutableClassDef(owner, 1, "Ljava/lang/Object;", List.of(), null,
                Set.of(), List.of(), List.of(method))));
    }

    public static byte[] arrayDex(String component, int width, List<Number> values) throws IOException {
        String owner = "Lsample/ArrayPayload;";
        var method = new ImmutableMethod(owner, "values", List.of(), "[" + component, 9,
                Set.of(), Set.of(), new ImmutableMethodImplementation(1, List.of(
                        new ImmutableInstruction11n(Opcode.CONST_4, 0, values.size()),
                        new ImmutableInstruction22c(Opcode.NEW_ARRAY, 0, 0,
                                new org.jf.dexlib2.immutable.reference.ImmutableTypeReference("[" + component)),
                        new ImmutableInstruction31t(Opcode.FILL_ARRAY_DATA, 0, 5),
                        new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                        new ImmutableInstruction10x(Opcode.NOP),
                        new ImmutableArrayPayload(width, values)), List.of(), List.of()));
        return encode(List.of(new ImmutableClassDef(owner, 1, "Ljava/lang/Object;", List.of(), null,
                Set.of(), List.of(), List.of(method))));
    }

    public static byte[] paddingDebugDex() throws IOException {
        String owner = "Lsample/PaddingDebug;";
        var method = new ImmutableMethod(owner, "values", List.of(), "[I", 9, Set.of(), Set.of(),
                new ImmutableMethodImplementation(1, List.of(
                        new ImmutableInstruction11n(Opcode.CONST_4, 0, 2),
                        new ImmutableInstruction22c(Opcode.NEW_ARRAY, 0, 0,
                                new org.jf.dexlib2.immutable.reference.ImmutableTypeReference("[I")),
                        new ImmutableInstruction31t(Opcode.FILL_ARRAY_DATA, 0, 5),
                        new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                        new ImmutableInstruction10x(Opcode.NOP),
                        new ImmutableArrayPayload(4, List.of(17, 18))), List.of(), List.of(
                        new ImmutableLineNumber(0, 10), new ImmutableLineNumber(7, 11),
                        new ImmutableLineNumber(8, 12), new ImmutableStartLocal(8, 0, "data", "[I", null),
                        new ImmutableEndLocal(9, 0))));
        return encode(List.of(new ImmutableClassDef(owner, 1, "Ljava/lang/Object;", List.of(), null,
                Set.of(), List.of(), List.of(method))));
    }

    private static ImmutableField field(String name, String type, ImmutableEncodedValue value) {
        return new ImmutableField(TARGET, name, type, 25, value, Set.of(), Set.of());
    }

    private static ImmutableClassDef caller(String name) {
        var method = new ImmutableMethod(name, "evaluate", List.of(), "I", 9,
                Set.of(), Set.of(), new ImmutableMethodImplementation(1, List.of(
                        new ImmutableInstruction21c(Opcode.SGET, 0,
                                new ImmutableFieldReference(TARGET, "NEG_A", "I")),
                        new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
                                new ImmutableMethodReference(TARGET, "mix", List.of("I"), "I")),
                        new ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                        new ImmutableInstruction11x(Opcode.RETURN, 0)), List.of(), List.of()));
        return new ImmutableClassDef(name, 1, "Ljava/lang/Object;", List.of(), null,
                Set.of(), List.of(), List.of(method));
    }

    private static byte[] encode(List<ImmutableClassDef> classes) throws IOException {
        MemoryDataStore store = new MemoryDataStore();
        try {
            DexPool.writeTo(store, new ImmutableDexFile(Opcodes.forDexVersion(35), classes));
            return store.getData();
        } finally {
            store.close();
        }
    }

    public static void write(Path directory) throws IOException {
        Files.createDirectories(directory);
        byte[] first = dex();
        Files.write(directory.resolve("fidelity.dex"), first);
        byte[] wideRegisters = moveObject16Dex();
        Files.write(directory.resolve("move-object-16.dex"), wideRegisters);
        try (var zip = new ZipOutputStream(Files.newOutputStream(directory.resolve("move-object-16.apk")))) {
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write(wideRegisters);
            zip.closeEntry();
        }
        try (var zip = new ZipOutputStream(Files.newOutputStream(directory.resolve("fidelity.apk")))) {
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write(first);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("classes2.dex"));
            zip.write(secondDex());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write("<manifest package=\"sample\"/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    public static void main(String[] args) throws IOException {
        write(Path.of(args[0]));
    }
}
