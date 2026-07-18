package com.byteq.ai.ragstudio.alert.service;

import com.byteq.ai.ragstudio.infra.model.ModelHealthStore;
import com.byteq.ai.ragstudio.infra.model.ModelRoutingExecutor;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 告警监控器
 * <p>
 * 监听熔断器事件和全部失败事件，判断是否需要发送告警邮件。
 * </p>
 * <ul>
 *   <li><b>全失败告警</b>：所有候选模型均调用失败时触发</li>
 *   <li><b>频繁熔断告警</b>：单个模型在规定时间窗口内熔断次数超过阈值时触发</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertMonitor {

    private final ModelHealthStore healthStore;
    private final ModelRoutingExecutor routingExecutor;
    private final AlertConfigService configService;
    private final EmailService emailService;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 记录每个模型熔断（OPEN）事件的时间戳
     * key: modelId, value: 有序时间戳队列（最近时间窗口内）
     */
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> circuitBreakerEvents = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 注册熔断器 OPEN 回调
        healthStore.setOnOpenCallback((modelId, count) -> {
            recordCircuitBreakerEvent(modelId);
            checkCircuitBreakerAlert(modelId);
        });

        // 注册全部失败回调
        routingExecutor.setOnAllFailedCallback(targets -> {
            checkAllFailedAlert(targets);
        });

        log.info("告警监控器已初始化");
    }

    /**
     * 记录熔断事件
     */
    private void recordCircuitBreakerEvent(String modelId) {
        if (modelId == null) return;
        long now = System.currentTimeMillis();
        circuitBreakerEvents.computeIfAbsent(modelId, k -> new ConcurrentLinkedDeque<>()).addLast(now);
    }

    /**
     * 检查是否需要发送频繁熔断告警
     */
    private void checkCircuitBreakerAlert(String modelId) {
        if (modelId == null || !configService.isReady()) return;

        int windowHours = configService.getTimeWindowHours();
        int threshold = configService.getFailureThreshold();
        long windowMs = windowHours * 3600L * 1000L;
        long now = System.currentTimeMillis();

        // 清理窗口外的旧事件
        ConcurrentLinkedDeque<Long> events = circuitBreakerEvents.get(modelId);
        if (events == null || events.isEmpty()) return;

        while (!events.isEmpty() && events.peekFirst() < now - windowMs) {
            events.pollFirst();
        }

        // 统计窗口内的事件数
        int count = events.size();
        if (count < threshold) return;

        // 触发告警（但避免重复发送：只记录日志，不做幂等去重—由管理员收到后自行处理）
        log.warn("模型 {} 在 {}h 内熔断 {} 次（阈值 {}），触发告警", modelId, windowHours, count, threshold);

        // 获取供应商信息（从 modelId 中提取）
        String provider = extractProvider(modelId);

        String nowStr = LocalDateTime.now().format(DTF);
        String html = emailService.buildCircuitBreakerHtml(nowStr, modelId, provider, count, windowHours, threshold);
        emailService.sendAlert("RAG Studio · 模型频繁熔断告警", html);
    }

    /**
     * 检查是否需要发送全失败告警
     */
    private void checkAllFailedAlert(List<ModelTarget> targets) {
        if (targets == null || targets.isEmpty() || !configService.isReady()) return;

        List<Map<String, String>> failures = new ArrayList<>();
        for (ModelTarget t : targets) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("provider", t.candidate() != null ? t.candidate().getProvider() : "-");
            row.put("model", t.candidate() != null ? t.candidate().getModel() : t.id());
            row.put("error", "不可用");
            failures.add(row);
        }

        String nowStr = LocalDateTime.now().format(DTF);
        String html = emailService.buildAllFailedHtml(nowStr, failures);
        emailService.sendAlert("RAG Studio · 模型调用全面失败", html);
    }

    /**
     * 从 modelId 中提取提供商名称（格式 "provider-modelName" 或 "provider::model"）
     */
    private String extractProvider(String modelId) {
        if (modelId == null) return "-";
        int idx1 = modelId.indexOf('-');
        int idx2 = modelId.indexOf("::");
        if (idx2 > 0) return modelId.substring(0, idx2);
        if (idx1 > 0) return modelId.substring(0, idx1);
        return modelId;
    }
}
