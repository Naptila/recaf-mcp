package me.naptila.recafmcp.mcp.tools;

import me.naptila.recafmcp.JvmTransformFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.bundle.BasicVersionedJvmClassBundle;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.bundle.VersionedJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class TransformationWorkspaceSupportTest {
    private Workspace workspace;
    private JvmClassBundle bundle;

    @BeforeEach
    void setup() {
        bundle = new BasicJvmClassBundle();
        bundle.put(new JvmClassInfoBuilder(JvmTransformFixture.bytecode("sample/Obfuscated")).build());
        var versioned = new BasicVersionedJvmClassBundle(22);
        versioned.put(new JvmClassInfoBuilder(JvmTransformFixture.bytecode("sample/Obfuscated")).build());
        var versions = new TreeMap<Integer, VersionedJvmClassBundle>();
        versions.put(22, versioned);
        var library = new BasicJvmClassBundle();
        library.put(new JvmClassInfoBuilder(JvmTransformFixture.bytecode("library/Support")).build());
        workspace = new BasicWorkspace(new WorkspaceResourceBuilder().withJvmClassBundle(bundle)
                .withVersionedJvmClassBundles(versions).build(),
                List.of(new WorkspaceResourceBuilder().withJvmClassBundle(library).build()), false);
    }

    @AfterEach
    void close() {
        workspace.close();
    }

    @Test
    void commitsOneBundleWithoutConfusingEquivalentMultiReleaseClassesOrClosingLibraries() {
        byte[] original = bundle.get("sample/Obfuscated").getBytecode().clone();
        try (var snapshot = new TransformationWorkspaceSupport(workspace)) {
            var copy = snapshot.workspace().getPrimaryResource().getJvmClassBundle();
            copy.put(changed(copy.get("sample/Obfuscated"), Opcodes.ICONST_1));
            assertArrayEquals(original, bundle.get("sample/Obfuscated").getBytecode());
            snapshot.commit("sample/");
            assertArrayEquals(copy.get("sample/Obfuscated").getBytecode(), bundle.get("sample/Obfuscated").getBytecode());
        }
        assertArrayEquals(original, workspace.getPrimaryResource().getVersionedJvmClassBundles()
                .get(22).get("sample/Obfuscated").getBytecode());
        assertEquals(1, workspace.getSupportingResources().getFirst().getJvmClassBundle().size());
    }

    @Test
    void refusesStaleLiveClassesBeforeAnyWrites() {
        try (var snapshot = new TransformationWorkspaceSupport(workspace)) {
            var copy = snapshot.workspace().getPrimaryResource().getJvmClassBundle();
            copy.put(changed(copy.get("sample/Obfuscated"), Opcodes.ICONST_1));
            JvmClassInfo concurrent = changed(bundle.get("sample/Obfuscated"), Opcodes.ICONST_4);
            bundle.put(concurrent);
            assertThrows(IllegalStateException.class, () -> snapshot.commit(null));
            assertSame(concurrent, bundle.get("sample/Obfuscated"));
        }
    }

    @Test
    void refusesChangesOutsidePrefixAndCancellationBeforeCommit() {
        byte[] original = bundle.get("sample/Obfuscated").getBytecode().clone();
        try (var snapshot = new TransformationWorkspaceSupport(workspace)) {
            var copy = snapshot.workspace().getPrimaryResource().getJvmClassBundle();
            copy.put(changed(copy.get("sample/Obfuscated"), Opcodes.ICONST_1));
            assertThrows(IllegalStateException.class, () -> snapshot.commit("other/"));
            assertArrayEquals(original, bundle.get("sample/Obfuscated").getBytecode());
            Thread.currentThread().interrupt();
            try {
                assertThrows(CancellationException.class, () -> snapshot.commit(null));
            } finally {
                Thread.interrupted();
            }
            assertArrayEquals(original, bundle.get("sample/Obfuscated").getBytecode());
        }
    }

    private static JvmClassInfo changed(JvmClassInfo info, int opcode) {
        ClassNode node = new ClassNode();
        info.getClassReader().accept(node, 0);
        var method = node.methods.getFirst();
        method.instructions.set(method.instructions.getFirst(), new InsnNode(opcode));
        var writer = new ClassWriter(0);
        node.accept(writer);
        return new JvmClassInfoBuilder(writer.toByteArray()).build();
    }
}
