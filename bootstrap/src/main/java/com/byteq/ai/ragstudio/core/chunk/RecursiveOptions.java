package com.byteq.ai.ragstudio.core.chunk;

import java.util.List;
import java.util.Map;

/**
 * 递归分块配置
 * <p>
 * 通过多级分隔符逐层递归切分，块过大时自动使用更细粒度的分隔符。
 *
 * @param chunkSize  目标块大小（字符数，默认 800）
 * @param overlap    块间重叠字符数（默认 128）
 * @param separators 分隔符优先级列表，从粗到细
 */
public record RecursiveOptions(
        int chunkSize,
        int overlap,
        List<String> separators
) implements ChunkingOptions {

    /** 默认分隔符列表（从粗到细） */
    public static final List<String> DEFAULT_SEPARATORS = List.of(
            "\n\n",  // 段落
            "\n",    // 行
            "。",    // 中文句号
            "！",    // 中文感叹号
            "？",    // 中文问号
            ". ",    // 英文句号+空格
            "，"     // 中文逗号（最后兜底）
    );

    public RecursiveOptions {
        if (separators == null || separators.isEmpty()) {
            separators = DEFAULT_SEPARATORS;
        }
    }

    @Override
    public Map<String, Integer> toConfigMap() {
        return Map.of("chunkSize", chunkSize, "overlapSize", overlap);
    }
}
