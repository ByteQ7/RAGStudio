package com.byteq.ai.ragstudio.core.parser;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 文档解析引擎枚举
 * <p>
 * 描述复杂文档（PDF/扫描件）的解析引擎偏好，由用户在知识库级配置
 * （{@code t_knowledge_base.parse_engine}）或文档级覆盖
 * （{@code t_knowledge_document.parse_engine}）中指定。
 * </p>
 * <ul>
 *   <li>{@link #AUTO}：自动。优先 MinerU 解析，失败/不可用时回退多模态 LLM。</li>
 *   <li>{@link #LOCAL_MINERU}：调用本地部署的 MinerU 服务。</li>
 *   <li>{@link #REMOTE_MINERU}：调用远程 MinerU 服务（SaaS/私有云）。</li>
 *   <li>{@link #MULTIMODAL_LLM}：强制使用多模态大模型（doc_image）兜底。</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum ParseEngine {

    /**
     * 自动：优先 MinerU，失败回退多模态 LLM
     */
    AUTO("AUTO", "自动（优先MinerU，失败回退多模态LLM）"),

    /**
     * 本地 MinerU
     */
    LOCAL_MINERU("LOCAL_MINERU", "本地 MinerU"),

    /**
     * 远程 MinerU
     */
    REMOTE_MINERU("REMOTE_MINERU", "远程 MinerU"),

    /**
     * 多模态 LLM
     */
    MULTIMODAL_LLM("MULTIMODAL_LLM", "多模态 LLM");

    /**
     * 枚举值（数据库存储值）
     */
    private final String value;

    /**
     * 中文展示标签
     */
    private final String label;

    /**
     * 将字符串规范化为 {@link ParseEngine} 枚举
     * <p>
     * 空 / null / 未知值一律归为 {@link #AUTO}，保证任何脏数据都不会破坏解析链路。
     * </p>
     *
     * @param value 原始字符串值（可为 null）
     * @return 规范化的枚举，永不返回 null
     */
    public static ParseEngine normalize(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        for (ParseEngine engine : values()) {
            if (engine.value.equalsIgnoreCase(value.trim())) {
                return engine;
            }
        }
        return AUTO;
    }

    /**
     * 是否为 MinerU 类引擎（本地或远程）
     */
    public boolean isMineru() {
        return this == LOCAL_MINERU || this == REMOTE_MINERU;
    }

    /**
     * 判断是否应当尝试 MinerU 解析
     * <p>
     * AUTO 与显式 MinerU 类引擎都会优先尝试 MinerU。
     * </p>
     */
    public boolean preferMineru() {
        return this == AUTO || isMineru();
    }
}