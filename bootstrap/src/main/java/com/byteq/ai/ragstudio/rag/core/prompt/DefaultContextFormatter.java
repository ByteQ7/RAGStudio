package com.byteq.ai.ragstudio.rag.core.prompt;

import cn.hutool.core.collection.CollUtil;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.byteq.ai.ragstudio.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;

/**
 * 上下文格式化器默认实现
 * <p>
 * 使用 {@link PromptTemplateLoader} 渲染模板 section，将知识库检索结果和 MCP 工具调用结果
 * 格式化为可嵌入 Prompt 的结构化文本，成功结果与错误信息分开处理。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class DefaultContextFormatter implements ContextFormatter {

    private final PromptTemplateLoader templateLoader;

    /**
     * 按 topK 限制收集检索文档块，拼接文本后使用 kb-section 模板渲染
     */
    @Override
    public String formatKbContext(Map<String, List<RetrievedChunk>> chunksByKey, int topK) {
        if (chunksByKey == null || chunksByKey.isEmpty()) {
            return "";
        }

        int limit = topK > 0 ? topK : Integer.MAX_VALUE;
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

        String body = chunks.stream()
                .map(RetrievedChunk::getText)
                .collect(Collectors.joining("\n"));
        return renderKbSection("", body);
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
