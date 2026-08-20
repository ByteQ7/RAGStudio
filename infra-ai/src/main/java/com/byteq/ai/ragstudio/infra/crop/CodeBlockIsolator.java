package com.byteq.ai.ragstudio.infra.crop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Markdown 代码块隔离器
 * <p>
 * 将 Markdown 代码块（```...```）从文本中提取出来，替换为唯一占位符（__INLINECODETWO{n}__），
 * 记录其原始位置；裁剪后按原始位置将保留的句子与全部代码块重组。
 * 代码块不参与语义裁剪，确保技术内容完整、不被拆分、不被过滤。
 * </p>
 */
@Component
public class CodeBlockIsolator {

    public static final String PLACEHOLDER_PREFIX = "__INLINECODETWO";
    public static final String PLACEHOLDER_SUFFIX = "__";

    /** 匹配围栏代码块：```lang\n...\n```（DOTALL 跨行） */
    private static final Pattern FENCE_BLOCK = Pattern.compile("```[ \\t]*([\\w+.-]*)[ \\t]*\\r?\\n(.*?)```", Pattern.DOTALL);
    /** 匹配 4 空格缩进代码块（行首 4 空格或 tab） */
    private static final Pattern INDENT_BLOCK = Pattern.compile("(?m)(^ {4,}.*(?:\\r?\\n {4,}.*)*)");

    /** 单个代码块：索引、内容、在占位文本中的起止位置 */
    public record CodeBlock(int index, String content, int placeholderStart, int placeholderEnd) {}

    /** 隔离结果：占位文本 + 代码块列表 + 按占位索引的映射 */
    public record IsolationResult(String placeholderText, List<CodeBlock> blocks, Map<Integer, CodeBlock> blockByPlaceholderIndex) {

        public CodeBlock blockByIndex(int placeholderIndex) {
            return blockByPlaceholderIndex.get(placeholderIndex);
        }
    }

    /**
     * 提取代码块并替换为占位符
     *
     * @param text 原始文本（Markdown）
     * @return 隔离结果
     */
    public IsolationResult isolate(String text) {
        List<CodeBlock> blocks = new ArrayList<>();
        String placeholderText = text;

        // 围栏代码块优先处理
        Matcher fenceMatcher = FENCE_BLOCK.matcher(placeholderText);
        StringBuffer sb = new StringBuffer();
        int index = 0;
        while (fenceMatcher.find()) {
            String content = fenceMatcher.group(2).stripTrailing();
            int start = fenceMatcher.start();
            int end = fenceMatcher.end();
            String placeholder = placeholder(index);
            blocks.add(new CodeBlock(index, content, start, start + placeholder.length()));
            fenceMatcher.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
            index++;
        }
        fenceMatcher.appendTail(sb);
        placeholderText = sb.toString();

        // 处理 4 空格缩进代码块（在围栏处理后的文本上）
        if (index == 0) {
            Matcher indentMatcher = INDENT_BLOCK.matcher(placeholderText);
            StringBuffer sb2 = new StringBuffer();
            int offset = 0;
            while (indentMatcher.find()) {
                String content = indentMatcher.group(1).replaceAll("(?m)^ {4}", "").strip();
                int start = indentMatcher.start() + offset;
                int end = indentMatcher.end() + offset;
                String placeholder = placeholder(index);
                blocks.add(new CodeBlock(index, content, start, start + placeholder.length()));
                indentMatcher.appendReplacement(sb2, Matcher.quoteReplacement(placeholder));
                index++;
            }
            indentMatcher.appendTail(sb2);
            placeholderText = sb2.toString();
        }

        Map<Integer, CodeBlock> blockByPlaceholderIndex = new HashMap<>();
        for (CodeBlock block : blocks) {
            blockByPlaceholderIndex.put(block.index(), block);
        }
        return new IsolationResult(placeholderText, blocks, blockByPlaceholderIndex);
    }

    /**
     * 按原始位置重组：保留的句子与全部代码块
     *
     * @param iso           隔离结果
     * @param keptSentences 保留的句子（按原文顺序，含占位符）
     * @return 重组后的裁剪文本
     */
    public String restore(IsolationResult iso, List<SplitSentence> keptSentences) {
        List<CodeBlock> sortedBlocks = new ArrayList<>(iso.blocks());
        sortedBlocks.sort(Comparator.comparingInt(CodeBlock::placeholderStart));

        StringBuilder out = new StringBuilder();
        int blockIdx = 0;
        int pos = 0;

        for (SplitSentence sentence : keptSentences) {
            // 插入落在本句之前（被丢弃区间）的代码块，保证代码块全部保留
            while (blockIdx < sortedBlocks.size()
                    && sortedBlocks.get(blockIdx).placeholderStart() < sentence.start()) {
                out.append(sortedBlocks.get(blockIdx).content());
                blockIdx++;
            }
            out.append(replacePlaceholders(sentence.text(), iso.blockByPlaceholderIndex()));
            // 本句内已通过占位符替换输出的代码块，跳过，避免重复输出
            while (blockIdx < sortedBlocks.size()
                    && sortedBlocks.get(blockIdx).placeholderStart() < sentence.end()) {
                blockIdx++;
            }
            pos = sentence.end();
        }

        // 末尾剩余的代码块（位于最后一个保留句子之后或被丢弃的尾部）
        while (blockIdx < sortedBlocks.size()) {
            out.append(sortedBlocks.get(blockIdx).content());
            blockIdx++;
        }

        return out.toString();
    }

    private String replacePlaceholders(String sentenceText, Map<Integer, CodeBlock> blockByPlaceholderIndex) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < sentenceText.length()) {
            if (sentenceText.startsWith(PLACEHOLDER_PREFIX, i)) {
                int end = sentenceText.indexOf(PLACEHOLDER_SUFFIX, i + PLACEHOLDER_PREFIX.length());
                if (end > 0) {
                    String token = sentenceText.substring(i, end + PLACEHOLDER_SUFFIX.length());
                    int idx = parseIndex(token);
                    CodeBlock block = blockByPlaceholderIndex.get(idx);
                    if (block != null) {
                        sb.append(block.content());
                        i = end + PLACEHOLDER_SUFFIX.length();
                        continue;
                    }
                }
            }
            sb.append(sentenceText.charAt(i));
            i++;
        }
        return sb.toString();
    }

    private String placeholder(int index) {
        return PLACEHOLDER_PREFIX + index + PLACEHOLDER_SUFFIX;
    }

    private int parseIndex(String token) {
        String digits = token.substring(PLACEHOLDER_PREFIX.length(), token.length() - PLACEHOLDER_SUFFIX.length());
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}