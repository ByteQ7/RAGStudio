package com.byteq.ai.ragstudio.infra.model;

import com.byteq.ai.ragstudio.infra.config.ModelRoutingProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/**
 * 模型健康状态存储器
 * <p>
 * 用于管理和跟踪各个 AI 模型的健康状况，实现断路器模式（Circuit Breaker Pattern）。
 * 当某个模型连续调用失败达到阈值后，断路器会"断开"（OPEN 状态），后续请求不会再尝试该模型，
 * 避免对已故障的服务造成不必要的压力。经过配置的冷却时间后，断路器进入"半开"（HALF_OPEN）状态，
 * 允许少量请求通过以探测服务是否恢复。
 * </p>
 * <p>
 * <b>状态流转（冷却时长指数退避）：</b>
 * <ul>
 *   <li><b>CLOSED（闭合）</b>：正常工作状态，请求正常通过</li>
 *   <li><b>OPEN（断开）</b>：连续失败达到阈值，请求被直接拒绝，等待冷却时间；
 *       冷却 = min(base × 2^(连续熔断轮数-1), 上限)——持续故障的模型探测频率越来越低，
 *       恢复过的模型（markSuccess）轮数清零，下次熔断从 base 重新起步</li>
 *   <li><b>HALF_OPEN（半开）</b>：冷却时间结束，允许一个探测请求通过</li>
 * </ul>
 * </p>
 * <p>
 * <b>跨实例共享：</b>本实例熔断（OPEN）时通过 Redis Topic 广播，其他实例收到后立即将
 * 本地状态置为 OPEN，避免多实例各自探测/重试故障模型。恢复走各实例本地冷却时间，
 * 冷却结束后各自进入 HALF_OPEN 探测。Redis 不可用时自动退化为纯本地熔断。
 * </p>
 *
 * @author byteq
 * @see ModelRoutingExecutor
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelHealthStore {

    /** 熔断事件广播 Topic：负载为 modelId */
    private static final String BREAKER_OPEN_TOPIC = "RAGStudio:aimodel:breaker:open";

    private final ModelRoutingProperties routingProperties;
    private final RedissonClient redisson;

    /** 模型健康状态缓存，key 为模型 ID，value 为健康状态对象 */
    private final Map<String, ModelHealth> healthById = new ConcurrentHashMap<>();

    /**
     * 断路器进入 OPEN 状态的回调（modelId + 当前连续失败次数）
     * <p>用于告警系统感知熔断事件</p>
     */
    private BiConsumer<String, Integer> onOpenCallback;

    private int listenerId = -1;
    private volatile boolean publishWarned = false;

    @PostConstruct
    public void init() {
        subscribe();
    }

    @PreDestroy
    public void destroy() {
        if (listenerId != -1) {
            try {
                redisson.getTopic(BREAKER_OPEN_TOPIC).removeListener(listenerId);
            } catch (Exception e) {
                log.debug("取消熔断事件订阅失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 订阅其他实例的熔断广播，收到后立即将本地状态置为 OPEN。
     * <p>
     * 负载格式为 {@code modelId|openRounds}（轮数随指数退避同步），远端按同一轮数计算退避冷却；
     * 兼容旧格式（仅 modelId，滚动升级窗口内旧实例发出）：按第 1 轮处理。
     * </p>
     */
    private void subscribe() {
        try {
            RTopic topic = redisson.getTopic(BREAKER_OPEN_TOPIC);
            listenerId = topic.addListener(String.class, (channel, payload) -> {
                if (payload == null || payload.isBlank()) {
                    return;
                }
                int idx = payload.indexOf('|');
                String modelId = idx >= 0 ? payload.substring(0, idx) : payload;
                int rounds = 1;
                if (idx >= 0) {
                    try {
                        rounds = Math.max(1, Integer.parseInt(payload.substring(idx + 1)));
                    } catch (NumberFormatException e) {
                        rounds = 1;
                    }
                }
                log.warn("收到其他实例熔断广播，模型 {} 置为断开状态（第 {} 轮，冷却 {}ms）",
                        modelId, rounds, computeCooldownMs(rounds));
                markOpenFromRemote(modelId, rounds);
            });
            log.info("模型熔断事件跨实例广播订阅成功");
        } catch (Exception e) {
            log.warn("订阅模型熔断事件广播失败，跨实例熔断共享不可用: {}", e.getMessage());
        }
    }

    /**
     * 根据远程熔断广播将本地状态置为 OPEN（轮数与退避冷却随广播同步，
     * 冷却时间按本实例配置独立计算）
     */
    private void markOpenFromRemote(String id, int openRounds) {
        long now = System.currentTimeMillis();
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                v = new ModelHealth();
            }
            v.state = State.OPEN;
            v.openRounds = Math.max(1, openRounds);
            v.openUntil = now + computeCooldownMs(v.openRounds);
            v.consecutiveFailures = 0;
            v.halfOpenInFlight = false;
            return v;
        });
    }

    /**
     * 广播本实例熔断事件（负载 = modelId|openRounds，Redis 不可用时静默降级）
     */
    private void publishOpen(String modelId, int openRounds) {
        try {
            redisson.getTopic(BREAKER_OPEN_TOPIC).publish(modelId + "|" + openRounds);
        } catch (Exception e) {
            if (!publishWarned) {
                publishWarned = true;
                log.warn("发布模型熔断广播失败（跨实例熔断共享不可用）: {}", e.getMessage());
            } else {
                log.debug("发布模型熔断广播失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 判断模型当前是否不可用
     * <p>
     * 不可用的判定条件：
     * <ul>
     *   <li>断路器处于 OPEN 状态且未超过冷却时间</li>
     *   <li>断路器处于 HALF_OPEN 状态且已有探测请求正在执行</li>
     * </ul>
     * </p>
     *
     * @param id 模型 ID
     * @return true 表示模型当前不可用，false 表示可用
     */
    public boolean isUnavailable(String id) {
        ModelHealth health = healthById.get(id);
        if (health == null) {
            return false;
        }
        // OPEN 状态下且在冷却期内，判定为不可用
        if (health.state == State.OPEN && health.openUntil > System.currentTimeMillis()) {
            return true;
        }
        // HALF_OPEN 状态下已有探测请求在执行，不再接受新请求；但若超时则视为探测已失效
        return health.state == State.HALF_OPEN && health.halfOpenInFlight
                && !isHalfOpenTimedOut(health);
    }

    // 判断半开状态下的探测请求是否已超时（超过冷却时间视为探测失效）。
    // 刻意使用 base 时长而不随退避放大：探测只是单个请求，不应因失败轮数增多
    // 而允许更长的挂起时间，否则封顶轮次下一个 hang 住的探测会拖满封顶时长才被替换
    private boolean isHalfOpenTimedOut(ModelHealth health) {
        if (!health.halfOpenInFlight || health.halfOpenStartedAt == 0) {
            return false;
        }
        long timeoutMs = routingProperties.getSelection().getOpenDurationMs();
        return System.currentTimeMillis() - health.halfOpenStartedAt > timeoutMs;
    }

    /**
     * 当前轮次的指数退避冷却时长
     */
    private long computeCooldownMs(int rounds) {
        return computeBackoffMs(routingProperties.getSelection().getOpenDurationMs(),
                routingProperties.getSelection().getMaxOpenDurationMs(), rounds);
    }

    /**
     * 指数退避冷却计算（纯函数）：冷却 = min(base × 2^(rounds-1), max)。
     * 上限误配（≤ base）时退化为固定 base，与旧固定冷却行为等价
     */
    static long computeBackoffMs(long base, long max, int rounds) {
        long cap = Math.max(base, max);
        long cooldown = base;
        for (int i = 1; i < Math.max(1, rounds) && cooldown < cap; i++) {
            cooldown <<= 1;
        }
        return Math.min(cooldown, cap);
    }

    /**
     * 判断是否允许发起模型调用
     * <p>
     * 该方法在 `isUnavailable` 的基础上增加了状态自动迁移逻辑：
     * <ul>
     *   <li>如果当前为 OPEN 状态且冷却时间已过，自动转为 HALF_OPEN 并允许请求</li>
     *   <li>如果当前为 HALF_OPEN 且没有正在执行的探测请求，允许请求并标记为 in-flight</li>
     *   <li>如果当前为 CLOSED，直接允许请求</li>
     * </ul>
     * 使用 {@code compute} 原子操作确保状态变更的线程安全。
     * </p>
     *
     * @param id 模型 ID
     * @return true 允许调用，false 拒绝调用
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean allowCall(String id) {
        if (id == null) {
            return false;
        }
        // 记录现在时间
        long now = System.currentTimeMillis();
        //
        AtomicBoolean allowed = new AtomicBoolean(false);
        // ConcurrentHashMap 对同一个 key 的操作是原子的
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                v = new ModelHealth();
            }
            // OPEN 状态下检查冷却时间是否已过，过了则转入 HALF_OPEN 并允许一次探测
            if (v.state == State.OPEN) {
                if (v.openUntil > now) {
                    return v;
                }
                v.state = State.HALF_OPEN;
                v.halfOpenInFlight = true;
                v.halfOpenStartedAt = now;
                allowed.set(true);
                return v;
            }
            // HALF_OPEN 状态下，如果没有正在执行的探测请求（或已超时）则允许通过
            if (v.state == State.HALF_OPEN) {
                if (v.halfOpenInFlight && !isHalfOpenTimedOut(v)) {
                    return v;
                }
                v.halfOpenInFlight = true;
                v.halfOpenStartedAt = now;
                allowed.set(true);
                return v;
            }
            // CLOSED 状态下直接允许请求
            allowed.set(true);
            return v;
        });
        return allowed.get();
    }

    /**
     * 标记模型调用成功
     * <p>
     * 将模型状态重置为 CLOSED，清零连续失败计数、退避轮数和冷却时间。
     * 这表示模型已恢复正常工作，下次熔断将从基础冷却时长重新起步。
     * </p>
     *
     * @param id 模型 ID
     */
    public void markSuccess(String id) {
        if (id == null) {
            return;
        }
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                return new ModelHealth();
            }
            v.state = State.CLOSED;
            v.consecutiveFailures = 0;
            v.openRounds = 0;
            v.openUntil = 0L;
            v.halfOpenInFlight = false;
            return v;
        });
    }

    /**
     * 设置 OPEN 状态回调
     */
    public void setOnOpenCallback(BiConsumer<String, Integer> callback) {
        this.onOpenCallback = callback;
    }

    /**
     * 获取指定模型的当前状态（用于告警系统查询）
     */
    public String getModelState(String id) {
        if (id == null) return null;
        ModelHealth health = healthById.get(id);
        return health != null ? health.state.name() : "CLOSED";
    }

    /**
     * 标记模型调用失败
     * <p>
     * 累加连续失败计数。在 HALF_OPEN 状态下失败会直接回到 OPEN 状态（轮数 +1，冷却指数退避）；
     * 在 CLOSED 状态下，当连续失败次数达到阈值时也会切换到 OPEN 状态并开始退避冷却。
     * </p>
     *
     * @param id 模型 ID
     */
    public void markFailure(String id) {
        if (id == null) {
            return;
        }
        long now = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicBoolean changedToOpen = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicInteger failureCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger openRounds = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicLong cooldownMs = new java.util.concurrent.atomic.AtomicLong(0);
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                v = new ModelHealth();
            }
            if (v.state == State.HALF_OPEN) {
                // 探测失败重新熔断：轮数累加，冷却按退避翻倍
                v.openRounds++;
                v.state = State.OPEN;
                long cooldown = computeCooldownMs(v.openRounds);
                v.openUntil = now + cooldown;
                v.consecutiveFailures = 0;
                v.halfOpenInFlight = false;
                changedToOpen.set(true);
                failureCount.set(routingProperties.getSelection().getFailureThreshold());
                openRounds.set(v.openRounds);
                cooldownMs.set(cooldown);
                return v;
            }
            v.consecutiveFailures++;
            if (v.consecutiveFailures >= routingProperties.getSelection().getFailureThreshold()) {
                failureCount.set(v.consecutiveFailures);
                v.openRounds++;
                v.state = State.OPEN;
                long cooldown = computeCooldownMs(v.openRounds);
                v.openUntil = now + cooldown;
                v.consecutiveFailures = 0;
                v.halfOpenInFlight = false;
                changedToOpen.set(true);
                openRounds.set(v.openRounds);
                cooldownMs.set(cooldown);
            }
            return v;
        });
        if (changedToOpen.get()) {
            log.warn("模型 {} 熔断（第 {} 轮），冷却 {}ms（指数退避，上限 {}ms）",
                    id, openRounds.get(), cooldownMs.get(), routingProperties.getSelection().getMaxOpenDurationMs());
        }
        if (changedToOpen.get() && onOpenCallback != null) {
            onOpenCallback.accept(id, failureCount.get());
        }
        if (changedToOpen.get()) {
            publishOpen(id, openRounds.get());
        }
    }

    /**
     * 模型健康状态内部类
     * <p>
     * 追踪单个模型的连续失败次数、断路器状态、退避轮数和冷却时间。
     * 所有字段使用 {@code volatile} 保证多线程可见性。
     * </p>
     */
    private static class ModelHealth {
        // 连续失败次数
        private volatile int consecutiveFailures;

        // 熔断截至时间戳
        private volatile long openUntil;

        // 连续熔断轮数（指数退避用）：每次进入 OPEN 时 +1，markSuccess 恢复后清零
        private volatile int openRounds;

        // 是否有探测请求正在探测
        private volatile boolean halfOpenInFlight;

        // 探测请求开始时间戳，用于超时检测
        private volatile long halfOpenStartedAt;

        // 当前状态
        private volatile State state;

        private ModelHealth() {
            this.consecutiveFailures = 0;
            this.openUntil = 0L;
            this.openRounds = 0;
            this.halfOpenInFlight = false;
            this.halfOpenStartedAt = 0L;
            this.state = State.CLOSED;
        }
    }

    /**
     * 断路器状态枚举
     */
    private enum State {
        /** 闭合状态：正常工作，请求正常通过 */
        CLOSED,
        /** 断开状态：模型不可用，请求被拒绝 */
        OPEN,
        /** 半开状态：允许探测请求以检查模型是否恢复 */
        HALF_OPEN
    }
}
