package com.byteq.ai.ragstudio.infra.embedding;

import com.byteq.ai.ragstudio.infra.http.HttpModelFactory;
import com.byteq.ai.ragstudio.infra.http.ModelHttpClient;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import com.byteq.ai.ragstudio.infra.protocol.ModelProtocol;
import com.byteq.ai.ragstudio.infra.protocol.ProtocolRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private final HttpModelFactory modelFactory;
    private final ModelHttpClient httpClient;
    private final ProtocolRegistry protocolRegistry;
    private final String provider;
    private final int maxBatchSize;

    public OpenAiEmbeddingClient(HttpModelFactory modelFactory, ModelHttpClient httpClient,
                                  ProtocolRegistry protocolRegistry, String provider, int maxBatchSize) {
        this.modelFactory = modelFactory;
        this.httpClient = httpClient;
        this.protocolRegistry = protocolRegistry;
        this.provider = provider;
        this.maxBatchSize = maxBatchSize;
    }

    @Override
    public String provider() { return provider; }

    @Override
    public List<Float> embed(String text, ModelTarget target) {
        return embedBatch(List.of(text), target).get(0);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        ModelProtocol protocol = protocolRegistry.get(target.protocolName());
        String url = modelFactory.resolveEmbeddingUrl(target);
        String modelName = target.candidate().getModel();
        Integer dimension = target.candidate().getDimension();

        List<List<Float>> results = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += maxBatchSize) {
            List<String> batch = texts.subList(i, Math.min(i + maxBatchSize, texts.size()));
            Object body = protocol.buildEmbeddingRequest(modelName, batch, dimension);
            results.addAll(httpClient.syncPost(url, target, body, protocol::extractEmbeddings));
        }
        return results;
    }

    @Override
    public List<List<Float>> embedImages(List<String> imageBase64List, ModelTarget target) {
        ModelProtocol protocol = protocolRegistry.get(target.protocolName());
        String url = modelFactory.resolveEmbeddingUrl(target);
        String modelName = target.candidate().getModel();
        Integer dimension = target.candidate().getDimension();

        List<List<Float>> results = new ArrayList<>(imageBase64List.size());
        for (int i = 0; i < imageBase64List.size(); i += maxBatchSize) {
            List<String> batch = imageBase64List.subList(i, Math.min(i + maxBatchSize, imageBase64List.size()));
            Object body = protocol.buildImageEmbeddingRequest(modelName, batch, dimension);
            results.addAll(httpClient.syncPost(url, target, body, protocol::extractEmbeddings));
        }
        return results;
    }
}
