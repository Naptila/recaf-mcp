package me.naptila.recafmcp.mcp.android;

import me.naptila.recafmcp.AndroidFidelityFixture;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.debug.LineNumber;
import org.jf.dexlib2.iface.debug.StartLocal;
import org.junit.jupiter.api.Test;
import software.coley.recaf.workspace.model.bundle.BasicAndroidClassBundle;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AndroidDebugSupportTest {
    @Test
    void retainsLinesAndLocalStartsWhenPayloadPaddingIsDropped() throws Exception {
        var info = AndroidDexReader.readClass(AndroidFidelityFixture.paddingDebugDex(), "sample/PaddingDebug");
        var debug = info.getBackingDefinition().getMethod("values", "()[I").getCode().getDebugInfo();
        assertEquals(List.of(0, 6, 6), debug.lineNumbers().stream().map(line -> line.label().position()).toList());
        assertEquals(List.of(10, 11, 12), debug.lineNumbers().stream().map(line -> line.line()).toList());
        assertEquals(6, debug.locals().getFirst().start().position());
        assertEquals(7, debug.locals().getFirst().end().position());
        byte[] bytes = AndroidDexReader.encodeClass(info, 35);
        var bundle = new BasicAndroidClassBundle(35, new byte[0]); bundle.initialPut(info);
        AndroidDexValidator.validate(bundle, bytes);
        var parsed = new DexBackedDexFile(Opcodes.forDexVersion(35), bytes);
        var method = parsed.getClasses().iterator().next().getMethods().iterator().next();
        var lines = new ArrayList<String>();
        for (var item : method.getImplementation().getDebugItems()) {
            if (item instanceof LineNumber line) lines.add(line.getCodeAddress() + ":" + line.getLineNumber());
            if (item instanceof StartLocal local) assertEquals(6, local.getCodeAddress());
        }
        assertEquals(List.of("0:10", "6:11", "6:12"), lines);
    }
}
