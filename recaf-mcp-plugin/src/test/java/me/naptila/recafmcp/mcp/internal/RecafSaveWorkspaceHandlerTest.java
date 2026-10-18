package me.naptila.recafmcp.mcp.internal;

import org.junit.jupiter.api.Test;
import software.coley.recaf.info.builder.ApkFileInfoBuilder;
import software.coley.recaf.info.builder.DexFileInfoBuilder;
import software.coley.recaf.info.builder.JarFileInfoBuilder;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResourceBuilder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecafSaveWorkspaceHandlerTest {
    @Test
    void supportsJarArchiveExport() {
        Workspace workspace = workspace(new JarFileInfoBuilder()
                .withName("input.jar")
                .withRawContent(new byte[]{1})
                .build());

        assertTrue(RecafSaveWorkspaceHandler.supportsArchiveExport(workspace));
    }

    @Test
    void supportsApkAndDexInTheirOriginalFormats() {
        Workspace apk = workspace(new ApkFileInfoBuilder()
                .withName("input.apk")
                .withRawContent(new byte[]{1})
                .build());
        Workspace dex = workspace(new DexFileInfoBuilder()
                .withName("classes.dex")
                .withRawContent(new byte[]{1})
                .build());

        assertTrue(RecafSaveWorkspaceHandler.supportsArchiveExport(apk));
        assertTrue(RecafSaveWorkspaceHandler.supportsArchiveExport(dex));
        org.junit.jupiter.api.Assertions.assertEquals("apk", RecafSaveWorkspaceHandler.outputFormat(apk));
        org.junit.jupiter.api.Assertions.assertEquals("dex", RecafSaveWorkspaceHandler.outputFormat(dex));
    }

    private static Workspace workspace(software.coley.recaf.info.FileInfo fileInfo) {
        WorkspaceFileResource primary = new WorkspaceFileResourceBuilder()
                .withFileInfo(fileInfo)
                .build();
        return new BasicWorkspace(primary, List.of(), false);
    }
}
