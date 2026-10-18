package me.naptila.recafmcp.mcp.tools;

import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.debug.DebugInformation;
import me.darknet.dex.tree.remap.ClassDefinitionRemapper;
import me.darknet.dex.tree.remap.DefinitionRemapper;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.tree.type.ReferenceType;
import me.naptila.recafmcp.mcp.android.AndroidDexReader;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.builder.AndroidClassInfoBuilder;
import software.coley.recaf.info.properties.builtin.HasMappedReferenceProperty;
import software.coley.recaf.info.properties.builtin.OriginalClassNameProperty;
import software.coley.recaf.info.properties.builtin.RemapOriginTaskProperty;
import software.coley.recaf.services.mapping.MappingListeners;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.services.mapping.Mappings;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Native DEX remapping with nullable debug names preserved and errors propagated. */
final class AndroidMappingSupport {
    private AndroidMappingSupport() {}

    static MappingResults prepare(Workspace workspace, WorkspaceResource resource, AndroidClassBundle bundle,
                                  List<AndroidClassInfo> classes, Mappings mappings, MappingListeners listeners) {
        var results = new MappingResults(workspace, mappings, listeners.createBundledMappingApplicationListener());
        var remapper = new ClassDefinitionRemapper(new DefinitionRemapper() {
            @Override
            public String mapInternalName(String name) {
                return Objects.requireNonNullElse(mappings.getMappedClassName(name), name);
            }

            @Override
            public String mapFieldName(InstanceType owner, String name, ClassType type) {
                return Objects.requireNonNullElse(mappings.getMappedFieldName(owner.internalName(), name, type.descriptor()), name);
            }

            @Override
            public String mapMethodName(ReferenceType owner, String name, MethodType type) {
                return Objects.requireNonNullElse(mappings.getMappedMethodName(owner.internalName(), name, type.descriptor()), name);
            }
        });
        for (AndroidClassInfo info : classes) {
            ToolCancellation.check();
            ClassDefinition original = info.getBackingDefinition();
            ClassDefinition source = original;
            String marker = null;
            if (hasUnnamedParameters(original)) {
                try {
                    source = AndroidDexReader.readClass(AndroidDexReader.encodeClass(info, bundle.getVersion()),
                            info.getName()).getBackingDefinition();
                } catch (IOException ex) {
                    throw new IllegalStateException("Android mapping snapshot failed: " + info.getName(), ex);
                }
                marker = "\u0000recaf-mcp-unnamed-" + UUID.randomUUID();
                replaceNames(source, null, marker);
            }
            ClassDefinition mapped = remapper.remap(source);
            if (marker != null) replaceNames(mapped, marker, null);
            if (!original.equals(mapped)) {
                AndroidClassInfo updated = new AndroidClassInfoBuilder(mapped).build();
                HasMappedReferenceProperty.set(updated);
                updated.setProperty(new RemapOriginTaskProperty(results));
                if (!info.getName().equals(updated.getName())) {
                    OriginalClassNameProperty.set(updated, Objects.requireNonNullElse(
                            OriginalClassNameProperty.get(info), info.getName()));
                }
                results.add(workspace, resource, bundle, info, updated);
            }
        }
        return results;
    }

    private static boolean hasUnnamedParameters(ClassDefinition definition) {
        return definition.getMethods().values().stream().anyMatch(method -> {
            var code = method.getCode();
            return code != null && code.getDebugInfo() != null && code.getDebugInfo().parameterNames() != null
                    && code.getDebugInfo().parameterNames().stream().anyMatch(Objects::isNull);
        });
    }

    private static void replaceNames(ClassDefinition definition, String from, String to) {
        for (var method : definition.getMethods().values()) {
            var code = method.getCode();
            if (code == null || code.getDebugInfo() == null) continue;
            var debug = code.getDebugInfo();
            if (debug.parameterNames() == null) continue;
            List<String> names = new ArrayList<>(debug.parameterNames());
            names.replaceAll(name -> Objects.equals(name, from) ? to : name);
            code.setDebugInfo(new DebugInformation(debug.lineNumbers(), debug.addressLines(), names, debug.locals()));
        }
    }
}
