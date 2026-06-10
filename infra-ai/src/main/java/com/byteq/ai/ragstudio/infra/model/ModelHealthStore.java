package com.byteq.ai.ragstudio.infra.model;

import com.byteq.ai.ragstudio.infra.config.AIModelProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型健康状态存储器
 * <p>
 * 用于管理和跟踪各个 AI 模型的健康状况，实现断路器模式（Circuit Breaker Pattern）。
 * 当某个模型连续调用失败达到阈值后，断路器会"断开"（OPEN 状态），后续请求不会再尝试该模型，
 * 避免对已故障的服务造成不必要的压力。经过配置的冷却时间后，断路器进入"半开"（HALF_OPEN）状态，
 * 允许少量请求通过以探测服务是否恢复。
 * </p>
 * <p>
 * <b>状态流转：</b>
 * <ul>
 *   <li><b>CLOSED（闭合）</b>：正常工作状态，请求正常通过</li>
 *   <li><b>OPEN（断开）</b>：连续失败达到阈值，请求被直接拒绝，等待冷却时间</li>
 *   <li><b>HALF_OPEN（半开）</b>：冷却时间结束，允许一个探测请求通过</li>
 * </ul>
 * </p>
 *
 * @author byteq
 * @see ModelRoutingExecutor
 */
@Component
@RequiredArgsConstructor
public class ModelHealthStore {

    private final AIModelProperties properties;

    /**
     * 模型健康状态缓存，key 为模型 ID，value 为健康状态对象
     * 使用 ConcurrentHashMap 保证并发安全
     */
    private final Map<String, ModelHealth> healthById = new ConcurrentHashMap<>();

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
        // HALF_OPEN 状态下已有探测请求在执行，不再接受新请求
        return health.state == State.HALF_OPEN && health.halfOpenInFlight;
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
                allowed.set(true);
                return v;
            }
            // HALF_OPEN 状态下，如果没有正在执行的探测请求则允许通过
            if (v.state == State.HALF_OPEN) {
                if (v.halfOpenInFlight) {
                    return v;
                }
                v.halfOpenInFlight = true;
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
     * 将模型状态重置为 CLOSED，清零连续失败计数和冷却时间。
     * 这表示模型已恢复正常工作。
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
            v.openUntil = 0L;
            v.halfOpenInFlight = false;
            return v;
        });
    }

    /**
     * 标记模型调用失败
     * <p>
     * 累加连续失败计数。在 HALF_OPEN 状态下失败会直接回到 OPEN 状态（重新开始冷却）；
     * 在 CLOSED 状态下，当连续失败次数达到阈值时也会切换到 OPEN 状态并开始冷却。
     * </p>
     *
     * @param id 模型 ID
     */
    public void markFailure(String id) {
        if (id == null) {
            return;
        }
        long now = System.currentTimeMillis();
        healthById.compute(id, (k, v) -> {
            if (v == null) {
                v = new ModelHealth();
            }
            // HALF_OPEN 状态下失败：探测失败，立即回到 OPEN 状态并重置冷却
            if (v.state == State.HALF_OPEN) {
                v.state = State.OPEN;
                v.openUntil = now + properties.getSelection().getOpenDurationMs();
                v.consecutiveFailures = 0;
                v.halfOpenInFlight = false;
                return v;
            }
            // CLOSED 状态下失败：累加失败次数，达到阈值时切换到 OPEN 状态
            v.consecutiveFailures++;
            if (v.consecutiveFailures >= properties.getSelection().getFailureThreshold()) {
                v.state = State.OPEN;
                v.openUntil = now + properties.getSelection().getOpenDurationMs();
                v.consecutiveFailures = 0;
            }
            return v;
        });
    }

    /**
     * 模型健康状态内部类
     * <p>
     * 追踪单个模型的连续失败次数、断路器状态和冷却时间。
     * 所有字段使用 {@code volatile} 保证多线程可见性。
     * </p>
     */
    private static class ModelHealth {
        // 连续失败次数
        private volatile int consecutiveFailures;

        // 熔断截至时间戳
        private volatile long openUntil;

        // 是否有探测请求正在探测
        private volatile boolean halfOpenInFlight;

        // 当前状态
        private volatile State state;

        private ModelHealth() {
            this.consecutiveFailures = 0;
            this.openUntil = 0L;
            this.halfOpenInFlight = false;
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
