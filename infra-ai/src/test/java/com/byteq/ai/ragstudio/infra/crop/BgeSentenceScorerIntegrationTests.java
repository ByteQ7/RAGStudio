package com.byteq.ai.ragstudio.infra.crop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * bge-small-zh-v1.5 打分器集成测试（依赖本地已下载的模型文件，
 * 模型缺失时自动跳过，不阻塞常规构建）
 */
class BgeSentenceScorerIntegrationTests {

    private static final Path MODEL_DIR =
            Path.of("../resources/models/bge-small-zh-v1.5");

    @Test
    void scoresRelatedSentencesHigherThanUnrelated() throws Exception {
        if (!Files.isRegularFile(MODEL_DIR.resolve("vocab.txt"))
                || !Files.isRegularFile(MODEL_DIR.resolve("onnx/model_quantized.onnx"))) {
            return; // 模型未下载则跳过
        }

        BgeSentenceScorer scorer = new BgeSentenceScorer();
        scorer.setEnabled(true);
        scorer.setModelPath(MODEL_DIR.toString());
        scorer.init();

        String question = "公司年假制度规定员工每年可以休多少天？";
        List<String> sentences = List.of(
                "员工入职满一年可休 5 天带薪年假。",
                "食堂提供每日三餐。",
                "公司年假需要提前一周申请。");

        SentenceScoring scoring = scorer.score(question, sentences, 0.0);
        assertTrue(scoring.scores().length == 3);
        // 相关句子的相似度应显著高于不相关句子
        assertTrue(scoring.scores()[0] > scoring.scores()[1]);
        assertTrue(scoring.scores()[2] > scoring.scores()[1]);
    }

    @Test
    void highlightsAboveThreshold() throws Exception {
        if (!Files.isRegularFile(MODEL_DIR.resolve("vocab.txt"))
                || !Files.isRegularFile(MODEL_DIR.resolve("onnx/model_quantized.onnx"))) {
            return;
        }
        BgeSentenceScorer scorer = new BgeSentenceScorer();
        scorer.setEnabled(true);
        scorer.setModelPath(MODEL_DIR.toString());
        scorer.init();

        String question = "年假申请流程";
        List<String> sentences = List.of(
                "员工需要在系统中提交年假申请。",
                "今天的天气很好。");
        SentenceScoring scoring = scorer.score(question, sentences, 0.35);
        assertFalse(scoring.highlightedIndices().length == 2); // 至少一条被过滤
    }
}