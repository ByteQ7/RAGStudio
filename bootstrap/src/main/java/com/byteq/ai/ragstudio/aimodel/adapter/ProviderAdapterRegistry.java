package com.byteq.ai.ragstudio.aimodel.adapter;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 供应商适配器注册中心
 * <p>
 * 管理所有 ProviderAdapter 实例，根据供应商名称自动匹配对应的适配器。
 * 注册顺序决定匹配优先级：先注册的优先匹配。
 * OpenaiCompatibleAdapter 作为兜底适配器始终处于最后。
 * </p>
 */
@Slf4j
@Component
public class ProviderAdapterRegistry {

    private final List<ProviderAdapter> adapters = new ArrayList<>();

    /**
     * 初始化默认适配器
     * <p>
     * 注册顺序 = 匹配优先级。SiliconFlow > DeepSeek > OpenAI 兼容（兜底）。
     * </p>
     */
    @PostConstruct
    public void init() {
        register(new SiliconFlowAdapter());
        register(new DeepSeekAdapter());
        register(new BailianAdapter());
        register(new VolcEngineAdapter());
        register(new ZhipuAdapter());
        register(new OpenaiCompatibleAdapter());
        log.info("已注册 {} 个 AI 供应商适配器", adapters.size());
    }

    /**
     * 注册一个适配器
     */
    public void register(ProviderAdapter adapter) {
        adapters.add(adapter);
    }

    /**
     * 根据供应商名称获取对应的适配器
     * <p>
     * 遍历已注册的适配器，返回第一个 supports() 返回 true 的实例。
     * 由于 OpenaiCompatibleAdapter 总是在最后，所以它会作为兜底适配。
     * </p>
     *
     * @param providerName 供应商名称
     * @return 匹配的适配器，如果没有找到则返回 null
     */
    public ProviderAdapter getAdapter(String providerName) {
        for (ProviderAdapter adapter : adapters) {
            if (adapter.supports(providerName)) {
                return adapter;
            }
        }
        return null;
    }

    /**
     * 获取所有已注册的适配器
     */
    public List<ProviderAdapter> getAllAdapters() {
        return new ArrayList<>(adapters);
    }
}
