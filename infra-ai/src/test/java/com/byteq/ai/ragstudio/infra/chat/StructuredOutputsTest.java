package com.byteq.ai.ragstudio.infra.chat;

import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.infra.config.DynamicModelConfig;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredOutputsTest {

    private static ModelTarget target(Boolean jsonOutput, Boolean jsonSchema) {
        DynamicModelConfig.ModelEntry candidate = DynamicModelConfig.ModelEntry.builder()
                .id("m")
                .provider("p")
                .model("m")
                .supportsJsonOutput(jsonOutput)
                .supportsJsonSchema(jsonSchema)
                .build();
        DynamicModelConfig.ProviderEntry provider = DynamicModelConfig.ProviderEntry.builder()
                .name("p")
                .apiKey("k")
                .build();
        return new ModelTarget("m", candidate, provider);
    }

    private static ChatRequest requestWithSchema() {
        return ChatRequest.builder()
                .jsonSchema(ChatRequest.JsonSchemaSpec.strict("demo", Map.of("type", "object")))
                .build();
    }

    @Test
    void schemaCapableModelShouldReceiveJsonSchema() {
        StructuredOutputs.Spec spec = StructuredOutputs.resolve(requestWithSchema(), target(false, true));
        assertEquals(StructuredOutputs.Mode.JSON_SCHEMA, spec.mode());
        assertEquals("demo", spec.name());
        assertTrue(spec.strict());
    }

    @Test
    void jsonOnlyModelShouldDegradeToJsonObject() {
        StructuredOutputs.Spec spec = StructuredOutputs.resolve(requestWithSchema(), target(true, false));
        assertEquals(StructuredOutputs.Mode.JSON_OBJECT, spec.mode());
    }

    @Test
    void unflaggedModelShouldReceiveNothing() {
        StructuredOutputs.Spec spec = StructuredOutputs.resolve(requestWithSchema(), target(false, false));
        assertEquals(StructuredOutputs.Mode.NONE, spec.mode());
        assertFalse(spec.active());
    }

    @Test
    void jsonObjectRequestShouldRespectOutputFlag() {
        ChatRequest request = ChatRequest.builder().responseFormat("json_object").build();
        assertEquals(StructuredOutputs.Mode.JSON_OBJECT,
                StructuredOutputs.resolve(request, target(true, false)).mode());
        assertEquals(StructuredOutputs.Mode.NONE,
                StructuredOutputs.resolve(request, target(false, false)).mode());
    }

    @Test
    void plainRequestShouldNeverTriggerFormat() {
        ChatRequest request = ChatRequest.builder().build();
        assertEquals(StructuredOutputs.Mode.NONE,
                StructuredOutputs.resolve(request, target(true, true)).mode());
    }
}
