package com.byteq.ai.ragstudio.ingestion.domain.graph;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 摄入流水线图 JSON 编解码
 * <p>
 * 存储于 {@code t_ingestion_pipeline.graph_json}；解析失败返回 null（调用方按"无图"降级）。
 */
public final class IngestionGraphJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // 宽容解析：未来新增字段时旧代码仍可读取历史 JSON
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private IngestionGraphJson() {
    }

    /** 解析图 JSON；空串或解析失败返回 null */
    public static IngestionGraph parse(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return MAPPER.readValue(json, IngestionGraph.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 序列化图；失败返回 null */
    public static String toJson(IngestionGraph graph) {
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
