package com.byteq.ai.ragstudio.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 检索配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.search")
public class SearchChannelProperties {

    /**
     * 默认返回的 TopK
     */
    private int defaultTopK = 10;

    /**
     * 每个知识库检索的最大 Chunk 数（per-KB RRF 融合后截断）
     */
    private int perKbChunkLimit = 5;

    /**
     * per-KB RRF 融合的扩量上限：分数接近时每个知识库最多保留的 Chunk 数。
     * 粗召阶段先融合到该上限，再由分数簇感知截断决定实际进入 rerank 的数量。
     */
    private int perKbOverflowCap = 15;

    /**
     * Rerank 后最终送入 LLM 的最大 Chunk 数（纯文本场景，IMAGE chunk 不计入）
     */
    private int maxFinalChunks = 5;

    /**
     * Rerank 最低分数阈值，低于此分数的 chunk 被丢弃（0 = 不启用）
     */
    private double rerankMinScore = 0.3;

    /**
     * 是否启用动态 TopK 断点检测
     */
    private boolean dynamicTopKEnabled = true;

    /**
     * 动态 TopK 断点检测的相邻 chunk 分数差值阈值
     */
    private double dynamicTopKScoreGap = 0.15;

    /**
     * 相对接近度比例：与最高分差距在 top × (1 - ratio) 内的 chunk 视为同一分数簇。
     * 分数相近时按该比例扩量，避免截断丢失同等相关的 chunk。
     */
    private double dynamicTopKTieBandRatio = 0.15;

    /**
     * 分数相近时的扩量上限（防止无显著落差时无限扩量）
     */
    private int dynamicTopKOverflowCap = 10;

    /**
     * 缩量保底：即使分数分布判出明显落差，也至少保留前 minChunks 条。
     * 避免"悬崖恰好把答案所在的接近群截在带外"，LLM 也需多条上下文交叉验证。
     */
    private int dynamicTopKMinChunks = 3;

    /**
     * 悬崖比值阈值：相邻分数比值 s[i-1]/s[i] ≥ 该值 且 差值 ≥ 阈值 才判定为悬崖。
     * 纯差值判定对分数尺度敏感（"孤峰+平台"分布频繁误判），叠加比值后对任意分数尺度鲁棒。
     */
    private double dynamicTopKCliffRatio = 1.5;

    /**
     * 知识库语义选择阈值（余弦相似度）
     * <p>
     * 用户问题向量与知识库（名称+描述+文档名）向量相似度高于该阈值的知识库才会被选中检索。
     * 若没有任何知识库超过该阈值，则降级保留相似度最高的 1 个知识库（分数需 ≥ 阈值的 60%），
     * 避免因阈值过严导致"不选知识库"。
     * </p>
     */
    private double kbSelectionThreshold = 0.30;

    /**
     * 知识库语义选择最大数量
     */
    private int kbSelectionTopK = 3;

    /**
     * 检索通道配置
     */
    private Channels channels = new Channels();

    /**
     * 检索通道聚合配置
     * <p>
     * 包含向量全局检索和知识库选择检索两个子通道的配置
     * </p>
     */
    @Data
    public static class Channels {

        /**
         * 向量全局检索配置
         */
        private VectorGlobal vectorGlobal = new VectorGlobal();

        /**
         * 知识库选择检索配置
         */
        private KnowledgeBaseSelection knowledgeBaseSelection = new KnowledgeBaseSelection();

        /**
         * 关键词检索配置
         */
        private Keyword keyword = new Keyword();

        /**
         * RRF 混合检索配置
         */
        private HybridRrf hybridRrf = new HybridRrf();
    }

    /**
     * 向量全局检索通道配置
     * <p>
     * 支持开关控制和 TopK 倍数设置，全局检索时先按倍数多召回候选，后续通过 Rerank 筛选
     * </p>
     */
    @Data
    public static class VectorGlobal {

        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * TopK 倍数
         * 全局检索时召回更多候选，后续通过 Rerank 筛选
         */
        private int topKMultiplier = 3;
    }

    /**
     * 知识库选择检索通道配置
     * <p>
     * 通过 TopK 倍数控制在每个选定知识库中检索的召回数量
     * </p>
     */
    @Data
    public static class KnowledgeBaseSelection {

        /**
         * TopK 倍数
         * 知识库选择检索时，在每个选定知识库中检索的 TopK 倍数
         */
        private int topKMultiplier = 2;
    }

    @Data
    public static class Keyword {

        /**
         * 是否启用关键词检索
         */
        private boolean enabled = true;

        /**
         * TopK 倍数
         */
        private int topKMultiplier = 2;

        /**
         * tsvector 词典（simple / english / zhparser 等）
         */
        private String dictionary = "simple";
    }

    @Data
    public static class HybridRrf {

        /**
         * 是否启用 RRF 混合检索
         */
        private boolean enabled = true;

        /**
         * RRF 平滑常数（推荐 60）
         */
        private int k = 60;

        /**
         * 融合后最终返回的 topK
         */
        private int topK = 5;
    }
}
