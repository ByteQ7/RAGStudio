package com.byteq.ai.ragstudio.infra.crop;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HanlpSentenceSplitterTests {

    private final HanlpSentenceSplitter splitter = new HanlpSentenceSplitter();

    @Test
    void splitsChineseSentencesKeepingDelimiters() {
        List<SplitSentence> sentences = splitter.split("公司年假制度规定员工每年享有带薪年假。事假需提前申请。");
        assertEquals(2, sentences.size());
        assertEquals("公司年假制度规定员工每年享有带薪年假。", sentences.get(0).text());
        assertEquals("事假需提前申请。", sentences.get(1).text());
    }

    @Test
    void keepsQuotedSentencesTogether() {
        // 引号内的句号不切分，整体作为一个句子
        List<SplitSentence> sentences = splitter.split("他说：\"你好。这是引号里的句子。\"然后走了。");
        assertEquals(1, sentences.size());
        assertEquals("他说：\"你好。这是引号里的句子。\"然后走了。", sentences.get(0).text());
    }

    @Test
    void doesNotSplitOnDecimalPoint() {
        List<SplitSentence> sentences = splitter.split("价格是5.6元。END. another test?");
        assertTrue(sentences.get(0).text().contains("5.6元。"));
    }

    @Test
    void splitsMixedChineseEnglish() {
        List<SplitSentence> sentences = splitter.split("第一句。The quick brown fox jumps. 第二句？");
        assertEquals(3, sentences.size());
        assertEquals("第一句。", sentences.get(0).text());
        assertEquals("The quick brown fox jumps.", sentences.get(1).text());
        assertEquals("第二句？", sentences.get(2).text());
    }

    @Test
    void offsetsAreOrderedAndCoverText() {
        String text = "公司年假制度规定员工每年享有带薪年假。事假需提前申请。";
        List<SplitSentence> sentences = splitter.split(text);
        String joined = sentences.stream().map(SplitSentence::text).collect(Collectors.joining());
        assertEquals(text, joined);
        for (int i = 0; i < sentences.size(); i++) {
            if (i > 0) {
                assertTrue(sentences.get(i).start() >= sentences.get(i - 1).end());
            }
        }
    }

    @Test
    void splitsOnNewlines() {
        List<SplitSentence> sentences = splitter.split("第一段内容。\n第二段内容。");
        assertEquals(2, sentences.size());
        assertEquals("第一段内容。", sentences.get(0).text());
        assertEquals("第二段内容。", sentences.get(1).text());
    }
}