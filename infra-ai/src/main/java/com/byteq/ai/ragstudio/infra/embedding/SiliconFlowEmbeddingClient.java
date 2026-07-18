package com.byteq.ai.ragstudio.infra.embedding;

import com.byteq.ai.ragstudio.infra.enums.ModelProvider;
import com.byteq.ai.ragstudio.infra.langchain4j.LangChain4jModelFactory;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SiliconFlow EmbeddingClient —— 基于 LangChain4j 1.0.0
 * <p>
 * 通过 SiliconFlow API 调用 Qwen3-Embedding 等嵌入模型。
 * 支持批量分片（maxBatchSize=32）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiliconFlowEmbeddingClient implements EmbeddingClient {

    private static final int MAX_BATCH_SIZE = 32;

    private final LangChain4jModelFactory modelFactory;

    @Override
    public String provider() {
        return ModelProvider.SILICON_FLOW.getId();
    }

    @Override
    public List<Float> embed(String text, ModelTarget target) {
        EmbeddingModel model = modelFactory.getOrCreateEmbeddingModel(target);
        // LangChain4j 1.0.0: embed() 返回 Response<Embedding>
        Response<Embedding> response = model.embed(text);
        return toFloatList(response.content().vector());
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        EmbeddingModel model = modelFactory.getOrCreateEmbeddingModel(target);
        List<List<Float>> results = new ArrayList<>(texts.size());

        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + MAX_BATCH_SIZE, texts.size()));
            List<TextSegment> segments = batch.stream()
                    .map(TextSegment::from)
                    .collect(Collectors.toList());
            Response<List<Embedding>> response = model.embedAll(segments);
            for (Embedding emb : response.content()) {
                results.add(toFloatList(emb.vector()));
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
