package me.naptila.recafmcp;

import me.darknet.dex.file.DexHeader;
import me.darknet.dex.file.DexMapBuilder;
import me.darknet.dex.io.GrowingOutput;
import me.darknet.dex.tree.DexFile;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.code.CodeBuilder;
import me.darknet.dex.tree.type.Types;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Generated fixtures shared by unit tests and the real-Worker runner. */
public final class AndroidFixture {
    public static byte[] dex(String className, String text) throws IOException {
        ClassDefinition definition = new ClassDefinition(
                Types.instanceTypeFromInternalName(className), Types.OBJECT, 1);
        MethodMember method = new MethodMember("greet", Types.methodTypeFromDescriptor("()V"), 9);
        method.setCode(new CodeBuilder().registers(1).const_string(0, text).return_void().build());
        definition.putMethod(method);
        MethodMember kept = new MethodMember("keep", Types.methodTypeFromDescriptor("()V"), 9);
        kept.setCode(new CodeBuilder().registers(1).const_string(0, "keep").return_void().build());
        definition.putMethod(kept);
        DexHeader header = DexFile.CODEC.unmap(new DexFile(35, List.of(definition)), new DexMapBuilder());
        GrowingOutput output = new GrowingOutput();
        DexHeader.CODEC.write(header, output);
        var buffer = output.buffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    public static void write(Path directory) throws IOException {
        Files.createDirectories(directory);
        byte[] first = dex("sample/Greeter", "before");
        Files.write(directory.resolve("input.dex"), first);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(directory.resolve("input.apk")))) {
            entry(zip, "classes.dex", first);
            entry(zip, "classes2.dex", dex("sample/Second", "second"));
            entry(zip, "AndroidManifest.xml", "<manifest package=\"sample\"/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            entry(zip, "assets/message.txt", "keep me".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            entry(zip, "META-INF/OLD.SF", new byte[]{1});
        }
    }

    private static void entry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    public static void main(String[] args) throws IOException {
        write(Path.of(args[0]));
    }
}
