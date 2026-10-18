package me.naptila.recafmcp.mcp.tools;

import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.builder.AndroidClassInfoBuilder;
import software.coley.recaf.workspace.model.bundle.BasicAndroidClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunRecafScriptChangesTest {
    @Test
    void observesAndroidEditsAcrossDexBundlesAndCollapsesTemporaryChanges() {
        var first = new BasicAndroidClassBundle(35, new byte[0]);
        var second = new BasicAndroidClassBundle(35, new byte[0]);
        first.put(info("sample/First"));
        second.put(info("sample/Removed"));
        var resource = new WorkspaceResourceBuilder().withAndroidClassBundles(
                Map.of("classes.dex", first, "classes2.dex", second)).build();
        var tracker = new RunRecafScriptTool.ChangeTracker(resource);
        resource.addResourceAndroidClassListener(tracker);
        try {
            first.put(info("sample/First"));
            first.put(info("sample/Transient"));
            first.remove("sample/Transient");
            second.remove("sample/Removed");
            second.put(info("sample/Added"));
            second.put(info("sample/Added"));

            assertEquals(List.of("sample/Added"), tracker.added());
            assertEquals(List.of("sample/First"), tracker.modified());
            assertEquals(List.of("sample/Removed"), tracker.removed());
        } finally {
            resource.removeResourceAndroidClassListener(tracker);
            resource.close();
        }
    }

    @Test
    void keepsEqualDexBundlesSeparateAndIgnoresUntrackedBundles() {
        var first = new BasicAndroidClassBundle(35, new byte[0]);
        var second = new BasicAndroidClassBundle(35, new byte[0]);
        var unrelated = new BasicAndroidClassBundle(35, new byte[0]);
        first.put(info("sample/Shared"));
        second.put(info("sample/Shared"));
        unrelated.put(info("sample/Shared"));
        var resource = new WorkspaceResourceBuilder().withAndroidClassBundles(
                Map.of("classes.dex", first, "classes2.dex", second)).build();
        var tracker = new RunRecafScriptTool.ChangeTracker(resource);
        resource.addResourceAndroidClassListener(tracker);
        try {
            tracker.onNewClass(resource, unrelated, info("sample/Ignore"));
            first.put(info("sample/Shared"));
            second.remove("sample/Shared");

            assertEquals(List.of(), tracker.added());
            assertEquals(List.of("sample/Shared"), tracker.modified());
            assertEquals(List.of("sample/Shared"), tracker.removed());
        } finally {
            resource.removeResourceAndroidClassListener(tracker);
            resource.close();
            unrelated.close();
        }
    }

    private static AndroidClassInfo info(String name) {
        return new AndroidClassInfoBuilder(new ClassDefinition(
                Types.instanceTypeFromInternalName(name), Types.OBJECT, 1)).build();
    }
}
