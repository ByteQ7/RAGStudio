package com.byteq.ai.ragstudio.rag.prompt;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.byteq.ai.ragstudio.rag.prompt.config.PromptConfigService;
import com.byteq.ai.ragstudio.rag.prompt.controller.request.PromptConfigUpdateRequest;
import com.byteq.ai.ragstudio.rag.prompt.dao.entity.PromptConfigDO;
import com.byteq.ai.ragstudio.rag.prompt.dao.mapper.PromptConfigHistoryMapper;
import com.byteq.ai.ragstudio.rag.prompt.dao.mapper.PromptConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PromptConfigService 核心逻辑测试
 * <p>覆盖：classpath 兜底读取、DB 快照优先、启动播种、section 解析、更新版本递增与历史记录。</p>
 */
class PromptConfigServiceTest {

    private PromptConfigMapper promptConfigMapper;
    private PromptConfigHistoryMapper historyMapper;
    private PromptConfigService service;

    @BeforeEach
    void setUp() {
        promptConfigMapper = mock(PromptConfigMapper.class);
        historyMapper = mock(PromptConfigHistoryMapper.class);
        service = new PromptConfigService(promptConfigMapper, historyMapper, new DefaultResourceLoader());
    }

    @Test
    void effectiveContent_fallsBackToClasspath_whenNoDbRow() {
        String content = service.getEffectiveContent("react_system");
        assertNotNull(content);
        assertTrue(content.contains("小码"), "react_system 默认内容应来自 classpath 模板");
    }

    @Test
    void sectionContent_fallsBackToClasspath_whenNoDbRow() {
        String section = service.getSectionContent("agent_reminder", "multi_turn");
        assertNotNull(section);
        assertTrue(section.contains("多轮"), "agent_reminder 的 multi_turn section 应可解析");
    }

    @Test
    void init_seedsAndPrefersDbSnapshot() {
        when(promptConfigMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(promptConfigMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                PromptConfigDO.builder()
                        .id("react_system")
                        .content("DB 自定义内容")
                        .enabled(true)
                        .build()));
        service.init();

        String content = service.getEffectiveContent("react_system");
        assertEquals("DB 自定义内容", content, "DB 快照应优先于 classpath 默认");
    }

    @Test
    void disabledDbRow_fallsBackToClasspath() {
        when(promptConfigMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(promptConfigMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                PromptConfigDO.builder()
                        .id("react_system")
                        .content("已禁用内容")
                        .enabled(false)
                        .build()));
        service.init();

        String content = service.getEffectiveContent("react_system");
        assertFalse(content.contains("已禁用"), "禁用的 DB 记录不应生效，应回退 classpath");
        assertTrue(content.contains("小码"));
    }

    @Test
    void update_incrementsVersionAndRecordsHistory() {
        PromptConfigDO row = PromptConfigDO.builder()
                .id("react_system")
                .name("Agent 主链路 ReAct 系统提示词")
                .content("默认内容")
                .version(1)
                .enabled(true)
                .build();
        when(promptConfigMapper.selectById("react_system")).thenReturn(row);
        when(promptConfigMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        service.scheduledRefresh();

        PromptConfigUpdateRequest req = new PromptConfigUpdateRequest();
        req.setContent("新内容");
        var updated = service.update("react_system", req);

        assertEquals(2, updated.getVersion(), "更新后版本应 +1");
        assertEquals("新内容", updated.getContent());
        // 变更前内容应写入历史（版本 1 对应默认内容）
        org.mockito.ArgumentCaptor<com.byteq.ai.ragstudio.rag.prompt.dao.entity.PromptConfigHistoryDO> captor =
                org.mockito.ArgumentCaptor.forClass(com.byteq.ai.ragstudio.rag.prompt.dao.entity.PromptConfigHistoryDO.class);
        org.mockito.Mockito.verify(historyMapper).insert(captor.capture());
        com.byteq.ai.ragstudio.rag.prompt.dao.entity.PromptConfigHistoryDO h = captor.getValue();
        assertEquals("react_system", h.getPromptId());
        assertEquals(1, h.getVersion());
        assertEquals("默认内容", h.getContent());
    }
}