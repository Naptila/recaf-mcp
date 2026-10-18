package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import software.coley.recaf.services.deobfuscation.transform.generic.CallResultInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.DuplicateCatchMergingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.GotoInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.InvokeDynamicInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.LongAnnotationRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.LongExceptionRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.RedundantTryCatchRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.StaticValueCollectionTransformer;
import software.coley.recaf.services.transform.TransformationParameters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Allowlisted, typed per-run parameters and ordered phase contracts. */
final class TransformationPlanSupport {
    private static final int MAX_PHASES = 8;
    private static final List<Option> OPTIONS = List.of(
            integer("long_annotations", "max_length", LongAnnotationRemovingTransformer.KEY_MAX_LENGTH, 150, 1, 10000),
            integer("long_exceptions", "max_length", LongExceptionRemovingTransformer.KEY_MAX_LENGTH, 150, 1, 10000),
            integer("duplicate_catches", "min_block_threshold", DuplicateCatchMergingTransformer.KEY_MIN_BLOCK_THRESHOLD, 4, 1, 1024),
            integer("static_value_inlining", "max_steps", StaticValueCollectionTransformer.KEY_MAX_STEPS, 20000, 1, 1000000),
            integer("call_result_inlining", "max_steps", CallResultInliningTransformer.KEY_MAX_STEPS, 20000, 1, 1000000),
            integer("invokedynamic_inlining", "max_steps", InvokeDynamicInliningTransformer.KEY_MAX_STEPS, 20000, 1, 1000000),
            bool("goto_inlining", "backwards_switch_flow", GotoInliningTransformer.KEY_BACKWARDS_SWITCH_FLOW),
            bool("redundant_try_catch", "delete_junk_workspace_exceptions", RedundantTryCatchRemovingTransformer.KEY_DELETE_JUNK_WORKSPACE_EXCEPTIONS),
            bool("call_result_inlining", "evaluate_class_initializers", CallResultInliningTransformer.KEY_EVALUATE_CLASS_INITIALIZERS)
    );

    private TransformationPlanSupport() {}

    static List<Phase> parse(JsonObject arguments) {
        if (!arguments.has("phases")) {
            return List.of(phase("main", arguments));
        }
        if (arguments.has("transformers") || arguments.has("options") || arguments.has("max_passes")) {
            throw new IllegalArgumentException("phases cannot be combined with top-level transformers/options/max_passes");
        }
        JsonElement value = arguments.get("phases");
        if (!value.isJsonArray() || value.getAsJsonArray().isEmpty() || value.getAsJsonArray().size() > MAX_PHASES) {
            throw new IllegalArgumentException("phases must contain 1 to " + MAX_PHASES + " entries");
        }
        List<Phase> phases = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (JsonElement element : value.getAsJsonArray()) {
            JsonObject phase = StructureQuerySupport.object(element, "phase");
            ToolArguments.rejectUnknown(phase, Set.of("id", "transformers", "options", "max_passes"));
            String id = ToolArguments.requiredString(phase, "id");
            if (!id.matches("[A-Za-z0-9_-]{1,64}") || !ids.add(id)) {
                throw new IllegalArgumentException("phase id must be unique and match [A-Za-z0-9_-]{1,64}");
            }
            if (!phase.has("transformers")) {
                throw new IllegalArgumentException("each phase requires transformers");
            }
            phases.add(phase(id, phase));
        }
        return List.copyOf(phases);
    }

    private static Phase phase(String id, JsonObject arguments) {
        List<String> transformers = TransformWorkspaceTool.parseTransformers(arguments.get("transformers"));
        int maxPasses = ToolArguments.integer(arguments, "max_passes", 1, 1, TransformWorkspaceTool.MAX_PASSES);
        JsonObject requested = arguments.has("options")
                ? StructureQuerySupport.object(arguments.get("options"), "options") : new JsonObject();
        Set<String> configurable = new LinkedHashSet<>();
        OPTIONS.forEach(option -> configurable.add(option.transformer()));
        ToolArguments.rejectUnknown(requested, configurable);
        for (String alias : requested.keySet()) {
            if (!transformers.contains(alias)) {
                throw new IllegalArgumentException("options require the transformer in the same phase: " + alias);
            }
            Set<String> names = new LinkedHashSet<>();
            OPTIONS.stream().filter(option -> option.transformer().equals(alias)).forEach(option -> names.add(option.name()));
            ToolArguments.rejectUnknown(StructureQuerySupport.object(requested.get(alias), alias), names);
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        Map<String, Map<String, Object>> effective = new LinkedHashMap<>();
        for (Option option : OPTIONS) {
            if (!transformers.contains(option.transformer())) {
                continue;
            }
            JsonObject source = requested.has(option.transformer())
                    ? requested.getAsJsonObject(option.transformer()) : new JsonObject();
            Object parsed = option.defaultValue() instanceof Boolean
                    ? ToolArguments.bool(source, option.name(), (boolean) option.defaultValue())
                    : ToolArguments.integer(source, option.name(), (int) option.defaultValue(), option.minimum(), option.maximum());
            parameters.put(option.nativeKey(), parsed);
            effective.computeIfAbsent(option.transformer(), ignored -> new LinkedHashMap<>()).put(option.name(), parsed);
        }
        Map<String, Map<String, Object>> frozen = new LinkedHashMap<>();
        effective.forEach((key, options) -> frozen.put(key, Map.copyOf(options)));
        return new Phase(id, transformers, maxPasses, Map.copyOf(frozen), new TransformationParameters(parameters));
    }

    static JsonArray toJson(List<Phase> phases) {
        JsonArray result = new JsonArray();
        for (Phase phase : phases) {
            JsonObject json = new JsonObject();
            json.addProperty("id", phase.id());
            json.addProperty("max_passes", phase.maxPasses());
            JsonArray transformers = new JsonArray();
            phase.transformers().forEach(transformers::add);
            json.add("transformers", transformers);
            JsonObject options = new JsonObject();
            phase.options().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                JsonObject values = new JsonObject();
                entry.getValue().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(option -> {
                    if (option.getValue() instanceof Boolean value) {
                        values.addProperty(option.getKey(), value);
                    } else {
                        values.addProperty(option.getKey(), (Number) option.getValue());
                    }
                });
                options.add(entry.getKey(), values);
            });
            json.add("options", options);
            result.add(json);
        }
        return result;
    }

    static JsonObject optionsSchema() {
        JsonObject properties = new JsonObject();
        for (Option option : OPTIONS) {
            if (!properties.has(option.transformer())) {
                properties.add(option.transformer(), objectSchema(new JsonObject()));
            }
            JsonObject schema = new JsonObject();
            if (option.defaultValue() instanceof Boolean value) {
                schema.addProperty("type", "boolean");
                schema.addProperty("default", value);
            } else {
                schema.addProperty("type", "integer");
                schema.addProperty("default", (Number) option.defaultValue());
                schema.addProperty("minimum", option.minimum());
                schema.addProperty("maximum", option.maximum());
            }
            properties.getAsJsonObject(option.transformer()).getAsJsonObject("properties").add(option.name(), schema);
        }
        JsonObject schema = objectSchema(properties);
        schema.addProperty("description", "Typed per-run options; each alias must be selected in the same phase.");
        return schema;
    }

    static JsonObject phasesSchema(JsonObject transformers, JsonObject maxPasses) {
        JsonObject properties = new JsonObject();
        JsonObject id = new JsonObject();
        id.addProperty("type", "string");
        id.addProperty("pattern", "^[A-Za-z0-9_-]{1,64}$");
        properties.add("id", id);
        properties.add("transformers", transformers.deepCopy());
        properties.add("max_passes", maxPasses.deepCopy());
        properties.add("options", optionsSchema());
        JsonObject item = objectSchema(properties);
        JsonArray required = new JsonArray();
        required.add("id");
        required.add("transformers");
        item.add("required", required);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "array");
        schema.add("items", item);
        schema.addProperty("minItems", 1);
        schema.addProperty("maxItems", MAX_PHASES);
        schema.addProperty("description", "Ordered native Recaf phases, with unique IDs. Mutually exclusive with "
                + "top-level transformers/options/max_passes. Intermediate results are isolated until the entire plan succeeds.");
        return schema;
    }

    private static JsonObject objectSchema(JsonObject properties) {
        JsonObject result = new JsonObject();
        result.addProperty("type", "object");
        result.add("properties", properties);
        result.addProperty("additionalProperties", false);
        return result;
    }

    private static Option integer(String alias, String name, String key, int defaultValue, int minimum, int maximum) {
        return new Option(alias, name, key, defaultValue, minimum, maximum);
    }

    private static Option bool(String alias, String name, String key) {
        return new Option(alias, name, key, false, 0, 0);
    }

    private record Option(String transformer, String name, String nativeKey, Object defaultValue, int minimum, int maximum) {}

    record Phase(String id, List<String> transformers, int maxPasses,
                 Map<String, Map<String, Object>> options, TransformationParameters parameters) {}
}
