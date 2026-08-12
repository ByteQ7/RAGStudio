package com.byteq.ai.ragstudio.rag.core.tool;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一工具接口
 */
public interface Tool {

    String name();
    String description();
    JsonSchema inputSchema();
    ToolResult execute(Map<String, Object> params);

    /**
     * 转换为 OpenAI function calling 格式
     */
    default Map<String, Object> toOpenAiTool() {
        Map<String, Object> func = new LinkedHashMap<>();
        func.put("name", name());
        func.put("description", description());
        func.put("parameters", convertSchemaToOpenAi(inputSchema()));
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", func);
        return tool;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> convertSchemaToOpenAi(JsonSchema schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "object");

        Map<String, Object> rawProps = schema.properties();
        if (rawProps != null && !rawProps.isEmpty()) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : rawProps.entrySet()) {
                Object val = e.getValue();
                if (val instanceof JsonSchema propSchema) {
                    converted.put(e.getKey(), convertPropToOpenAi(propSchema));
                } else if (val instanceof Map<?, ?> m) {
                    converted.put(e.getKey(), new LinkedHashMap<>(m));
                }
            }
            result.put("properties", converted);
        } else {
            result.put("properties", Map.of());
        }

        List<String> required = schema.required();
        if (required != null && !required.isEmpty()) result.put("required", required);
        result.put("additionalProperties", false);
        return result;
    }

    private static Map<String, Object> convertPropToOpenAi(JsonSchema prop) {
        Map<String, Object> m = new LinkedHashMap<>();
        String type = prop.type() != null ? prop.type() : "string";
        m.put("type", type);
        if ("object".equals(type) && prop.properties() != null && !prop.properties().isEmpty()) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : prop.properties().entrySet()) {
                if (e.getValue() instanceof JsonSchema ns) nested.put(e.getKey(), convertPropToOpenAi(ns));
            }
            m.put("properties", nested);
        }
        return m;
    }
}
