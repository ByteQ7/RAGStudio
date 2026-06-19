package com.byteq.ai.ragstudio.rag.core.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.infra.chat.LLMService;
import com.byteq.ai.ragstudio.infra.util.LLMResponseCleaner;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateLoader;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库相关性判断器
 * <p>
 * 在进入 ReACT Agent 循环之前，判断用户问题与所选知识库是否存在语义关联。
 * 使用轻量 LLM 调用（低 temperature、小 maxTokens），延迟约 200ms。
 * <p>
 * 关联 → 执行知识库检索，结果注入 Agent 上下文。
 * 不关联 → 跳过检索，Agent 上下文中注入"不相干"标记。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbRelevanceChecker {

    private static final String RELEVANCE_PROMPT_PATH = "prompt/kb-relevance-check.st";
    private static final Gson GSON = new Gson();

    private final LLMService llmService;
    private final PromptTemplateLoader templateLoader;

    /**
     * 判断用户问题与所选知识库是否相关
     *
     * @param userQuestion     用户原始问题
     * @param knowledgeBaseIds 所选知识库 ID 列表
     * @param kbInfoProvider   知识库信息提供者（按 ID 获取名称和描述）
     * @return 相关性判断结果
     */
    public RelevanceResult check(String userQuestion,
                                 List<String> knowledgeBaseIds,
                                 KbInfoProvider kbInfoProvider) {
        if (StrUtil.isBlank(userQuestion) || CollUtil.isEmpty(knowledgeBaseIds)) {
            return RelevanceResult.irrelevant("未选择知识库");
        }

        // 构建知识库列表文本
        String kbListText = buildKbListText(knowledgeBaseIds, kbInfoProvider);
        if (StrUtil.isBlank(kbListText)) {
            log.warn("无法获取知识库信息，默认判定为相关");
            return RelevanceResult.relevant("无法获取知识库信息，默认检索");
        }

        // 加载模板并填充占位符
        String template = templateLoader.load(RELEVANCE_PROMPT_PATH);
        String prompt = template
                .replace("{kb_list}", kbListText)
                .replace("{user_question}", userQuestion);

        log.info("相关性判断开始 - question='{}', kbCount={}",
                truncate(userQuestion, 50), knowledgeBaseIds.size());

        String raw = null;
        try {
            // 轻量 LLM 调用
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(ChatMessage.user(prompt)))
                    .temperature(0.0)
                    .maxTokens(100)
                    .thinking(false)
                    .build();
            raw = llmService.chat(request);

            if (StrUtil.isBlank(raw)) {
                log.warn("相关性判断 LLM 返回空，默认判定为相关");
                return RelevanceResult.relevant("LLM 返回空，默认检索");
            }

            // 解析 JSON 响应
            String json = LLMResponseCleaner.stripMarkdownCodeFence(raw.trim());
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            boolean relevant = obj.has("relevant") && obj.get("relevant").getAsBoolean();
            String reasoning = obj.has("reasoning") ? obj.get("reasoning").getAsString() : "";

            log.info("相关性判断结果: relevant={}, reasoning='{}'", relevant, reasoning);

            return relevant
                    ? RelevanceResult.relevant(reasoning)
                    : RelevanceResult.irrelevant(reasoning);

        } catch (JsonSyntaxException e) {
            log.warn("相关性判断 JSON 解析失败: {}，原始响应: {}，默认判定为相关",
                    e.getMessage(), truncate(raw, 100));
            return RelevanceResult.relevant("JSON 解析失败，默认检索");
        } catch (Exception e) {
            log.error("相关性判断异常: {}", e.getMessage());
            return RelevanceResult.relevant("判断异常，默认检索");
        }
    }

    /**
     * 构建知识库列表文本（供 LLM 判断用）
     */
    private String buildKbListText(List<String> kbIds, KbInfoProvider provider) {
        if (CollUtil.isEmpty(kbIds) || provider == null) {
            return "";
        }
        List<KbInfo> infos = provider.getKbInfos(kbIds);
        if (CollUtil.isEmpty(infos)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < infos.size(); i++) {
            KbInfo info = infos.get(i);
            sb.append(i + 1).append(". ").append(info.name());
            if (StrUtil.isNotBlank(info.description())) {
                sb.append(" - ").append(info.description());
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ==================== 支持类型 ====================

    /**
     * 知识库基本信息（名称 + 描述）
     */
    public record KbInfo(String id, String name, String description) {}

    /**
     * 知识库信息提供者接口
     * <p>
     * 调用方实现此接口，按 ID 批量查询知识库的名称和描述。
     */
    @FunctionalInterface
    public interface KbInfoProvider {
        List<KbInfo> getKbInfos(List<String> kbIds);
    }

    /**
     * 相关性判断结果
     */
    public record RelevanceResult(boolean relevant, String reasoning) {
        public static RelevanceResult relevant(String reasoning) {
            return new RelevanceResult(true, reasoning);
        }

        public static RelevanceResult irrelevant(String reasoning) {
            return new RelevanceResult(false, reasoning);
        }
    }
}
