package com.byteq.ai.ragstudio.infra.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态模型配置数据对象
 * <p>
 * 从数据库加载的运行时模型配置，替代原有的 YAML 静态配置。
 * 由 {@link ModelConfigProvider} 提供，供 {@link com.byteq.ai.ragstudio.infra.model.ModelSelector} 使用。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamicModelConfig {

    /**
     * 供应商配置映射，key 为供应商 name（如 bailian、siliconflow、deepseek）
     */
    @Builder.Default
    private Map<String, ProviderEntry> providers = new HashMap<>();

    /**
     * 所有已启用的模型列表
     */
    @Builder.Default
    private List<ModelEntry> models = new ArrayList<>();

    // ==================== 便捷查询方法 ====================

    /**
     * 获取 Chat 模型分组
     */
    public ModelGroup getChatGroup() {
        return buildGroup("CHAT");
    }

    /**
     * 获取 Embedding 模型分组
     */
    public ModelGroup getEmbeddingGroup() {
        return buildGroup("EMBEDDING");
    }

    /**
     * 获取 Rerank 模型分组
     */
    public ModelGroup getRerankGroup() {
        return buildGroup("RERANK");
    }

    // 按能力类型（CHAT/EMBEDDING/RERANK）筛选模型列表，并确定默认模型，构建 ModelGroup
    private ModelGroup buildGroup(String capability) {
        List<ModelEntry> filtered = models.stream()
                .filter(m -> capability.equalsIgnoreCase(m.getCapability()))
                .toList();
        String defaultModel = filtered.stream()
                .filter(m -> m.getIsDefault() != null && m.getIsDefault())
                .map(ModelEntry::getId)
                .findFirst()
                .orElse(null);
        return new ModelGroup(defaultModel, filtered);
    }

    // ==================== 内部数据类 ====================

    /**
     * 供应商配置条目
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProviderEntry {
        private String name;
        private String url;
        private String apiKey;
        /** 端点映射，如 {"chat": "/v1/chat/completions", "embedding": "/v1/embeddings"} */
        @Builder.Default
        private Map<String, String> endpoints = new HashMap<>();
    }

    /**
     * 模型配置条目
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelEntry {
        private String id;
        private String provider;
        private String model;
        private String url;
        private Integer dimension;
        @Builder.Default
        private Integer priority = 100;
        @Builder.Default
        private Boolean enabled = true;
        @Builder.Default
        private Boolean supportsThinking = false;
        @Builder.Default
        private Boolean supportsMultimodal = false;
        private Boolean isDefault;
        private String capability;
    }

    /**
     * 模型分组（按 capability 分组后的结果）
     */
    @Data
    @NoArgsConstructor
    public static class ModelGroup {
        private String defaultModel;
        private List<ModelEntry> candidates;

        public ModelGroup(String defaultModel, List<ModelEntry> candidates) {
            this.defaultModel = defaultModel;
            this.candidates = candidates != null ? candidates : new ArrayList<>();
        }
    }
}
