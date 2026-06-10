package com.byteq.ai.ragstudio.core.chunk;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 文本分块策略工厂
 * <p>
 * 用于管理和获取不同的文本分块策略实现。通过构造器注入所有 {@link ChunkingStrategy} 类型的
 * Spring Bean，在 {@link PostConstruct} 初始化阶段自动注册到策略映射表中。
 * </p>
 * <p>
 * 使用方式：
 * <ul>
 *   <li>{@link #findStrategy(ChunkingMode)} - 查找策略，返回 Optional</li>
 *   <li>{@link #requireStrategy(ChunkingMode)} - 获取策略，不存在时抛出异常</li>
 * </ul>
 * </p>
 *
 * @see ChunkingStrategy
 * @see ChunkingMode
 */
@Component
@RequiredArgsConstructor
public class ChunkingStrategyFactory {

    /**
     * Spring 注入的所有分块策略实现列表
     */
    private final List<ChunkingStrategy> chunkingStrategies;

    /**
     * 分块策略映射表
     * volatile 保证多线程环境下的可见性
     */
    private volatile Map<ChunkingMode, ChunkingStrategy> strategies = Map.of();

    /**
     * 根据策略类型查找对应的分块策略实现
     *
     * @param type 分块策略类型枚举
     * @return 包含策略实现的 Optional，如果未找到则返回 Optional.empty()
     */
    public Optional<ChunkingStrategy> findStrategy(ChunkingMode type) {
        if (type == null) return Optional.empty();
        return Optional.ofNullable(strategies.get(type));
    }

    /**
     * 获取指定类型的分块策略实现，如果不存在则抛出异常
     *
     * @param type 分块策略类型枚举
     * @return 分块策略实现
     * @throws IllegalArgumentException 如果指定的策略类型不存在
     */
    public ChunkingStrategy requireStrategy(ChunkingMode type) {
        Objects.requireNonNull(type, "ChunkingMode type must not be null");
        return findStrategy(type)
                .orElseThrow(() -> new IllegalArgumentException("Unknown strategy: " + type));
    }

    /**
     * 初始化策略映射表
     * <p>
     * 在 Spring 完成依赖注入后自动调用，将所有可用的分块策略实现
     * 注册到枚举映射中。如果发现重复的策略类型，则抛出异常。
     * </p>
     */
    @PostConstruct
    public void init() {
        Map<ChunkingMode, ChunkingStrategy> map = new EnumMap<>(ChunkingMode.class);

        chunkingStrategies.forEach(s -> {
            ChunkingStrategy old = map.put(s.getType(), s);
            if (old != null) {
                throw new IllegalStateException(
                        "Duplicate ChunkingStrategy for type: " + s.getType()
                                + " (" + old.getClass().getName() + " vs " + s.getClass().getName() + ")"
                );
            }
        });

        this.strategies = Map.copyOf(map);
    }
}
