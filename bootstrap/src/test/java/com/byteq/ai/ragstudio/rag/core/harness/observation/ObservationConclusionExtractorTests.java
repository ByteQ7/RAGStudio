package com.byteq.ai.ragstudio.rag.core.harness.observation;

import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.convention.DefaultModelService;
import com.byteq.ai.ragstudio.infra.chat.LLMService;
import com.byteq.ai.ragstudio.rag.config.ObservationMaskProperties;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 观察结论提取器测试：成功置 READY、异常/空结果降级 DIGEST、场景键回退 summary。
 */
class ObservationConclusionExtractorTests {

    private static final String LONG_TEXT = "FULL_" + "内容".repeat(400);

    private LLMService llmService;
    private DefaultModelService defaultModelService;
    private PromptTemplateLoader promptTemplateLoader;
    private ObservationMaskProperties properties;
    private ObservationConclusionExtractor extractor;

    @BeforeEach
    void setUp() {
        llmService = Mockito.mock(LLMService.class);
        defaultModelService = Mockito.mock(DefaultModelService.class);
        promptTemplateLoader = Mockito.mock(PromptTemplateLoader.class);
        properties = new ObservationMaskProperties();
        Executor directExecutor = Runnable::run;
        extractor = new ObservationConclusionExtractor(llmService, defaultModelService,
                promptTemplateLoader, properties, directExecutor);
        when(promptTemplateLoader.render(anyString(), anyMap())).thenReturn("prompt");
    }

    private ObservationEntry entry() {
        return new ObservationEntry("obs_1", "call-1", "rag_search",
                Map.of("query", "年假"), LONG_TEXT, 0, true);
    }

    @Test
    void successMarksReadyAndKeepsCitationMarkers() {
        when(defaultModelService.getDefaultModelId("observation_extract")).thenReturn("extract-model");
        when(llmService.chat(any(ChatRequest.class), eq("extract-model")))
                .thenReturn("结论：入职满一年5天 [^chunk_1]");

        ObservationEntry entry = entry();
        extractor.extractAsync(entry, "年假有几天？");

        assertEquals(ObservationEntry.Status.READY, entry.getStatus());
        assertTrue(entry.maskBody().contains("[^chunk_1]"));
        assertTrue(entry.maskBody().startsWith("入职满一年5天"), "应剥离模型误加的'结论：'前缀");
    }

    @Test
    void fallsBackToSummarySceneWhenSceneMissing() {
        when(defaultModelService.getDefaultModelId("observation_extract")).thenReturn(null);
        when(defaultModelService.getDefaultModelId("summary")).thenReturn("summary-model");
        when(llmService.chat(any(ChatRequest.class), eq("summary-model"))).thenReturn("摘要模型结论");

        ObservationEntry entry = entry();
        extractor.extractAsync(entry, "问题");

        assertEquals(ObservationEntry.Status.READY, entry.getStatus());
        assertEquals("摘要模型结论", entry.maskBody());
    }

    @Test
    void llmFailureFallsBackToDigest() {
        when(defaultModelService.getDefaultModelId(anyString())).thenReturn("extract-model");
        when(llmService.chat(any(ChatRequest.class), anyString()))
                .thenThrow(new RuntimeException("boom"));

        ObservationEntry entry = entry();
        extractor.extractAsync(entry, "问题");

        assertEquals(ObservationEntry.Status.DIGEST, entry.getStatus());
        assertTrue(entry.maskBody().contains("中间省略"));
    }

    @Test
    void blankResponseFallsBackToDigest() {
        when(defaultModelService.getDefaultModelId(anyString())).thenReturn("extract-model");
        when(llmService.chat(any(ChatRequest.class), anyString())).thenReturn("   ");

        ObservationEntry entry = entry();
        extractor.extractAsync(entry, "问题");

        assertEquals(ObservationEntry.Status.DIGEST, entry.getStatus());
    }

    @Test
    void oversizeConclusionTruncated() {
        properties.setConclusionMaxChars(100);
        when(defaultModelService.getDefaultModelId(anyString())).thenReturn("extract-model");
        when(llmService.chat(any(ChatRequest.class), anyString())).thenReturn("结".repeat(300));

        ObservationEntry entry = entry();
        extractor.extractAsync(entry, "问题");

        assertEquals(ObservationEntry.Status.READY, entry.getStatus());
        assertEquals(101, entry.maskBody().length());
        assertTrue(entry.maskBody().endsWith("…"));
    }
}
