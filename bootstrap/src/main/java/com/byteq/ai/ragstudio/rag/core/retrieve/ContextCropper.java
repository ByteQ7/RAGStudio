package com.byteq.ai.ragstudio.rag.core.retrieve;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.infra.crop.BgeSentenceScorer;
import com.byteq.ai.ragstudio.infra.crop.CodeBlockIsolator;
import com.byteq.ai.ragstudio.infra.crop.HanlpSentenceSplitter;
import com.byteq.ai.ragstudio.infra.crop.SentenceScoring;
import com.byteq.ai.ragstudio.infra.crop.SplitSentence;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import com.byteq.ai.ragstudio.rag.core.retrieve.channel.SearchChannelResult;
import com.byteq.ai.ragstudio.rag.core.retrieve.channel.SearchContext;
import com.byteq.ai.ragstudio.rag.core.retrieve.postprocessor.SearchResultPostProcessor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 上下文裁剪器（句子级 Chunk 裁剪）
 * <p>
 * 在检索 Chunk 之后、Rerank 之前执行：对每个 Chunk 逐句打分，只保留与问题语义相关的句子。
 * 处理流程：
 * 1. 代码块隔离（Markdown 代码块提取为占位符，代码块不参与裁剪）
 * 2. HanLP 分句（剥离代码块后的自然语言文本，异常时回退正则）
 * 3. bge-small-zh-v1.5 进程内批量 Embedding（句子 + 查询）计算余弦相似度
 * 4. 保留相似度 ≥ 阈值的句子，与全部代码块按原始位置重组
 * </p>
 * <p>
 * 性能优化（进程内模型，单句约 10ms）：
 * <ul>
 *   <li>阈值跳过：参与裁剪文本总量过小时直接保原文</li>
 *   <li>句数上限：每条 chunk 只裁前 N 句，长 chunk 尾部句子贡献边际递减</li>
 *   <li>Redis 缓存：同一(问题, chunk)的裁剪结果幂等，追问/重复提问直接命中</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class ContextCropper implements SearchResultPostProcessor {

    private static final String CACHE_KEY_PREFIX = "rag:crop:v2:";

    private final BgeSentenceScorer scorer;
    private final HanlpSentenceSplitter splitter;
    private final CodeBlockIsolator isolator;
    private final SearchChannelProperties searchProperties;
    private final RedissonClient redissonClient;

    public ContextCropper(BgeSentenceScorer scorer,
                          HanlpSentenceSplitter splitter,
                          CodeBlockIsolator isolator,
                          SearchChannelProperties searchProperties,
                          RedissonClient redissonClient) {
        this.scorer = scorer;
        this.splitter = splitter;
        this.isolator = isolator;
        this.searchProperties = searchProperties;
        this.redissonClient = redissonClient;
    }

    @Override
    public String getName() {
        return "SemanticCrop";
    }

    @Override
    public int getOrder() {
        // 在去重(1)之后、Rerank(10)之前执行
        return 5;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return scorer.isEnabled();
    }

    /**
     * 裁剪 Chunk 列表：只保留与问题语义相关的句子（直接修改 chunk 的 text 字段）
     */
    @Override
    @RagTraceNode(name = "语义裁剪", type = "CROP")
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        if (!scorer.isEnabled()) {
            log.debug("语义裁剪服务未启用，跳过裁剪");
            return chunks;
        }
        if (CollUtil.isEmpty(chunks)) {
            return chunks;
        }

        String question = context.getRewrittenQuestion();
        if (question == null || question.isBlank()) {
            return chunks;
        }

        // 构建裁剪输入（跳过 IMAGE chunk 与空文本），并应用句数上限
        List<CropItem> items = chunks.stream()
                .filter(c -> !c.isImage())
                .filter(c -> StrUtil.isNotBlank(c.getText()))
                .map(c -> new CropItem(c, limitSentences(c.getText())))
                .filter(i -> StrUtil.isNotBlank(i.inputText))
                .toList();
        if (items.isEmpty()) {
            return chunks;
        }

        // 阈值跳过：总量过小时裁剪收益接近 0，直接保原文
        int totalChars = items.stream().mapToInt(i -> i.inputText.length()).sum();
        int minChars = searchProperties.getCrop().getMinChars();
        if (totalChars < minChars) {
            log.debug("语义裁剪跳过（文本过短）: {} chars < {}，保原文", totalChars, minChars);
            return chunks;
        }

        // 查询缓存（幂等结果：f(question, chunkId, chunk内容)），未命中的才执行进程内打分
        boolean cacheEnabled = searchProperties.getCrop().isCacheEnabled();
        String questionHash = SecureUtil.sha1(question);
        for (CropItem item : items) {
            String cached = cacheEnabled ? readCache(questionHash, item.chunk.getId(), item.inputText) : null;
            if (cached != null) {
                item.cropped = cached;
            }
        }

        List<CropItem> misses = items.stream().filter(i -> i.cropped == null).toList();
        if (misses.isEmpty()) {
            applyResults(items);
            log.debug("语义裁剪全部命中缓存: {} chunks", items.size());
            return chunks;
        }

        double threshold = searchProperties.getCrop().getThreshold();
        for (CropItem item : misses) {
            try {
                item.cropped = cropSingle(question, item.inputText, threshold);
            } catch (Exception e) {
                log.warn("单个 Chunk 裁剪失败，保留原文: id={}, err={}", item.chunk.getId(), e.getMessage());
            }
            if (cacheEnabled && item.cropped != null && !item.cropped.isBlank()) {
                writeCache(questionHash, item.chunk.getId(), item.cropped, item.inputText);
            }
        }

        applyResults(items);
        return chunks;
    }

    /** 单 Chunk 裁剪：隔离代码块 → HanLP 分句 → 打分过滤 → 按位置重组 */
    private String cropSingle(String question, String inputText, double threshold) {
        CodeBlockIsolator.IsolationResult iso = isolator.isolate(inputText);

        List<SplitSentence> sentences = splitter.split(iso.placeholderText());
        if (sentences.isEmpty()) {
            return inputText;
        }

        List<String> sentenceTexts = sentences.stream().map(SplitSentence::text).toList();
        SentenceScoring scoring = scorer.score(question, sentenceTexts, threshold);
        if (scoring.highlightedIndices().length == 0) {
            return inputText; // 全部被过滤则保留原文
        }

        List<SplitSentence> kept = new ArrayList<>();
        for (int idx : scoring.highlightedIndices()) {
            if (idx >= 0 && idx < sentences.size()) {
                kept.add(sentences.get(idx));
            }
        }
        if (kept.isEmpty()) {
            return inputText;
        }

        return isolator.restore(iso, kept);
    }

    // 将裁剪结果（命中缓存或进程内打分返回）替换回 chunk 文本
    private void applyResults(List<CropItem> items) {
        for (CropItem item : items) {
            if (item.cropped == null || item.cropped.isBlank()) {
                continue;
            }
            log.debug("裁剪 Chunk {}: 输入 {} chars → 裁剪后 {} chars", item.chunk.getId(), item.inputText.length(), item.cropped.length());
            item.chunk.setText(item.cropped);
        }
    }

    // 句数上限：按 HanLP 分句仅保留前 N 句（N<=0 表示不限制）
    private String limitSentences(String text) {
        int maxSentences = searchProperties.getCrop().getMaxSentencesPerChunk();
        if (maxSentences <= 0) {
            return text;
        }
        List<SplitSentence> sentences = splitter.split(text);
        if (sentences.size() <= maxSentences) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxSentences; i++) {
            sb.append(sentences.get(i).text());
        }
        return sb.toString();
    }

    private String readCache(String questionHash, String chunkId, String inputText) {
        if (StrUtil.isBlank(chunkId)) {
            return null;
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(cacheKey(questionHash, chunkId, inputText));
            return bucket.get();
        } catch (Exception e) {
            log.debug("语义裁剪缓存读取失败，降级直算: {}", e.getMessage());
            return null;
        }
    }

    private void writeCache(String questionHash, String chunkId, String cropped, String inputText) {
        if (StrUtil.isBlank(chunkId)) {
            return;
        }
        try {
            int ttlHours = searchProperties.getCrop().getCacheTtlHours();
            RBucket<String> bucket = redissonClient.getBucket(cacheKey(questionHash, chunkId, inputText));
            bucket.set(cropped, ttlHours > 0 ? ttlHours : 6, TimeUnit.HOURS);
        } catch (Exception e) {
            log.debug("语义裁剪缓存写入失败，忽略: {}", e.getMessage());
        }
    }

    private String cacheKey(String questionHash, String chunkId, String inputText) {
        return CACHE_KEY_PREFIX + questionHash + ":" + chunkId + ":" + SecureUtil.sha1(inputText);
    }

    /** 裁剪输入项：chunk + 句数限制后的输入文本 + 裁剪结果 */
    private static class CropItem {
        private final RetrievedChunk chunk;
        private final String inputText;
        private String cropped;

        private CropItem(RetrievedChunk chunk, String inputText) {
            this.chunk = chunk;
            this.inputText = inputText;
            this.cropped = null;
        }
    }
}