package com.byteq.ai.ragstudio.framework.convention;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Builder.Default;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通用大模型请求对象
 *
 * <p>
 * 用于封装一次完整对话所需的所有上下文与控制参数，作为「统一入参」传给
 * 各种不同厂商 / 协议的大模型接口（如百炼、DeepSeek、OpenAI 兼容协议等），
 * 方便在适配层做统一转换
 * </p>
 *
 * <p>典型使用方式：</p>
 * <pre>
 * ChatRequest req = ChatRequest.builder()
 *     .temperature(0.3)
 *     .maxTokens(512)
 *     .build();
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatRequest {

    /**
     * 完整消息列表
     * <p>
     * 用于直接传入 system/user/assistant 消息序列。
     * 当 messages 非空时，适配层使用该字段构造请求；
     * prompt 会作为额外的 user 消息追加。
     * </p>
     */
    @Default
    private List<ChatMessage> messages = new ArrayList<>();

    /** function calling 工具定义列表 */
    @Default
    private List<Map<String, Object>> tools = new ArrayList<>();

    // ================== 模型控制参数 ==================

    /**
     * 采样温度参数，取值通常为 0～2
     * <p>
     * 数值越小，输出越稳定、保守；数值越大，生成内容越发散、创造性更强
     * 例如：问答场景可用 0.1～0.5，创作类可用 0.7 以上
     * </p>
     */
    private Double temperature;

    /**
     * nucleus sampling（Top-P）参数
     * <p>
     * 表示从累积概率为 P 的词集合中采样，常与 {@link #temperature} 搭配使用
     * 一般取值在 0.8～0.95 之间，越小越保守
     * 若为 {@code null} 则使用模型默认值
     * </p>
     */
    private Double topP;

    /**
     * Top-K 采样参数
     * <p>
     * 表示每一步只从概率最高的 K 个 token 中采样，常与 {@link #temperature}
     * 或 {@link #topP} 搭配使用。K 越小越保守，K 越大越发散
     * 若为 {@code null} 则使用模型默认值
     * </p>
     */
    private Integer topK;

    /**
     * 限制模型本次回答最多生成的 token 数量
     * <p>
     * 可用于控制回复长度与成本；若为 {@code null}，则走模型或服务端默认配置
     * </p>
     */
    private Integer maxTokens;

    /**
     * 推理深度级别（0-100）
     * <p>
     * 0 = 关闭思考；1-100 = 思考深度，由 ReasoningRouter 适配为各模型原生参数。
     * 替代旧的 thinking boolean 字段。
     * </p>
     */
    private Integer thinkingLevel;

    /**
     * 可选：是否启用工具调用（Tool Calling / Function Calling）
     * <p>
     * 当前预留字段，方便后续扩展为带工具调用能力的对话请求：
     * <ul>
     *   <li>{@code false}：只进行纯文本对话</li>
     *   <li>{@code true}：允许模型按照定义调用工具 / 函数</li>
     * </ul>
     * 具体工具列表、调用结果处理由上层或实现层定义
     * </p>
     */
    private Boolean enableTools;

    /**
     * 响应格式约束
     * <p>
     * 对于 OpenAI 兼容协议：{@code "json_object"} 强制模型输出合法 JSON。
     * 对于 DashScope 协议：{@code "json"} 等价于设置 result_format = "json"。
     * 若为 {@code null} 则不设置，由模型自行决定输出格式。
     * </p>
     */
    private String responseFormat;

    /**
     * 结构化输出 JSON Schema（可选，优先级高于 responseFormat）
     * <p>
     * 非空时请求 JSON Schema 约束输出。实际下发格式由模型能力决定（见各网关降级链）：
     * 模型支持 json_schema 则下发 schema（强约束）；仅支持 JSON Output 则降级为
     * json_object（弱约束，此时仍依赖提示词描述结构）；两者都不支持则不下发任何
     * response_format，完全依赖提示词约束，保持旧行为。
     * </p>
     */
    private JsonSchemaSpec jsonSchema;

    /**
     * JSON Schema 定义
     *
     * @param name   schema 名称（OpenAI json_schema 必填，如 "graph_extraction"）
     * @param schema JSON Schema 内容（type/properties/required 等）
     * @param strict 是否启用严格模式（true 时 schema 需满足所有字段 required 且
     *               additionalProperties=false 的供应商强约束；null 视为 false）
     */
    public record JsonSchemaSpec(String name, Map<String, Object> schema, Boolean strict) {

        public static JsonSchemaSpec of(String name, Map<String, Object> schema) {
            return new JsonSchemaSpec(name, schema, Boolean.FALSE);
        }

        public static JsonSchemaSpec strict(String name, Map<String, Object> schema) {
            return new JsonSchemaSpec(name, schema, Boolean.TRUE);
        }
    }
}
