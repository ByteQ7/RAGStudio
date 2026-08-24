package com.byteq.ai.ragstudio.graph.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Graph RAG 配置
 * <p>前缀 {@code rag.graph}，控制图谱构建与检索的限额与成本参数。
 * 总开关不在此配置：由后管「知识图谱」页动态控制（{@link GraphConfigService}，t_graph_config 表）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.graph")
public class GraphProperties {

    /**
     * 抽取配置
     */
    private Extract extract = new Extract();

    /**
     * 检索配置
     */
    private Retrieval retrieval = new Retrieval();

    /**
     * 实体配置
     */
    private Entity entity = new Entity();

    /**
     * 社区配置（Phase 2 预留）
     */
    private Community community = new Community();

    /**
     * 抽取配置
     */
    @Data
    public static class Extract {

        /**
         * 抽取模型 config_key（t_default_model_config），缺省回退 chat 默认模型
         */
        private String modelKey = "graph_extract";

        /**
         * 每个 chunk 抽取实体上限
         */
        private int maxEntitiesPerChunk = 30;

        /**
         * 每个 chunk 抽取关系上限
         */
        private int maxRelationsPerChunk = 50;

        /**
         * 抽取温度（低温度保证 JSON 输出确定性）
         */
        private double temperature = 0.1;

        /**
         * 单 chunk 抽取超时（毫秒）
         */
        private long timeoutMs = 30_000;

        /**
         * 抽取并发度（LLM 调用并发上限）
         */
        private int parallelLimit = 4;

        /**
         * 参与抽取的 chunk 内容最大字符数（超出截断，图谱抽取只需语义信息）
         */
        private int maxChunkChars = 6000;

        /**
         * 证据文本截断长度
         */
        private int maxEvidenceChars = 200;

        /**
         * 单次构建的最大 chunk 数（防止超大文档一次性触发过多 LLM 调用）
         */
        private int maxChunksPerBuild = 500;
    }

    /**
     * 检索配置
     */
    @Data
    public static class Retrieval {

        /**
         * 图谱检索通道开关（依赖图谱已构建）
         */
        private boolean enabled = true;

        /**
         * K 跳展开深度
         */
        private int maxDepth = 2;

        /**
         * 每跳邻居展开上限（防 hub 节点爆炸）
         */
        private int maxNeighborsPerHop = 30;

        /**
         * 展开节点总数上限
         */
        private int maxNodes = 200;

        /**
         * 渲染给 LLM 的三元组上限
         */
        private int maxContextTriples = 40;

        /**
         * 查询实体识别上限
         */
        private int queryEntityLimit = 5;

        /**
         * 实体匹配相似度下限（trgm/向量通道）
         */
        private double entityMatchThreshold = 0.3;

        /**
         * 查询实体识别 LLM 超时（毫秒）
         */
        private long queryExtractTimeoutMs = 10_000;
    }

    /**
     * 实体配置
     */
    @Data
    public static class Entity {

        /**
         * 实体向量兜底通道（Phase 2）
         */
        private boolean embeddingEnabled = false;

        /**
         * 疑似重复实体检测阈值（仅标记，不自动合并）
         */
        private double mergeCosineThreshold = 0.92;

        /**
         * 知识库实体数量上限（超限停止增量抽取并告警）
         */
        private int maxEntitiesPerKb = 50_000;

        /**
         * 知识库关系数量上限
         */
        private int maxRelationsPerKb = 200_000;
    }

    /**
     * 社区配置（全局检索，Phase 2）
     */
    @Data
    public static class Community {

        /**
         * 社区构建开关
         */
        private boolean enabled = false;

        /**
         * 全局检索命中社区数上限
         */
        private int maxCommunities = 3;
    }
}