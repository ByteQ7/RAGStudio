package com.byteq.ai.ragstudio.rag.core.prompt;

import cn.hutool.core.collection.CollUtil;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.graph.config.GraphConfigService;
import com.byteq.ai.ragstudio.graph.config.GraphProperties;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.byteq.ai.ragstudio.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;

/**
 * 上下文格式化器默认实现
 * <p>
 * 使用 {@link PromptTemplateLoader} 渲染模板 section，将知识库检索结果和 MCP 工具调用结果
 * 格式化为可嵌入 Prompt 的结构化文本，成功结果与错误信息分开处理。
 * </p>
 * <p>
 * 图谱证据小节：命中图谱通道的 chunk 携带 graph_evidence 元数据，在正文后追加
 * 【图谱关系证据】小节，让 LLM 直接看到跨 chunk 拼接出的关系链（编号与正文 [^chunk_N] 一致）。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class DefaultContextFormatter implements ContextFormatter {

    private final PromptTemplateLoader templateLoader;
    private final GraphProperties graphProperties;
    private final GraphConfigService graphConfigService;

    /**
     * 按 topK 限制收集检索文档块，拼接文本后使用 kb-section 模板渲染
     */
    @Override
    public String formatKbContext(Map<String, List<RetrievedChunk>> chunksByKey, int topK, int citationStartIndex) {
        if (chunksByKey == null || chunksByKey.isEmpty()) {
            return "";
        }

        int limit = topK > 0 ? Math.min(topK, 200) : 50;
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (List<RetrievedChunk> list : chunksByKey.values()) {
            if (CollUtil.isEmpty(list)) {
                continue;
            }
            for (RetrievedChunk chunk : list) {
                chunks.add(chunk);
                if (chunks.size() >= limit) {
                    break;
                }
            }
            if (chunks.size() >= limit) {
                break;
            }
        }
        if (chunks.isEmpty()) {
            return "";
        }

        // 给每个 Chunk 加上 [^chunk_{idx}] 前缀（使用顺序编号 1、2、3... 而非原始 DB ID，
        // 避免长数字 Snowflake ID 导致 LLM 编造/截断引用编号）
        // 编号从 citationStartIndex 起（Agent 多次检索时由调用方传入已累计的 chunk 数），
        // 保证跨多次检索编号全局唯一，引用溯源按位置映射才精确
        final int[] idx = {citationStartIndex};
        Map<String, Integer> chunkIndexById = new HashMap<>();
        String body = chunks.stream()
                .map(chunk -> {
                    String sourceMetadata = formatSourceMetadata(chunk);
                    if (chunk.isImage()) {
                        return sourceMetadata + "[^chunk_" + (++idx[0]) + "] [相关图片已随消息附上]";
                    }
                    chunkIndexById.put(chunk.getId(), idx[0] + 1);
                    return sourceMetadata + "[^chunk_" + (++idx[0]) + "] "
                            + (chunk.getText() != null ? chunk.getText() : "");
                })
                .collect(Collectors.joining("\n"));
        String graphSection = formatGraphEvidence(chunks, chunkIndexById);
        if (!graphSection.isEmpty()) {
            body = body + "\n" + graphSection;
        }
        return renderKbSection("", body);
    }

    /**
     * 渲染图谱关系证据小节：聚合所有命中图谱的 chunk 三元组（跨 chunk 去重），
     * 编号与正文 [^chunk_N] 一致，上限受 rag.graph.retrieval.max-context-triples 约束。
     */
    private String formatGraphEvidence(List<RetrievedChunk> chunks, Map<String, Integer> chunkIndexById) {
        if (!graphConfigService.isEnabled() || !graphConfigService.isRetrievalEnabled()) {
            return "";
        }
        int maxTriples = Math.max(1, graphProperties.getRetrieval().getMaxContextTriples());
        List<String> lines = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RetrievedChunk chunk : chunks) {
            Integer chunkIndex = chunkIndexById.get(chunk.getId());
            if (chunkIndex == null || chunk.getMetadata() == null) {
                continue;
            }
            Object evidenceNode = chunk.getMetadata().get("graph_evidence");
            if (!(evidenceNode instanceof List<?> evidenceList) || evidenceList.isEmpty()) {
                continue;
            }
            for (Object item : evidenceList) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> evidence = (Map<String, Object>) item;
                String source = String.valueOf(evidence.getOrDefault("source", ""));
                String predicate = String.valueOf(evidence.getOrDefault("predicate", ""));
                String target = String.valueOf(evidence.getOrDefault("target", ""));
                String evidenceText = evidence.get("evidence") == null
                        ? "" : String.valueOf(evidence.get("evidence"));
                if (source.isEmpty() || predicate.isEmpty() || target.isEmpty()) {
                    continue;
                }
                String dedupKey = source + "|" + predicate + "|" + target;
                if (!seen.add(dedupKey)) {
                    continue;
                }
                StringBuilder line = new StringBuilder("- [^chunk_")
                        .append(chunkIndex).append("] ")
                        .append(oneLine(source)).append(" →").append(oneLine(predicate)).append("→ ")
                        .append(oneLine(target));
                if (!evidenceText.isEmpty()) {
                    line.append("（").append(oneLine(evidenceText)).append("）");
                }
                lines.add(line.toString());
                if (lines.size() >= maxTriples) {
                    break;
                }
            }
            if (lines.size() >= maxTriples) {
                break;
            }
        }
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【图谱关系证据】\n");
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.substring(0, sb.length() - 1);
    }

    /**
     * 将文档名称、知识库名称和现有文档时间元数据放在 Chunk 正文前，
     * 让 LLM 在多份资料冲突时具备基本的新旧判断依据。
     */
    private String formatSourceMetadata(RetrievedChunk chunk) {
        List<String> parts = new ArrayList<>();
        if (hasText(chunk.getDocName())) {
            parts.add("来源文档：" + oneLine(chunk.getDocName()));
        }
        if (hasText(chunk.getKbName())) {
            parts.add("知识库：" + oneLine(chunk.getKbName()));
        }
        Map<String, Object> metadata = chunk.getMetadata();
        if (metadata != null) {
            addMetadataPart(parts, "上传时间", metadata.get("document_created_at"));
            addMetadataPart(parts, "最后更新时间", metadata.get("document_updated_at"));
            addMetadataPart(parts, "来源类型", metadata.get("source_type"));
        }
        return parts.isEmpty() ? "" : "[" + String.join("；", parts) + "]\n";
    }

    private void addMetadataPart(List<String> parts, String label, Object value) {
        if (value != null && hasText(String.valueOf(value))) {
            parts.add(label + "：" + oneLine(String.valueOf(value)));
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String oneLine(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    /**
     * 将所有工具调用结果展平合并为统一文本，成功结果与失败信息分开展示
     */
    @Override
    public String formatMcpContext(Map<String, List<CallToolResult>> results) {
        if (CollUtil.isEmpty(results)) {
            return "";
        }
        return mergeAllResultsToText(results);
    }

    // ==================== 工具方法 ====================

    // 使用 kb-section 模板渲染知识库检索结果片段
    private String renderKbSection(String snippetSection, String chunksBody) {
        return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "kb-section", Map.of(
                "snippet_section", snippetSection,
                "chunks_body", chunksBody
        ));
    }

    // 将所有工具调用结果按分组展平后合并为统一文本
    private String mergeAllResultsToText(Map<String, List<CallToolResult>> toolResults) {
        List<CallToolResult> allResults = toolResults.values().stream()
                .flatMap(List::stream)
                .toList();
        return mergeResultsToText(allResults);
    }

    /**
     * 将多个 CallToolResult 合并为文本
     */
    private String mergeResultsToText(List<CallToolResult> results) {
        if (CollUtil.isEmpty(results)) {
            return "";
        }

        List<String> successTexts = new ArrayList<>();
        List<String> errorTexts = new ArrayList<>();

        for (CallToolResult result : results) {
            boolean isError = result.isError() != null && result.isError();
            String text = extractTextContent(result);
            if (!isError && text != null) {
                successTexts.add(text);
            } else if (isError && text != null) {
                errorTexts.add("- 工具调用失败: " + text);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String text : successTexts) {
            sb.append(text).append("\n\n");
        }

        if (CollUtil.isNotEmpty(errorTexts)) {
            String errorList = String.join("\n", errorTexts);
            sb.append(templateLoader.renderSection(CONTEXT_FORMAT_PATH, "mcp-error", Map.of("error_list", errorList)));
        }

        return sb.toString().trim();
    }

    // 从 CallToolResult 中提取 TextContent 类型的文本内容，多段文本以换行拼接
    private String extractTextContent(CallToolResult result) {
        if (result == null || result.content() == null) {
            return null;
        }
        List<String> texts = result.content().stream()
                .filter(c -> c instanceof TextContent)
                .map(c -> ((TextContent) c).text())
                .toList();
        return texts.isEmpty() ? null : String.join("\n", texts);
    }
}
