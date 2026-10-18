package me.naptila.recafmcp.mcp.tools;

import me.naptila.recafmcp.AndroidFidelityFixture;
import me.naptila.recafmcp.mcp.android.AndroidDexReader;
import me.darknet.dex.tree.definitions.instructions.InstanceFieldInstruction;
import me.darknet.dex.tree.definitions.instructions.InvokeInstruction;
import me.darknet.dex.tree.definitions.instructions.StaticFieldInstruction;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.builder.AndroidClassInfoBuilder;
import software.coley.recaf.services.inheritance.*;
import software.coley.recaf.services.mapping.*;
import software.coley.recaf.services.mapping.aggregate.*;
import software.coley.recaf.services.search.*;
import software.coley.recaf.services.workspace.*;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.bundle.*;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AndroidMappingIntegrationTest {
    @Test
    void updatesMemberOwnersInSameDexAndOtherDexWithoutMutatingThePreview() throws Exception {
        var first = bundle(AndroidFidelityFixture.dex());
        var second = bundle(AndroidFidelityFixture.secondDex());
        var workspace = new BasicWorkspace(new WorkspaceResourceBuilder()
                .withAndroidClassBundles(Map.of("classes.dex", first, "classes2.dex", second)).build(), List.of(), false);
        var manager = new BasicWorkspaceManager(new WorkspaceManagerConfig());
        var listeners = new MappingListeners(new MappingListenersConfig());
        var graph = new InheritanceGraphService(manager, listeners, new InheritanceGraphServiceConfig());
        var aggregate = new AggregateMappingManager(new AggregateMappingManagerConfig(), manager);
        manager.setCurrentIgnoringConditions(workspace);
        try {
            var applier = new TargetedMappingApplier(new SearchService(new SearchServiceConfig()), graph, listeners, aggregate);
            var mappings = new IntermediateMappings();
            mappings.addClass("sample/Fidelity", "sample/Renamed");
            var prepared = applier.prepare(workspace, mappings);
            assertEquals(3, prepared.summary().changes().size());
            assertTrue(first.containsKey("sample/Fidelity"));
            assertOwners(first, "sample/Caller", "sample/Fidelity");
            assertEquals(java.util.Arrays.asList("known", null), first.get("sample/Fidelity")
                    .getBackingDefinition().getMethod("unnamed", "(II)I").getCode().getDebugInfo().parameterNames());
            prepared.apply();
            assertFalse(first.containsKey("sample/Fidelity"));
            assertTrue(first.containsKey("sample/Renamed"));
            assertOwners(first, "sample/Caller", "sample/Renamed");
            assertOwners(second, "sample/OtherCaller", "sample/Renamed");
            assertEquals(-515, first.get("sample/Renamed").getDeclaredField("NEG_A", "I").getDefaultValue());
            assertNotNull(first.get("sample/Renamed").getBackingDefinition().getMethod("mix", "(I)I").getCode().getDebugInfo());
            var names = first.get("sample/Renamed").getBackingDefinition().getMethod("unnamed", "(II)I")
                    .getCode().getDebugInfo().parameterNames();
            assertEquals(java.util.Arrays.asList("known", null), names);
        } finally {
            manager.setCurrentIgnoringConditions(null);
        }
    }

    @Test
    void mappingPreparationFailureLeavesEveryLiveDexUnchanged() throws Exception {
        var first = bundle(AndroidFidelityFixture.dex());
        var second = bundle(AndroidFidelityFixture.secondDex());
        var original = first.get("sample/Fidelity");
        var workspace = new BasicWorkspace(new WorkspaceResourceBuilder()
                .withAndroidClassBundles(Map.of("classes.dex", first, "classes2.dex", second)).build(), List.of(), false);
        var manager = new BasicWorkspaceManager(new WorkspaceManagerConfig());
        var listeners = new MappingListeners(new MappingListenersConfig());
        var graph = new InheritanceGraphService(manager, listeners, new InheritanceGraphServiceConfig());
        var aggregate = new AggregateMappingManager(new AggregateMappingManagerConfig(), manager);
        manager.setCurrentIgnoringConditions(workspace);
        try {
            var mappings = new MappingsAdapter(true, true) {
                @Override
                public String getMappedFieldName(String owner, String name, String descriptor) {
                    throw new IllegalStateException("injected mapping preparation failure");
                }
            };
            mappings.addClass("sample/Fidelity", "sample/Renamed");
            var applier = new TargetedMappingApplier(new SearchService(new SearchServiceConfig()), graph, listeners, aggregate);
            assertThrows(IllegalStateException.class, () -> applier.prepare(workspace, mappings));
            assertSame(original, first.get("sample/Fidelity"));
            assertFalse(first.containsKey("sample/Renamed"));
            assertOwners(first, "sample/Caller", "sample/Fidelity");
            assertOwners(second, "sample/OtherCaller", "sample/Fidelity");
            assertEquals(java.util.Arrays.asList("known", null), original.getBackingDefinition()
                    .getMethod("unnamed", "(II)I").getCode().getDebugInfo().parameterNames());
        } finally {
            manager.setCurrentIgnoringConditions(null);
        }
    }

    private static BasicAndroidClassBundle bundle(byte[] bytes) throws Exception {
        var file = AndroidDexReader.read(bytes);
        var bundle = new BasicAndroidClassBundle(file.version(), file.link());
        file.definitions().forEach(definition -> bundle.initialPut(new AndroidClassInfoBuilder(definition).build()));
        return bundle;
    }

    private static void assertOwners(AndroidClassBundle bundle, String caller, String expected) {
        int references = 0;
        for (var instruction : bundle.get(caller).getBackingDefinition().getMethod("evaluate", "()I").getCode().getInstructions()) {
            String owner = switch (instruction) {
                case InvokeInstruction invoke -> invoke.owner().internalName();
                case StaticFieldInstruction field -> field.owner().internalName();
                case InstanceFieldInstruction field -> field.owner().internalName();
                default -> null;
            };
            if (owner != null) {
                assertEquals(expected, owner);
                references++;
            }
        }
        assertEquals(2, references);
    }
}
