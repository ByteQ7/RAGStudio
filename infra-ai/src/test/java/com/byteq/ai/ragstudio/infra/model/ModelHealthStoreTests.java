package com.byteq.ai.ragstudio.infra.model;

import com.byteq.ai.ragstudio.infra.config.ModelRoutingProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型熔断器指数退避单元测试（Mock Redis，不依赖 Spring / 真实 Redis）。
 */
class ModelHealthStoreTests {

    private ModelRoutingProperties props(long base, long max) {
        ModelRoutingProperties p = new ModelRoutingProperties();
        p.getSelection().setOpenDurationMs(base);
        p.getSelection().setMaxOpenDurationMs(max);
        return p;
    }

    private static ModelHealthStore newStore(ModelRoutingProperties p) {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getTopic(anyString())).thenReturn(mock(RTopic.class));
        return new ModelHealthStore(p, redisson);
    }

    // ==================== 纯函数：退避计算 ====================

    @Test
    void backoffShouldStartAtBaseAndDouble() {
        assertEquals(100L, ModelHealthStore.computeBackoffMs(100, 600000, 1));
        assertEquals(200L, ModelHealthStore.computeBackoffMs(100, 600000, 2));
        assertEquals(400L, ModelHealthStore.computeBackoffMs(100, 600000, 3));
    }

    @Test
    void backoffShouldCapAndNeverOverflow() {
        // 30000 × 2^5 = 960000 ≥ 600000 → 封顶
        assertEquals(600000L, ModelHealthStore.computeBackoffMs(30000, 600000, 6));
        // 轮数极大时循环提前终止，不溢出
        assertEquals(600000L, ModelHealthStore.computeBackoffMs(30000, 600000, 1000));
        // 上限误配（≤ base）时退化为固定 base，与旧固定冷却行为等价
        assertEquals(30000L, ModelHealthStore.computeBackoffMs(30000, 0, 5));
        assertEquals(30000L, ModelHealthStore.computeBackoffMs(30000, 10000, 5));
        // 轮数非法值兜底为第 1 轮
        assertEquals(30000L, ModelHealthStore.computeBackoffMs(30000, 600000, 0));
        // 上限误配为超大值：饱和处理返回 cap 本身，不整型溢出为负（否则冷却为负 = 熔断立即失效）
        assertEquals(Long.MAX_VALUE, ModelHealthStore.computeBackoffMs(1, Long.MAX_VALUE, 100));
    }

    // ==================== 行为：状态机退避流转 ====================

    @Test
    void repeatedTripsShouldDoubleCooldown() throws InterruptedException {
        ModelHealthStore store = newStore(props(200, 100000));
        String id = "m1";
        // 连续 2 次失败 → OPEN（第 1 轮，冷却 200ms）
        store.markFailure(id);
        store.markFailure(id);
        assertTrue(store.isUnavailable(id));
        // 冷却期内 allowCall 拒绝
        assertFalse(store.allowCall(id));
        // 冷却结束 → 探测放行（HALF_OPEN）
        Thread.sleep(350);
        assertTrue(store.allowCall(id));
        // 探测失败 → 重新 OPEN（第 2 轮，冷却 400ms）：200ms 后应仍不可用
        store.markFailure(id);
        Thread.sleep(250);
        assertTrue(store.isUnavailable(id));
        assertFalse(store.allowCall(id));
        // 第 2 轮冷却过后再次进入探测
        Thread.sleep(300);
        assertTrue(store.allowCall(id));
    }

    @Test
    void markSuccessShouldResetBackoffToBase() throws InterruptedException {
        ModelHealthStore store = newStore(props(200, 100000));
        String id = "m2";
        store.markFailure(id);
        store.markFailure(id);           // OPEN 第 1 轮（200ms）
        Thread.sleep(350);
        assertTrue(store.allowCall(id)); // 探测
        store.markSuccess(id);           // 恢复 CLOSED，轮数清零
        assertFalse(store.isUnavailable(id));
        // 再次熔断应从第 1 轮（200ms）重新起步；若轮数未重置则为 400ms，此处将仍不可用
        store.markFailure(id);
        store.markFailure(id);
        Thread.sleep(350);
        assertTrue(store.allowCall(id));
    }

    // ==================== 远端广播同步 ====================

    @Test
    @SuppressWarnings("unchecked")
    void remoteBroadcastShouldApplySameBackoffRound() throws InterruptedException {
        RedissonClient redisson = mock(RedissonClient.class);
        RTopic topic = mock(RTopic.class);
        when(redisson.getTopic(anyString())).thenReturn(topic);
        ModelHealthStore store = new ModelHealthStore(props(200, 100000), redisson);
        store.init();

        ArgumentCaptor<MessageListener> captor = ArgumentCaptor.forClass(MessageListener.class);
        verify(topic).addListener(eq(String.class), captor.capture());
        MessageListener<String> listener = captor.getValue();

        // 第 3 轮 → 冷却 200×4 = 800ms
        String id = "m3";
        listener.onMessage("channel", id + "|3");
        assertTrue(store.isUnavailable(id));
        Thread.sleep(400);
        // 若未按轮数退避（固定 200ms），此处应已放行
        assertTrue(store.isUnavailable(id));
        Thread.sleep(600);
        assertTrue(store.allowCall(id));

        // 兼容旧格式（无轮数后缀）按第 1 轮处理
        listener.onMessage("channel", "m4");
        assertTrue(store.isUnavailable("m4"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedBroadcastShouldBeIgnoredSafely() throws InterruptedException {
        RedissonClient redisson = mock(RedissonClient.class);
        RTopic topic = mock(RTopic.class);
        when(redisson.getTopic(anyString())).thenReturn(topic);
        ModelHealthStore store = new ModelHealthStore(props(200, 100000), redisson);
        store.init();

        ArgumentCaptor<MessageListener> captor = ArgumentCaptor.forClass(MessageListener.class);
        verify(topic).addListener(eq(String.class), captor.capture());
        MessageListener<String> listener = captor.getValue();

        // 畸形负载：空 modelId / 空白 modelId / 非法轮数 → 不抛异常、不创建垃圾状态条目
        listener.onMessage("channel", "|3");
        listener.onMessage("channel", " |3");
        listener.onMessage("channel", "mX|abc");
        assertFalse(store.isUnavailable(""));
        assertFalse(store.isUnavailable(" "));

        // 非法轮数回退第 1 轮：mX 按 base 200ms 冷却，350ms 后可探测
        Thread.sleep(350);
        assertTrue(store.allowCall("mX"));
    }
}
