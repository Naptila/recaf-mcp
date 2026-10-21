package me.naptila.recafmcp.mcp.android;

import me.naptila.recafmcp.AndroidFidelityFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.coley.recaf.info.builder.AndroidClassInfoBuilder;
import software.coley.recaf.info.builder.ApkFileInfoBuilder;
import software.coley.recaf.services.text.TextFormatConfig;
import software.coley.recaf.services.workspace.io.*;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResourceBuilder;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class AndroidWorkspaceSupportTest {
    @TempDir Path directory;
    private final List<BasicWorkspace> opened = new ArrayList<>();

    @AfterEach
    void closeWorkspaces() {
        opened.forEach(BasicWorkspace::close);
    }

    private BasicWorkspace workspace(String format) throws IOException {
        AndroidFidelityFixture.write(directory);
        var importer = new BasicResourceImporter(new BasicInfoImporter(
                new InfoImporterConfig(), new TextFormatConfig(), new BasicClassPatcher()),
                new ResourceImporterConfig());
        var workspace = new BasicWorkspace(importer.importResource(directory.resolve("fidelity." + format)), List.of(), false);
        opened.add(workspace);
        return workspace;
    }

    @Test
    void rejectsAnEntireMissingDexBundle() throws Exception {
        var workspace = workspace("apk");
        workspace.getPrimaryResource().getAndroidClassBundles().remove("classes2.dex");
        var error = assertThrows(IOException.class, () -> AndroidWorkspaceSupport.prepare(workspace));
        assertTrue(error.getMessage().contains("classes2.dex"));
        assertTrue(error.getMessage().contains("not imported"));
    }

    @Test
    void rejectsAnApkWithEveryDexBundleMissing() throws Exception {
        var workspace = workspace("apk");
        workspace.getPrimaryResource().getAndroidClassBundles().clear();
        assertThrows(IOException.class, () -> AndroidWorkspaceSupport.prepare(workspace));
    }

    @Test
    void rejectsMissingClasses() throws Exception {
        var apk = workspace("apk");
        apk.getPrimaryResource().getAndroidClassBundles().get("classes2.dex").clear();
        assertThrows(IOException.class, () -> AndroidWorkspaceSupport.prepare(apk));
    }

    @Test
    void rejectsAnEmptyStandaloneImport() throws Exception {
        var original = workspace("dex");
        var resource = new WorkspaceFileResourceBuilder().withFileInfo(
                ((WorkspaceFileResource) original.getPrimaryResource()).getFileInfo()).build();
        var dex = new BasicWorkspace(resource, List.of(), false);
        opened.add(dex);
        assertThrows(IOException.class, () -> AndroidWorkspaceSupport.prepare(dex));
    }

    @Test
    void preparesAnArchiveFromRetainedBytesWithoutAnInputPath() throws Exception {
        var original = workspace("apk");
        var resource = new WorkspaceFileResourceBuilder()
                .withFileInfo(new ApkFileInfoBuilder().withName("retained.apk")
                        .withRawContent(Files.readAllBytes(directory.resolve("fidelity.apk"))).build())
                .withAndroidClassBundles(original.getPrimaryResource().getAndroidClassBundles()).build();
        var retained = new BasicWorkspace(resource, List.of(), false);
        opened.add(retained);
        AndroidWorkspaceSupport.prepare(retained);
        assertEquals(2, resource.getAndroidClassBundles().size());
        resource.getAndroidClassBundles().values().forEach(bundle -> assertTrue(bundle.getDirtyKeys().isEmpty()));
    }

    @Test
    void inventoriesDexMagicEvenWithANonstandardEntryName() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("assets/hidden.bin"));
            zip.write(AndroidFidelityFixture.secondDex());
            zip.closeEntry();
        }
        var resource = new WorkspaceFileResourceBuilder().withFileInfo(
                new ApkFileInfoBuilder().withName("hidden.apk").withRawContent(bytes.toByteArray()).build()).build();
        var workspace = new BasicWorkspace(resource, List.of(), false);
        opened.add(workspace);
        var error = assertThrows(IOException.class, () -> AndroidWorkspaceSupport.prepare(workspace));
        assertTrue(error.getMessage().contains("assets/hidden.bin"));
    }

    @Test
    void repairedTreesAreTheCleanBaselineAndSubsequentEditsHaveHistory() throws Exception {
        var workspace = workspace("apk");
        AndroidWorkspaceSupport.prepare(workspace);
        for (var bundle : workspace.getPrimaryResource().getAndroidClassBundles().values()) {
            assertTrue(bundle.getDirtyKeys().isEmpty());
            for (var info : bundle.values()) {
                assertEquals(List.of(info), bundle.getHistory(info.getName()));
            }
        }
        var bundle = workspace.getPrimaryResource().getAndroidClassBundles().get("classes.dex");
        var baseline = bundle.get("sample/Fidelity");
        var edit = new AndroidClassInfoBuilder(baseline.getBackingDefinition()).build();
        bundle.put(edit);
        assertEquals(List.of(baseline, edit), bundle.getHistory(baseline.getName()));
        assertTrue(bundle.getDirtyKeys().contains(baseline.getName()));
        bundle.decrementHistory(baseline.getName());
        assertSame(baseline, bundle.get(baseline.getName()));
    }
}
