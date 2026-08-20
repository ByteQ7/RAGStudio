package com.byteq.ai.ragstudio.infra.crop;

import com.hankcs.hanlp.seg.common.Term;
import com.hankcs.hanlp.tokenizer.StandardTokenizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 基于 HanLP 1.x portable 的中文/英文分句器
 * <p>
 * 使用 HanLP 分词器（StandardTokenizer）得到非重叠 term 流并还原偏移，
 * 仅在句末标点（。！？!?…换行）处切分并保留分隔符；
 * 引号/括号配对深度跟踪避免在引号或括号内误切（如 “他说：”你好。这是引号里的句子。“然后走了。）
 * 正确保留数字小数点/URL 等不切分场景。
 * 任一步骤异常时回退正则切分（[。！？!?\n]），保证分句鲁棒性。
 * </p>
 */
@Component
public class HanlpSentenceSplitter {

    private static final Set<String> TERMINATORS = Set.of("。", "！", "？", "!", "?", "…", "\n", "\r", "\n\r");

    private static final Set<String> OPENING_QUOTES = Set.of("\"", "“", "‘", "「", "『", "（", "(", "[", "【");
    private static final Set<String> CLOSING_QUOTES = Set.of("\"", "”", "’", "」", "』", "）", ")", "]", "】");

    /** 回退正则切分（保留分隔符） */
    private static final String REGEX_FALLBACK = "(?<=[。！？!?\\n])";

    /**
     * 将文本切分为句子列表（保留分隔符并带偏移）
     *
     * @param text 待分句文本（已剥离代码块、含占位符）
     * @return 句子列表，按原文顺序排列
     */
    public List<SplitSentence> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            return splitWithHanlp(text);
        } catch (Exception e) {
            return splitWithRegex(text);
        }
    }

    /** HanLP 分词器驱动分句（StandardTokenizer 非重叠 term + 手动还原偏移） */
    private List<SplitSentence> splitWithHanlp(String text) {
        List<Term> terms = StandardTokenizer.segment(text);
        List<SplitSentence> result = new ArrayList<>();

        StringBuilder sb = new StringBuilder();
        int start = 0;
        int cursor = 0;
        int quoteDepth = 0;
        int parenDepth = 0;

        for (Term term : terms) {
            String word = term.word;
            int termStart = text.indexOf(word, cursor);
            if (termStart < 0) {
                termStart = cursor;
            }
            int termEnd = termStart + word.length();
            cursor = termEnd;

            sb.append(word);

            if (isQuoteToggle(word)) {
                quoteDepth = (quoteDepth == 0) ? 1 : 0;
            } else if (isOpeningQuote(word)) {
                quoteDepth++;
            } else if (isClosingQuote(word)) {
                if (quoteDepth > 0) {
                    quoteDepth--;
                }
            } else if (isOpeningBracket(word)) {
                parenDepth++;
            } else if (isClosingBracket(word)) {
                if (parenDepth > 0) {
                    parenDepth--;
                }
            }

            if (isTerminator(word, terms, text, termStart) && quoteDepth == 0 && parenDepth == 0) {
                addSentence(result, sb, start, termEnd);
                sb.setLength(0);
                start = termEnd;
            }
        }

        String tail = sb.toString().trim();
        if (!tail.isEmpty()) {
            result.add(new SplitSentence(tail, start, text.length()));
        }

        return result.isEmpty() ? List.of(new SplitSentence(text, 0, text.length())) : result;
    }

    /** 正则回退分句（按句末标点切分，保留分隔符） */
    private List<SplitSentence> splitWithRegex(String text) {
        String[] parts = text.split(REGEX_FALLBACK);
        List<SplitSentence> result = new ArrayList<>();
        int offset = 0;
        for (String part : parts) {
            String s = part.trim();
            if (s.isEmpty()) {
                offset += part.length();
                continue;
            }
            result.add(new SplitSentence(s, offset, offset + part.length()));
            offset += part.length();
        }
        return result.isEmpty() ? List.of(new SplitSentence(text, 0, text.length())) : result;
    }

    private void addSentence(List<SplitSentence> result, StringBuilder sb, int start, int end) {
        String s = sb.toString().trim();
        if (!s.isEmpty()) {
            result.add(new SplitSentence(s, start, end));
        }
    }

    private boolean isTerminator(String word, List<Term> terms, String text, int termStart) {
        if (TERMINATORS.contains(word)) {
            return true;
        }
        // 处理英文句点：仅在句点后跟空白+大写字母、或空白+中文、或文本末尾时切分，避免 3.14、example.com 被切开
        if (word.equals(".")) {
            int after = termStart + word.length();
            if (after >= text.length()) {
                return true;
            }
            char next = text.charAt(after);
            if (next == '\n' || next == '\r') {
                return true;
            }
            if (Character.isWhitespace(next)) {
                int j = after + 1;
                while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
                    j++;
                }
                if (j >= text.length()) {
                    return true;
                }
                char c = text.charAt(j);
                return Character.isUpperCase(c) || (c >= 0x4E00 && c <= 0x9FFF);
            }
            return false;
        }
        return false;
    }

    /** 直双引号（"）兼具开关两种角色，作为成对引号切换处理 */
    private boolean isQuoteToggle(String w) {
        return w.equals("\"");
    }

    private boolean isOpeningQuote(String w) {
        return OPENING_QUOTES.contains(w);
    }

    private boolean isClosingQuote(String w) {
        return CLOSING_QUOTES.contains(w);
    }

    private boolean isOpeningBracket(String w) {
        return w.equals("(") || w.equals("（") || w.equals("[") || w.equals("【") || w.equals("〔") || w.equals("《") || w.equals("<");
    }

    private boolean isClosingBracket(String w) {
        return w.equals(")") || w.equals("）") || w.equals("]") || w.equals("】") || w.equals("〕") || w.equals("》") || w.equals(">");
    }
}