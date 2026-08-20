package com.byteq.ai.ragstudio.infra.crop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BertWordPieceTokenizerTests {

    private static Path vocabFile() {
        return Path.of("../resources/models/bge-small-zh-v1.5/vocab.txt");
    }

    @Test
    void encodesBatchWithClsSepPadding() throws Exception {
        Path vocabFile = vocabFile();
        if (!Files.isRegularFile(vocabFile)) {
            return; // 未下载模型时跳过
        }
        BertWordPieceTokenizer tokenizer = new BertWordPieceTokenizer(vocabFile, 32);

        BertWordPieceTokenizer.Encoding encoding = tokenizer.encodeBatch(
                java.util.List.of("公司年假制度", "hello world"));

        assertEquals(2, encoding.inputIds().length);
        assertEquals(32, encoding.inputIds()[0].length);
        assertEquals(1, encoding.attentionMask()[0][0]);   // [CLS] 有效
        assertEquals(1, encoding.attentionMask()[0][1]);   // 首个内容 token 有效
        assertEquals(0, encoding.inputIds()[0][encoding.inputIds()[0].length - 1]); // 尾部 PAD
    }

    @Test
    void splitsChineseCharsIntoTokenIds() throws Exception {
        Path vocabFile = vocabFile();
        if (!Files.isRegularFile(vocabFile)) {
            return;
        }
        BertWordPieceTokenizer tokenizer = new BertWordPieceTokenizer(vocabFile, 32);
        long[] ids = tokenizer.encodeBatch(java.util.List.of("你好")).inputIds()[0];
        // [CLS] + 2 个汉字 + [SEP]，内容 token 非零且非 [CLS]
        assertTrue(ids[1] > 0);
        assertTrue(ids[2] > 0);
        assertTrue(ids[1] != ids[2] || ids[1] != 0);
    }
}