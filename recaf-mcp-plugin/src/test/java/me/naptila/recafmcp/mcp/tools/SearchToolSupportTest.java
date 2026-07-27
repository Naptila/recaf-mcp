package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import software.coley.recaf.services.search.match.StringPredicate;
import software.coley.recaf.services.search.match.StringPredicateProvider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchToolSupportTest {
    private final StringPredicateProvider provider = new StringPredicateProvider();

    @Test
    void createsCaseSensitiveLiteralSubstringOrPartialRegexPredicates() {
        StringPredicate literal = SearchToolSupport.predicate(
                provider,
                "needle",
                false
        );
        assertTrue(literal.match("prefix-needle-suffix"));
        assertFalse(literal.match("NEEDLE"));

        StringPredicate regex = SearchToolSupport.predicate(
                provider,
                "needle-[0-9]+",
                true
        );
        assertTrue(regex.match("prefix-needle-42-suffix"));
        assertFalse(regex.match("needle-value"));
        assertNull(SearchToolSupport.predicate(provider, null, false));
    }

    @Test
    void validatesPartialRegexDuringSharedArgumentParsing() {
        JsonObject valid = new JsonObject();
        valid.addProperty("text", "needle-[0-9]+");
        valid.addProperty("regex", true);
        assertTrue(SearchToolSupport.parse(valid).regex());

        JsonObject invalid = new JsonObject();
        invalid.addProperty("text", "[");
        invalid.addProperty("regex", true);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> SearchToolSupport.parse(invalid)
        );
        assertEquals(
                "text is not a valid Java regex: Unclosed character class at index 0",
                error.getMessage()
        );

        JsonObject limit = SearchToolSupport.inputSchema("Search text.")
                .getAsJsonObject("properties")
                .getAsJsonObject("limit");
        assertEquals(10, limit.get("default").getAsInt());
        assertEquals(200, limit.get("maximum").getAsInt());
    }
}
