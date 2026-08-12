package com.byteq.ai.ragstudio.core.chunk;

import com.byteq.ai.ragstudio.aimodel.enums.AiModelErrorCode;
import com.byteq.ai.ragstudio.framework.exception.ServiceException;
import com.byteq.ai.ragstudio.infra.http.HttpModelFactory;
import com.byteq.ai.ragstudio.infra.http.ModelHttpClient;
import com.byteq.ai.ragstudio.infra.model.ModelSelector;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import com.byteq.ai.ragstudio.infra.protocol.ModelProtocol;
import com.byteq.ai.ragstudio.infra.protocol.ProtocolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class MultimodalEmbeddingService {

    private final HttpModelFactory modelFactory;
    private final ModelHttpClient httpClient;
    private final ModelSelector modelSelector;
    private final ProtocolRegistry protocolRegistry;

    public MultimodalEmbeddingService(HttpModelFactory modelFactory, ModelHttpClient httpClient,
                                       ModelSelector modelSelector, ProtocolRegistry protocolRegistry) {
        this.modelFactory = modelFactory;
        this.httpClient = httpClient;
        this.modelSelector = modelSelector;
        this.protocolRegistry = protocolRegistry;
    }

    public List<List<Float>> embedImages(List<String> imageBase64List, String modelId, Integer dimension) {
        if (imageBase64List == null || imageBase64List.isEmpty()) {
            return List.of();
        }
        ModelTarget target;
        if (StringUtils.hasText(modelId)) {
            target = resolveTarget(modelId);
        } else {
            target = modelSelector.selectEmbeddingCandidates().stream()
                    .findFirst()
                    .orElseThrow(() -> new ServiceException("无可用的 Embedding 模型", AiModelErrorCode.EMBEDDING_MODEL_UNAVAILABLE));
        }

        ModelProtocol protocol = protocolRegistry.get(target.protocolName());
        String url = modelFactory.resolveEmbeddingUrl(target);
        String modelName = target.candidate().getModel();
        Integer effectiveDim = dimension != null && dimension > 0
                ? dimension
                : target.candidate().getDimension();
        Object body = protocol.buildImageEmbeddingRequest(modelName, imageBase64List, effectiveDim);

        return httpClient.syncPost(url, target, body, protocol::extractEmbeddings);
    }

    public List<List<Float>> embedImages(List<String> imageBase64List, String modelId) {
        return embedImages(imageBase64List, modelId, null);
    }

    private ModelTarget resolveTarget(String modelId) {
        if (!StringUtils.hasText(modelId)) {
            throw new ServiceException("Embedding 模型ID不能为空", AiModelErrorCode.EMBEDDING_MODEL_ID_EMPTY);
        }
        return modelSelector.selectEmbeddingCandidates().stream()
                .filter(target -> modelId.equals(target.id()))
                .findFirst()
                .orElseThrow(() -> new ServiceException("Embedding 模型不可用: " + modelId, AiModelErrorCode.EMBEDDING_MODEL_UNAVAILABLE));
    }
}
