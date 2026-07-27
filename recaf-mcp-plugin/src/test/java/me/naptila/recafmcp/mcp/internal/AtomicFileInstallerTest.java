package me.naptila.recafmcp.mcp.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AtomicFileInstallerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesAndAtomicallyInstallsZip() throws IOException {
        Path input = temporaryDirectory.resolve("input.jar");
        writeZip(input, "input.txt", "input");
        Path output = temporaryDirectory.resolve("output.jar");

        AtomicFileInstaller.Result result = AtomicFileInstaller.install(
                input,
                output,
                false,
                path -> writeZip(path, "example/Foo.class", "bytecode")
        );

        assertEquals(output.toRealPath(), result.path());
        assertEquals(Files.size(output), result.sizeBytes());
        assertEquals(1, result.zipEntries());
        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertEquals(1, zip.size());
        }
    }

    @Test
    void rejectsOriginalInput() throws IOException {
        Path input = temporaryDirectory.resolve("input.jar");
        writeZip(input, "input.txt", "input");

        IOException directError = assertThrows(IOException.class, () ->
                AtomicFileInstaller.install(input, input, true, path -> writeZip(path, "x", "y"))
        );

        assertTrue(directError.getMessage().contains("original input"));
    }

    @Test
    void rejectsSymlinkAliasWhenSupported() throws IOException {
        Path input = temporaryDirectory.resolve("input.jar");
        writeZip(input, "input.txt", "input");
        Path alias = temporaryDirectory.resolve("alias.jar");
        try {
            Files.createSymbolicLink(alias, input.getFileName());
        } catch (IOException | UnsupportedOperationException error) {
            assumeTrue(false, () -> "symbolic links are unavailable: " + error);
            return;
        }

        IOException aliasError = assertThrows(IOException.class, () ->
                AtomicFileInstaller.install(input, alias, true, path -> writeZip(path, "x", "y"))
        );

        assertTrue(aliasError.getMessage().contains("original input"));
        assertTrue(Files.isSymbolicLink(alias));
    }

    @Test
    void preservesExistingDestinationWithoutOverwrite() throws IOException {
        Path input = temporaryDirectory.resolve("input.jar");
        writeZip(input, "input.txt", "input");
        Path output = temporaryDirectory.resolve("output.jar");
        byte[] existing = "existing".getBytes(StandardCharsets.UTF_8);
        Files.write(output, existing);

        assertThrows(FileAlreadyExistsException.class, () ->
                AtomicFileInstaller.install(input, output, false, path -> writeZip(path, "x", "y"))
        );

        assertArrayEquals(existing, Files.readAllBytes(output));
    }

    @Test
    void removesInvalidTemporaryExportAndPreservesDestination() throws IOException {
        Path input = temporaryDirectory.resolve("input.jar");
        writeZip(input, "input.txt", "input");
        Path output = temporaryDirectory.resolve("output.jar");
        writeZip(output, "old.txt", "old");
        byte[] existing = Files.readAllBytes(output);

        assertThrows(IOException.class, () ->
                AtomicFileInstaller.install(
                        input,
                        output,
                        true,
                        path -> Files.writeString(path, "not a zip")
                )
        );

        assertArrayEquals(existing, Files.readAllBytes(output));
        try (var paths = Files.list(temporaryDirectory)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    private static void writeZip(Path path, String entryName, String content) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
