package com.byteq.ai.ragstudio.rag.core.harness.observation;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.convention.DefaultModelService;
import com.byteq.ai.ragstudio.infra.chat.LLMService;
import com.byteq.ai.ragstudio.rag.config.ObservationMaskProperties;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.byteq.ai.ragstudio.rag.constant.RAGConstant.OBSERVATION_CONCLUSION_PROMPT_PATH;

/**
 * 观察结论提取器（Observation Mask）
 * <p>
 * 工具结果登记后立即异步调用轻量模型（默认场景键 {@code observation_extract}，
 * 缺省回退 {@code summary}）把完整结果压缩为「结论 + 保留引用标记」。
 * 提取与主模型的下一次推理并行，不阻塞 Agent 循环；失败/超时则写入头尾摘要兜底。
 */
@Slf4j
@Component
public class ObservationConclusionExtractor {

    private final LLMService llmService;
    private final DefaultModelService defaultModelService;
    private final PromptTemplateLoader promptTemplateLoader;
    private final ObservationMaskProperties properties;
    private final Executor observationExtractExecutor;

    public ObservationConclusionExtractor(LLMService llmService,
                                          DefaultModelService defaultModelService,
                                          PromptTemplateLoader promptTemplateLoader,
                                          ObservationMaskProperties properties,
                                          @Qualifier("observationExtractExecutor") Executor observationExtractExecutor) {
        this.llmService = llmService;
        this.defaultModelService = defaultModelService;
        this.promptTemplateLoader = promptTemplateLoader;
        this.properties = properties;
        this.observationExtractExecutor = observationExtractExecutor;
    }

    /**
     * 异步提取结论；任何失败都不抛出，保证主循环不受影响
     */
    public void extractAsync(ObservationEntry entry, String question) {
        if (entry == null || entry.getStatus() != ObservationEntry.Status.PENDING) {
            return;
        }
        try {
            CompletableFuture.runAsync(() -> doExtract(entry, question), observationExtractExecutor)
                    .orTimeout(properties.getExtractTimeoutMs(), TimeUnit.MILLISECONDS)
                    .whenComplete((unused, error) -> {
                        if (error != null) {
                            entry.markDigest();
                            boolean timeout = error instanceof TimeoutException
                                    || error.getCause() instanceof TimeoutException;
                            log.debug("观察结论提取未完成，使用头尾摘要兜底: handle={}, tool={}, error={}",
                                    entry.getHandle(), entry.getToolName(),
                                    timeout ? "timeout" : error.getMessage());
                        }
                    });
        } catch (Exception e) {
            entry.markDigest();
            log.debug("观察结论提取任务提交失败，使用头尾摘要兜底: handle={}, error={}",
                    entry.getHandle(), e.getMessage());
        }
    }

    private void doExtract(ObservationEntry entry, String question) {
        if (!entry.getStatus().equals(ObservationEntry.Status.PENDING)) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            String prompt = promptTemplateLoader.render(OBSERVATION_CONCLUSION_PROMPT_PATH, Map.of(
                    "question", StrUtil.blankToDefault(question, "（无）"),
                    "tool_name", StrUtil.blankToDefault(entry.getToolName(), "unknown"),
                    "tool_args", formatArguments(entry),
                    "max_chars", String.valueOf(properties.getConclusionMaxChars()),
                    "observation", entry.getFullText()));
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(ChatMessage.user(prompt)))
                    .temperature(0.2D)
                    .thinkingLevel(0)
                    .build();
            String modelId = resolveModelId();
            String result = StrUtil.isNotBlank(modelId)
                    ? llmService.chat(request, modelId)
                    : llmService.chat(request);
            String conclusion = normalize(result);
            if (StrUtil.isBlank(conclusion)) {
                entry.markDigest();
                return;
            }
            if (entry.markReady(conclusion)) {
                log.info("观察结论提取完成: handle={}, tool={}, chars={}, cost={}ms",
                        entry.getHandle(), entry.getToolName(), conclusion.length(),
                        System.currentTimeMillis() - start);
            }
        } catch (Exception e) {
            entry.markDigest();
            log.debug("观察结论提取失败，使用头尾摘要兜底: handle={}, tool={}, error={}",
                    entry.getHandle(), entry.getToolName(), e.getMessage());
        }
    }

    /** 场景键优先，缺省回退 summary，再回退默认路由（返回 null） */
    private String resolveModelId() {
        String modelId = defaultModelService.getDefaultModelId(properties.getExtractModelScene());
        if (StrUtil.isBlank(modelId)) {
            modelId = defaultModelService.getDefaultModelId("summary");
        }
        return StrUtil.isBlank(modelId) ? null : modelId;
    }

    private String formatArguments(ObservationEntry entry) {
        Map<String, Object> input = entry.getToolInput();
        if (input == null || input.isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : input.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            String value = String.valueOf(e.getValue());
            if (value.length() > 200) {
                value = value.substring(0, 200) + "…";
            }
            sb.append(e.getKey()).append('=').append(value);
        }
        return sb.toString();
    }

    /** 清理模型输出：剥代码围栏与"结论："前缀，超长截断 */
    private String normalize(String raw) {
        String text = StrUtil.trimToEmpty(raw);
        if (text.startsWith("```")) {
            text = text.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "");
        }
        text = text.replaceAll("^(结论|摘要)\\s*[:：]\\s*", "");
        text = StrUtil.trimToEmpty(text);
        int max = properties.getConclusionMaxChars();
        if (text.length() > max) {
            text = text.substring(0, max) + "…";
        }
        return text;
    }
}
