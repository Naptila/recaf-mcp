package me.naptila.recafmcp.mcp.internal;

import me.naptila.recafmcp.AndroidFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.coley.recaf.services.text.TextFormatConfig;
import software.coley.recaf.services.workspace.BasicWorkspaceManager;
import software.coley.recaf.services.workspace.WorkspaceManagerConfig;
import software.coley.recaf.services.workspace.io.*;
import software.coley.recaf.util.android.DexIOUtil;
import software.coley.recaf.workspace.model.BasicWorkspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class AndroidSaveTest {
    @TempDir Path directory;

    @Test
    void savesMutatedMultidexAndRawDexAndReopensBoth() throws Exception {
        AndroidFixture.write(directory);
        var importer = new BasicResourceImporter(new BasicInfoImporter(
                new InfoImporterConfig(), new TextFormatConfig(), new BasicClassPatcher()),
                new ResourceImporterConfig());
        for (String format : List.of("apk", "dex")) {
            Path input = directory.resolve("input." + format);
            byte[] original = Files.readAllBytes(input);
            var workspace = new BasicWorkspace(importer.importResource(input), List.of(), false);
            var manager = new BasicWorkspaceManager(new WorkspaceManagerConfig());
            manager.setCurrent(workspace);
            try {
                var bundle = workspace.getPrimaryResource().getAndroidClassBundles().values().iterator().next();
                String name = bundle.containsKey("sample/Greeter") ? "sample/Greeter" : "sample/Second";
                bundle.put(DexIOUtil.read(AndroidFixture.dex(name, "after")).get(name));
                Path output = directory.resolve("output." + format);
                var handler = new RecafSaveWorkspaceHandler(manager);
                assertThrows(java.io.IOException.class,
                        () -> handler.save(directory.resolve("wrong.jar").toString(), false));
                assertFalse(Files.exists(directory.resolve("wrong.jar")));
                var saved = handler.save(output.toString(), false);
                assertEquals(format, saved.get("format").getAsString());
                assertEquals(format.equals("apk") ? "unsigned" : "not_applicable",
                        saved.get("apk_signature_status").getAsString());
                var reopened = importer.importResource(output);
                try {
                    assertEquals(format.equals("apk") ? 2 : 1, reopened.getAndroidClassBundles().size());
                    var changed = reopened.getAndroidClassBundles().values().stream()
                            .map(b -> b.get(name)).filter(java.util.Objects::nonNull).findFirst().orElseThrow();
                    assertTrue(changed.getBackingDefinition().getMethod("greet", "()V")
                            .getCode().getInstructions().toString().contains("after"));
                    if (format.equals("apk")) {
                        try (ZipFile zip = new ZipFile(output.toFile())) {
                            assertNotNull(zip.getEntry("classes.dex"));
                            assertNotNull(zip.getEntry("classes2.dex"));
                            assertNull(zip.getEntry("META-INF/OLD.SF"));
                            assertEquals("keep me", new String(zip.getInputStream(zip.getEntry("assets/message.txt")).readAllBytes()));
                        }
                    }
                } finally {
                    reopened.close();
                }
                assertArrayEquals(original, Files.readAllBytes(input));
            } finally {
                manager.setCurrent(null);
            }
        }
    }
}
