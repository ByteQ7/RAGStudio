package com.byteq.ai.ragstudio.infra.protocol;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ProtocolRegistry {

    private final Map<String, ModelProtocol> protocols;

    public ProtocolRegistry(List<ModelProtocol> protocolList) {
        this.protocols = protocolList.stream()
                .collect(Collectors.toMap(
                        ModelProtocol::name,
                        Function.identity(),
                        (existing, replacement) -> {
                            log.warn("重复的 Protocol '{}', 使用 {}", existing.name(), replacement.getClass().getSimpleName());
                            return replacement;
                        }));
    }

    public ModelProtocol get(String protocolName) {
        ModelProtocol p = protocols.get(protocolName);
        if (p == null) {
            log.warn("未知协议 '{}', 回退到 openai", protocolName);
            return protocols.get("openai");
        }
        return p;
    }

    public ModelProtocol getOpenAi() {
        return protocols.get("openai");
    }
}
