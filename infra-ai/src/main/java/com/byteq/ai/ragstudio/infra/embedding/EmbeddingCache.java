package com.byteq.ai.ragstudio.infra.embedding;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Embedding 结果缓存（本地 L1 + Redis L2）
 * <p>
 * 按「模型 + 维度 + 文本」去重，避免同一文本在多知识库/多次调用场景下重复调用远程
 * Embedding 服务（该服务冷调用单次可高达数秒）。两级缓存：
 * <ul>
 *   <li>L1 本地内存（Guava，2000 条 / 24h）：热路径零网络开销；同时承载 in-flight 并发合并，
 *       相同 key 的并发请求只发起一次远程调用，其余等待结果</li>
 *   <li>L2 Redis（可选，{@code rag.embedding-cache.redis-enabled}）：跨服务重启/多实例共享，
 *       重启后首个重复 query 不再付冷调用成本</li>
 * </ul>
 * Redis 不可用时自动降级为纯本地缓存（仅告警一次），不影响检索主流程。
 * 大批次（&gt;16 条）跳过 Redis——通常是文档索引任务，文本不会复用，避免无谓的读写。
 * 缓存仅记录成功结果；加载失败不会写入缓存，直接抛出由上层降级。
 * </p>
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "rag.embedding-cache")
public class EmbeddingCache {

    private static final int MAX_CACHE_SIZE = 2000;
    /** 缓存 TTL（小时）。模型服务端更新/漂移时，过长的 TTL 会冻结陈旧向量导致选库/检索排名失真，默认 24h，建议 ≤ 6h */
    private static final long DEFAULT_CACHE_TTL_HOURS = 24;
    private static final String REDIS_KEY_PREFIX = "ragstudio:embedding:";
    /** 超过该数量的批量请求不读写 Redis（大批次通常是索引任务，文本不会复用） */
    private static final int REDIS_BATCH_THRESHOLD = 16;

    @Setter
    private boolean redisEnabled = false;

    /** 缓存 TTL（小时），可通过 {@code rag.embedding-cache.ttl-hours} 覆盖 */
    @Setter
    private long ttlHours = DEFAULT_CACHE_TTL_HOURS;

    private final Map<String, CompletableFuture<List<Float>>> inFlight = new ConcurrentHashMap<>();
    private final RedisTemplate<String, byte[]> redisTemplate;
    private volatile boolean redisWarned = false;

    /** 本地 Guava 缓存：构造时先按默认 TTL 创建，属性绑定后由 {@link #init()} 按配置 TTL 重建 */
    private volatile Cache<String, List<Float>> values;

    /** Spring 构造：尝试获取 Redis 连接工厂（未配置时降级为纯本地缓存） */
    @Autowired
    public EmbeddingCache(ObjectProvider<RedisConnectionFactory> connectionFactoryProvider) {
        this(buildTemplate(connectionFactoryProvider.getIfAvailable()));
    }

    /** 测试用：纯本地缓存 */
    EmbeddingCache() {
        this((RedisTemplate<String, byte[]>) null);
    }

    private EmbeddingCache(RedisTemplate<String, byte[]> redisTemplate) {
        this.values = buildLocalCache(DEFAULT_CACHE_TTL_HOURS);
        this.redisTemplate = redisTemplate;
        log.info("Embedding 缓存初始化完成: 本地容量={}, TTL={}h, Redis 连接可用={}",
                MAX_CACHE_SIZE, DEFAULT_CACHE_TTL_HOURS, redisTemplate != null);
    }

    /** 属性绑定完成后按配置 TTL 重建本地缓存（@ConfigurationProperties 绑定发生在构造之后、init 之前） */
    @jakarta.annotation.PostConstruct
    public void init() {
        long ttl = ttlHours > 0 ? ttlHours : DEFAULT_CACHE_TTL_HOURS;
        this.values = buildLocalCache(ttl);
        log.info("Embedding 本地缓存 TTL 已按配置应用: {}h", ttl);
    }

    private static Cache<String, List<Float>> buildLocalCache(long ttlHours) {
        return CacheBuilder.newBuilder()
                .maximumSize(MAX_CACHE_SIZE)
                .expireAfterWrite(ttlHours, TimeUnit.HOURS)
                .recordStats()
                .build();
    }

    private static RedisTemplate<String, byte[]> buildTemplate(RedisConnectionFactory factory) {
        if (factory == null) {
            return null;
        }
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(RedisSerializer.string());
        template.setValueSerializer(RedisSerializer.byteArray());
        template.setHashKeySerializer(RedisSerializer.string());
        template.setHashValueSerializer(RedisSerializer.byteArray());
        template.afterPropertiesSet();
        return template;
    }

    private boolean redisActive() {
        return redisEnabled && redisTemplate != null;
    }

    /**
     * 构造缓存 key。维度为空时用占位符，避免与指定维度的结果混淆。
     */
    public static String key(String modelId, Integer dimension, String text) {
        return modelId + "|" + (dimension == null ? "-" : dimension) + "|" + text;
    }

    /**
     * 获取单文本向量：命中本地缓存直接返回；未命中时对相同 key 的并发请求合并为一次加载，
     * 加载路径为「Redis L2 → 远程调用」，成功后双写本地与 Redis。
     *
     * @param modelId   模型 ID
     * @param dimension 输出维度，可为 null（使用模型默认）
     * @param text      待嵌入文本
     * @param loader    本地与 Redis 均未命中时执行一次远程加载
     * @return 向量结果
     */
    public List<Float> compute(String modelId, Integer dimension, String text, Supplier<List<Float>> loader) {
        String k = key(modelId, dimension, text);
        List<Float> cached = values.getIfPresent(k);
        if (cached != null) {
            return cached;
        }
        CompletableFuture<List<Float>> future = inFlight.get(k);
        if (future == null) {
            future = new CompletableFuture<>();
            CompletableFuture<List<Float>> raced = inFlight.putIfAbsent(k, future);
            if (raced != null) {
                future = raced;
            } else {
                // 本线程赢得加载权：Redis → 远程调用，成功才写缓存
                try {
                    future.complete(loadWithRedis(k, loader));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                } finally {
                    inFlight.remove(k);
                }
            }
        }
        return unwrap(future);
    }

    /**
     * 批量获取向量：先查本地缓存，再查 Redis（小批量时），仅对仍未命中的「唯一文本」
     * 执行一次批量加载，结果双写本地与 Redis，最后按原始顺序组装返回。
     * <p>
     * 同一批次内重复的文本只会加载一次；部分命中的请求会显著减少远程调用量。
     * </p>
     *
     * @param modelId   模型 ID
     * @param dimension 输出维度，可为 null
     * @param texts     待嵌入文本列表（保留原始顺序与重复项）
     * @param loader    对「未命中的唯一文本列表」执行一次批量加载，返回与入参顺序一致的向量列表
     * @return 与 {@code texts} 顺序一致、一一对应的向量列表
     */
    public List<List<Float>> computeBatch(String modelId, Integer dimension, List<String> texts,
                                          Function<List<String>, List<List<Float>>> loader) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(texts));
        Map<String, List<Float>> resolved = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String t : distinct) {
            List<Float> v = values.getIfPresent(key(modelId, dimension, t));
            if (v != null) {
                resolved.put(t, v);
            } else {
                missing.add(t);
            }
        }
        if (!missing.isEmpty()) {
            boolean useRedis = redisActive() && missing.size() <= REDIS_BATCH_THRESHOLD;
            if (useRedis) {
                List<String> stillMissing = new ArrayList<>();
                for (String t : missing) {
                    String mk = key(modelId, dimension, t);
                    List<Float> v = redisGet(mk);
                    if (v != null) {
                        values.put(mk, v);
                        resolved.put(t, v);
                    } else {
                        stillMissing.add(t);
                    }
                }
                missing = stillMissing;
            }
            if (!missing.isEmpty()) {
                List<List<Float>> loaded = loader.apply(missing);
                if (loaded == null || loaded.size() != missing.size()) {
                    throw new IllegalStateException(
                            "批量 embedding 返回数量(" + (loaded == null ? "null" : loaded.size())
                                    + ")与请求数量(" + missing.size() + ")不一致");
                }
                for (int i = 0; i < missing.size(); i++) {
                    String mk = key(modelId, dimension, missing.get(i));
                    List<Float> v = loaded.get(i);
                    if (v != null && !v.isEmpty()) {
                        values.put(mk, v);
                        if (useRedis) {
                            redisSet(mk, v);
                        }
                    }
                    resolved.put(missing.get(i), v);
                }
            }
        }
        List<List<Float>> result = new ArrayList<>(texts.size());
        for (String t : texts) {
            result.add(resolved.get(t));
        }
        return result;
    }

    // ==================== Redis L2 ====================

    private List<Float> loadWithRedis(String cacheKey, Supplier<List<Float>> loader) {
        if (redisActive()) {
            List<Float> fromRedis = redisGet(cacheKey);
            if (fromRedis != null) {
                values.put(cacheKey, fromRedis);
                return fromRedis;
            }
        }
        List<Float> v = loader.get();
        if (v != null && !v.isEmpty()) {
            values.put(cacheKey, v);
            if (redisActive()) {
                redisSet(cacheKey, v);
            }
        }
        return v;
    }

    private List<Float> redisGet(String cacheKey) {
        try {
            byte[] data = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + cacheKey);
            if (data == null || data.length == 0) {
                return null;
            }
            List<Float> v = deserialize(data);
            return v.isEmpty() ? null : v;
        } catch (Exception e) {
            warnRedis("读取", e);
            return null;
        }
    }

    private void redisSet(String cacheKey, List<Float> vec) {
        try {
            long ttl = ttlHours > 0 ? ttlHours : DEFAULT_CACHE_TTL_HOURS;
            redisTemplate.opsForValue()
                    .set(REDIS_KEY_PREFIX + cacheKey, serialize(vec), ttl, TimeUnit.HOURS);
        } catch (Exception e) {
            warnRedis("写入", e);
        }
    }

    private void warnRedis(String op, Exception e) {
        if (!redisWarned) {
            redisWarned = true;
            log.warn("Embedding 缓存 Redis {} 失败，已降级为纯本地缓存: {}", op, e.getMessage());
        }
    }

    // ==================== 序列化（float32 紧凑二进制，大端） ====================

    private static byte[] serialize(List<Float> vec) {
        ByteBuffer buf = ByteBuffer.allocate(4 + vec.size() * 4).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(vec.size());
        for (float f : vec) {
            buf.putFloat(f);
        }
        return buf.array();
    }

    private static List<Float> deserialize(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int n = buf.getInt();
        if (n <= 0 || n > 100_000) {
            return List.of();
        }
        List<Float> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            result.add(buf.getFloat());
        }
        return result;
    }

    public long cacheSize() {
        return values.size();
    }

    public void clear() {
        values.invalidateAll();
    }

    private List<Float> unwrap(CompletableFuture<List<Float>> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        } catch (CancellationException e) {
            throw new RuntimeException("embedding 加载被取消", e);
        }
    }
}
