package com.byteq.ai.ragstudio.rag.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定位请求兜底判定测试。
 * <p>
 * 模型偶发只口头询问城市名而漏输出 [LOCATION_REQUEST]，后端需在最终回答命中"询问位置"语义时补发标记。
 */
class LocationRequestFallbackTest {

    @Test
    void detectsAskingForCity() {
        assertTrue(AgentScopeReActExecutor.needsLocationRequest(
                "请提供您所在的城市名称，以便我查询当地的天气预报。"));
        assertTrue(AgentScopeReActExecutor.needsLocationRequest(
                "请告诉我你所在的城市名称。"));
        assertTrue(AgentScopeReActExecutor.needsLocationRequest(
                "但首先，我需要知道您所在的城市位置。"));
        assertTrue(AgentScopeReActExecutor.needsLocationRequest(
                "请问您在哪个城市呢？"));
        assertTrue(AgentScopeReActExecutor.needsLocationRequest(
                "方便告诉我您所在的城市吗？"));
        assertTrue(AgentScopeReActExecutor.needsLocationRequest(
                "需要您的位置信息才能继续。"));
        assertTrue(AgentScopeReActExecutor.needsLocationRequest(
                "您所在的城市是哪里？"));
    }

    @Test
    void ignoresAnswersWithMarker() {
        assertFalse(AgentScopeReActExecutor.needsLocationRequest(
                "请提供您所在的城市名称。\n[LOCATION_REQUEST]"));
    }

    @Test
    void ignoresNormalAnswers() {
        assertFalse(AgentScopeReActExecutor.needsLocationRequest(
                "明天杭州天气为晴转多云，无降水，无需带伞。"));
        assertFalse(AgentScopeReActExecutor.needsLocationRequest(
                "您所在的城市今天天气晴，气温 23℃~30℃。"));
        assertFalse(AgentScopeReActExecutor.needsLocationRequest(
                "北京是中国的首都，也是直辖市。"));
        assertFalse(AgentScopeReActExecutor.needsLocationRequest(
                "我查询了您所在城市的历史天气数据。"));
    }

    @Test
    void ignoresBlankAnswer() {
        assertFalse(AgentScopeReActExecutor.needsLocationRequest(null));
        assertFalse(AgentScopeReActExecutor.needsLocationRequest(""));
        assertFalse(AgentScopeReActExecutor.needsLocationRequest("   "));
    }
}
