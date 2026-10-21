package me.naptila.recafmcp.mcp.android;

import com.google.gson.JsonObject;
import me.naptila.recafmcp.AndroidFixture;
import me.naptila.recafmcp.mcp.internal.RecafSaveWorkspaceHandler;
import me.naptila.recafmcp.mcp.tools.ListFilesTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.coley.lljzip.ZipIO;
import software.coley.recaf.info.builder.FileInfoBuilder;
import software.coley.recaf.info.properties.builtin.ZipCommentProperty;
import software.coley.recaf.info.properties.builtin.ZipCompressionProperty;
import software.coley.recaf.services.text.TextFormatConfig;
import software.coley.recaf.services.workspace.BasicWorkspaceManager;
import software.coley.recaf.services.workspace.WorkspaceManagerConfig;
import software.coley.recaf.services.workspace.io.*;
import software.coley.recaf.workspace.model.BasicWorkspace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class AndroidInputImporterTest {
    @TempDir Path directory;

    private AndroidInputImporter importer() {
        return new AndroidInputImporter(new BasicInfoImporter(
                new InfoImporterConfig(), new TextFormatConfig(), new BasicClassPatcher()));
    }

    private Path apk(Map<String, byte[]> files, boolean stored) throws IOException {
        Path input = directory.resolve("input.apk");
        try (var zip = new ZipOutputStream(Files.newOutputStream(input))) {
            zip.setComment("archive comment");
            for (var file : files.entrySet()) {
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setComment("entry comment");
                if (stored) {
                    var crc = new CRC32(); crc.update(file.getValue());
                    entry.setMethod(ZipEntry.STORED); entry.setSize(file.getValue().length); entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry); zip.write(file.getValue()); zip.closeEntry();
            }
        }
        return input;
    }

    @Test
    void listsLazyResourcesWithoutLoadingThemAndReadsWithoutDirtyingHistory() throws Exception {
        byte[] text = "hello\n".repeat(200000).getBytes(StandardCharsets.UTF_8);
        byte[] dex = AndroidFixture.dex("sample/Greeter", "before");
        Path input = apk(Map.of("classes.dex", dex, "assets/large.txt", text,
                "assets/nested.zip", new byte[]{'P', 'K', 3, 4, 0, 0, 0, 0}), false);
        var resource = importer().readIfAndroid(input);
        var workspace = new BasicWorkspace(resource, List.of(), false);
        var manager = new BasicWorkspaceManager(new WorkspaceManagerConfig());
        manager.setCurrentIgnoringConditions(workspace);
        try {
            AndroidWorkspaceSupport.prepare(workspace);
            assertEquals(dex.length, resource.archive().materializedBytes());
            assertEquals(2, resource.getFileBundle().size());
            assertTrue(resource.getEmbeddedResources().isEmpty());
            var args = new JsonObject(); args.addProperty("filter", "large.txt");
            var result = new ListFilesTool(manager).execute(args).getAsJsonObject("structuredContent");
            assertEquals(text.length, result.getAsJsonArray("files").get(0).getAsJsonObject().get("size_bytes").getAsLong());
            assertEquals(dex.length, resource.archive().materializedBytes());
            var file = resource.getFileBundle().get("assets/large.txt");
            assertTrue(file.isTextFile());
            assertArrayEquals(text, file.getRawContent());
            assertEquals(dex.length + text.length, resource.archive().materializedBytes());
            assertTrue(resource.getFileBundle().getDirtyKeys().isEmpty());
            assertEquals(List.of(file), resource.getFileBundle().getHistory(file.getName()));
            assertTrue(resource.getAndroidClassBundles().get("classes.dex").getDirtyKeys().isEmpty());
        } finally { manager.setCurrentIgnoringConditions(null); }
        // The resource's ZipFile handle must be closed, including on Windows.
        Files.move(input, input.resolveSibling("closed.apk"));
    }

    @Test
    void streamsCompressedResourcesAndPersistsDexAndInPlaceFileEdits() throws Exception {
        for (boolean stored : List.of(false, true)) {
            byte[] payload = new byte[131072]; new Random(123).nextBytes(payload);
            Map<String, byte[]> contents = new LinkedHashMap<>();
            contents.put("classes.dex", AndroidFixture.dex("sample/Greeter", "before"));
            contents.put("assets/unchanged.bin", payload);
            contents.put("assets/change.txt", "before".getBytes(StandardCharsets.UTF_8));
            contents.put("assets/remove.txt", new byte[]{1});
            contents.put("META-INF/OLD.RSA", new byte[]{2});
            Path input = apk(contents, stored);
            byte[] original = Files.readAllBytes(input);
            var resource = importer().readIfAndroid(input);
            var manager = new BasicWorkspaceManager(new WorkspaceManagerConfig());
            manager.setCurrentIgnoringConditions(new BasicWorkspace(resource, List.of(), false));
            try {
                var bundle = resource.getAndroidClassBundles().get("classes.dex");
                bundle.put(AndroidDexReader.readBundle(AndroidFixture.dex("sample/Greeter", "after"),
                        software.coley.recaf.workspace.model.bundle.AndroidDexMetadata.DexEntryProperties.empty()).get("sample/Greeter"));
                var changedFile = resource.getFileBundle().get("assets/change.txt");
                changedFile.getRawContent()[0] = 'B';
                ZipCommentProperty.set(changedFile, "new comment");
                ZipCompressionProperty.set(changedFile, stored ? ZipEntry.DEFLATED : ZipEntry.STORED);
                resource.getFileBundle().remove("assets/remove.txt");
                resource.getFileBundle().put(new FileInfoBuilder<>().withName("assets/added.txt").withRawContent(new byte[]{3}).build());
                Path output = directory.resolve("saved-" + stored + ".apk");
                new RecafSaveWorkspaceHandler(manager).save(output.toString(), false);
                try (ZipFile zip = new ZipFile(output.toFile())) {
                    assertEquals("Before", new String(zip.getInputStream(zip.getEntry("assets/change.txt")).readAllBytes(), StandardCharsets.UTF_8));
                    assertEquals("new comment", zip.getEntry("assets/change.txt").getComment());
                    assertEquals(stored ? ZipEntry.DEFLATED : ZipEntry.STORED, zip.getEntry("assets/change.txt").getMethod());
                    assertNull(zip.getEntry("assets/remove.txt")); assertNull(zip.getEntry("META-INF/OLD.RSA"));
                    assertNotNull(zip.getEntry("assets/added.txt"));
                    assertArrayEquals(payload, zip.getInputStream(zip.getEntry("assets/unchanged.bin")).readAllBytes());
                }
                assertArrayEquals(compressed(input, "assets/unchanged.bin"), compressed(output, "assets/unchanged.bin"));
                assertArrayEquals(original, Files.readAllBytes(input));
                var reopened = importer().readIfAndroid(output);
                try {
                    assertTrue(reopened.getAndroidClassBundles().get("classes.dex").get("sample/Greeter")
                            .getBackingDefinition().getMethod("greet", "()V").getCode().getInstructions().toString().contains("after"));
                } finally { reopened.close(); }
            } finally { manager.setCurrentIgnoringConditions(null); }
            // Export must release its input mapping without waiting for GC.
            Files.delete(input);
        }
    }

    @Test
    void releasesMappedInputAfterFailedExport() throws Exception {
        Path input = apk(Map.of("classes.dex", AndroidFixture.dex("sample/Greeter", "before")), false);
        var resource = importer().readIfAndroid(input);
        try {
            assertThrows(IOException.class, () -> new AndroidArchiveExporter(resource).export(directory));
        } finally { resource.close(); }
        Files.delete(input);
    }

    @Test
    void rejectsInvalidDexWithItsEntryNameAndClosesTheArchive() throws Exception {
        Path input = apk(Map.of("classes2.dex", new byte[]{0, 1, 2}), false);
        assertTrue(assertThrows(IOException.class, () -> importer().readIfAndroid(input)).getMessage().contains("classes2.dex"));
        Files.move(input, input.resolveSibling("rejected.apk"));
    }

    @Test
    void inventoriesDexWithAnUnusualNameAndDetectsSourceReplacement() throws Exception {
        Path input = apk(Map.of("assets/hidden.bin", AndroidFixture.dex("sample/Greeter", "before")), false);
        var resource = importer().readIfAndroid(input);
        try {
            assertEquals(1, resource.getAndroidClassBundles().size());
            assertTrue(resource.getAndroidClassBundles().containsKey("assets/hidden.bin"));
            Files.setLastModifiedTime(input, FileTime.fromMillis(Files.getLastModifiedTime(input).toMillis() + 2000));
            assertThrows(IOException.class, resource::checkInput);
            assertThrows(IOException.class, () -> new AndroidArchiveExporter(resource).export(directory.resolve("changed.apk")));
        } finally { resource.close(); }
    }

    @Test
    void streamsZip64DirectoryWithoutDroppingEntries() throws Exception {
        Path input = directory.resolve("many.apk");
        try (var zip = new ZipOutputStream(Files.newOutputStream(input))) {
            for (int index = 0; index < 65536; index++) {
                zip.putNextEntry(new ZipEntry("d" + index + "/")); zip.closeEntry();
            }
        }
        var resource = importer().readIfAndroid(input);
        try {
            Path output = directory.resolve("many-saved.apk");
            new AndroidArchiveExporter(resource).export(output);
            try (var zip = new ZipFile(output.toFile())) { assertEquals(65536, zip.size()); }
        } finally { resource.close(); }
    }

    @Test
    void rewritesForcedZip64SizesAndOffsetsInASmallArchive() throws Exception {
        byte[] name = "payload.txt".getBytes(StandardCharsets.UTF_8), data = {1, 2, 3, 4, 5};
        CRC32 crc = new CRC32(); crc.update(data);
        var local = ByteBuffer.allocate(30 + name.length + 23).order(ByteOrder.LITTLE_ENDIAN);
        local.putInt(0, 0x04034b50).putShort(4, (short) 45).putInt(14, (int) crc.getValue())
                .putInt(18, -1).putInt(22, -1).putShort(26, (short) name.length).putShort(28, (short) 23);
        local.position(30); local.put(name).putShort((short) 1).putShort((short) 16).putLong(data.length).putLong(data.length);
        var central = ByteBuffer.allocate(46 + name.length + 28).order(ByteOrder.LITTLE_ENDIAN);
        central.putInt(0, 0x02014b50).putShort(4, (short) 45).putShort(6, (short) 45)
                .putInt(16, (int) crc.getValue()).putInt(20, -1).putInt(24, -1)
                .putShort(28, (short) name.length).putShort(30, (short) 28).putInt(42, -1);
        central.position(46); central.put(name).putShort((short) 1).putShort((short) 24)
                .putLong(data.length).putLong(data.length).putLong(0);
        var end = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        end.putInt(0, 0x06054b50).putShort(8, (short) 1).putShort(10, (short) 1)
                .putInt(12, central.capacity()).putInt(16, local.capacity() + data.length);
        var bytes = new ByteArrayOutputStream();
        bytes.write(local.array()); bytes.write(data); bytes.write(central.array()); bytes.write(end.array());
        Path input = directory.resolve("forced.apk"); Files.write(input, bytes.toByteArray());
        var resource = importer().readIfAndroid(input);
        try {
            Path output = directory.resolve("forced-saved.apk");
            new AndroidArchiveExporter(resource).export(output);
            try (var zip = new ZipFile(output.toFile())) {
                assertEquals(1, zip.size());
                assertArrayEquals(data, zip.getInputStream(zip.getEntry("payload.txt")).readAllBytes());
            }
        } finally { resource.close(); }
    }

    @Test
    void leavesGenericJarImportsToRecaf() throws Exception {
        Path input = directory.resolve("input.jar");
        Files.write(input, new byte[]{'P', 'K', 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        assertNull(importer().readIfAndroid(input));
    }

    private static byte[] compressed(Path path, String name) throws IOException {
        try (var archive = ZipIO.readJvm(Files.readAllBytes(path))) {
            var central = archive.getCentralDirectories().stream().filter(entry -> entry.getFileNameAsString().equals(name)).findFirst().orElseThrow();
            return central.getLinkedFileHeader().getFileData().asSlice(0, central.getCompressedSize()).toArray(ValueLayout.JAVA_BYTE);
        }
    }
}
