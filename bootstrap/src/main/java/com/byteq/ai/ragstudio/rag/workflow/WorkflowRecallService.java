package com.byteq.ai.ragstudio.rag.workflow;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.rag.workflow.dao.entity.WorkflowDO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 工作流语义召回（低精度 Embedding + 低阈值）
 * <p>
 * 在内存中维护"启用工作流"的向量索引（名称+标题+描述），请求时用用户问题做余弦相似度检索，
 * 阈值以上全部返回（宁多勿漏，候选只注入名称+描述，token 成本低）。
 * <ul>
 *   <li>写入路径：监听 {@link WorkflowChangedEvent} 后重建（增量：仅补算缺失/换模型的向量）</li>
 *   <li>降级：embedding 不可用或索引为空时返回空列表，对话链路不受影响</li>
 * </ul>
 */
@Slf4j
@Service
public class WorkflowRecallService {

    private final WorkflowService workflowService;

    @Value("${rag.workflow.enabled:true}")
    private boolean enabled;

    @Value("${rag.workflow.recall-threshold:0.25}")
    private double recallThreshold;

    @Value("${rag.workflow.recall-top-k:8}")
    private int recallTopK;

    /** 当前索引快照（写时复制，读取无锁） */
    private volatile List<Entry> index = List.of();

    /** 索引构建时使用的向量模型（与当前模型不一致时索引不可用，避免跨语义空间比对产生噪声召回） */
    private volatile String indexModel;

    public WorkflowRecallService(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /**
     * 重建触发（启动 / 工作流变更）。
     * <p>
     * 变更事件可能短时间连续到达（批量导入、连续编辑），用单线程执行器串行化并合并重复触发：
     * 执行器队列容量 1，已有任务排队时直接丢弃新触发（因为重建总是读全量，等价于合并）。
     */
    private final java.util.concurrent.ExecutorService rebuildExecutor =
            new java.util.concurrent.ThreadPoolExecutor(0, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(1),
                    r -> {
                        Thread t = new Thread(r, "workflow-recall-rebuild");
                        t.setDaemon(true);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        triggerRebuild();
    }

    @EventListener(WorkflowChangedEvent.class)
    public void onWorkflowChanged(WorkflowChangedEvent event) {
        triggerRebuild();
    }

    /** 异步触发一次重建；队列满（已有待执行任务）时丢弃本次触发（全量重建天然幂等） */
    private void triggerRebuild() {
        try {
            rebuildExecutor.execute(this::rebuildIndex);
        } catch (Exception e) {
            log.warn("工作流召回索引重建任务提交失败: {}", e.getMessage());
        }
    }

    /**
     * 重建索引：读取全部启用工作流，缺失/换模型的向量即时补算并回写 DB。
     * 单线程执行；并发触发时由 synchronized 串行化。
     */
    public synchronized void rebuildIndex() {
        try {
            String currentModel = workflowService.currentEmbeddingModel();
            List<WorkflowDO> workflows = workflowService.listEnabled();
            List<Entry> newIndex = new ArrayList<>();
            for (WorkflowDO workflow : workflows) {
                float[] vector = WorkflowService.fromJson(workflow.getEmbedding());
                // 向量缺失、模型切换、文本指纹变化（标题/描述被编辑）→ 重算并回写
                if (vector == null || workflowService.embeddingStale(workflow, currentModel)) {
                    workflowService.applyEmbedding(workflow, true);
                    if (workflow.getEmbedding() != null) {
                        workflowService.updateEmbedding(workflow);
                        vector = WorkflowService.fromJson(workflow.getEmbedding());
                    }
                }
                if (vector != null) {
                    newIndex.add(new Entry(workflow.getName(), workflow.getTitle(),
                            workflow.getDescription(), vector));
                }
            }
            this.index = List.copyOf(newIndex);
            this.indexModel = currentModel;
            log.info("工作流召回索引构建完成: {} / {} 条启用工作流（model={}）",
                    newIndex.size(), workflows.size(), currentModel != null ? currentModel : "default");
        } catch (Exception e) {
            log.error("工作流召回索引构建失败，保留旧索引", e);
        }
    }

    /**
     * 召回与问题语义相关的工作流候选（阈值低、按相似度降序）
     *
     * @param question 用户问题
     * @return 候选列表；关闭/为空/失败时返回空列表
     */
    public List<WorkflowRenderer.Card> recall(String question) {
        if (!enabled || StrUtil.isBlank(question) || index.isEmpty()) {
            return List.of();
        }
        try {
            String model = workflowService.currentEmbeddingModel();
            // 模型已切换但索引尚未重建：新旧向量处于不同语义空间，比对结果无意义，直接跳过本次召回
            if (!Objects.equals(model, indexModel)) {
                log.info("工作流召回跳过：召回模型({})与索引模型({})不一致，等待索引重建",
                        model != null ? model : "default", indexModel != null ? indexModel : "default");
                return List.of();
            }
            float[] query = workflowService.embed(question, model);
            if (query == null) {
                return List.of();
            }
            List<Scored> scored = new ArrayList<>();
            for (Entry entry : index) {
                double score = cosine(query, entry.embedding());
                if (score >= recallThreshold) {
                    scored.add(new Scored(entry, score));
                }
            }
            scored.sort(Comparator.comparingDouble(Scored::score).reversed());
            int limit = Math.min(Math.max(1, recallTopK), scored.size());
            List<WorkflowRenderer.Card> result = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                Entry entry = scored.get(i).entry();
                result.add(new WorkflowRenderer.Card(entry.name(), entry.title(), entry.description(),
                        scored.get(i).score()));
            }
            if (!result.isEmpty()) {
                log.info("工作流召回命中 {} 条（阈值 {}）: {}", result.size(), recallThreshold,
                        result.stream().map(WorkflowRenderer.Card::name).toList());
            }
            return result;
        } catch (Exception e) {
            log.warn("工作流召回失败，跳过本次注入: {}", e.getMessage());
            return List.of();
        }
    }

    /** 当前索引大小（诊断/测试） */
    public int size() {
        return index.size();
    }

    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    private record Entry(String name, String title, String description, float[] embedding) {
    }

    private record Scored(Entry entry, double score) {
    }
}
