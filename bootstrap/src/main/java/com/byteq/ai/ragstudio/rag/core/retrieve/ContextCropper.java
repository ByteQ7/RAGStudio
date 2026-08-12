package com.byteq.ai.ragstudio.rag.core.retrieve;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.infra.highlight.SemanticHighlightClient;
import com.byteq.ai.ragstudio.infra.highlight.SemanticHighlightRequest;
import com.byteq.ai.ragstudio.infra.highlight.SemanticHighlightResponse;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 上下文裁剪器
 * <p>
 * 在检索 Chunk 之后、发给 LLM 之前，用语义高亮模型对每个 Chunk 逐句打分，
 * 只保留与用户问题相关的句子，裁剪无关内容。
 * 这相当于在 RAG pipeline 中插入一道 context pruning 步骤。
 * </p>
 * <p>
 * 性能优化（CPU-only 环境，模型推理约 1.9s/20 句/1 chunk）：
 * <ul>
 *   <li>阈值跳过：参与裁剪文本总量过小时直接保原文，省固定推理成本</li>
 *   <li>句数上限：每条 chunk 只裁前 N 句，长 chunk 尾部句子贡献边际递减</li>
 *   <li>Redis 缓存：同一(问题, chunk)的裁剪结果幂等，追问/重复提问直接命中</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class ContextCropper {

    private static final double DEFAULT_THRESHOLD = 0.3;

    private static final String CACHE_KEY_PREFIX = "rag:crop:v1:";

    private final SemanticHighlightClient semanticHighlightClient;
    private final SearchChannelProperties searchProperties;
    private final RedissonClient redissonClient;
    /** 裁剪远程调用执行线程池：硬超时后后台任务继续完成并写缓存，不占用检索线程 */
    private final Executor cropExecutor;

    public ContextCropper(SemanticHighlightClient semanticHighlightClient,
                          SearchChannelProperties searchProperties,
                          RedissonClient redissonClient,
                          @Qualifier("cropExecutor") Executor cropExecutor) {
        this.semanticHighlightClient = semanticHighlightClient;
        this.searchProperties = searchProperties;
        this.redissonClient = redissonClient;
        this.cropExecutor = cropExecutor;
    }

    /**
     * 裁剪 Chunk 列表：只保留与问题语义相关的句子
     *
     * @param question 用户问题（建议使用 rewrite 后的 query）
     * @param chunks   待裁剪的 Chunk 列表（直接修改其 text 字段）
     */
    @RagTraceNode(name = "语义裁剪", type = "CROP")
    public void crop(String question, List<RetrievedChunk> chunks) {
        if (!semanticHighlightClient.isEnabled()) {
            log.debug("语义高亮服务未启用，跳过裁剪");
            return;
        }
        if (CollUtil.isEmpty(chunks) || question == null || question.isBlank()) {
            return;
        }

        // 构建裁剪输入（跳过 IMAGE chunk 与空文本），并应用句数上限
        List<CropItem> items = chunks.stream()
                .filter(c -> !c.isImage())
                .filter(c -> StrUtil.isNotBlank(c.getText()))
                .map(c -> new CropItem(c, limitSentences(c.getText())))
                .filter(i -> StrUtil.isNotBlank(i.inputText))
                .toList();
        if (items.isEmpty()) {
            return;
        }

        // 阈值跳过：总量过小时裁剪收益接近 0，直接保原文
        int totalChars = items.stream().mapToInt(i -> i.inputText.length()).sum();
        int minChars = searchProperties.getCrop().getMinChars();
        if (totalChars < minChars) {
            log.debug("语义裁剪跳过（文本过短）: {} chars < {}，保原文", totalChars, minChars);
            return;
        }

        // 查询缓存（幂等结果：f(question, chunkId)），未命中的才请求 Python 服务
        boolean cacheEnabled = searchProperties.getCrop().isCacheEnabled();
        String questionHash = SecureUtil.sha1(question);
        for (CropItem item : items) {
            String cached = cacheEnabled ? readCache(questionHash, item.chunk.getId()) : null;
            if (cached != null) {
                item.cropped = cached;
            }
        }

        List<CropItem> misses = items.stream().filter(i -> i.cropped == null).toList();
        if (misses.isEmpty()) {
            applyResults(items);
            log.debug("语义裁剪全部命中缓存: {} chunks", items.size());
            return;
        }

        List<SemanticHighlightRequest.ChunkItem> chunkItems = misses.stream()
                .map(i -> SemanticHighlightRequest.ChunkItem.builder()
                        .id(i.chunk.getId())
                        .text(i.inputText)
                        .build())
                .toList();

        long timeoutMs = searchProperties.getCrop().getTimeoutMs();
        if (timeoutMs > 0) {
            // 硬超时保护：异步调用远程语义服务，超时直接保留原文返回；
            // 后台任务完成后仅写缓存（不修改 chunk 文本，避免与主流程数据竞争）
            CompletableFuture<SemanticHighlightResponse> future;
            try {
                future = CompletableFuture.supplyAsync(
                        () -> semanticHighlightClient.highlight(question, chunkItems, DEFAULT_THRESHOLD),
                        cropExecutor);
            } catch (RejectedExecutionException e) {
                // 专用线程池繁忙（AbortPolicy）：本次直接降级保留原文，不让调用线程代跑阻塞任务
                log.warn("语义裁剪线程池繁忙，本次保留原文: {}", e.getMessage());
                return;
            }
            SemanticHighlightResponse response;
            try {
                response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("语义裁剪超时（{}ms），本次保留原文，后台完成结果将写入缓存: chunks={}",
                        timeoutMs, chunkItems.size());
                future.thenAccept(resp -> writeMissCacheQuietly(questionHash, misses, resp));
                return;
            } catch (Exception e) {
                log.warn("语义裁剪失败，保留原文: {}", e.getMessage());
                return;
            }
            applyHighlightResponse(questionHash, misses, response);
            applyResults(items);
            return;
        }

        // 无超时配置：保持原有同步调用语义
        SemanticHighlightResponse response;
        try {
            response = semanticHighlightClient.highlight(question, chunkItems, DEFAULT_THRESHOLD);
        } catch (Exception e) {
            log.warn("语义裁剪失败，保留原文: {}", e.getMessage());
            return;
        }

        applyHighlightResponse(questionHash, misses, response);
        applyResults(items);
    }

    /**
     * 解析语义服务响应：填充未命中条目的裁剪结果并写缓存（线程安全：只读写局部 CropItem）
     */
    private void applyHighlightResponse(String questionHash, List<CropItem> misses,
                                        SemanticHighlightResponse response) {
        if (response == null || CollUtil.isEmpty(response.getResults())) {
            return;
        }

        for (SemanticHighlightResponse.ChunkHighlightResult result : response.getResults()) {
            if (result == null) continue;
            List<Integer> highlightedIndices = result.getHighlightedIndices();
            List<String> sentences = result.getSentences();

            if (CollUtil.isEmpty(highlightedIndices) || CollUtil.isEmpty(sentences)) {
                continue;
            }

            // 只保留分数 >= threshold 的句子
            String cropped = highlightedIndices.stream()
                    .filter(idx -> idx >= 0 && idx < sentences.size())
                    .map(sentences::get)
                    .collect(Collectors.joining());

            if (cropped.isBlank()) {
                continue;  // 裁剪后为空则保留原文
            }

            for (CropItem item : misses) {
                if (item.chunk.getId() != null && item.chunk.getId().equals(result.getChunkId())) {
                    item.cropped = cropped;
                    break;
                }
            }
        }

        // 写缓存（仅未命中的条目）
        if (searchProperties.getCrop().isCacheEnabled()) {
            for (CropItem item : misses) {
                if (item.cropped != null) {
                    writeCache(questionHash, item.chunk.getId(), item.cropped);
                }
            }
        }
    }

    /**
     * 超时路径的后台补写缓存：仅将服务结果写入 Redis，不修改 chunk 文本。
     * 独立于 {@link #applyHighlightResponse} 的可选调用路径，异常全部静默。
     */
    private void writeMissCacheQuietly(String questionHash, List<CropItem> misses,
                                       SemanticHighlightResponse response) {
        try {
            if (!searchProperties.getCrop().isCacheEnabled()
                    || response == null || CollUtil.isEmpty(response.getResults())) {
                return;
            }
            for (SemanticHighlightResponse.ChunkHighlightResult result : response.getResults()) {
                if (result == null) continue;
                List<Integer> highlightedIndices = result.getHighlightedIndices();
                List<String> sentences = result.getSentences();
                if (CollUtil.isEmpty(highlightedIndices) || CollUtil.isEmpty(sentences)) {
                    continue;
                }
                String cropped = highlightedIndices.stream()
                        .filter(idx -> idx >= 0 && idx < sentences.size())
                        .map(sentences::get)
                        .collect(Collectors.joining());
                if (cropped.isBlank()) {
                    continue;
                }
                for (CropItem item : misses) {
                    if (item.chunk.getId() != null && item.chunk.getId().equals(result.getChunkId())) {
                        writeCache(questionHash, item.chunk.getId(), cropped);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("语义裁剪超时后台写缓存失败，忽略: {}", e.getMessage());
        }
    }

    // 将裁剪结果（命中缓存或 Python 返回）替换回 chunk 文本
    private void applyResults(List<CropItem> items) {
        for (CropItem item : items) {
            if (item.cropped == null || item.cropped.isBlank()) {
                continue;
            }
            log.debug("裁剪 Chunk {}: {} 句 → 裁剪后 {} chars", item.chunk.getId(), item.sentences, item.cropped.length());
            item.chunk.setText(item.cropped);
        }
    }

    // 句数上限：按中英文句号/换行切句，仅保留前 N 句（N<=0 表示不限制）
    private String limitSentences(String text) {
        int maxSentences = searchProperties.getCrop().getMaxSentencesPerChunk();
        if (maxSentences <= 0) {
            return text;
        }
        List<String> sentences = splitSentences(text);
        if (sentences.size() <= maxSentences) {
            return text;
        }
        return String.join("", sentences.subList(0, maxSentences));
    }

    // 与语义高亮服务分句策略一致的中英文分句（保留分隔符）
    static List<String> splitSentences(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == '.' || c == '\n') {
                String sentence = current.toString().trim();
                if (!sentence.isEmpty()) {
                    result.add(sentence);
                }
                current.setLength(0);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            result.add(tail);
        }
        return result;
    }

    private String readCache(String questionHash, String chunkId) {
        if (StrUtil.isBlank(chunkId)) {
            return null;
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(cacheKey(questionHash, chunkId));
            return bucket.get();
        } catch (Exception e) {
            log.debug("语义裁剪缓存读取失败，降级直调: {}", e.getMessage());
            return null;
        }
    }

    private void writeCache(String questionHash, String chunkId, String cropped) {
        if (StrUtil.isBlank(chunkId)) {
            return;
        }
        try {
            int ttlHours = searchProperties.getCrop().getCacheTtlHours();
            RBucket<String> bucket = redissonClient.getBucket(cacheKey(questionHash, chunkId));
            bucket.set(cropped, ttlHours > 0 ? ttlHours : 6, TimeUnit.HOURS);
        } catch (Exception e) {
            log.debug("语义裁剪缓存写入失败，忽略: {}", e.getMessage());
        }
    }

    private String cacheKey(String questionHash, String chunkId) {
        return CACHE_KEY_PREFIX + questionHash + ":" + chunkId;
    }

    /** 裁剪输入项：chunk + 句数限制后的输入文本 + 裁剪结果 */
    private static class CropItem {
        private final RetrievedChunk chunk;
        private final String inputText;
        private final int sentences;
        private String cropped;

        private CropItem(RetrievedChunk chunk, String inputText) {
            this.chunk = chunk;
            this.inputText = inputText;
            this.sentences = splitSentences(inputText).size();
            this.cropped = null;
        }
    }
}
