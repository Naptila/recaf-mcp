package me.naptila.recafmcp.mcp.internal;

import me.naptila.recafmcp.AndroidFidelityFixture;
import me.naptila.recafmcp.mcp.android.AndroidDexValidator;
import me.naptila.recafmcp.mcp.android.AndroidWorkspaceSupport;
import me.naptila.recafmcp.mcp.android.AndroidInputImporter;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.debug.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.coley.recaf.services.text.TextFormatConfig;
import software.coley.recaf.services.workspace.BasicWorkspaceManager;
import software.coley.recaf.services.workspace.WorkspaceManagerConfig;
import software.coley.recaf.services.workspace.io.*;
import software.coley.recaf.workspace.model.BasicWorkspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class AndroidFidelitySaveTest {
    @TempDir Path directory;

    @Test
    void savesIndependentDexAndMultidexWithoutChangingValuesAnnotationsOrDebug() throws Exception {
        AndroidFidelityFixture.write(directory);
        var importer = new BasicResourceImporter(new BasicInfoImporter(
                new InfoImporterConfig(), new TextFormatConfig(), new BasicClassPatcher()),
                new ResourceImporterConfig());
        for (boolean managed : List.of(false, true)) {
            for (String format : List.of("dex", "apk")) {
                Path input = directory.resolve("fidelity." + format);
                byte[] original = Files.readAllBytes(input);
                var resource = managed ? new AndroidInputImporter(new BasicInfoImporter(new InfoImporterConfig(),
                        new TextFormatConfig(), new BasicClassPatcher())).readIfAndroid(input) : importer.importResource(input);
                var workspace = new BasicWorkspace(resource, List.of(), false);
                var manager = new BasicWorkspaceManager(new WorkspaceManagerConfig());
                manager.setCurrentIgnoringConditions(workspace);
                try {
                    AndroidWorkspaceSupport.prepare(workspace);
                    Path output = directory.resolve("saved-" + managed + "." + format);
                    new RecafSaveWorkspaceHandler(manager).save(output.toString(), false);
                    byte[] actual;
                    if (format.equals("apk")) {
                        try (var zip = new ZipFile(output.toFile())) {
                            actual = zip.getInputStream(zip.getEntry("classes.dex")).readAllBytes();
                            compare(AndroidFidelityFixture.secondDex(),
                                    zip.getInputStream(zip.getEntry("classes2.dex")).readAllBytes());
                        }
                    } else actual = Files.readAllBytes(output);
                    compare(AndroidFidelityFixture.dex(), actual);
                    assertArrayEquals(original, Files.readAllBytes(input));
                } finally {
                    manager.setCurrentIgnoringConditions(null);
                }
            }
        }
    }

    @Test
    void validatorRejectsAFieldValueMismatch() throws Exception {
        AndroidFidelityFixture.write(directory);
        var importer = new BasicResourceImporter(new BasicInfoImporter(
                new InfoImporterConfig(), new TextFormatConfig(), new BasicClassPatcher()),
                new ResourceImporterConfig());
        var workspace = new BasicWorkspace(importer.importResource(directory.resolve("fidelity.dex")), List.of(), false);
        try {
            AndroidWorkspaceSupport.prepare(workspace);
            var bundle = workspace.getPrimaryResource().getAndroidClassBundles().values().iterator().next();
            bundle.get("sample/Fidelity").getBackingDefinition().getField("NEG_A", "I")
                    .setStaticValue(new me.darknet.dex.tree.definitions.constant.IntConstant(42));
            assertThrows(java.io.IOException.class,
                    () -> AndroidDexValidator.validate(bundle, AndroidFidelityFixture.dex()));
        } finally {
            workspace.close();
        }
    }

    private static void compare(byte[] original, byte[] output) {
        Map<String, ClassDef> expected = classes(original);
        Map<String, ClassDef> actual = classes(output);
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((name, before) -> {
            ClassDef after = actual.get(name);
            assertEquals(before.getAnnotations(), after.getAnnotations());
            assertEquals(fields(before), fields(after));
            assertEquals(methods(before), methods(after));
        });
    }

    private static Map<String, ClassDef> classes(byte[] bytes) {
        Map<String, ClassDef> result = new TreeMap<>();
        new DexBackedDexFile(Opcodes.forDexVersion(35), bytes).getClasses()
                .forEach(value -> result.put(value.getType(), value));
        return result;
    }

    private static Map<String, Object> fields(ClassDef type) {
        Map<String, Object> result = new TreeMap<>();
        for (var field : type.getFields()) {
            result.put(field.getName() + field.getType(), field.getInitialValue());
        }
        return result;
    }

    private static Map<String, Object> methods(ClassDef type) {
        Map<String, Object> result = new TreeMap<>();
        for (Method method : type.getMethods()) {
            List<Object> data = new ArrayList<>();
            data.add(method.getAnnotations());
            data.add(method.getParameters().stream().map(parameter -> parameter.getName()).toList());
            if (method.getImplementation() != null) {
                for (var item : method.getImplementation().getDebugItems()) {
                    String detail = switch (item) {
                        case LineNumber line -> "line:" + line.getLineNumber();
                        case StartLocal local -> "local:" + local.getRegister() + ":" + local.getName() + ":" + local.getType() + ":" + local.getSignature();
                        case EndLocal local -> "end:" + local.getRegister();
                        default -> "debug:" + item.getDebugItemType();
                    };
                    data.add(item.getCodeAddress() + ":" + detail);
                }
            }
            result.put(method.getName() + method.getParameterTypes() + method.getReturnType(), data);
        }
        return result;
    }
}
