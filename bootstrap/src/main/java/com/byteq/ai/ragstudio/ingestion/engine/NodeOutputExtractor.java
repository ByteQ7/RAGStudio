package com.byteq.ai.ragstudio.ingestion.engine;

import com.byteq.ai.ragstudio.ingestion.domain.context.DocumentSource;
import com.byteq.ai.ragstudio.ingestion.domain.context.IngestionContext;
import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionNodeType;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.NodeConfig;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节点输出提取器
 * 负责从 IngestionContext 中提取特定节点的输出信息
 */
@Component
public class NodeOutputExtractor {

    /**
     * 从上下文中提取指定节点的输出信息
     * <p>
     * 根据节点类型从上下文中提取对应的输出数据，用于记录节点执行日志。
     * 不同节点类型提取不同的输出字段，如 Fetcher 提取来源信息和原始字节，
     * Parser 提取解析文本，Chunker 提取分块结果等。
     * </p>
     *
     * @param context 摄入上下文，包含所有中间数据
     * @param config  当前节点配置
     * @return 节点输出信息的键值对映射
     */
    public Map<String, Object> extract(IngestionContext context, NodeConfig config) {
        if (context == null || config == null) {
            return Map.of();
        }
        IngestionNodeType nodeType = resolveNodeType(config.getNodeType());
        if (nodeType == null) {
            return genericOutput(context);
        }
        return switch (nodeType) {
            case FETCHER -> fetcherOutput(context);
            case PARSER -> parserOutput(context);
            case ENHANCER -> enhancerOutput(context);
            case CHUNKER -> chunkerOutput(context);
            case ENRICHER -> enricherOutput(context);
            case INDEXER -> indexerOutput(context, config);
        };
    }

    // 提取 Fetcher 节点的输出：文档来源信息、MIME 类型和原始字节的 Base64 编码
    private Map<String, Object> fetcherOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        DocumentSource source = context.getSource();
        if (source != null) {
            Map<String, Object> sourceView = new LinkedHashMap<>();
            sourceView.put("type", source.getType() == null ? null : source.getType().getValue());
            sourceView.put("location", source.getLocation());
            sourceView.put("fileName", source.getFileName());
            output.put("source", sourceView);
        }
        output.put("mimeType", context.getMimeType());
        byte[] raw = context.getRawBytes();
        if (raw != null) {
            output.put("rawBytesLength", raw.length);
            output.put("rawBytesBase64", Base64.getEncoder().encodeToString(raw));
        }
        return output;
    }

    // 提取 Parser 节点的输出：MIME 类型、原始文本和结构化文档
    private Map<String, Object> parserOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("mimeType", context.getMimeType());
        output.put("rawText", context.getRawText());
        output.put("document", context.getDocument());
        return output;
    }

    // 提取 Enhancer 节点的输出：增强文本、关键词、问题和元数据
    private Map<String, Object> enhancerOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("enhancedText", context.getEnhancedText());
        output.put("keywords", context.getKeywords());
        output.put("questions", context.getQuestions());
        output.put("metadata", context.getMetadata());
        return output;
    }

    // 提取 Chunker 节点的输出：分块数量和分块列表
    private Map<String, Object> chunkerOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("chunkCount", context.getChunks() == null ? 0 : context.getChunks().size());
        output.put("chunks", context.getChunks());
        return output;
    }

    // 提取 Enricher 节点的输出：分块数量和富化后的分块列表
    private Map<String, Object> enricherOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("chunkCount", context.getChunks() == null ? 0 : context.getChunks().size());
        output.put("chunks", context.getChunks());
        return output;
    }

    // 提取 Indexer 节点的输出：索引配置、分块数量和分块列表
    private Map<String, Object> indexerOutput(IngestionContext context, NodeConfig config) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("settings", config.getSettings());
        output.put("chunkCount", context.getChunks() == null ? 0 : context.getChunks().size());
        output.put("chunks", context.getChunks());
        return output;
    }

    // 未知节点类型的通用输出：提取上下文中所有可用字段
    private Map<String, Object> genericOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("mimeType", context.getMimeType());
        output.put("rawText", context.getRawText());
        output.put("enhancedText", context.getEnhancedText());
        output.put("keywords", context.getKeywords());
        output.put("questions", context.getQuestions());
        output.put("metadata", context.getMetadata());
        output.put("chunks", context.getChunks());
        return output;
    }

    // 将字符串类型的节点标识解析为 IngestionNodeType 枚举
    private IngestionNodeType resolveNodeType(String nodeType) {
        if (nodeType == null || nodeType.isBlank()) {
            return null;
        }
        try {
            return IngestionNodeType.fromValue(nodeType);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
