package com.byteq.ai.ragstudio.infra.crop;

/**
 * 分句结果：一句文本及其在输入文本中的起止偏移
 *
 * @param text  句子文本（保留原始分隔符）
 * @param start 句子在输入文本中的起始偏移（含）
 * @param end   句子在输入文本中的结束偏移（不含）
 */
public record SplitSentence(String text, int start, int end) {
}