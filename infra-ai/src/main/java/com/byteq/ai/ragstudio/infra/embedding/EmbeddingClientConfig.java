package com.byteq.ai.ragstudio.infra.embedding;

import com.byteq.ai.ragstudio.infra.enums.ModelProvider;
import com.byteq.ai.ragstudio.infra.http.HttpModelFactory;
import com.byteq.ai.ragstudio.infra.http.ModelHttpClient;
import com.byteq.ai.ragstudio.infra.protocol.ProtocolRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingClientConfig {

    @Bean
    public EmbeddingClient bailianEmbeddingClient(HttpModelFactory modelFactory, ModelHttpClient httpClient,
                                                   ProtocolRegistry protocolRegistry) {
        return new OpenAiEmbeddingClient(modelFactory, httpClient, protocolRegistry,
                ModelProvider.BAI_LIAN.getId(), 10);
    }

    @Bean
    public EmbeddingClient siliconFlowEmbeddingClient(HttpModelFactory modelFactory, ModelHttpClient httpClient,
                                                       ProtocolRegistry protocolRegistry) {
        return new OpenAiEmbeddingClient(modelFactory, httpClient, protocolRegistry,
                ModelProvider.SILICON_FLOW.getId(), 32);
    }
}
