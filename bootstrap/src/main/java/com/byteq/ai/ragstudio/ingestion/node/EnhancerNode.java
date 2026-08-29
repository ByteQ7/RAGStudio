package com.byteq.ai.ragstudio.ingestion.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.ingestion.domain.context.IngestionContext;
import lombok.extern.slf4j.Slf4j;
import com.byteq.ai.ragstudio.ingestion.domain.enums.EnhanceType;
import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionNodeType;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.NodeConfig;
import com.byteq.ai.ragstudio.ingestion.domain.result.NodeResult;
import com.byteq.ai.ragstudio.ingestion.domain.settings.EnhancerSettings;
import com.byteq.ai.ragstudio.ingestion.prompt.EnhancerPromptManager;
import com.byteq.ai.ragstudio.ingestion.util.JsonResponseParser;
import com.byteq.ai.ragstudio.ingestion.util.PromptTemplateRenderer;
import com.byteq.ai.ragstudio.infra.chat.LLMService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档增强节点（EnhancerNode）
 * <p>
 * 数据摄入流水线的增强处理节点，负责对整个文档级别的文本进行 AI 驱动的增强处理。
 * 与 {@link EnricherNode} 对每个文本块的处理不同，EnhancerNode 是在文档级别
 * （document-level）进行整体处理的。
 * </p>
 * <p>
 * 核心处理逻辑：
 * <ol>
 *   <li>根据增强任务类型选择输入文本（原始文本或已增强文本）</li>
 *   <li>构造系统提示词和用户提示词，调用大语言模型（LLM）执行增强任务</li>
 *   <li>将 LLM 返回的结果应用到上下文中，更新对应的字段</li>
 * </ol>
 * </p>
 * <p>
 * 支持的增强类型（{@link EnhanceType}）：
 * <ul>
 *   <li>CONTEXT_ENHANCE - 上下文增强：重写或扩写文本以提升语义理解</li>
 *   <li>KEYWORDS - 关键词提取：从文档中提取重要关键词</li>
 *   <li>QUESTIONS - 问题生成：基于文档内容生成可能被问到的问题</li>
 *   <li>METADATA - 元数据提取：提取文档的元数据信息（作者、日期、主题等）</li>
 * </ul>
 * </p>
 */
@Component
@Slf4j
public class EnhancerNode implements IngestionNode {

    /** METADATA 任务的结构化输出 Schema：开放对象（键值不定），仅引导不收紧 */
    private static final ChatRequest.JsonSchemaSpec METADATA_SCHEMA =
            ChatRequest.JsonSchemaSpec.of("doc_metadata", Map.of("type", "object"));

    /**
     * Jackson JSON 对象映射器，用于解析节点配置
     */
    private final ObjectMapper objectMapper;

    /**
     * 大语言模型调用服务，用于执行 AI 增强任务
     */
    private final LLMService llmService;

    /**
     * 文档增强默认提示词管理器（DB 优先、classpath 兜底，热重载）
     */
    private final EnhancerPromptManager enhancerPromptManager;

    public EnhancerNode(ObjectMapper objectMapper, LLMService llmService, EnhancerPromptManager enhancerPromptManager) {
        this.objectMapper = objectMapper;
        this.llmService = llmService;
        this.enhancerPromptManager = enhancerPromptManager;
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.ENHANCER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        EnhancerSettings settings = parseSettings(config.getSettings());
        if (settings.getTasks() == null || settings.getTasks().isEmpty()) {
            return NodeResult.ok("未配置增强任务");
        }
        if (context.getMetadata() == null) {
            context.setMetadata(new HashMap<>());
        }

        for (EnhancerSettings.EnhanceTask task : settings.getTasks()) {
            if (task == null || task.getType() == null) {
                continue;
            }
            EnhanceType type = task.getType();
            String input = resolveInputText(context, type);
            if (!StringUtils.hasText(input)) {
                continue;
            }

            // 超长文本跳过 CONTEXT_ENHANCE 的 LLM 调用，直接以原文作为增强结果
            if (type == EnhanceType.CONTEXT_ENHANCE && input.length() > 10000) {
                log.warn("文本过长（{} 字符），跳过 CONTEXT_ENHANCE LLM 调用，直接使用原始文本", input.length());
                context.setEnhancedText(input);
                continue;
            }

            String systemPrompt = StringUtils.hasText(task.getSystemPrompt())
                    ? task.getSystemPrompt()
                    : enhancerPromptManager.systemPrompt(type);
            String userPrompt = buildUserPrompt(task.getUserPromptTemplate(), input, context);

            ChatRequest.ChatRequestBuilder requestBuilder = ChatRequest.builder()
                    .messages(List.of(
                            ChatMessage.system(systemPrompt == null ? "" : systemPrompt),
                            ChatMessage.user(userPrompt)
                    ));
            // METADATA 输出为任意键值 JSON 对象，用开放 schema 引导；KEYWORDS/QUESTIONS
            // 为顶层 JSON 数组，多数供应商的 response_format 要求对象根节点，保持纯提示词
            if (type == EnhanceType.METADATA) {
                requestBuilder.jsonSchema(METADATA_SCHEMA);
            }
            String response = chat(requestBuilder.build(), settings.getModelId());
            applyTaskResult(context, type, response);
        }

        return NodeResult.ok("增强完成");
    }

    /**
     * 解析节点配置中的 settings JSON 为 EnhancerSettings 对象
     *
     * @param node JSON 配置节点
     * @return 增强设置对象
     */
    private EnhancerSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return EnhancerSettings.builder().tasks(List.of()).build();
        }
        return objectMapper.convertValue(node, EnhancerSettings.class);
    }

    /**
     * 根据增强类型解析输入文本
     * <p>
     * 上下文增强（CONTEXT_ENHANCE）使用原始文本作为输入，
     * 其他类型优先使用已增强文本，其次使用原始文本。
     * </p>
     */
    private String resolveInputText(IngestionContext context, EnhanceType type) {
        if (type == EnhanceType.CONTEXT_ENHANCE) {
            return context.getRawText();
        }
        if (StringUtils.hasText(context.getEnhancedText())) {
            return context.getEnhancedText();
        }
        return context.getRawText();
    }

    /**
     * 构建用户提示词
     * <p>
     * 如果配置了用户提示词模板，则将文本内容和上下文变量注入模板后返回；
     * 否则直接返回文本内容作为提示词。
     * </p>
     *
     * @param template 用户提示词模板（可选）
     * @param input    输入文本
     * @param context  摄入上下文
     * @return 构建后的用户提示词
     */
    private String buildUserPrompt(String template, String input, IngestionContext context) {
        if (!StringUtils.hasText(template)) {
            return input;
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("text", input);
        vars.put("content", input);
        vars.put("mimeType", context.getMimeType());
        vars.put("taskId", context.getTaskId());
        vars.put("pipelineId", context.getPipelineId());
        return PromptTemplateRenderer.render(template, vars);
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

    /**
     * 将 LLM 增强结果应用到上下文中
     *
     * @param context  摄入上下文
     * @param type     增强类型
     * @param response LLM 返回的原始响应字符串
     */
    private void applyTaskResult(IngestionContext context, EnhanceType type, String response) {
        switch (type) {
            case CONTEXT_ENHANCE -> context.setEnhancedText(StringUtils.hasText(response) ? response.trim() : response);
            case KEYWORDS -> context.setKeywords(JsonResponseParser.parseStringList(response));
            case QUESTIONS -> context.setQuestions(JsonResponseParser.parseStringList(response));
            case METADATA -> context.getMetadata().putAll(JsonResponseParser.parseObject(response));
            default -> {
            }
        }
    }
}
