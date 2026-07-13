package com.byteq.ai.ragstudio.infra.reasoning;

/**
 * 推理能力类型枚举
 * <p>定义各 LLM 厂商/模型对推理深度参数的支持模式。</p>
 */
public enum ReasoningType {
    /** 连续型——通过 Token 预算控制思考深度，值域为连续整数范围 */
    CONTINUOUS,
    /** 离散型——推理强度为固定的几档（如 low / medium / high） */
    DISCRETE,
    /** 布尔型——仅支持开/关，无渐变 */
    BOOLEAN,
    /** 不支持——不暴露推理深度参数 */
    NONE
}
