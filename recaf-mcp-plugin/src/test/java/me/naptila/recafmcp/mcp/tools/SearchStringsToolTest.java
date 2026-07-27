package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchStringsToolTest {
    @Test
    void returnsStableBoundedPagesWithLocations() {
        String longValue = "x".repeat(5000);
        SearchStringsTool tool = new SearchStringsTool(
                () -> true,
                () -> null,
                (workspace, request) -> List.of(
                        new SearchStringsTool.StringHit(
                                "z/Last", "method", "run", "()V", 4, longValue),
                        new SearchStringsTool.StringHit(
                                "a/First", null, null, null, null, "needle-one"),
                        new SearchStringsTool.StringHit(
                                "a/First", "field", "VALUE", "Ljava/lang/String;", null,
                                "needle-two")
                )
        );
        JsonObject arguments = new JsonObject();
        arguments.addProperty("text", "needle");
        arguments.addProperty("offset", 1);
        arguments.addProperty("limit", 2);

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");
        JsonArray hits = structured.getAsJsonArray("hits");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals(3, structured.get("total").getAsInt());
        assertEquals(2, structured.get("returned").getAsInt());
        assertTrue(structured.get("next_offset").isJsonNull());
        assertEquals("field", hits.get(0).getAsJsonObject()
                .get("member_kind").getAsString());
        assertEquals("z/Last", hits.get(1).getAsJsonObject()
                .get("class_name").getAsString());
        assertEquals(4096, hits.get(1).getAsJsonObject().get("value").getAsString().length());
        assertTrue(hits.get(1).getAsJsonObject().get("value_truncated").getAsBoolean());
    }

    @Test
    void validatesQueriesBeforeSearching() {
        SearchStringsTool tool = new SearchStringsTool(
                () -> false,
                () -> null,
                (workspace, request) -> List.of()
        );
        JsonObject missingWorkspace = new JsonObject();
        missingWorkspace.addProperty("text", "needle");
        assertTrue(tool.execute(missingWorkspace).get("isError").getAsBoolean());

        JsonObject badLimit = new JsonObject();
        badLimit.addProperty("text", "needle");
        badLimit.addProperty("limit", 201);
        assertThrows(IllegalArgumentException.class, () -> tool.execute(badLimit));

        JsonObject badRegexFlag = new JsonObject();
        badRegexFlag.addProperty("text", "needle");
        badRegexFlag.addProperty("regex", "yes");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(badRegexFlag));

        JsonObject badRegex = new JsonObject();
        badRegex.addProperty("text", "[");
        badRegex.addProperty("regex", true);
        assertThrows(IllegalArgumentException.class, () -> tool.execute(badRegex));
    }
}
