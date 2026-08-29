package com.byteq.ai.ragstudio.infra.chat;

import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.infra.config.DynamicModelConfig;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;

/**
 * 结构化输出能力解析
 * <p>
 * 业务侧通过 {@link ChatRequest#getJsonSchema()} 或 {@link ChatRequest#getResponseFormat()}
 * 声明期望的输出格式；本类结合目标模型在数据库中标记的结构化输出能力
 * （supports_json_output / supports_json_schema），解析出本次调用实际应下发的
 * response_format，形成统一的降级链：
 * </p>
 * <ul>
 *   <li>模型支持 json_schema → 下发 json_schema（约束解码，格式强保证）</li>
 *   <li>仅支持 JSON Output → 降级为 json_object（仅保证合法 JSON，结构靠提示词）</li>
 *   <li>两者都不支持 → 不下发任何 response_format（保持纯提示词 + 宽容解析的旧行为）</li>
 * </ul>
 * <p>
 * 能力标记由用户在模型管理中按供应商文档维护，误标时由网关层的
 * 参数错误降级重试兜底（见 OpenAiGateway / DashScopeGateway）。
 * </p>
 */
public final class StructuredOutputs {

    /** 解析出的实际下发格式 */
    public enum Mode {
        /** 不下发 response_format */
        NONE,
        /** 下发 {"type": "json_object"}（JSON Output） */
        JSON_OBJECT,
        /** 下发 {"type": "json_schema", ...}（结构化输出） */
        JSON_SCHEMA
    }

    /**
     * 解析结果
     *
     * @param mode   实际下发格式
     * @param name   schema 名称（仅 JSON_SCHEMA 时非空）
     * @param schema schema 内容（仅 JSON_SCHEMA 时非空）
     * @param strict 是否严格模式（仅 JSON_SCHEMA 时有意义）
     */
    public record Spec(Mode mode, String name, java.util.Map<String, Object> schema, boolean strict) {

        public static final Spec NONE = new Spec(Mode.NONE, null, null, false);

        public boolean active() {
            return mode != Mode.NONE;
        }
    }

    private StructuredOutputs() {
    }

    /**
     * 结合请求意图与模型能力，解析本次调用实际下发的 response_format
     */
    public static Spec resolve(ChatRequest request, ModelTarget target) {
        if (request == null || target == null) {
            return Spec.NONE;
        }
        DynamicModelConfig.ModelEntry candidate = target.candidate();
        boolean jsonOutputSupported = flag(candidate != null ? candidate.getSupportsJsonOutput() : null);
        boolean schemaSupported = flag(candidate != null ? candidate.getSupportsJsonSchema() : null);

        ChatRequest.JsonSchemaSpec requested = request.getJsonSchema();
        if (requested != null && requested.schema() != null && !requested.schema().isEmpty()) {
            if (schemaSupported) {
                return new Spec(Mode.JSON_SCHEMA, requested.name(), requested.schema(),
                        Boolean.TRUE.equals(requested.strict()));
            }
            if (jsonOutputSupported) {
                return new Spec(Mode.JSON_OBJECT, null, null, false);
            }
            return Spec.NONE;
        }

        if ("json_object".equals(request.getResponseFormat())) {
            if (jsonOutputSupported || schemaSupported) {
                return new Spec(Mode.JSON_OBJECT, null, null, false);
            }
        }
        return Spec.NONE;
    }

    private static boolean flag(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    /** 供日志输出使用 */
    public static String describe(Spec spec) {
        return switch (spec.mode()) {
            case JSON_SCHEMA -> "json_schema:" + spec.name();
            case JSON_OBJECT -> "json_object";
            case NONE -> "none";
        };
    }
}
