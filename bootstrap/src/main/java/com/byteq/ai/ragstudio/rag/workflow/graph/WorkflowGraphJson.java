package com.byteq.ai.ragstudio.rag.workflow.graph;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工作流图 JSON 编解码
 * <p>
 * 存储于 {@code t_workflow.graph_json}；解析失败返回 null（由调用方按"无图"降级处理）。
 */
public final class WorkflowGraphJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // 宽容解析：未来新增字段时旧代码仍可读取历史 JSON
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private WorkflowGraphJson() {
    }

    /** 解析图 JSON；空串或解析失败返回 null */
    public static WorkflowGraph parse(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return MAPPER.readValue(json, WorkflowGraph.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 序列化图；失败返回 null（不阻断保存，steps 仍可用） */
    public static String toJson(WorkflowGraph graph) {
        if (graph == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(graph);
        } catch (Exception e) {
            return null;
        }
    }
}
