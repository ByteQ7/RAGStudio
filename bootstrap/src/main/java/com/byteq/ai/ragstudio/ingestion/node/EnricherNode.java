package com.byteq.ai.ragstudio.ingestion.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.byteq.ai.ragstudio.core.chunk.VectorChunk;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.ingestion.domain.context.IngestionContext;
import com.byteq.ai.ragstudio.ingestion.domain.enums.ChunkEnrichType;
import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionNodeType;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.NodeConfig;
import com.byteq.ai.ragstudio.ingestion.domain.result.NodeResult;
import com.byteq.ai.ragstudio.ingestion.domain.settings.EnricherSettings;
import com.byteq.ai.ragstudio.ingestion.prompt.EnricherPromptManager;
import com.byteq.ai.ragstudio.ingestion.util.JsonResponseParser;
import com.byteq.ai.ragstudio.ingestion.util.PromptTemplateRenderer;
import com.byteq.ai.ragstudio.infra.chat.LLMService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分块富化节点（EnricherNode）
 * <p>
 * 数据摄入流水线的富化处理节点，负责对 ChunkerNode 切分后的每个文本块进行 AI 驱动的
 * 信息提取和元数据补充。与 {@link EnhancerNode} 对整个文档的处理不同，EnricherNode
 * 是逐块（per-chunk）处理的。
 * </p>
 * <p>
 * 核心处理逻辑：
 * <ol>
 *   <li>遍历 ChunkerNode 输出的每个文本块</li>
 *   <li>可选的全局元数据附着：将文档级别的元数据复制到每个文本块的元数据中</li>
 *   <li>根据配置的富化任务类型，调用大语言模型（LLM）对每个文本块进行处理</li>
 *   <li>将 LLM 返回的结果合并到文本块的元数据中</li>
 * </ol>
 * </p>
 * <p>
 * 支持的富化类型（{@link ChunkEnrichType}）：
 * <ul>
 *   <li>KEYWORDS - 从文本块中提取关键词</li>
 *   <li>SUMMARY - 为文本块生成摘要</li>
 *   <li>METADATA - 提取额外的元数据信息</li>
 * </ul>
 * </p>
 */
@Component
public class EnricherNode implements IngestionNode {

    /**
     * Jackson JSON 对象映射器，用于解析节点配置
     */
    private final ObjectMapper objectMapper;

    /**
     * 大语言模型调用服务，用于执行 AI 富化任务
     */
    private final LLMService llmService;

    public EnricherNode(ObjectMapper objectMapper, LLMService llmService) {
        this.objectMapper = objectMapper;
        this.llmService = llmService;
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.ENRICHER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        List<VectorChunk> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            return NodeResult.ok("没有需要富化的文本块");
        }
        EnricherSettings settings = parseSettings(config.getSettings());
        if (settings.getTasks() == null || settings.getTasks().isEmpty()) {
            return NodeResult.ok("未配置富化任务");
        }
        boolean attachMetadata = settings.getAttachDocumentMetadata() == null || settings.getAttachDocumentMetadata();
        for (VectorChunk chunk : chunks) {
            if (chunk == null || !StringUtils.hasText(chunk.getContent())) {
                continue;
            }
            if (chunk.getMetadata() == null) {
                chunk.setMetadata(new HashMap<>());
            }
            // 将文档级别的元数据复制到每个文本块中
            if (attachMetadata && context.getMetadata() != null) {
                chunk.getMetadata().putAll(context.getMetadata());
            }
            for (EnricherSettings.ChunkEnrichTask task : settings.getTasks()) {
                if (task == null || task.getType() == null) {
                    continue;
                }
                ChunkEnrichType type = task.getType();
                String systemPrompt = StringUtils.hasText(task.getSystemPrompt())
                        ? task.getSystemPrompt()
                        : EnricherPromptManager.systemPrompt(type);
                String userPrompt = buildUserPrompt(task.getUserPromptTemplate(), chunk, context);
                ChatRequest request = ChatRequest.builder()
                        .messages(List.of(
                                ChatMessage.system(systemPrompt == null ? "" : systemPrompt),
                                ChatMessage.user(userPrompt)
                        ))
                        .build();
                String response = chat(request, settings.getModelId());
                applyResult(chunk, type, response);
            }
        }
        return NodeResult.ok("分块富化完成");
    }

    /**
     * 解析节点配置中的 settings JSON 为 EnricherSettings 对象
     *
     * @param node JSON 配置节点
     * @return 富化设置对象
     */
    private EnricherSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return EnricherSettings.builder().tasks(List.of()).build();
        }
        return objectMapper.convertValue(node, EnricherSettings.class);
    }

    /**
     * 构建用户提示词
     * <p>
     * 如果配置了用户提示词模板，则将文本块内容和上下文变量注入模板后返回；
     * 否则直接返回文本块内容作为提示词。
     * </p>
     *
     * @param template 用户提示词模板（可选）
     * @param chunk    当前处理的文本块
     * @param context  摄入上下文
     * @return 构建后的用户提示词
     */
    private String buildUserPrompt(String template, VectorChunk chunk, IngestionContext context) {
        String input = chunk.getContent();
        if (!StringUtils.hasText(template)) {
            return input;
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("text", input);
        vars.put("content", input);
        vars.put("chunkIndex", chunk.getIndex());
        vars.put("taskId", context.getTaskId());
        vars.put("pipelineId", context.getPipelineId());
        return PromptTemplateRenderer.render(template, vars);
    }

    /**
     * 将 LLM 返回的结果应用到文本块的元数据中
     *
     * @param chunk    目标文本块
     * @param type     富化类型
     * @param response LLM 返回的原始响应字符串
     */
    private void applyResult(VectorChunk chunk, ChunkEnrichType type, String response) {
        switch (type) {
            case KEYWORDS -> chunk.getMetadata().put("keywords", JsonResponseParser.parseStringList(response));
            case SUMMARY ->
                    chunk.getMetadata().put("summary", StringUtils.hasText(response) ? response.trim() : response);
            case METADATA -> chunk.getMetadata().putAll(JsonResponseParser.parseObject(response));
            default -> {
            }
        }
    }

    /**
     * 调用大语言模型执行对话
     *
     * @param request 聊天请求
     * @param modelId 模型 ID（可选，为空时使用默认模型）
     * @return LLM 返回的响应文本
     */
    private String chat(ChatRequest request, String modelId) {
        return llmService.chat(request, modelId);
    }
}
