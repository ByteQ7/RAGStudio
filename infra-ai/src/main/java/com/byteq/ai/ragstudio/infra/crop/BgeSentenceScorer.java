package com.byteq.ai.ragstudio.infra.crop;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * bge-small-zh-v1.5 句子打分器（进程内加载）
 * <p>
 * 从本地模型目录加载 bge-small-zh-v1.5 量化 ONNX 模型（onnx/model_quantized.onnx 或 onnx/model.onnx），
 * 对「问题 + 句子列表」进行批量 Embedding 计算，再计算各句子与问题的余弦相似度。
 * </p>
 * <p>
 * 配置项（{@code rag.search.crop}）：
 * <ul>
 *   <li>{@code enabled}：是否启用语义裁剪（默认 false）。开启时模型目录缺失或加载失败，
 *       应用启动即抛异常（fail-fast）。</li>
 *   <li>{@code model-path}：bge-small-zh-v1.5 量化 ONNX 模型目录。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Setter
@Component
@ConfigurationProperties(prefix = "rag.search.crop")
public class BgeSentenceScorer {

    private static final String DEFAULT_MODEL_FILE = "onnx/model_quantized.onnx";
    private static final String FALLBACK_MODEL_FILE = "onnx/model.onnx";
    private static final int BATCH_SIZE = 16;

    private boolean enabled = false;
    private String modelPath = "";

    private OrtEnvironment env;
    private OrtSession session;
    private BertWordPieceTokenizer tokenizer;

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("语义裁剪未启用（rag.search.crop.enabled=false），跳过模型加载");
            return;
        }
        if (modelPath == null || modelPath.isBlank()) {
            throw new IllegalStateException("语义裁剪已启用但未配置 rag.search.crop.model-path");
        }
        Path modelDir = Path.of(modelPath);
        if (!Files.isDirectory(modelDir)) {
            throw new IllegalStateException("bge-small-zh-v1.5 模型目录不存在: " + modelDir
                    + "（请先执行 resources/models/bge-small-zh-v1.5/download.sh 下载模型）");
        }

        this.env = OrtEnvironment.getEnvironment();
        this.tokenizer = new BertWordPieceTokenizer(modelDir.resolve("vocab.txt"), readMaxLength(modelDir));

        Path modelFile = modelDir.resolve(DEFAULT_MODEL_FILE);
        if (!Files.isRegularFile(modelFile)) {
            modelFile = modelDir.resolve(FALLBACK_MODEL_FILE);
        }
        if (!Files.isRegularFile(modelFile)) {
            throw new IllegalStateException("bge-small-zh-v1.5 模型文件缺失: " + modelFile
                    + "（请先执行模型下载脚本或配置 rag.search.crop.model-path）");
        }

        try {
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            this.session = env.createSession(modelFile.toString(), options);
            log.info("bge-small-zh-v1.5 模型加载成功: {}", modelFile);
        } catch (Exception e) {
            throw new IllegalStateException("bge-small-zh-v1.5 模型加载失败: " + modelFile, e);
        }
    }

    /** 语义裁剪是否启用 */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 对问题与句子列表打分
     *
     * @param question  查询问题（使用改写后的 query）
     * @param sentences 句子列表
     * @param threshold 余弦相似度阈值，分数 &gt;= 阈值的句子被保留
     * @return 打分结果（句子、分数、保留索引）
     */
    public SentenceScoring score(String question, List<String> sentences, double threshold) {
        if (!enabled || session == null) {
            return new SentenceScoring(sentences, new double[0], new int[0]);
        }
        if (question == null || question.isBlank() || sentences == null || sentences.isEmpty()) {
            return new SentenceScoring(sentences, new double[0], new int[0]);
        }

        long start = System.nanoTime();

        float[] queryEmbedding = embedSingle(question);
        List<float[]> sentenceEmbeddings = embedBatch(sentences);

        double[] scores = new double[sentences.size()];
        List<Integer> highlighted = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            double sim = cosineSimilarity(queryEmbedding, sentenceEmbeddings.get(i));
            scores[i] = sim;
            if (sim >= threshold) {
                highlighted.add(i);
            }
        }

        int[] highlightedIndices = highlighted.stream().mapToInt(Integer::intValue).toArray();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.debug("句子打分完成: sentences={}, 保留={}, 耗时={}ms", sentences.size(), highlighted.size(), elapsedMs);

        return new SentenceScoring(sentences, scores, highlightedIndices);
    }

    private float[] embedSingle(String text) {
        BertWordPieceTokenizer.Encoding encoding = tokenizer.encodeBatch(List.of(text));
        try (OnnxTensor inputIds = OnnxTensor.createTensor(env, encoding.inputIds());
             OnnxTensor attentionMask = OnnxTensor.createTensor(env, encoding.attentionMask());
             OnnxTensor tokenTypeIds = OnnxTensor.createTensor(env, encoding.tokenTypeIds())) {
            try (OrtSession.Result result = session.run(Map.of(
                    "input_ids", inputIds,
                    "attention_mask", attentionMask,
                    "token_type_ids", tokenTypeIds))) {
                OnnxTensor output = (OnnxTensor) result.get(0);
                float[][][] hidden = (float[][][]) output.getValue();
                return normalize(hidden[0][0]);
            }
        } catch (Exception e) {
            throw new IllegalStateException("bge 模型推理失败: " + e.getMessage(), e);
        }
    }

    private List<float[]> embedBatch(List<String> texts) {
        List<float[]> result = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + BATCH_SIZE, texts.size()));
            BertWordPieceTokenizer.Encoding encoding = tokenizer.encodeBatch(batch);
            try (OnnxTensor inputIds = OnnxTensor.createTensor(env, encoding.inputIds());
                 OnnxTensor attentionMask = OnnxTensor.createTensor(env, encoding.attentionMask());
                 OnnxTensor tokenTypeIds = OnnxTensor.createTensor(env, encoding.tokenTypeIds())) {
                try (OrtSession.Result res = session.run(Map.of(
                        "input_ids", inputIds,
                        "attention_mask", attentionMask,
                        "token_type_ids", tokenTypeIds))) {
                    OnnxTensor output = (OnnxTensor) res.get(0);
                    float[][][] hidden = (float[][][]) output.getValue();
                    for (int j = 0; j < hidden.length; j++) {
                        result.add(normalize(hidden[j][0]));
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("bge 批量推理失败: " + e.getMessage(), e);
            }
        }
        return result;
    }

    private float[] normalize(float[] vector) {
        double norm = 0.0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        if (norm == 0.0) {
            return vector.clone();
        }
        float[] out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = (float) (vector[i] / norm);
        }
        return out;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    private int readMaxLength(Path modelDir) {
        try {
            Path configFile = modelDir.resolve("config.json");
            if (Files.isRegularFile(configFile)) {
                JsonNode config = new ObjectMapper().readTree(configFile.toFile());
                return config.path("max_position_embeddings").asInt(512);
            }
        } catch (Exception e) {
            log.warn("读取 config.json 失败，使用默认 max_length=512: {}", e.getMessage());
        }
        return 512;
    }
}