package me.naptila.recafmcp;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Small runnable JVM sample with long metadata and constant arithmetic. */
public final class JvmTransformFixture {
    public static byte[] bytecode(String name) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V22, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitAnnotation("Lsample/" + "A".repeat(80) + ";", true).visitEnd();
        var value = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()I", null, null);
        value.visitCode();
        value.visitInsn(Opcodes.ICONST_2);
        value.visitInsn(Opcodes.ICONST_3);
        value.visitInsn(Opcodes.IADD);
        value.visitInsn(Opcodes.IRETURN);
        value.visitMaxs(2, 0);
        value.visitEnd();
        var main = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        main.visitCode();
        main.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(Opcodes.INVOKESTATIC, name, "value", "()I", false);
        main.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitInsn(Opcodes.RETURN);
        main.visitMaxs(2, 1);
        main.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static void main(String[] args) throws IOException {
        Path destination = Path.of(args[0]);
        Files.createDirectories(destination.getParent());
        try (var zip = new ZipOutputStream(Files.newOutputStream(destination))) {
            for (String name : new String[]{"sample/Obfuscated", "other/Untouched"}) {
                zip.putNextEntry(new ZipEntry(name + ".class"));
                zip.write(bytecode(name));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("assets/message.txt"));
            zip.write("keep me".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
