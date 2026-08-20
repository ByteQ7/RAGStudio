package com.byteq.ai.ragstudio.infra.crop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeBlockIsolatorTests {

    private final CodeBlockIsolator isolator = new CodeBlockIsolator();

    @Test
    void isolatesFencedCodeBlocksWithPlaceholders() {
        String text = "第一段说明文字。\n```java\nSystem.out.println(\"hi\");\n```\n第二段说明文字。";
        CodeBlockIsolator.IsolationResult iso = isolator.isolate(text);

        assertEquals(1, iso.blocks().size());
        assertEquals("System.out.println(\"hi\");", iso.blocks().get(0).content());
        assertTrue(iso.placeholderText().contains("__INLINECODETWO0__"));
        assertTrue(iso.placeholderText().contains("第一段说明文字。"));
        assertTrue(iso.placeholderText().contains("第二段说明文字。"));
    }

    @Test
    void restorePreservesAllCodeBlocksAndOrder() {
        String text = "第一段。\n```python\nprint(1)\n```\n第二段。\n```bash\necho hi\n```\n第三段。";
        CodeBlockIsolator.IsolationResult iso = isolator.isolate(text);

        // 假设只保留第 1、3 句（第一段与第三段），中间的代码块与第二段被丢弃
        List<SplitSentence> sentences = new HanlpSentenceSplitter().split(iso.placeholderText());
        List<SplitSentence> kept = List.of(sentences.get(0), sentences.get(sentences.size() - 1));

        String restored = isolator.restore(iso, kept);
        assertTrue(restored.contains("第一段。"));
        assertTrue(restored.contains("第三段。"));
        assertTrue(restored.contains("print(1)"));
        assertTrue(restored.contains("echo hi"));
        // 代码块顺序保持原始顺序
        assertTrue(restored.indexOf("print(1)") < restored.indexOf("echo hi"));
    }

    @Test
    void restoreKeepsPlaceholderWithinKeptSentence() {
        String text = "前言。\n```sql\nSELECT 1;\n```\n结束语。";
        CodeBlockIsolator.IsolationResult iso = isolator.isolate(text);

        List<SplitSentence> sentences = new HanlpSentenceSplitter().split(iso.placeholderText());
        // 保留全部句子
        String restored = isolator.restore(iso, sentences);
        assertEquals("前言。SELECT 1;结束语。", restored);
    }

    @Test
    void handlesIndentedCodeBlocks() {
        String text = "说明文字。\n    indented code line\n    another line\n尾部文字。";
        CodeBlockIsolator.IsolationResult iso = isolator.isolate(text);
        assertTrue(iso.blocks().size() >= 1);
        assertTrue(iso.blocks().get(0).content().contains("indented code line"));
    }
}