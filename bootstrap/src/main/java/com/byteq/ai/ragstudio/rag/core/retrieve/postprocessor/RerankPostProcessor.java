package com.byteq.ai.ragstudio.rag.core.retrieve.postprocessor;

import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.infra.rerank.RerankService;
import com.byteq.ai.ragstudio.rag.core.retrieve.channel.SearchChannelResult;
import com.byteq.ai.ragstudio.rag.core.retrieve.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Rerank 后置处理器
 * <p>
 * 使用 Rerank 模型对结果进行重排序
 * 这是最后一个处理器，输出最终的 Top-K 结果
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankPostProcessor implements SearchResultPostProcessor {

    private final RerankService rerankService;

    @Override
    public String getName() {
        return "Rerank";
    }

    @Override
    public int getOrder() {
        return 10;  // 最后执行
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;  // 始终启用
    }

    @Override
    @RagTraceNode(name = "重排序", type = "RERANK")
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        if (chunks.isEmpty()) {
            log.info("Chunk 列表为空，跳过 Rerank");
            return chunks;
        }

        // IMAGE chunks 无法参与文本重排序，分离后单独处理
        List<RetrievedChunk> imageChunks = chunks.stream()
                .filter(RetrievedChunk::isImage)
                .collect(Collectors.toList());
        List<RetrievedChunk> textChunks = chunks.stream()
                .filter(c -> !c.isImage())
                .collect(Collectors.toList());

        if (textChunks.isEmpty()) {
            return imageChunks;
        }

        List<RetrievedChunk> rerankedText = rerankService.rerank(
                context.getMainQuestion(), textChunks, context.getTopK());

        // 按原始向量分数降序排列 IMAGE chunk，排在重排序后的文本结果之前
        imageChunks.sort(Comparator.comparingDouble(c -> c.getScore() != null ? -c.getScore() : 0));
        List<RetrievedChunk> merged = new ArrayList<>(imageChunks);
        merged.addAll(rerankedText);
        return merged;
    }
}
