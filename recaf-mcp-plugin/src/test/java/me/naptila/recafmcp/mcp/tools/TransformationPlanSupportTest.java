package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import software.coley.recaf.services.deobfuscation.transform.generic.GotoInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.LongAnnotationRemovingTransformer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransformationPlanSupportTest {
    @Test
    void keepsConservativeDefaultsAndScopesOptionsToOneRun() {
        var defaults = TransformationPlanSupport.parse(new JsonObject());
        assertEquals(List.of("duplicate_annotations", "illegal_annotations", "illegal_signatures", "illegal_varargs"),
                defaults.getFirst().transformers());
        assertTrue(defaults.getFirst().options().isEmpty());
        var explicit = TransformationPlanSupport.parse(json("""
                {"transformers":["long_annotations","goto_inlining"],"options":{
                  "long_annotations":{"max_length":40},"goto_inlining":{"backwards_switch_flow":true}}}
                """)).getFirst();
        assertEquals(40, explicit.parameters().getInt(LongAnnotationRemovingTransformer.KEY_MAX_LENGTH, 0));
        assertTrue(explicit.parameters().getBoolean(GotoInliningTransformer.KEY_BACKWARDS_SWITCH_FLOW, false));
        var following = TransformationPlanSupport.parse(json("""
                {"transformers":["long_annotations","goto_inlining"]}
                """)).getFirst();
        assertEquals(150, following.parameters().getInt(LongAnnotationRemovingTransformer.KEY_MAX_LENGTH, 0));
        assertFalse(following.parameters().getBoolean(GotoInliningTransformer.KEY_BACKWARDS_SWITCH_FLOW, true));
    }

    @Test
    void preservesPhaseOrderAndEffectiveOptions() {
        var phases = TransformationPlanSupport.parse(json("""
                {"phases":[{"id":"cleanup","transformers":["long_annotations"],"max_passes":2,
                    "options":{"long_annotations":{"max_length":40}}},
                  {"id":"fold","transformers":["opaque_constants"]}]}
                """));
        assertEquals(List.of("cleanup", "fold"), phases.stream().map(TransformationPlanSupport.Phase::id).toList());
        assertEquals(2, phases.getFirst().maxPasses());
        assertEquals(40, TransformationPlanSupport.toJson(phases).get(0).getAsJsonObject()
                .getAsJsonObject("options").getAsJsonObject("long_annotations").get("max_length").getAsInt());
    }

    @Test
    void rejectsAmbiguousPlansInvalidOptionsAndUnboundedInputs() {
        for (String invalid : List.of(
                "{\"phases\":[]}", "{\"phases\":null}",
                "{\"phases\":[{\"id\":\"one\"}]}",
                "{\"phases\":[{\"id\":\"../x\",\"transformers\":[\"dead_code\"]}]}",
                "{\"phases\":[{\"id\":\"x\",\"transformers\":[\"dead_code\"]},{\"id\":\"x\",\"transformers\":[\"dead_code\"]}]}",
                "{\"phases\":[{\"id\":\"x\",\"transformers\":[\"dead_code\"]}],\"max_passes\":2}",
                "{\"transformers\":[\"dead_code\"],\"options\":{\"long_annotations\":{\"max_length\":40}}}",
                "{\"transformers\":[\"long_annotations\"],\"options\":{\"long_annotations\":{\"max_length\":0}}}",
                "{\"transformers\":[\"long_annotations\"],\"options\":{\"long_annotations\":{\"max_length\":1.5}}}",
                "{\"transformers\":[\"long_annotations\"],\"options\":{\"long_annotations\":{\"unknown\":1}}}",
                "{\"transformers\":[\"goto_inlining\"],\"options\":{\"goto_inlining\":{\"backwards_switch_flow\":1}}}",
                "{\"transformers\":[\"call_result_inlining\"],\"options\":{\"call_result_inlining\":{\"max_steps\":1000001}}}"
        )) {
            assertThrows(IllegalArgumentException.class, () -> TransformationPlanSupport.parse(json(invalid)), invalid);
        }
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
