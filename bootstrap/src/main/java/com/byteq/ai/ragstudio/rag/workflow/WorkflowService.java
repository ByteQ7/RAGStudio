package com.byteq.ai.ragstudio.rag.workflow;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.byteq.ai.ragstudio.aimodel.service.DefaultModelConfigService;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.infra.embedding.EmbeddingService;
import com.byteq.ai.ragstudio.rag.workflow.dao.entity.WorkflowDO;
import com.byteq.ai.ragstudio.rag.workflow.dao.mapper.WorkflowMapper;
import com.byteq.ai.ragstudio.rag.workflow.graph.WorkflowGraph;
import com.byteq.ai.ragstudio.rag.workflow.graph.WorkflowGraphCompiler;
import com.byteq.ai.ragstudio.rag.workflow.graph.WorkflowGraphFactory;
import com.byteq.ai.ragstudio.rag.workflow.graph.WorkflowGraphJson;
import com.byteq.ai.ragstudio.rag.workflow.graph.WorkflowGraphValidator;
import com.byteq.ai.ragstudio.rag.workflow.model.WorkflowDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 工作流存储服务（DB 事实源）
 * <p>
 * 负责 CRUD、结构化校验、向量缓存计算与变更事件发布；
 * 召回索引由 {@link WorkflowRecallService} 监听事件后重建（单向依赖，避免循环）。
 */
@Slf4j
@Service
public class WorkflowService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorkflowMapper workflowMapper;
    private final EmbeddingService embeddingService;
    private final DefaultModelConfigService defaultModelConfigService;
    private final ApplicationEventPublisher eventPublisher;

    /** 召回向量使用的场景模型 key（默认复用语义选择模型；未配置时走默认 embedding 路由） */
    @Value("${rag.workflow.embedding-model-key:tool_selector}")
    private String embeddingModelKey;

    public WorkflowService(WorkflowMapper workflowMapper,
                           EmbeddingService embeddingService,
                           DefaultModelConfigService defaultModelConfigService,
                           ApplicationEventPublisher eventPublisher) {
        this.workflowMapper = workflowMapper;
        this.embeddingService = embeddingService;
        this.defaultModelConfigService = defaultModelConfigService;
        this.eventPublisher = eventPublisher;
    }

    // ==================== 查询 ====================

    public List<WorkflowDO> listAll() {
        return workflowMapper.selectList(new LambdaQueryWrapper<WorkflowDO>()
                .orderByDesc(WorkflowDO::getUpdateTime));
    }

    public List<WorkflowDO> listEnabled() {
        return workflowMapper.selectList(new LambdaQueryWrapper<WorkflowDO>()
                .eq(WorkflowDO::getEnabled, true)
                .orderByDesc(WorkflowDO::getUpdateTime));
    }

    public WorkflowDO getByName(String name) {
        if (StrUtil.isBlank(name)) {
            return null;
        }
        return workflowMapper.selectOne(new LambdaQueryWrapper<WorkflowDO>()
                .eq(WorkflowDO::getName, name));
    }

    // ==================== 写入 ====================

    /**
     * 创建或更新工作流（name 已存在时更新），返回持久化后的实体
     *
     * @param steps     线性步骤；{@code graph} 非空时忽略（以图编译结果为准）
     * @param graph     画布图（可空）；非空时校验并编译为 steps，图与 steps 一并落库
     * @param source    来源（EXTRACTED / MANUAL）
     * @param changeLog 变更说明
     */
    public WorkflowDO saveOrUpdate(String name, String title, String description,
                                   List<WorkflowDefinition.WorkflowStep> steps, String notes,
                                   String source, String changeLog, String operator) {
        return saveOrUpdate(name, title, description, steps, notes, null, source, changeLog, operator);
    }

    /** 带画布图的保存：图存在时校验 + 编译，steps 以编译产物为准（编辑态事实源 = 图） */
    public WorkflowDO saveOrUpdate(String name, String title, String description,
                                   List<WorkflowDefinition.WorkflowStep> steps, String notes,
                                   WorkflowGraph graph,
                                   String source, String changeLog, String operator) {
        List<WorkflowDefinition.WorkflowStep> effectiveSteps = steps;
        String graphJson = null;
        if (graph != null && !graph.isEmpty()) {
            WorkflowGraphValidator.ValidationResult validation = WorkflowGraphValidator.validate(graph);
            if (validation.hasError()) {
                throw new ClientException("工作流图校验失败：" + String.join("；", validation.errors()));
            }
            effectiveSteps = WorkflowGraphCompiler.compile(graph);
            graphJson = WorkflowGraphJson.toJson(graph);
        } else if (effectiveSteps != null) {
            // 线性保存（旧接口 / 对话提取）：同步生成线性图，保证图与 steps 始终一致
            graphJson = WorkflowGraphJson.toJson(WorkflowGraphFactory.fromSteps(effectiveSteps));
        }

        List<String> errors = WorkflowJson.validate(name, title, description, effectiveSteps, notes);
        if (CollUtil.isNotEmpty(errors)) {
            throw new ClientException("工作流校验失败：" + String.join("；", errors));
        }

        WorkflowDO existing = getByName(name);
        String stepsJson = WorkflowJson.toJson(effectiveSteps, notes);
        if (existing != null) {
            existing.setTitle(title);
            existing.setDescription(description);
            existing.setSteps(stepsJson);
            if (graphJson != null) {
                existing.setGraphJson(graphJson);
            }
            if (StrUtil.isNotBlank(source)) {
                existing.setSource(source);
            }
            existing.setChangeLog(changeLog);
            existing.setUpdatedBy(operator);
            existing.setUpdateTime(new Date());
            applyEmbedding(existing, false);
            workflowMapper.updateById(existing);
            publish(existing, WorkflowChangedEvent.Action.UPDATED);
            return existing;
        }

        WorkflowDO workflow = WorkflowDO.builder()
                .name(name)
                .title(title)
                .description(description)
                .steps(stepsJson)
                .graphJson(graphJson)
                .source(StrUtil.isNotBlank(source) ? source : "MANUAL")
                .enabled(true)
                .changeLog(changeLog)
                .updatedBy(operator)
                .createTime(new Date())
                .updateTime(new Date())
                .build();
        applyEmbedding(workflow, true);
        workflowMapper.insert(workflow);
        publish(workflow, WorkflowChangedEvent.Action.CREATED);
        return workflow;
    }

    /**
     * 读取工作流图：优先 DB 的 graph_json；为空时由线性 steps 自动生成（旧数据兼容，不落库）。
     * 返回的图保证可直接用于画布编辑。
     */
    public WorkflowGraph resolveGraph(WorkflowDO workflow) {
        WorkflowGraph graph = WorkflowGraphJson.parse(workflow.getGraphJson());
        if (graph != null && !graph.isEmpty()) {
            return graph;
        }
        WorkflowDefinition definition = WorkflowJson.parse(workflow.getName(), workflow.getTitle(),
                workflow.getDescription(), workflow.getSteps());
        return WorkflowGraphFactory.fromSteps(definition.steps());
    }

    public void delete(String name) {
        WorkflowDO workflow = require(name);
        workflowMapper.deleteById(workflow.getId());
        publish(workflow, WorkflowChangedEvent.Action.DELETED);
    }

    public WorkflowDO toggle(String name, boolean enabled) {
        WorkflowDO workflow = require(name);
        workflow.setEnabled(enabled);
        workflow.setUpdateTime(new Date());
        workflowMapper.updateById(workflow);
        publish(workflow, WorkflowChangedEvent.Action.TOGGLED);
        return workflow;
    }

    /** 回写向量缓存（索引重建时补算缺失向量） */
    public void updateEmbedding(WorkflowDO workflow) {
        workflowMapper.updateById(workflow);
    }

    // ==================== 向量 ====================

    /**
     * 当前召回向量模型 ID（可能为 null = 默认路由）。
     * <p>进程内短 TTL 缓存：召回路径每次对话都会读取，直查 DB 会给主链路增加无谓查询；
     * 管理端切换默认模型后最多 {@link #MODEL_CACHE_TTL_MS} 内生效（索引重建随后跟上）。</p>
     */
    private static final long MODEL_CACHE_TTL_MS = 30_000;

    private volatile String cachedModelId;
    private volatile long cachedModelAt;

    public String currentEmbeddingModel() {
        long now = System.currentTimeMillis();
        if (now - cachedModelAt < MODEL_CACHE_TTL_MS) {
            return cachedModelId;
        }
        try {
            String modelId = defaultModelConfigService.getModelId(embeddingModelKey);
            cachedModelId = StrUtil.isNotBlank(modelId) ? modelId : null;
        } catch (Exception e) {
            log.warn("读取工作流召回模型配置失败，使用默认 embedding 路由: {}", e.getMessage());
            cachedModelId = null;
        }
        cachedModelAt = now;
        return cachedModelId;
    }

    /**
     * 计算"标题+描述"的向量并写入实体。失败时不阻断保存（embedding 为可空缓存字段），
     * 由索引重建时补算。
     * <p>
     * 是否重算的判定：模型变化 或 文本内容变化（标题/描述被编辑后必须重算，
     * 否则召回语义与展示文本失配）。文本指纹用与向量同源的 embeddingText 计算。
     */
    public void applyEmbedding(WorkflowDO workflow, boolean force) {
        String model = currentEmbeddingModel();
        String text = embeddingText(workflow.getTitle(), workflow.getDescription());
        String fingerprint = sha256(text);
        if (!force && workflow.getEmbedding() != null
                && java.util.Objects.equals(model, workflow.getEmbeddingModel())
                && java.util.Objects.equals(fingerprint, workflow.getEmbeddingTextHash())) {
            return;
        }
        float[] vector = embed(text, model);
        if (vector != null) {
            workflow.setEmbedding(toJson(vector));
            workflow.setEmbeddingModel(model);
            workflow.setEmbeddingTextHash(fingerprint);
        }
    }

    /** 向量缓存是否已失效（模型变化或标题/描述被编辑） */
    public boolean embeddingStale(WorkflowDO workflow, String currentModel) {
        if (workflow.getEmbedding() == null) {
            return true;
        }
        if (!java.util.Objects.equals(currentModel, workflow.getEmbeddingModel())) {
            return true;
        }
        String fingerprint = sha256(embeddingText(workflow.getTitle(), workflow.getDescription()));
        return !java.util.Objects.equals(fingerprint, workflow.getEmbeddingTextHash());
    }

    /** 文本 SHA-256（向量缓存失效判定用） */
    private static String sha256(String text) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return null;
        }
    }

    /** 文本向量化；失败返回 null（调用方降级） */
    public float[] embed(String text, String model) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            List<Float> vec = model != null
                    ? embeddingService.embed(text, model)
                    : embeddingService.embed(text);
            if (CollUtil.isEmpty(vec)) {
                return null;
            }
            float[] arr = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) {
                arr[i] = vec.get(i);
            }
            return arr;
        } catch (Exception e) {
            log.warn("工作流向量化失败（该条目本次不参与召回）: {}", e.getMessage());
            return null;
        }
    }

    public static String embeddingText(String title, String description) {
        return (title != null ? title : "") + "：" + (description != null ? description : "");
    }

    public static String toJson(float[] vector) {
        try {
            return MAPPER.writeValueAsString(vector);
        } catch (Exception e) {
            return null;
        }
    }

    public static float[] fromJson(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return MAPPER.readValue(json, float[].class);
        } catch (Exception e) {
            return null;
        }
    }

    private WorkflowDO require(String name) {
        WorkflowDO workflow = getByName(name);
        if (workflow == null) {
            throw new ClientException("工作流不存在：" + name);
        }
        return workflow;
    }

    private void publish(WorkflowDO workflow, WorkflowChangedEvent.Action action) {
        try {
            eventPublisher.publishEvent(WorkflowChangedEvent.of(workflow, action));
        } catch (Exception e) {
            log.warn("工作流变更事件发布失败: {}", e.getMessage());
        }
    }
}
