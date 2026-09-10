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
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Component
public class EnricherNode implements IngestionNode {

    /** 单文档逐块富化的 chunk 数量上限：串行同步 LLM 调用无上限会长时间占用消费线程 */
    private static final int MAX_ENRICH_CHUNKS = 2000;
    /** 单 chunk 参与富化的最大输入字符数：超长 chunk 截断，控制单次 LLM 请求规模 */
    private static final int MAX_ENRICH_INPUT_CHARS = 8000;

    /**
     * Jackson JSON 对象映射器，用于解析节点配置
     */
    private final ObjectMapper objectMapper;

    /**
     * 大语言模型调用服务，用于执行 AI 富化任务
     */
    private final LLMService llmService;

    /**
     * 分块富化默认提示词管理器（DB 优先、classpath 兜底，热重载）
     */
    private final EnricherPromptManager enricherPromptManager;

    /** METADATA 任务的结构化输出 Schema：开放对象（键值不定），仅引导不收紧 */
    private static final ChatRequest.JsonSchemaSpec METADATA_SCHEMA =
            ChatRequest.JsonSchemaSpec.of("chunk_metadata", Map.of("type", "object"));

    public EnricherNode(ObjectMapper objectMapper, LLMService llmService, EnricherPromptManager enricherPromptManager) {
        this.objectMapper = objectMapper;
        this.llmService = llmService;
        this.enricherPromptManager = enricherPromptManager;
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
        if (chunks.size() > MAX_ENRICH_CHUNKS) {
            log.warn("分块数量 {} 超过富化上限 {}，仅富化前 {} 个 chunk", chunks.size(), MAX_ENRICH_CHUNKS, MAX_ENRICH_CHUNKS);
        }
        int processed = 0;
        for (VectorChunk chunk : chunks) {
            if (processed >= MAX_ENRICH_CHUNKS) {
                break;
            }
            if (chunk == null || !StringUtils.hasText(chunk.getContent())) {
                continue;
            }
            processed++;
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
                        : enricherPromptManager.systemPrompt(type);
                String userPrompt = buildUserPrompt(task.getUserPromptTemplate(), chunk, context);
                ChatRequest.ChatRequestBuilder requestBuilder = ChatRequest.builder()
                        .messages(List.of(
                                ChatMessage.system(systemPrompt == null ? "" : systemPrompt),
                                ChatMessage.user(userPrompt)
                        ));
                // METADATA 输出为任意键值 JSON 对象，用开放 schema 引导；KEYWORDS
                // 为顶层 JSON 数组，多数供应商的 response_format 要求对象根节点，保持纯提示词
                if (type == ChunkEnrichType.METADATA) {
                    requestBuilder.jsonSchema(METADATA_SCHEMA);
                }
                String response = chat(requestBuilder.build(), settings.getModelId());
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
        String input = truncateForEnrichment(chunk.getContent());
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

    // 超长 chunk 截断，防止单次富化请求内容过大导致 prompt 超限失败
    private String truncateForEnrichment(String content) {
        if (content == null || content.length() <= MAX_ENRICH_INPUT_CHARS) {
            return content;
        }
        return content.substring(0, MAX_ENRICH_INPUT_CHARS);
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
