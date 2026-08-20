package com.byteq.ai.ragstudio.rag.core.agent;

import cn.hutool.core.collection.CollUtil;
import com.byteq.ai.ragstudio.aimodel.service.DefaultModelConfigService;
import com.byteq.ai.ragstudio.alert.service.EmailService;
import com.byteq.ai.ragstudio.infra.config.DynamicModelConfig;
import com.byteq.ai.ragstudio.infra.embedding.EmbeddingService;
import com.byteq.ai.ragstudio.infra.http.HttpModelFactory;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import com.byteq.ai.ragstudio.rag.core.mcp.McpToolRegistry;
import com.byteq.ai.ragstudio.rag.core.skill.SkillDefinition;
import com.byteq.ai.ragstudio.rag.core.skill.SkillLoader;
import jakarta.annotation.PostConstruct;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ToolRetriever {

    private static final int TOP_K = 5;
    private static final long ALERT_COOLDOWN_MS = 30 * 60 * 1000L;

    private final EmbeddingService embeddingService;
    private final SkillLoader skillLoader;
    private final McpToolRegistry mcpToolRegistry;
    private final EmailService emailService;
    private final DefaultModelConfigService defaultModelConfigService;
    private final HttpModelFactory modelFactory;
    private final com.byteq.ai.ragstudio.infra.model.ModelSelector modelSelector;
    private final ToolCardStore store = new ToolCardStore();

    @Setter
    private String toolRoutingModel;

    private long lastAlertTs = 0;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ToolRetriever(EmbeddingService embeddingService, SkillLoader skillLoader,
                         McpToolRegistry mcpToolRegistry, EmailService emailService,
                         DefaultModelConfigService defaultModelConfigService,
                         HttpModelFactory modelFactory,
                         com.byteq.ai.ragstudio.infra.model.ModelSelector modelSelector) {
        this.embeddingService = embeddingService;
        this.skillLoader = skillLoader;
        this.mcpToolRegistry = mcpToolRegistry;
        this.emailService = emailService;
        this.defaultModelConfigService = defaultModelConfigService;
        this.modelFactory = modelFactory;
        this.modelSelector = modelSelector;
    }

    @PostConstruct
    public void init() {
        String stored = defaultModelConfigService.getModelId("tool_selector");
        if (stored != null && !stored.isEmpty()) {
            this.toolRoutingModel = stored;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread thread = new Thread(this::rebuildIndex, "tool-retriever-rebuild");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void rebuildIndex() {
        try {
            List<ToolCard> cards = buildAllCards();
            if (cards.isEmpty()) {
                log.warn("工具卡片为空，跳过索引构建");
                return;
            }
            List<String> texts = cards.stream()
                    .map(c -> "[" + c.getType() + "] " + c.getName() + ": " + c.getDescription())
                    .collect(Collectors.toList());

            List<List<Float>> vectors;
            if (toolRoutingModel != null) {
                vectors = embeddingService.embedBatch(texts, toolRoutingModel);
            } else {
                vectors = embeddingService.embedBatch(texts);
            }
            for (int i = 0; i < cards.size() && i < vectors.size(); i++) {
                List<Float> vec = vectors.get(i);
                float[] arr = new float[vec.size()];
                for (int j = 0; j < vec.size(); j++) {
                    arr[j] = vec.get(j);
                }
                cards.get(i).setEmbedding(arr);
            }
            store.rebuild(cards);
            log.info("工具检索索引构建完成: {} 个工具, TopK={}, model={}",
                    cards.size(), TOP_K, toolRoutingModel != null ? toolRoutingModel : "default");
        } catch (Exception e) {
            log.error("工具检索索引构建失败，保留旧索引", e);
        }
    }

    public synchronized void setModelAndRebuild(String modelId) {
        this.toolRoutingModel = modelId;
        rebuildIndex();
    }

    /**
     * 直接探测 Embedding 模型可用性（绕过断路器，不触发告警）
     */
    public void probeModel(String modelId) {
        ModelTarget target = modelSelector.selectEmbeddingCandidates().stream()
                .filter(t -> modelId.equals(t.id()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("未找到模型: " + modelId));
        embeddingService.embedDirect("你好", modelId);
    }

    public List<String> retrieve(String question, int topK) {
        if (store.size() == 0) return List.of();
        try {
            List<Float> queryVec;
            if (toolRoutingModel != null) {
                queryVec = embeddingService.embed(question, toolRoutingModel);
            } else {
                queryVec = embeddingService.embed(question);
            }
            float[] arr = new float[queryVec.size()];
            for (int i = 0; i < queryVec.size(); i++) {
                arr[i] = queryVec.get(i);
            }
            List<ToolCard> cards = store.search(arr, topK);
            return cards.stream().map(ToolCard::getName).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("工具检索失败 (model={})，回退全量注册: {}",
                    toolRoutingModel != null ? toolRoutingModel : "default", e.getMessage());
            maybeAlert(e.getMessage());
            return List.of();
        }
    }

    public List<String> retrieve(String question) {
        return retrieve(question, TOP_K);
    }

    public String getEnabledModel() {
        return toolRoutingModel;
    }

    private void maybeAlert(String error) {
        long now = System.currentTimeMillis();
        if (now - lastAlertTs < ALERT_COOLDOWN_MS) return;
        lastAlertTs = now;
        String model = toolRoutingModel != null ? toolRoutingModel : "default";
        log.warn("⚠️ 语义选择嵌入模型不可用 (model={}), 已降级为全量注册。错误: {}", model, error);
        if (toolRoutingModel != null) {
            try {
                String nowStr = LocalDateTime.now().format(DTF);
                String html = emailService.buildToolRoutingFailedHtml(nowStr, toolRoutingModel, error);
                emailService.sendAlert("RAG Studio · 语义选择嵌入模型不可用", html);
            } catch (Exception e) {
                log.warn("发送工具路由模型告警邮件失败", e);
            }
        }
    }

    private List<ToolCard> buildAllCards() {
        List<ToolCard> cards = new ArrayList<>();

        for (SkillDefinition skill : skillLoader.getAllSkills()) {
            cards.add(new ToolCard(skill.getName(), "SKILL",
                    skill.getDescription() != null ? skill.getDescription() : "", null));
        }

        mcpToolRegistry.listAllExecutors().forEach(executor -> {
            var toolDef = executor.getToolDefinition();
            String name = toolDef.name();
            String desc = toolDef.description() != null ? toolDef.description() : "";
            cards.add(new ToolCard(name, "MCP", desc, null));
        });

        return cards;
    }
}
