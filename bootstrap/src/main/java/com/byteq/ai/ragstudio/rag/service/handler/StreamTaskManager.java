package com.byteq.ai.ragstudio.rag.service.handler;

import cn.hutool.core.util.StrUtil;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.byteq.ai.ragstudio.rag.enums.SSEEventType;
import com.byteq.ai.ragstudio.rag.dto.CompletionPayload;
import com.byteq.ai.ragstudio.framework.web.SseEmitterSender;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 流式任务管理器
 * <p>
 * 管理 SSE 流式对话任务的生命周期，支持本地和分布式（Redis）两级取消机制。
 * 通过 Guava Cache 维护本地任务注册表，通过 Redis Topic 实现跨实例取消通知。
 * </p>
 */
@Slf4j
@Component
public class StreamTaskManager {

    private static final String CANCEL_TOPIC = "ragstudio:stream:cancel";
    private static final String CANCEL_KEY_PREFIX = "ragstudio:stream:cancel:";
    private static final Duration CANCEL_TTL = Duration.ofMinutes(30);

    private final Cache<String, StreamTaskInfo> tasks = CacheBuilder.newBuilder()
            .expireAfterWrite(CANCEL_TTL)
            .maximumSize(10000)  // 限制最大数量，基本上不可能超出这个数量。如果觉得不稳妥，可以把值调大并在配置文件声明
            .build();

    private final RedissonClient redissonClient;
    private int listenerId = -1;

    public StreamTaskManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 启动时订阅 Redis 取消主题，接收跨实例的取消通知并触发本地取消
     */
    @PostConstruct
    public void subscribe() {
        RTopic topic = redissonClient.getTopic(CANCEL_TOPIC);
        listenerId = topic.addListener(String.class, (channel, taskId) -> {
            if (StrUtil.isBlank(taskId)) {
                return;
            }
            cancelLocal(taskId);
        });
    }

    /**
     * 销毁时取消订阅 Redis 取消主题
     */
    @PreDestroy
    public void unsubscribe() {
        if (listenerId == -1) {
            return;
        }
        redissonClient.getTopic(CANCEL_TOPIC).removeListener(listenerId);
    }

    /**
     * 注册流式任务，绑定 SSE 发送器和取消回调
     * <p>
     * 注册后会立即检查 Redis 中是否已有取消标记，若有则直接触发取消流程。
     * </p>
     *
     * @param taskId           任务 ID
     * @param sender           SSE 发送器
     * @param onCancelSupplier 取消时执行的回调，返回完成载荷用于通知前端
     */
    public void register(String taskId, SseEmitterSender sender, Supplier<CompletionPayload> onCancelSupplier) {
        StreamTaskInfo taskInfo = new StreamTaskInfo();
        taskInfo.sender = sender;
        taskInfo.onCancelSupplier = onCancelSupplier;
        StreamTaskInfo existing = tasks.asMap().putIfAbsent(taskId, taskInfo);
        if (existing != null) {
            synchronized (existing) {
                // 在同步块内更新所有volatile字段，确保可见性和一致性
                existing.sender = sender;
                existing.onCancelSupplier = onCancelSupplier;
                taskInfo = existing;
            }
        }
        
        Supplier<CompletionPayload> cancelSupplier;
        SseEmitterSender taskSender;
        synchronized (taskInfo) {
            cancelSupplier = taskInfo.onCancelSupplier;
            taskSender = taskInfo.sender;
        }
        
        if (isTaskCancelledInRedis(taskId, taskInfo)) {
            CompletionPayload payload = cancelSupplier.get();
            sendCancelAndDone(taskSender, payload);
            taskSender.complete();
        }
    }

    /**
     * 绑定底层流式取消句柄，使取消操作能中断 LLM 的 HTTP 连接
     *
     * @param taskId 任务 ID
     * @param handle 流式取消句柄
     */
    public void bindHandle(String taskId, StreamCancellationHandle handle) {
        StreamTaskInfo taskInfo = getOrCreate(taskId);
        taskInfo.handle = handle;
        if (taskInfo.cancelled.get() && handle != null) {
            handle.cancel();
        }
    }

    /**
     * 检查指定任务是否已被取消
     *
     * @param taskId 任务 ID
     * @return true 表示已取消
     */
    public boolean isCancelled(String taskId) {
        StreamTaskInfo info = tasks.getIfPresent(taskId);
        return info != null && info.cancelled.get();
    }

    /**
     * 取消指定任务，通过 Redis 发布取消通知到所有实例
     * <p>
     * 先在 Redis 设置取消标记，再通过 Topic 广播通知，确保跨实例一致性。
     * </p>
     *
     * @param taskId 任务 ID
     */
    public void cancel(String taskId) {
        // 先设置 Redis 标记，再发布消息
        RBucket<Boolean> bucket = redissonClient.getBucket(cancelKey(taskId));
        bucket.set(Boolean.TRUE, CANCEL_TTL);

        // 发布消息通知所有节点（包括本地）
        // 本地节点也通过监听器统一处理，避免重复调用 cancelLocal
        redissonClient.getTopic(CANCEL_TOPIC).publish(taskId);
    }

    /**
     * 检查任务是否在 Redis 中被标记为已取消
     * 如果是，会同步状态到本地缓存
     */
    private boolean isTaskCancelledInRedis(String taskId, StreamTaskInfo taskInfo) {
        if (taskInfo.cancelled.get()) {
            return true;
        }

        RBucket<Boolean> bucket = redissonClient.getBucket(cancelKey(taskId));
        Boolean cancelled = bucket.get();
        if (Boolean.TRUE.equals(cancelled)) {
            taskInfo.cancelled.set(true);
            return true;
        }
        return false;
    }

    // 本地取消任务: CAS 确保只执行一次 -> 中断 LLM 连接 -> 执行取消回调保存已有内容
    private void cancelLocal(String taskId) {
        StreamTaskInfo taskInfo = tasks.getIfPresent(taskId);
        if (taskInfo == null) {
            return;
        }

        // 使用 CAS 确保只执行一次
        if (!taskInfo.cancelled.compareAndSet(false, true)) {
            return;
        }

        if (taskInfo.handle != null) {
            taskInfo.handle.cancel();
        }

        // 在取消时执行回调，保存已累积的内容
        if (taskInfo.sender != null && taskInfo.onCancelSupplier != null) {
            CompletionPayload payload = taskInfo.onCancelSupplier.get();
            sendCancelAndDone(taskInfo.sender, payload);
            taskInfo.sender.complete();
        }
    }

    /**
     * 注销任务，清理本地缓存和 Redis 取消标记
     *
     * @param taskId 任务 ID
     */
    public void unregister(String taskId) {
        // 清理本地缓存
        tasks.invalidate(taskId);

        // 清理Redis
        redissonClient.getBucket(cancelKey(taskId)).deleteAsync();
    }

    // 生成 Redis 取消标记的 Key
    private String cancelKey(String taskId) {
        return CANCEL_KEY_PREFIX + taskId;
    }

    // 向前端发送取消事件和完成信号
    private void sendCancelAndDone(SseEmitterSender sender, CompletionPayload payload) {
        CompletionPayload actualPayload = payload == null ? new CompletionPayload(null, null) : payload;
        sender.sendEvent(SSEEventType.CANCEL.value(), actualPayload);
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
    }

    // 从缓存获取任务信息，不存在则创建新的空任务信息
    @SneakyThrows
    private StreamTaskInfo getOrCreate(String taskId) {
        return tasks.get(taskId, StreamTaskInfo::new);
    }

    private static final class StreamTaskInfo {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile StreamCancellationHandle handle;
        private volatile SseEmitterSender sender;
        private volatile Supplier<CompletionPayload> onCancelSupplier;
    }
}
