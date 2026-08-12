package com.byteq.ai.ragstudio.rag.core.agent;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.rag.core.tool.Tool;
import com.byteq.ai.ragstudio.rag.core.tool.ToolNameUtil;
import com.byteq.ai.ragstudio.rag.core.tool.ToolResult;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目统一 {@link Tool} 到 AgentScope {@link AgentTool} 的适配器
 * <p>
 * 将 rag_search / time_now / MCP / SKILL / tool_reader 等既有工具包装为
 * AgentScope 原生工具注册进 Toolkit，复用现有执行逻辑与参数校验。
 * </p>
 */
@Slf4j
public class ProjectToolAdapter implements AgentTool {

    /** 工具执行完成后的结果回调（引用溯源 / 图片收集等） */
    @FunctionalInterface
    public interface ResultConsumer {
        void accept(ToolResult result);
    }

    private final Tool delegate;
    private final ResultConsumer resultConsumer;

    /** 注册时规范化后的对外工具名（MCP/SKILL 原始名可能含中文、点号等非法字符） */
    private volatile String exposedName;

    public ProjectToolAdapter(Tool delegate) {
        this(delegate, null);
    }

    public ProjectToolAdapter(Tool delegate, ResultConsumer resultConsumer) {
        this.delegate = delegate;
        this.resultConsumer = resultConsumer;
    }

    public void setExposedName(String exposedName) {
        this.exposedName = exposedName;
    }

    @Override
    public String getName() {
        if (exposedName != null) {
            return exposedName;
        }
        return sanitizeToolName(delegate.name());
    }

    /**
     * 将工具名规范化为 OpenAI 兼容协议合法函数名（实现见 {@link ToolNameUtil}）
     */
    public static String sanitizeToolName(String raw) {
        return ToolNameUtil.sanitize(raw);
    }

    @Override
    public String getDescription() {
        return delegate.description();
    }

    @Override
    public Map<String, Object> getParameters() {
        // 归一化非标准 JSON Schema 类型（如 MCP 服务返回的 "bool"），
        // 否则 DeepSeek 等厂商严格校验 schema 时直接 400
        return normalizeSchemaTypes(convertSchema(delegate.inputSchema()));
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            Map<String, Object> params = param.getInput() != null ? param.getInput() : Map.of();
            long start = System.currentTimeMillis();
            ToolResult result;
            try {
                result = delegate.execute(params);
            } catch (Exception e) {
                log.warn("工具 [{}] 执行异常: {}", delegate.name(), e.getMessage());
                result = ToolResult.failure(delegate.name(),
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
            result.setDurationMs(System.currentTimeMillis() - start);

            if (resultConsumer != null) {
                try {
                    resultConsumer.accept(result);
                } catch (Exception e) {
                    log.warn("工具 [{}] 结果回调异常: {}", delegate.name(), e.getMessage());
                }
            }
            return buildResultBlock(result);
        });
    }

    private ToolResultBlock buildResultBlock(ToolResult result) {
        // toObservation() 已含 "Observation: 工具 [name] 执行成功/失败..." 完整前缀
        String content = result.toObservation();
        if (result.isSuccess()) {
            return ToolResultBlock.text(content);
        }
        return ToolResultBlock.error(content);
    }

    /**
     * 递归归一化 JSON Schema 中的非标准类型名：
     * 部分 MCP 服务（如阿里百炼）返回 "bool"/"int"/"str" 等非标准 JSON Schema 类型，
     * DeepSeek 等厂商按标准校验（anyOf 子结构同样校验），不符直接 400。
     * 未知类型兜底为 "string"（宁可放宽语义也不让请求失败）。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeSchemaTypes(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return schema;
        }
        Object type = schema.get("type");
        if (type instanceof String typeStr) {
            schema.put("type", normalizeTypeName(typeStr));
        } else if (type instanceof List<?> typeList) {
            schema.put("type", typeList.stream()
                    .map(t -> t instanceof String s ? normalizeTypeName(s) : t)
                    .toList());
        }
        for (String key : new String[]{"properties", "items", "anyOf", "oneOf", "allOf",
                "$defs", "definitions", "additionalProperties", "patternProperties", "contains"}) {
            Object value = schema.get(key);
            if (value instanceof Map<?, ?> map) {
                if ("properties".equals(key) || "definitions".equals(key)
                        || "$defs".equals(key) || "patternProperties".equals(key)) {
                    for (Object sub : map.values()) {
                        if (sub instanceof Map<?, ?> subMap) {
                            normalizeSchemaTypes((Map<String, Object>) subMap);
                        }
                    }
                } else {
                    normalizeSchemaTypes((Map<String, Object>) map);
                }
            } else if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> subMap) {
                        normalizeSchemaTypes((Map<String, Object>) subMap);
                    }
                }
            }
        }
        return schema;
    }

    /** 将非标准类型名映射为标准 JSON Schema 类型 */
    private static String normalizeTypeName(String rawType) {
        if (rawType == null) {
            return "string";
        }
        return switch (rawType.trim().toLowerCase()) {
            case "bool", "boolean" -> "boolean";
            case "int", "integer", "long" -> "integer";
            case "float", "double", "decimal", "number" -> "number";
            case "str", "string" -> "string";
            case "object" -> "object";
            case "array" -> "array";
            case "null" -> "null";
            default -> "string";
        };
    }

    /**
     * 将 MCP JsonSchema 转换为 AgentScope 期望的 JSON Schema Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> convertSchema(McpSchema.JsonSchema schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (schema == null) {
            result.put("type", "object");
            result.put("properties", Map.of());
            return result;
        }
        result.put("type", schema.type() != null ? schema.type() : "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        Object rawProps = schema.properties();
        if (rawProps instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> e : props.entrySet()) {
                Object value = e.getValue();
                if (value instanceof McpSchema.JsonSchema propSchema) {
                    properties.put(String.valueOf(e.getKey()), convertSchema(propSchema));
                } else if (value instanceof Map<?, ?> m) {
                    properties.put(String.valueOf(e.getKey()), convertPropMap(m));
                }
            }
        }
        result.put("properties", properties);

        List<String> required = schema.required();
        if (required != null && !required.isEmpty()) {
            result.put("required", new ArrayList<>(required));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertPropMap(Map<?, ?> m) {
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            Object value = e.getValue();
            if (value instanceof Map<?, ?> nested) {
                converted.put(String.valueOf(e.getKey()), convertPropMap(nested));
            } else {
                converted.put(String.valueOf(e.getKey()), value);
            }
        }
        return converted;
    }
}
