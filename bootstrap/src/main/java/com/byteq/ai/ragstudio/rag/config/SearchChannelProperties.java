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
     * 送入远程 Rerank 的全局候选数量上限。
     * <p>
     * 多知识库场景下粗召候选可能膨胀到上百条（per-kb-overflow-cap × KB 数），
     * 远程 Rerank 输入量线性放大且结果只取前几条，性价比极低。
     * 在 Rerank 前先按 RRF/向量分数截断到该上限，再发送远程调用。
     * </p>
     */
    private int maxRerankCandidates = 40;

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
     * 没有任何知识库超过该阈值时判定为与知识库无关，不检索（不降级保留），
     * 避免实时信息（天气/新闻）等明显无关问题被误判为相关。
     * </p>
     */
    private double kbSelectionThreshold = 0.32;

    /**
     * 知识库语义选择最大数量
     * <p>
     * 默认覆盖用户选定的全部知识库（8 个以内不做剔除）：
     * 选库相似度排序受嵌入模型稳定性影响较大（模型侧更新/缓存陈旧会导致排序翻转），
     * 硬截断会把正确知识库整体排除、直接造成检索零召回。
     * 相关度裁剪交给检索阶段的 rerank + 动态 TopK 完成，选库只做相关性判断。
     * </p>
     */
    private int kbSelectionTopK = 8;

    /**
     * 知识库语义选择分数接近度比例
     * <p>
     * 与已选中最后一个知识库分数差距在 {@code lastScore × (1 - ratio)} 内的候选库一并保留，
     * 避免嵌入模型分数抖动导致正确知识库恰好被截在第 topK 名之外。
     * 0 表示关闭该扩量逻辑。
     * </p>
     */
    private double kbSelectionTieBandRatio = 0.10;

    /**
     * 检索通道配置
     */
    private Channels channels = new Channels();

    /**
     * 语义裁剪（ContextCropper）配置
     */
    private Crop crop = new Crop();

    /**
     * 语义裁剪配置：控制 CPU 上 0.6B 语义高亮模型的调用成本（实测与句子总量成正比）
     */
    @Data
    public static class Crop {

        /**
         * 参与裁剪的非图片 chunk 文本总字符数低于该值时跳过裁剪、保留原文。
         * 短检索内容裁剪收益接近 0，但成本固定（CPU 推理）。
         */
        private int minChars = 800;

        /**
         * 每条 chunk 最多参与裁剪的句子数（0 = 不限制）。
         * 长 chunk 尾部句子对准确度贡献边际递减，限制后可线性降低裁剪时延。
         */
        private int maxSentencesPerChunk = 0;

        /**
         * 裁剪结果 Redis 缓存开关：key=(sha1(question), chunkId)，命中直接复用结果。
         */
        private boolean cacheEnabled = true;

        /**
         * 裁剪缓存 TTL（小时），与 embedding 缓存保持一致
         */
        private int cacheTtlHours = 6;

        /**
         * 裁剪调用硬超时（毫秒）：超时直接保留原文进入后续流程，不阻塞回答。
         * 后台任务仍会完成并把结果写入缓存，后续相同(问题, chunk)请求可直接命中。
         * 0 或负数表示不限制（保持原有同步阻塞行为）。
         * 注意：CPU 语义模型实测约 1.9s/20句/1 chunk，默认值偏保守，
         * 长内容大概率超时跳过裁剪（让位于延迟）；如需保留裁剪效果请调大或置 0。
         */
        private long timeoutMs = 2000;
    }

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
