package com.byteq.ai.ragstudio.core.chunk.strategy;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.core.chunk.ChunkingMode;
import com.byteq.ai.ragstudio.core.chunk.ChunkingOptions;
import com.byteq.ai.ragstudio.core.chunk.ChunkingStrategy;
import com.byteq.ai.ragstudio.core.chunk.RecursiveOptions;
import com.byteq.ai.ragstudio.core.chunk.VectorChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归分块器
 * <p>
 * 参考 LangChain 的 RecursiveCharacterTextSplitter。
 * 通过多级分隔符从粗到细递归切分，块过大时自动使用更细粒度的分隔符，
 * 确保每个块在目标大小附近，同时尽量保持语义完整性。
 * </p>
 * <p>
 * 算法：
 * <ol>
 *   <li>使用当前分隔符切分文本</li>
 *   <li>如果某一块仍然超过目标大小，用下一个更细的分隔符递归切分</li>
 *   <li>当用尽所有分隔符后，块仍然太大，强制按 chunkSize 切分</li>
 *   <li>最后对每个结果块追加 overlap 内容</li>
 * </ol>
 * </p>
 */
@Component
public class RecursiveTextChunker implements ChunkingStrategy {

    @Override
    public ChunkingMode getType() {
        return ChunkingMode.RECURSIVE;
    }

    @Override
    public List<VectorChunk> chunk(String text, ChunkingOptions config) {
        if (StrUtil.isBlank(text)) return List.of();

        RecursiveOptions opts = (RecursiveOptions) config;
        int overlap = Math.max(0, opts.overlap());

        List<ChunkBuffer> raw = new ArrayList<>();
        recursiveSplit(text, opts.separators(), 0, opts.chunkSize(), raw);

        // 物化为 VectorChunk，追加 overlap
        List<VectorChunk> result = new ArrayList<>();
        String prevTail = null;

        for (int i = 0; i < raw.size(); i++) {
            String body = raw.get(i).text();

            // 拼接上一块尾部的 overlap
            if (overlap > 0 && prevTail != null && !prevTail.isEmpty()) {
                body = prevTail + body;
            }

            result.add(VectorChunk.builder()
                    .content(body)
                    .index(i)
                    .chunkId(IdUtil.getSnowflakeNextIdStr())
                    .build());

            // 计算下一块的 overlap 尾部
            if (overlap > 0) {
                int len = raw.get(i).text().length();
                prevTail = len <= overlap ? raw.get(i).text() : raw.get(i).text().substring(len - overlap);
            }
        }

        return result;
    }

    /**
     * 递归切分核心算法
     *
     * @param text       待切分文本
     * @param separators 分隔符列表
     * @param depth      当前递归深度（使用的分隔符索引）
     * @param chunkSize  目标块大小
     * @param result     结果收集列表
     */
    private void recursiveSplit(String text, List<String> separators, int depth,
                                int chunkSize, List<ChunkBuffer> result) {
        if (StrUtil.isBlank(text)) return;

        // 如果文本已经在目标大小内，直接作为一个块
        if (text.length() <= chunkSize) {
            result.add(new ChunkBuffer(text));
            return;
        }

        // 用尽所有分隔符 → 强制按 chunkSize 切分
        if (depth >= separators.size()) {
            forceSplit(text, chunkSize, result);
            return;
        }

        String sep = separators.get(depth);
        List<String> pieces = splitBySeparator(text, sep);

        // 如果分隔符没切出有效片段（text 中不包含 sep），跳到下一级
        if (pieces.size() <= 1) {
            recursiveSplit(text, separators, depth + 1, chunkSize, result);
            return;
        }

        // 贪心合并：尽可能把小的片段凑到 chunkSize 附近
        List<String> batch = new ArrayList<>();
        int batchLen = 0;

        for (String piece : pieces) {
            if (piece.isEmpty()) continue;

            if (piece.length() > chunkSize) {
                // 当前片段仍然太大 → 先 flush 已有的 batch，再递归切此片段
                flushBatch(batch, batchLen, result);
                batch.clear();
                batchLen = 0;
                recursiveSplit(piece, separators, depth + 1, chunkSize, result);
            } else if (batchLen + piece.length() > chunkSize && !batch.isEmpty()) {
                // 加这个就超了 → flush 已有的，从当前片段重新开始
                flushBatch(batch, batchLen, result);
                batch.clear();
                batch.add(piece);
                batchLen = piece.length();
            } else {
                batch.add(piece);
                batchLen += piece.length();
            }
        }
        flushBatch(batch, batchLen, result);
    }

    /**
     * 强制按固定大小切分（用尽所有分隔符后的兜底）
     */
    private void forceSplit(String text, int chunkSize, List<ChunkBuffer> result) {
        int pos = 0;
        while (pos < text.length()) {
            int end = Math.min(pos + chunkSize, text.length());
            result.add(new ChunkBuffer(text.substring(pos, end)));
            pos = end;
        }
    }

    /**
     * 用指定分隔符切分文本，保留分隔符在片段末尾
     */
    private List<String> splitBySeparator(String text, String sep) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int idx;
        while ((idx = text.indexOf(sep, start)) != -1) {
            String piece = text.substring(start, idx + sep.length()).trim();
            if (!piece.isEmpty()) {
                result.add(piece);
            }
            start = idx + sep.length();
        }
        // 剩余部分
        if (start < text.length()) {
            String remaining = text.substring(start).trim();
            if (!remaining.isEmpty()) {
                result.add(remaining);
            }
        }
        return result;
    }

    /**
     * Flush 当前 batch 为一个 ChunkBuffer
     */
    private void flushBatch(List<String> batch, int batchLen, List<ChunkBuffer> result) {
        if (batch.isEmpty()) return;
        result.add(new ChunkBuffer(String.join("", batch)));
    }

    private record ChunkBuffer(String text) {}
}
