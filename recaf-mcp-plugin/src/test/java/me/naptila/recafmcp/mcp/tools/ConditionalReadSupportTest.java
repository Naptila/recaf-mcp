package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionalReadSupportTest {
    private static final String SHA_256 = "a".repeat(64);

    @Test
    void acceptsOnlyCanonicalSha256Values() {
        assertNull(ConditionalReadSupport.parse(new JsonObject()));

        JsonObject valid = new JsonObject();
        valid.addProperty(ConditionalReadSupport.ARGUMENT, SHA_256);
        assertEquals(SHA_256, ConditionalReadSupport.parse(valid));

        JsonObject shortValue = new JsonObject();
        shortValue.addProperty(ConditionalReadSupport.ARGUMENT, "a".repeat(63));
        assertThrows(
                IllegalArgumentException.class,
                () -> ConditionalReadSupport.parse(shortValue)
        );

        JsonObject uppercase = new JsonObject();
        uppercase.addProperty(ConditionalReadSupport.ARGUMENT, "A".repeat(64));
        assertThrows(
                IllegalArgumentException.class,
                () -> ConditionalReadSupport.parse(uppercase)
        );

        JsonObject nonString = new JsonObject();
        nonString.addProperty(ConditionalReadSupport.ARGUMENT, 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> ConditionalReadSupport.parse(nonString)
        );
    }

    @Test
    void advertisesAndShapesConditionalResponses() {
        JsonObject property = ConditionalReadSupport.schemaProperty();
        assertEquals("^[0-9a-f]{64}$", property.get("pattern").getAsString());
        assertEquals(
                "Omit the body if its full SHA-256 matches.",
                property.get("description").getAsString()
        );
        assertTrue(ConditionalReadSupport.matches(SHA_256, SHA_256));
        assertFalse(ConditionalReadSupport.matches(null, SHA_256));

        JsonObject response = new JsonObject();
        response.addProperty("body", "large");
        response.addProperty("sha256", SHA_256);
        ConditionalReadSupport.markUnchanged(response, "body");
        assertFalse(response.has("body"));
        assertEquals(SHA_256, response.get("sha256").getAsString());
        assertTrue(response.get("unchanged").getAsBoolean());
    }
}
