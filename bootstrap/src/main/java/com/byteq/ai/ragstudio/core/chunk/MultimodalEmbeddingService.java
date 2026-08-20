package com.byteq.ai.ragstudio.core.chunk;

import com.byteq.ai.ragstudio.aimodel.enums.AiModelErrorCode;
import com.byteq.ai.ragstudio.framework.exception.ServiceException;
import com.byteq.ai.ragstudio.infra.model.ModelSelector;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import com.byteq.ai.ragstudio.infra.sdk.ProviderGateway;
import com.byteq.ai.ragstudio.infra.sdk.ProviderGatewayRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Component
public class MultimodalEmbeddingService {

    private final ModelSelector modelSelector;
    private final ProviderGatewayRegistry gatewayRegistry;

    public MultimodalEmbeddingService(ModelSelector modelSelector, ProviderGatewayRegistry gatewayRegistry) {
        this.modelSelector = modelSelector;
        this.gatewayRegistry = gatewayRegistry;
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

        ProviderGateway gateway = gatewayRegistry.resolve(target.candidate().getProvider(), target.protocolName());
        return gateway.embedImages(imageBase64List, target);
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