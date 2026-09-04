package com.byteq.ai.ragstudio.rag.core.rewrite;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.byteq.ai.ragstudio.infra.util.LLMResponseCleaner;
import com.byteq.ai.ragstudio.rag.config.RAGConfigProperties;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.infra.chat.LLMService;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.byteq.ai.ragstudio.rag.constant.RAGConstant.QUERY_REWRITE_AND_SPLIT_PROMPT_PATH;

/**
 * 查询预处理：改写 + 拆分多问句
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiQuestionRewriteService implements QueryRewriteService {

    private final LLMService llmService;
    private final RAGConfigProperties ragConfigProperties;
    private final QueryTermMappingService queryTermMappingService;
    private final PromptTemplateLoader promptTemplateLoader;

    /**
     * 将用户问题改写为适合检索的查询（不拆分多问句）
     *
     * @param userQuestion 原始用户问题
     * @return 改写后的查询文本
     */
    @Override
    @RagTraceNode(name = "query-rewrite", type = "REWRITE")
    public String rewrite(String userQuestion) {
        return rewriteAndSplit(userQuestion).rewrittenQuestion();
    }

    /**
     * 改写用户问题并拆分为多个子问题（无历史上下文）
     *
     * @param userQuestion 原始用户问题
     * @return 改写结果，包含改写后的问题和子问题列表
     */
    @Override
    public RewriteResult rewriteWithSplit(String userQuestion) {
        return rewriteAndSplit(userQuestion);
    }

    /**
     * 改写用户问题并拆分为多个子问题，结合会话历史理解上下文
     *
     * @param userQuestion 原始用户问题
     * @param history      会话历史消息列表
     * @return 改写结果，包含改写后的问题和子问题列表
     */
    @Override
    @RagTraceNode(name = "query-rewrite-and-split", type = "REWRITE")
    public RewriteResult rewriteWithSplit(String userQuestion, List<ChatMessage> history) {
        return rewriteWithSplit(userQuestion, history, null);
    }

    /**
     * 改写用户问题并拆分子问题，支持会话历史和知识库 ID 过滤
     * <p>
     * 流程：术语归一化 -> LLM 改写+拆分（若开关开启）-> 失败时降级为归一化问题
     *
     * @param userQuestion     原始用户问题
     * @param history          会话历史消息列表
     * @param knowledgeBaseIds 用户选定的知识库 ID 列表，用于过滤术语映射规则
     * @return 改写结果，包含改写后的问题和子问题列表
     */
    @Override
    @RagTraceNode(name = "query-rewrite-and-split-with-kb", type = "REWRITE")
    public RewriteResult rewriteWithSplit(String userQuestion, List<ChatMessage> history, List<String> knowledgeBaseIds) {
        if (!ragConfigProperties.getQueryRewriteEnabled()) {
            String normalized = queryTermMappingService.normalize(userQuestion, knowledgeBaseIds);
            List<String> subs = ruleBasedSplit(normalized);
            return new RewriteResult(normalized, subs);
        }

        String normalizedQuestion = queryTermMappingService.normalize(userQuestion, knowledgeBaseIds);

        // 简单问题跳过 LLM 改写：无历史 + 短问题 + 无指代/追问意图，
        // 规则归一化 + 规则拆分已足够，省一次固定 LLM 往返
        if (shouldSkipLlmRewrite(userQuestion, history)) {
            log.info("查询改写跳过 LLM（简单问题）: question={}", userQuestion);
            return new RewriteResult(normalizedQuestion, ruleBasedSplit(normalizedQuestion));
        }

        return completeFollowUpIfNeeded(callLLMRewriteAndSplit(normalizedQuestion, userQuestion, history),
                userQuestion, history);
    }

    /**
     * 先用默认改写做归一化，再进行多问句拆分。
     */
    private RewriteResult rewriteAndSplit(String userQuestion) {
        // 开关关闭：直接做规则归一化 + 规则拆分
        if (!ragConfigProperties.getQueryRewriteEnabled()) {
            String normalized = queryTermMappingService.normalize(userQuestion);
            List<String> subs = ruleBasedSplit(normalized);
            return new RewriteResult(normalized, subs);
        }

        String normalizedQuestion = queryTermMappingService.normalize(userQuestion);

        return callLLMRewriteAndSplit(normalizedQuestion, userQuestion, List.of());

        // 兜底：使用归一化结果 + 规则拆分
    }

    /** 简单问题最大长度：超过该长度认为需要 LLM 改写（长问题含多意图、复杂限定词概率高） */
    private static final int SIMPLE_QUESTION_MAX_LEN = 30;

    /** 指代/追问/并列意图词：命中任一即认为需要 LLM 改写（规则拆分无法处理指代消解与语义扩展） */
    private static final String[] REFERENCE_WORDS = {
            "这个", "这些", "那个", "那些", "上述", "以上", "它们", "其中", "分别",
            "继续", "再试", "另外", "其他", "还有", "相比", "区别"
    };

    /**
     * 判断简单问题是否可跳过 LLM 改写：
     * <ul>
     *   <li>无对话历史（首轮问题）——多轮指代消解由 LLM 完成，历史非空时必须走 LLM</li>
     *   <li>问题较短（≤ {@value #SIMPLE_QUESTION_MAX_LEN} 字）</li>
     *   <li>不含指代/追问/并列意图词（规则拆分与归一化可覆盖）</li>
     * </ul>
     */
    private boolean shouldSkipLlmRewrite(String userQuestion, List<ChatMessage> history) {
        if (!Boolean.TRUE.equals(ragConfigProperties.getQueryRewriteSkipSimple())) {
            return false;
        }
        // 仅 USER/ASSISTANT 历史视为"有多轮上下文"：
        // SYSTEM 消息（会话摘要、对话分组指令）会常驻历史头部，若计入判空，
        // 首轮简单问题将永远无法命中跳过改写快路径，多付一次 LLM 调用
        if (history != null && history.stream()
                .anyMatch(msg -> msg.getRole() != ChatMessage.Role.SYSTEM)) {
            return false;
        }
        if (StrUtil.isBlank(userQuestion) || userQuestion.length() > SIMPLE_QUESTION_MAX_LEN) {
            return false;
        }
        if (FollowUpQueryUtil.isWeakFollowUp(userQuestion)) {
            return false;
        }
        for (String word : REFERENCE_WORDS) {
            if (userQuestion.contains(word)) {
                return false;
            }
        }
        return true;
    }

    // 调用 LLM 执行查询改写和多问句拆分，失败时使用归一化问题作为兜底
    private RewriteResult callLLMRewriteAndSplit(String normalizedQuestion,
                                                 String originalQuestion,
                                                 List<ChatMessage> history) {        String systemPrompt = promptTemplateLoader.load(QUERY_REWRITE_AND_SPLIT_PROMPT_PATH);
        ChatRequest req = buildRewriteRequest(systemPrompt, normalizedQuestion, history);

        try {
            String raw = llmService.chat(req);
            RewriteResult parsed = parseRewriteAndSplit(raw);

            if (parsed != null) {
                log.info("""
                        RAG用户问题查询改写+拆分：
                        原始问题：{}
                        归一化后：{}
                        改写结果：{}
                        子问题：{}
                        """, originalQuestion, normalizedQuestion, parsed.rewrittenQuestion(), parsed.subQuestions());
                return parsed;
            }

            log.warn("查询改写+拆分解析失败，使用归一化问题兜底 - normalizedQuestion={}", normalizedQuestion);
        } catch (Exception e) {
            log.warn("查询改写+拆分 LLM 调用失败，使用归一化问题兜底 - question={}，normalizedQuestion={}", originalQuestion, normalizedQuestion, e);
        }

        // 统一兜底逻辑
        return new RewriteResult(normalizedQuestion, List.of(normalizedQuestion));
    }

    /**
     * 上下文补全兜底：LLM 改写结果仍是弱追问短语（如"再试试呢"、"继续"）时，
     * 取最近一轮用户问题作为完整检索查询，保证检索不落入字面追问词。
     */
    private RewriteResult completeFollowUpIfNeeded(RewriteResult result,
                                                   String userQuestion,
                                                   List<ChatMessage> history) {
        if (result == null) {
            return null;
        }
        if (!FollowUpQueryUtil.isWeakFollowUp(result.rewrittenQuestion())) {
            return result;
        }
        String base = findLastUserQuestion(history);
        if (StrUtil.isBlank(base)) {
            return result;
        }
        log.info("改写结果仍为弱追问短语'{}'，使用历史上一轮用户问题补全: '{}'",
                result.rewrittenQuestion(), base);
        return new RewriteResult(base, List.of(base));
    }

    // 从历史中提取最近一轮（除当前问题外）的纯用户问题
    private String findLastUserQuestion(List<ChatMessage> history) {
        if (CollUtil.isEmpty(history)) {
            return null;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage msg = history.get(i);
            if (msg.getRole() != ChatMessage.Role.USER) {
                continue;
            }
            String content = msg.getContent() != null ? msg.getContent().trim() : "";
            if (StrUtil.isBlank(content)) {
                continue;
            }
            if (content.startsWith("Observation:") || content.startsWith("{\"query\"")
                    || content.contains("[^chunk_")) {
                continue;
            }
            return content;
        }
        return null;
    }

    // 构建查询改写的 LLM 请求，组装系统提示词、最近两轮对话历史和用户问题
    private ChatRequest buildRewriteRequest(String systemPrompt,
                                            String question,
                                            List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(ChatMessage.system(systemPrompt));
        }

        // 只保留最近 1-2 轮的 User 和 Assistant 消息
        // 过滤掉 System 摘要，避免 Token 浪费
        if (CollUtil.isNotEmpty(history)) {
            // 先过滤再截取：skip 必须基于过滤后的数量计算，
            // 否则 Agent 模式历史中混入大量 Observation/工具消息时会把最近对话整体丢弃
            List<ChatMessage> filteredHistory = history.stream()
                    .filter(msg -> msg.getRole() == ChatMessage.Role.USER
                            || msg.getRole() == ChatMessage.Role.ASSISTANT)
                    .toList();
            List<ChatMessage> recentHistory = filteredHistory.stream()
                    .skip(Math.max(0, filteredHistory.size() - 4))  // 最多保留最近 4 条消息（2 轮对话）
                    .toList();
            messages.addAll(recentHistory);
        }

        messages.add(ChatMessage.user(question));

        return ChatRequest.builder()
                .messages(messages)
                .temperature(0.4D)
                .topP(0.3D)
                .thinkingLevel(0)
                .jsonSchema(REWRITE_SCHEMA)
                .build();
    }

    /** 查询改写结构化输出 Schema（与 parseRewriteAndSplit 的解析字段对应） */
    private static final ChatRequest.JsonSchemaSpec REWRITE_SCHEMA = ChatRequest.JsonSchemaSpec.strict(
            "query_rewrite",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "rewrite", Map.of("type", "string"),
                            "sub_questions", Map.of(
                                    "type", "array",
                                    "items", Map.of("type", "string"))),
                    "required", List.of("rewrite", "sub_questions"),
                    "additionalProperties", false));


    // 解析 LLM 返回的 JSON 结果，提取改写后的问题和子问题列表
    private RewriteResult parseRewriteAndSplit(String raw) {
        try {
            // 移除可能存在的 Markdown 代码块标记
            String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
            // 从可能包含自然语言解释的响应中提取 JSON 对象
            cleaned = LLMResponseCleaner.extractJson(cleaned);

            JsonElement root = JsonParser.parseString(cleaned);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject obj = root.getAsJsonObject();
            String rewrite = obj.has("rewrite") ? obj.get("rewrite").getAsString().trim() : "";
            List<String> subs = new ArrayList<>();
            if (obj.has("sub_questions") && obj.get("sub_questions").isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray("sub_questions");
                for (JsonElement el : arr) {
                    if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                        String s = el.getAsString().trim();
                        if (StrUtil.isNotBlank(s)) {
                            subs.add(s);
                        }
                    }
                }
            }
            if (StrUtil.isBlank(rewrite)) {
                return null;
            }
            if (CollUtil.isEmpty(subs)) {
                subs = List.of(rewrite);
            }
            return new RewriteResult(rewrite, subs);
        } catch (Exception e) {
            log.warn("解析改写+拆分结果失败，raw={}", raw, e);
            return null;
        }
    }

    // 基于规则的多问句拆分：按常见标点分隔符拆分，并为每个子句补全问号
    private List<String> ruleBasedSplit(String question) {
        // 兜底：按常见分隔符拆分
        List<String> parts = Arrays.stream(question.split("[?？。；;\\n]+"))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());

        if (CollUtil.isEmpty(parts)) {
            return List.of(question);
        }
        return parts.stream()
                .map(s -> s.endsWith("？") || s.endsWith("?") ? s : s + "？")
                .toList();
    }
}
