package com.byteq.ai.ragstudio.infra.embedding;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingCacheTest {

    private static List<Float> vec(String text) {
        return List.of((float) text.length(), (float) text.hashCode());
    }

    @Test
    void computeShouldCacheResultAndLoadOnlyOnce() {
        EmbeddingCache cache = new EmbeddingCache();
        AtomicInteger loadCount = new AtomicInteger();
        List<Float> first = cache.compute("m1", 1536, "hello", () -> {
            loadCount.incrementAndGet();
            return vec("hello");
        });
        List<Float> second = cache.compute("m1", 1536, "hello", () -> {
            loadCount.incrementAndGet();
            return vec("hello");
        });
        assertEquals(first, second);
        assertEquals(1, loadCount.get());
    }

    @Test
    void computeShouldSeparateKeysByModelDimensionAndText() {
        EmbeddingCache cache = new EmbeddingCache();
        AtomicInteger count = new AtomicInteger();
        cache.compute("m1", 1536, "t", () -> { count.incrementAndGet(); return vec("a"); });
        cache.compute("m1", 1024, "t", () -> { count.incrementAndGet(); return vec("b"); });
        cache.compute("m1", 1536, "t2", () -> { count.incrementAndGet(); return vec("c"); });
        cache.compute("m2", 1536, "t", () -> { count.incrementAndGet(); return vec("d"); });
        assertEquals(4, count.get());
    }

    @Test
    void concurrentComputeShouldCoalesceInFlightRequests() throws Exception {
        EmbeddingCache cache = new EmbeddingCache();
        AtomicInteger loadCount = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        int threads = 8;
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    cache.compute("m1", 1536, "same", () -> {
                        loadCount.incrementAndGet();
                        return vec("same");
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, loadCount.get());
    }

    @Test
    void computeBatchShouldDedupeAndReuseCache() {
        EmbeddingCache cache = new EmbeddingCache();
        AtomicInteger loadCount = new AtomicInteger();
        // 预热缓存："a"
        cache.compute("m1", 1536, "a", () -> vec("a"));
        // 批量：a(已缓存) + b + a(重复)，loader 只应被调用一次且只收到 [b]
        List<List<Float>> result = cache.computeBatch("m1", 1536,
                List.of("a", "b", "a"),
                missing -> {
                    loadCount.incrementAndGet();
                    assertEquals(List.of("b"), missing);
                    return List.of(vec("b"));
                });
        assertEquals(3, result.size());
        assertEquals(vec("a"), result.get(0));
        assertEquals(vec("b"), result.get(1));
        assertEquals(vec("a"), result.get(2));
        assertEquals(1, loadCount.get());
    }

    @Test
    void computeShouldNotCacheFailedLoad() {
        EmbeddingCache cache = new EmbeddingCache();
        AtomicInteger loadCount = new AtomicInteger();
        assertThrows(RuntimeException.class, () -> cache.compute("m1", 1536, "x", () -> {
            loadCount.incrementAndGet();
            throw new IllegalStateException("boom");
        }));
        // 失败不缓存，下次仍会重试加载
        assertThrows(RuntimeException.class, () -> cache.compute("m1", 1536, "x", () -> {
            loadCount.incrementAndGet();
            throw new IllegalStateException("boom");
        }));
        assertEquals(2, loadCount.get());
    }

    @Test
    void computeBatchShouldRejectSizeMismatch() {
        EmbeddingCache cache = new EmbeddingCache();
        assertThrows(IllegalStateException.class, () -> cache.computeBatch("m1", 1536,
                List.of("a", "b"),
                missing -> List.of(vec("onlyOne"))));
    }

    @Test
    void redisSerializationShouldRoundtrip() throws Exception {
        List<Float> original = new java.util.ArrayList<>();
        for (int i = 0; i < 1536; i++) {
            original.add((float) Math.sin(i) * 0.5f);
        }
        // 通过反射调用私有序列化方法，验证二进制格式可无损还原
        java.lang.reflect.Method ser = EmbeddingCache.class.getDeclaredMethod("serialize", List.class);
        java.lang.reflect.Method deser = EmbeddingCache.class.getDeclaredMethod("deserialize", byte[].class);
        ser.setAccessible(true);
        deser.setAccessible(true);
        byte[] bytes = (byte[]) ser.invoke(null, original);
        assertEquals(4 + 1536 * 4, bytes.length);
        @SuppressWarnings("unchecked")
        List<Float> restored = (List<Float>) deser.invoke(null, bytes);
        assertEquals(original, restored);
    }
}
