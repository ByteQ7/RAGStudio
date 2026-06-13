package com.byteq.ai.ragstudio.infra.embedding;

import com.byteq.ai.ragstudio.infra.enums.ModelProvider;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import com.byteq.ai.ragstudio.infra.springai.SpringAiChatModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * SiliconFlow EmbeddingClient —— 基于 Spring AI OpenAI 兼容模式
 * <p>
 * 通过 SiliconFlow API 调用 Qwen3-Embedding 等嵌入模型。
 * 支持批量分片（maxBatchSize=32），超出时自动拆分请求。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiliconFlowEmbeddingClient implements EmbeddingClient {

    private static final int MAX_BATCH_SIZE = 32;

    private final SpringAiChatModelFactory modelFactory;

    @Override
    public String provider() {
        return ModelProvider.SILICON_FLOW.getId();
    }

    @Override
    public List<Float> embed(String text, ModelTarget target) {
        EmbeddingModel model = modelFactory.getOrCreateEmbeddingModel(target);
        float[] embedding = model.embed(text);
        return toFloatList(embedding);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        EmbeddingModel model = modelFactory.getOrCreateEmbeddingModel(target);
        List<List<Float>> results = new ArrayList<>(texts.size());

        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + MAX_BATCH_SIZE, texts.size()));
            List<float[]> embeddings = model.embed(batch);
            for (float[] emb : embeddings) {
                results.add(toFloatList(emb));
            }
        }

        return results;
    }

    private static List<Float> toFloatList(float[] array) {
        List<Float> list = new ArrayList<>(array.length);
        for (float v : array) {
            list.add(v);
        }
        return list;
    }
}
