package com.byteq.ai.ragstudio.rag.core.prompt;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * Prompt 规划结果，封装从意图识别阶段选用的基模板信息
 */
@Data
@RequiredArgsConstructor
@AllArgsConstructor
@Builder
public class PromptPlan {

    /**
     * 选用的基模板（单意图且有模板才会有值，否则为 null 表示用默认模板）
     */
    private String baseTemplate;
}
