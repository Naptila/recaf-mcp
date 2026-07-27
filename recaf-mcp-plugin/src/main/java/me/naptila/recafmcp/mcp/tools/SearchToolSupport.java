package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import software.coley.recaf.services.search.match.StringPredicate;
import software.coley.recaf.services.search.match.StringPredicateProvider;

import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Shared strict query and pagination contract for search tools.
 */
final class SearchToolSupport {
    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 200;
    static final int MAX_QUERY_LENGTH = 500;
    static final Set<String> ARGUMENTS = Set.of("text", "regex", "offset", "limit");

    private SearchToolSupport() {
    }

    static Request parse(JsonObject arguments) {
        return parse(arguments, ARGUMENTS);
    }

    static Request parse(JsonObject arguments, Set<String> allowedArguments) {
        ToolArguments.rejectUnknown(arguments, allowedArguments);
        String text = ToolArguments.requiredString(arguments, "text");
        if (text.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("text is too long");
        }
        boolean regex = ToolArguments.bool(arguments, "regex", false);
        if (regex) {
            validateRegex(text, "text");
        }
        return new Request(
                text,
                regex,
                ToolArguments.integer(arguments, "offset", 0, 0, Integer.MAX_VALUE),
                ToolArguments.integer(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT)
        );
    }

    static JsonObject inputSchema(String description) {
        JsonObject properties = new JsonObject();
        JsonObject text = new JsonObject();
        text.addProperty("type", "string");
        text.addProperty("minLength", 1);
        text.addProperty("maxLength", MAX_QUERY_LENGTH);
        text.addProperty("description", description);
        properties.add("text", text);
        JsonObject regex = new JsonObject();
        regex.addProperty("type", "boolean");
        regex.addProperty("default", false);
        regex.addProperty("description", "Use a partial Java regex.");
        properties.add("regex", regex);
        JsonObject offset = new JsonObject();
        offset.addProperty("type", "integer");
        offset.addProperty("minimum", 0);
        offset.addProperty("default", 0);
        properties.add("offset", offset);
        JsonObject limit = new JsonObject();
        limit.addProperty("type", "integer");
        limit.addProperty("minimum", 1);
        limit.addProperty("maximum", MAX_LIMIT);
        limit.addProperty("default", DEFAULT_LIMIT);
        properties.add("limit", limit);

        JsonArray required = new JsonArray();
        required.add("text");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    static void addPageMetadata(JsonObject target,
                                Request request,
                                int total,
                                int returned) {
        target.addProperty("text", request.text());
        target.addProperty("regex", request.regex());
        target.addProperty("offset", request.offset());
        target.addProperty("limit", request.limit());
        target.addProperty("total", total);
        target.addProperty("returned", returned);
        long next = (long) request.offset() + returned;
        if (returned > 0 && next < total) {
            target.addProperty("next_offset", next);
        } else {
            target.add("next_offset", JsonNull.INSTANCE);
        }
    }

    static boolean inPage(int index, Request request) {
        long end = (long) request.offset() + request.limit();
        return index >= request.offset() && index < end;
    }

    static StringPredicate predicate(StringPredicateProvider provider,
                                     String text,
                                     boolean regex) {
        if (text == null) {
            return null;
        }
        return regex
                ? provider.newPartialRegexPredicate(text)
                : provider.newContainsPredicate(text);
    }

    static Predicate<String> matcher(String text, boolean regex) {
        if (text == null) {
            return ignored -> true;
        }
        if (!regex) {
            return value -> value.contains(text);
        }
        Pattern pattern = Pattern.compile(text);
        return value -> pattern.matcher(value).find();
    }

    static void validateRegex(String expression, String name) {
        if (expression == null) {
            return;
        }
        try {
            Pattern.compile(expression);
        } catch (PatternSyntaxException ex) {
            String index = ex.getIndex() < 0 ? "" : " at index " + ex.getIndex();
            throw new IllegalArgumentException(
                    name + " is not a valid Java regex: "
                            + ex.getDescription() + index,
                    ex
            );
        }
    }

    record Request(String text, boolean regex, int offset, int limit) {
    }
}
