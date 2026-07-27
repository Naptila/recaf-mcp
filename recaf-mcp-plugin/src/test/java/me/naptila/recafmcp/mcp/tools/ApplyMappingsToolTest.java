package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.builder.AndroidClassInfoBuilder;
import software.coley.recaf.services.mapping.Mappings;
import software.coley.recaf.services.mapping.UniqueKeyMappings;
import software.coley.recaf.services.mapping.format.SimpleMappings;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.bundle.BasicAndroidClassBundle;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyMappingsToolTest {
    private static final String OLD_NAME = "example/OldName";
    private static final String NEW_NAME = "example/NewName";
    private static final List<MappingToolSupport.Descriptor> FORMATS = List.of(
            format("Enigma", true, true),
            format("Jadx (Legacy)", true, true),
            format("Proguard", true, false),
            format("Simple", true, true),
            format("SRG", false, false),
            format("Tiny-V1", true, true),
            format("Tiny-V2", true, true)
    );

    @Test
    void parsesAndAppliesCanonicalizedRecafFormatWithBoundedChanges() {
        Workspace workspace = workspace();
        AtomicBoolean applied = new AtomicBoolean();
        AtomicReference<Mappings> parsed = new AtomicReference<>();
        ApplyMappingsTool tool = tool(
                () -> workspace,
                (currentWorkspace, mappings) -> {
                    assertEquals(workspace, currentWorkspace);
                    parsed.set(mappings);
                    return new ApplyMappingsTool.PreparedMapping(
                            List.of(
                                    new ApplyMappingsTool.ClassChange(
                                            OLD_NAME,
                                            NEW_NAME
                                    ),
                                    new ApplyMappingsTool.ClassChange(
                                            "example/Reference",
                                            "example/Reference"
                                    )
                            ),
                            null,
                            () -> applied.set(true)
                    );
                }
        );
        JsonObject arguments = arguments("simple", OLD_NAME + " " + NEW_NAME);
        arguments.addProperty("limit", 1);

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean());
        assertTrue(applied.get());
        assertEquals(NEW_NAME, parsed.get().getMappedClassName(OLD_NAME));
        assertEquals("Simple", structured.get("format").getAsString());
        assertEquals("current_to_target",
                structured.get("mapping_direction").getAsString());
        assertEquals(2, structured.get("changed_classes").getAsInt());
        assertEquals(2, structured.get("selected_scope_changed_classes").getAsInt());
        assertEquals(2, structured.get("jvm_changed_classes").getAsInt());
        assertEquals(0, structured.get("android_changed_classes").getAsInt());
        assertEquals(1, structured.get("renamed_classes").getAsInt());
        assertEquals(1, structured.get("reference_only_classes").getAsInt());
        assertEquals(1, structured.get("returned").getAsInt());
        assertEquals(1, structured.get("next_offset").getAsInt());
        assertTrue(structured.get("applied").getAsBoolean());
        assertTrue(structured.get("save_required").getAsBoolean());
        assertEquals(1, structured.getAsJsonObject("mapping_counts")
                .get("classes").getAsInt());
        assertEquals(64, structured.get("mapping_sha256").getAsString().length());
        assertEquals("jvm", structured.get("requested_scope").getAsString());

        JsonArray schemaFormats = tool.inputSchema()
                .getAsJsonObject("properties")
                .getAsJsonObject("format")
                .getAsJsonArray("enum");
        assertEquals(7, schemaFormats.size());
        assertEquals("Enigma", schemaFormats.get(0).getAsString());
        assertEquals("Tiny-V2", schemaFormats.get(6).getAsString());
        JsonArray schemaScopes = tool.inputSchema()
                .getAsJsonObject("properties")
                .getAsJsonObject("scope")
                .getAsJsonArray("enum");
        assertEquals(List.of("jvm", "android", "all"), schemaScopes.asList().stream()
                .map(element -> element.getAsString())
                .toList());
    }

    @Test
    void dryRunSupportsRecafUniqueKeysWithoutApplying() {
        Workspace workspace = workspace();
        AtomicBoolean applied = new AtomicBoolean();
        ApplyMappingsTool tool = tool(
                () -> workspace,
                (currentWorkspace, mappings) -> {
                    assertInstanceOf(UniqueKeyMappings.class, mappings);
                    return new ApplyMappingsTool.PreparedMapping(
                            List.of(new ApplyMappingsTool.ClassChange(
                                    OLD_NAME,
                                    NEW_NAME
                            )),
                            null,
                            () -> applied.set(true)
                    );
                }
        );
        JsonObject arguments = arguments("Simple", OLD_NAME + " " + NEW_NAME);
        arguments.addProperty("unique_keys", true);
        arguments.addProperty("dry_run", true);

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean());
        assertFalse(applied.get());
        assertFalse(structured.get("applied").getAsBoolean());
        assertFalse(structured.get("save_required").getAsBoolean());
        assertTrue(structured.get("unique_keys").getAsBoolean());
        assertTrue(structured.get("dry_run").getAsBoolean());
    }

    @Test
    void appliesAndroidMappingsInMemoryWithDexIdentity() {
        Workspace workspace = androidWorkspace("classes2.dex");
        AtomicBoolean applied = new AtomicBoolean();
        ApplyMappingsTool tool = tool(
                () -> workspace,
                (currentWorkspace, mappings) -> new ApplyMappingsTool.PreparedMapping(
                        List.of(new ApplyMappingsTool.ClassChange(
                                OLD_NAME,
                                NEW_NAME,
                                ClassToolSupport.Scope.ANDROID,
                                "classes2.dex"
                        )),
                        null,
                        () -> applied.set(true)
                )
        );
        JsonObject arguments = arguments("Simple", OLD_NAME + " " + NEW_NAME);
        arguments.addProperty("scope", "android");

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        assertTrue(applied.get());
        assertEquals("android", structured.get("requested_scope").getAsString());
        assertEquals("primary_android", structured.get("scope").getAsString());
        assertEquals(1, structured.get("selected_scope_changed_classes").getAsInt());
        assertEquals(0, structured.get("jvm_changed_classes").getAsInt());
        assertEquals(1, structured.get("android_changed_classes").getAsInt());
        JsonObject change = structured.getAsJsonArray("class_changes")
                .get(0).getAsJsonObject();
        assertEquals("android", change.get("class_scope").getAsString());
        assertEquals("classes2.dex", change.get("dex_name").getAsString());
        assertTrue(structured.get("workspace_mutated_in_memory").getAsBoolean());
        assertTrue(structured.get("in_memory_only").getAsBoolean());
        assertFalse(structured.get("workspace_archive_export_supported").getAsBoolean());
        assertFalse(structured.get("save_required").getAsBoolean());
    }

    @Test
    void rejectsEmptyUnmatchedConflictingAndUnavailableMappings() {
        Workspace workspace = workspace();
        ApplyMappingsTool noChanges = tool(
                () -> workspace,
                (currentWorkspace, mappings) -> new ApplyMappingsTool.PreparedMapping(
                        List.of(),
                        null,
                        () -> {
                            throw new AssertionError("empty mapping must not apply");
                        }
                )
        );
        assertTrue(noChanges.execute(arguments(
                "Simple",
                OLD_NAME + " " + NEW_NAME
        )).get("isError").getAsBoolean());
        assertTrue(noChanges.execute(arguments(
                "Simple",
                "# only a comment"
        )).get("isError").getAsBoolean());

        ApplyMappingsTool wrongScope = tool(
                () -> workspace,
                (currentWorkspace, mappings) -> new ApplyMappingsTool.PreparedMapping(
                        List.of(new ApplyMappingsTool.ClassChange(OLD_NAME, NEW_NAME)),
                        null,
                        () -> {
                            throw new AssertionError("wrong-scope mapping must not apply");
                        }
                )
        );
        JsonObject androidArguments = arguments("Simple", OLD_NAME + " " + NEW_NAME);
        androidArguments.addProperty("scope", "android");
        JsonObject wrongScopeResult = wrongScope.execute(androidArguments);
        assertTrue(wrongScopeResult.get("isError").getAsBoolean());
        assertTrue(wrongScopeResult.getAsJsonObject("structuredContent")
                .get("error").getAsString().contains("primary android classes"));

        ApplyMappingsTool conflicting = tool(
                () -> workspace,
                (currentWorkspace, mappings) -> new ApplyMappingsTool.PreparedMapping(
                        List.of(new ApplyMappingsTool.ClassChange(
                                OLD_NAME,
                                NEW_NAME
                        )),
                        "mapped class target already exists",
                        () -> {
                            throw new AssertionError("conflicting mapping must not apply");
                        }
                )
        );
        JsonObject conflict = conflicting.execute(arguments(
                "Simple",
                OLD_NAME + " " + NEW_NAME
        ));
        assertTrue(conflict.get("isError").getAsBoolean());
        assertTrue(conflict.getAsJsonObject("structuredContent")
                .get("error").getAsString().contains("target already exists"));

        ApplyMappingsTool unavailable = new ApplyMappingsTool(
                () -> false,
                () -> null,
                name -> new SimpleMappings(),
                (currentWorkspace, mappings) -> {
                    throw new AssertionError("unavailable workspace must not prepare");
                },
                FORMATS
        );
        assertTrue(unavailable.execute(arguments(
                "Simple",
                OLD_NAME + " " + NEW_NAME
        )).get("isError").getAsBoolean());

        JsonObject invalidFormat = arguments("Unknown", OLD_NAME + " " + NEW_NAME);
        assertThrows(IllegalArgumentException.class,
                () -> noChanges.execute(invalidFormat));
    }

    private static ApplyMappingsTool tool(
            java.util.function.Supplier<Workspace> workspaceSupplier,
            ApplyMappingsTool.ApplyOperation operation) {
        return new ApplyMappingsTool(
                () -> true,
                workspaceSupplier,
                name -> "Simple".equals(name) ? new SimpleMappings() : null,
                operation,
                FORMATS
        );
    }

    private static JsonObject arguments(String format, String mappings) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("format", format);
        arguments.addProperty("mappings", mappings);
        return arguments;
    }

    private static Workspace workspace() {
        WorkspaceResource primary = new WorkspaceResourceBuilder()
                .withJvmClassBundle(new BasicJvmClassBundle())
                .build();
        return new BasicWorkspace(primary, List.of(), false);
    }

    private static Workspace androidWorkspace(String dexName) {
        ClassDefinition definition = new ClassDefinition(
                Types.instanceTypeFromInternalName(OLD_NAME),
                Types.OBJECT,
                Opcodes.ACC_PUBLIC
        );
        AndroidClassInfo classInfo = new AndroidClassInfoBuilder(definition).build();
        BasicAndroidClassBundle bundle = new BasicAndroidClassBundle(39, new byte[0]);
        bundle.put(classInfo);
        Map<String, AndroidClassBundle> bundles = new LinkedHashMap<>();
        bundles.put(dexName, bundle);
        WorkspaceResource primary = new WorkspaceResourceBuilder()
                .withAndroidClassBundles(bundles)
                .build();
        return new BasicWorkspace(primary, List.of(), false);
    }

    private static MappingToolSupport.Descriptor format(
            String name,
            boolean fieldTypes,
            boolean variableTypes) {
        return new MappingToolSupport.Descriptor(
                name,
                true,
                fieldTypes,
                variableTypes
        );
    }
}
