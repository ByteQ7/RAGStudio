package com.byteq.ai.ragstudio.rag.core.skill;

import com.byteq.ai.ragstudio.rag.core.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SkillTool 脚本类型位置参数顺序测试。
 * <p>
 * 回归场景：AgentScope ToolCallParam 入参为 HashMap（迭代顺序与声明/JSON 顺序无关），
 * geo-reverse 的 lat/lng 曾按 HashMap 桶顺序传成 lng/lat，导致正确坐标被判为不在中国境内。
 */
class SkillToolTest {

    private final SandboxExecutor sandbox = Mockito.mock(SandboxExecutor.class);

    private SkillTool scriptTool() {
        SkillDefinition def = new SkillDefinition();
        def.setName("geo-reverse");
        def.setType("script");
        def.setSkillDir(Path.of("/tmp/ragstudio-skill-test/geo-reverse"));
        def.setConfig(Map.of("scriptFile", "geo_reverse.py"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("lat", Map.of("type", "string"));
        properties.put("lng", Map.of("type", "string"));
        def.setParameters(Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("lat", "lng")));
        return new SkillTool(def, sandbox, true, true, List.of());
    }

    @Test
    void scriptPositionalArgsFollowDeclaredOrder() {
        when(sandbox.isAvailable()).thenReturn(true);
        when(sandbox.scriptBasePath("geo-reverse")).thenReturn("/scripts");
        when(sandbox.execute(anyString(), anyBoolean(), anyList())).thenReturn(
                SandboxExecutor.SandboxResult.builder()
                        .success(true).output("ok").exitCode(0).durationMs(1).build());

        // HashMap 迭代顺序为 lng -> lat（与 JSON 书写顺序无关）
        Map<String, Object> params = new HashMap<>();
        params.put("lat", "39.9042");
        params.put("lng", "116.4074");

        ToolResult result = scriptTool().execute(params);

        assertTrue(result.isSuccess());
        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(sandbox).execute(command.capture(), anyBoolean(), anyList());
        assertTrue(command.getValue().endsWith("'39.9042' '116.4074'"), command.getValue());
    }
}
