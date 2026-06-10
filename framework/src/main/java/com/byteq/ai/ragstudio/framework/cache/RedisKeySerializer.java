package com.byteq.ai.ragstudio.framework.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Redis Key 序列化器
 *
 * <p>用于为 Redis 缓存 Key 自动添加统一前缀，避免多个微服务或应用共用同一 Redis 实例时
 * 发生 Key 冲突。通过配置文件 {@code framework.cache.redis.prefix} 指定前缀内容，
 * 当配置了该属性时此序列化器才会生效。</p>
 *
 * <p>设计说明：</p>
 * <ul>
 *   <li>序列化时：将配置的前缀与原始 Key 拼接，转换为 UTF-8 字节数组</li>
 *   <li>反序列化时：将字节数组转回字符串（此时前缀仍然保留在字符串中）</li>
 *   <li>Key 为 {@code null} 时序列化为字符串 {@code "null"}，避免空指针异常</li>
 * </ul>
 */
@RequiredArgsConstructor
@Component
@ConditionalOnProperty(name = "framework.cache.redis.prefix")
public class RedisKeySerializer implements RedisSerializer<String> {

    /**
     * Redis Key 前缀，通过配置文件注入
     */
    @Value("${framework.cache.redis.prefix:}")
    private String keyPrefix;

    /**
     * 将字符串 Key 序列化为字节数组
     * <p>在原始 Key 前面追加配置的前缀，然后以 UTF-8 编码转换为字节数组。</p>
     *
     * @param key 原始 Redis Key，可能为 null
     * @return 拼接前缀后的 UTF-8 字节数组
     * @throws SerializationException 序列化异常
     */
    @Override
    public byte[] serialize(String key) throws SerializationException {
        String effectiveKey = key == null ? "null" : key;
        String builderKey = keyPrefix + effectiveKey;
        return builderKey.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 将字节数组反序列化为字符串 Key
     *
     * @param bytes Redis Key 的 UTF-8 字节数组
     * @return 反序列化后的字符串（包含前缀）
     * @throws SerializationException 反序列化异常
     */
    @Override
    public String deserialize(byte[] bytes) throws SerializationException {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
