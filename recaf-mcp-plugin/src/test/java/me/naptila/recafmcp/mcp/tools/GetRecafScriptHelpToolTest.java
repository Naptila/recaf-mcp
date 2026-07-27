package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GetRecafScriptHelpToolTest {
    private final GetRecafScriptHelpTool tool = new GetRecafScriptHelpTool();

    @Test
    void returnsOverviewAndStableTopicCatalogByDefault() {
        JsonObject result = tool.execute(new JsonObject());
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals("overview", structured.get("topic").getAsString());
        assertTrue(structured.get("guide").getAsString().contains("compile_only=true"));
        assertTrue(structured.get("guide").getAsString().contains("not sandboxed"));
        assertEquals(
                "[\"overview\",\"workspace\",\"asm\",\"services\",\"deobfuscation\"]",
                structured.getAsJsonArray("available_topics").toString()
        );
        assertTrue(structured.get("high_privilege").getAsBoolean());
    }

    @Test
    void returnsAsmAndDeobfuscationTemplates() {
        JsonObject asmArguments = new JsonObject();
        asmArguments.addProperty("topic", "asm");
        String asm = tool.execute(asmArguments)
                .getAsJsonObject("structuredContent")
                .get("guide")
                .getAsString();
        assertTrue(asm.contains("ClassNode"));
        assertTrue(asm.contains("bundle.put"));

        JsonObject deobfuscationArguments = new JsonObject();
        deobfuscationArguments.addProperty("topic", "deobfuscation");
        String deobfuscation = tool.execute(deobfuscationArguments)
                .getAsJsonObject("structuredContent")
                .get("guide")
                .getAsString();
        assertTrue(deobfuscation.contains("Do not execute arbitrary target bytecode"));
        assertTrue(deobfuscation.contains("descriptor plus opcode shape"));
    }

    @Test
    void schemaAdvertisesAllTopicsAndRejectsUnknownValuesDefensively() {
        JsonObject schema = tool.inputSchema();
        assertEquals(
                5,
                schema.getAsJsonObject("properties")
                        .getAsJsonObject("topic")
                        .getAsJsonArray("enum")
                        .size()
        );

        JsonObject arguments = new JsonObject();
        arguments.addProperty("topic", "test2-xor");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(arguments));
    }
}
