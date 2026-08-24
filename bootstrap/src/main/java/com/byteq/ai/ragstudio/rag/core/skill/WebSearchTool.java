package com.byteq.ai.ragstudio.rag.core.skill;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.rag.core.tool.Tool;
import com.byteq.ai.ragstudio.rag.core.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * 网络搜索引用溯源包装器
 * <p>
 * 装饰器模式包装网络搜索类 SKILL（如 web-search/SearXNG），使搜索结果接入统一引用溯源体系：
 * <ul>
 *   <li>解析搜索结果 JSON，每条结果分配与知识库 Chunk 共用的全局 {@code [^chunk_N]} 编号</li>
 *   <li>结果以 RetrievedChunk(sourceType=WEB) 追加进 Agent 引用收集列表，最终答案中的
 *       {@code [^chunk_N]} 标记可统一映射为带 url/title 的 WEB 引用条目</li>
 *   <li>改写 Observation 文本，为每条结果标注编号、标题与链接，供 LLM 回答时标注引用</li>
 * </ul>
 * 解析失败时原样透传底层结果，不影响既有行为。
 */
@Slf4j
public class WebSearchTool implements Tool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 单次搜索最多进入引用溯源的结果条数，防止 Observation 与引用列表膨胀 */
    private static final int MAX_CITED_RESULTS = 8;

    private final Tool delegate;
    /** 搜索结果收集回调（与知识库 Chunk 共用同一引用列表，保证编号连续） */
    private final Consumer<List<RetrievedChunk>> chunksConsumer;
    /** 引用编号起始偏移提供者（返回已累计的 Chunk 数，保证跨工具编号全局唯一） */
    private final IntSupplier citationStartIndexSupplier;

    public WebSearchTool(Tool delegate,
                         Consumer<List<RetrievedChunk>> chunksConsumer,
                         IntSupplier citationStartIndexSupplier) {
        this.delegate = delegate;
        this.chunksConsumer = chunksConsumer;
        this.citationStartIndexSupplier = citationStartIndexSupplier;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String description() {
        return delegate.description();
    }

    @Override
    public JsonSchema inputSchema() {
        return delegate.inputSchema();
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ToolResult result = delegate.execute(params);
        if (!result.isSuccess() || StrUtil.isBlank(result.getContent())) {
            return result;
        }
        try {
            return attachCitations(result);
        } catch (Exception e) {
            log.warn("网络搜索结果引用化失败，回退原始 Observation: {}", e.getMessage());
            return result;
        }
    }

    /**
     * 解析底层工具返回内容中的搜索结果 JSON，改写 Observation 并收集 WEB 引用条目。
     * 底层内容格式为 SkillTool 拼接的 "HTTP 200 (xxms):\n{json}"，取首个换行后的 JSON 解析。
     */
    private ToolResult attachCitations(ToolResult result) throws Exception {
        String content = result.getContent();
        String json = content.contains("\n") ? content.substring(content.indexOf('\n') + 1) : content;

        JsonNode root = OBJECT_MAPPER.readTree(json);
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return result;
        }

        int startIndex = citationStartIndexSupplier != null ? citationStartIndexSupplier.getAsInt() : 0;
        List<RetrievedChunk> webChunks = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        StringBuilder observation = new StringBuilder();
        observation.append("网络搜索完成，共 ")
                .append(Math.min(results.size(), MAX_CITED_RESULTS))
                .append(" 条结果。回答中引用某条结果时，必须在句末标注对应的 [^chunk_{id}] 编号：\n");

        for (JsonNode item : results) {
            if (webChunks.size() >= MAX_CITED_RESULTS) {
                break;
            }
            String url = item.path("url").asText("");
            String title = item.path("title").asText("");
            String summary = item.path("content").asText("");
            String engine = item.path("engine").asText("");
            if (StrUtil.isBlank(url) || !seenUrls.add(url)) {
                continue;
            }

            int num = startIndex + webChunks.size() + 1;
            observation.append("\n[^chunk_").append(num).append("] 标题：")
                    .append(oneLine(title)).append("\n链接：").append(url);
            if (StrUtil.isNotBlank(summary)) {
                observation.append("\n摘要：").append(oneLine(summary));
            }

            RetrievedChunk chunk = RetrievedChunk.builder()
                    .id(String.valueOf(num))
                    .text(StrUtil.isNotBlank(summary) ? summary : title)
                    .score(item.path("score").isNumber() ? item.path("score").floatValue() : 0f)
                    .docName(oneLine(title))
                    .contentType("TEXT")
                    .sourceType("WEB")
                    .url(url)
                    .title(oneLine(title))
                    .engine(engine)
                    .build();
            webChunks.add(chunk);
        }

        if (webChunks.isEmpty()) {
            return result;
        }
        if (chunksConsumer != null) {
            chunksConsumer.accept(webChunks);
        }
        return ToolResult.success(result.getToolName(), observation.toString().trim());
    }

    private String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
