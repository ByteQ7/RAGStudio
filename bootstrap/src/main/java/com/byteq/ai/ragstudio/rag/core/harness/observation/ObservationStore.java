package com.byteq.ai.ragstudio.rag.core.harness.observation;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.rag.config.ObservationMaskProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 请求内观察存储（Observation Mask）
 * <p>
 * 保存本请求所有已登记的工具结果全文与句柄，供两类消费方使用：
 * <ul>
 *   <li>{@link ObservationMaskMiddleware}：每轮模型调用前把旧观察压缩为「结论 + 句柄」；</li>
 *   <li>{@link ObservationReaderTool}：Agent 凭句柄按需回读全文/检索/区间读取。</li>
 * </ul>
 * 生命周期与单次 Agent 运行绑定（请求结束随 RunState 丢弃），不落库、不跨请求共享。
 * 采用「登记时定序 + 掩码文本冻结」策略：一旦某观察被压缩，其掩码文本不再变化，
 * 保证多轮请求前缀稳定（KV-cache 友好，参考 Manus/Anthropic 的缓存实践）。
 */
@Slf4j
public class ObservationStore {

    private static final String HANDLE_PREFIX = "obs_";
    private static final int ARG_VALUE_MAX_LEN = 80;
    private static final int ARGS_MAX_LEN = 300;

    private final ObservationMaskProperties properties;

    private final Map<String, ObservationEntry> byToolCallId = new ConcurrentHashMap<>();

    private final Map<String, ObservationEntry> byHandle = new ConcurrentHashMap<>();

    /** 句柄自增序号（run 内唯一） */
    private final AtomicInteger sequence = new AtomicInteger();

    /** 已登记观察原文总字符数 */
    private final AtomicInteger storedChars = new AtomicInteger();

    /** 已捕获观察的最大轮次（判定"最新一轮批次"用） */
    private final AtomicInteger maxIteration = new AtomicInteger(-1);

    private final AtomicLong maskedObservations = new AtomicLong();

    private final AtomicLong savedChars = new AtomicLong();

    private final AtomicInteger readerCalls = new AtomicInteger();

    public ObservationStore(ObservationMaskProperties properties) {
        this.properties = properties;
    }

    /**
     * 登记一次工具观察（不满足压缩条件时返回 null，结果保持原文）
     *
     * @param toolCallId AgentScope 工具调用 ID
     * @param toolName   工具名
     * @param toolInput  工具参数
     * @param fullText   完整观察文本
     * @param iteration  产生该观察的模型调用轮次
     * @param success    工具调用是否成功
     * @return 观察条目；未登记时返回 null
     */
    public ObservationEntry register(String toolCallId, String toolName, Map<String, Object> toolInput,
                                     String fullText, int iteration, boolean success) {
        if (StrUtil.isBlank(toolCallId) || StrUtil.isBlank(fullText)) {
            return null;
        }
        ObservationEntry existing = byToolCallId.get(toolCallId);
        if (existing != null) {
            return existing;
        }
        if (fullText.length() < properties.getMinCharsToMask()) {
            return null;
        }
        if (!success && !Boolean.TRUE.equals(properties.getMaskErrors())) {
            return null;
        }
        if (properties.getExcludeTools() != null && properties.getExcludeTools().contains(toolName)) {
            return null;
        }
        if (byToolCallId.size() >= properties.getMaxObservations()
                || storedChars.get() + fullText.length() > properties.getMaxStoredChars()) {
            log.debug("观察存储容量已满，保持原文不压缩: tool={}, chars={}", toolName, fullText.length());
            return null;
        }

        ObservationEntry entry = new ObservationEntry(
                HANDLE_PREFIX + sequence.incrementAndGet(), toolCallId, toolName,
                toolInput, fullText, iteration, success);
        ObservationEntry race = byToolCallId.putIfAbsent(toolCallId, entry);
        if (race != null) {
            return race;
        }
        byHandle.put(entry.getHandle(), entry);
        storedChars.addAndGet(fullText.length());
        maxIteration.accumulateAndGet(iteration, Math::max);
        return entry;
    }

    public ObservationEntry findByToolCallId(String toolCallId) {
        return toolCallId != null ? byToolCallId.get(toolCallId) : null;
    }

    public ObservationEntry findByHandle(String handle) {
        return handle != null ? byHandle.get(handle.trim()) : null;
    }

    /**
     * 计算并冻结某条观察的掩码文本；不满足压缩条件时返回 null（保持原文）。
     * <p>
     * 条件：结论已就绪（READY/DIGEST）+ 不在最近 {@code keepRecentRounds} 轮批次内。
     */
    public String maskedText(ObservationEntry entry) {
        if (entry == null || !entry.isConclusive()) {
            return null;
        }
        int keepFrom = maxIteration.get() - properties.getKeepRecentRounds() + 1;
        if (entry.getIteration() >= keepFrom) {
            return null;
        }
        String frozen = entry.getMaskedText();
        if (frozen != null) {
            return frozen;
        }
        String candidate = formatMasked(entry);
        String result = entry.freezeMaskedText(candidate);
        if (result.equals(candidate)) {
            maskedObservations.incrementAndGet();
            savedChars.addAndGet(Math.max(0, entry.getFullText().length() - candidate.length()));
            log.debug("观察已压缩: handle={}, tool={}, before={}, after={}",
                    entry.getHandle(), entry.getToolName(), entry.getFullText().length(), candidate.length());
        }
        return result;
    }

    /** 当前可回读的句柄清单（回读失败时提示模型） */
    public List<String> availableHandles() {
        List<ObservationEntry> entries = new ArrayList<>(byHandle.values());
        entries.sort(Comparator.comparing(ObservationEntry::getHandle));
        List<String> handles = new ArrayList<>(entries.size());
        for (ObservationEntry entry : entries) {
            handles.add(entry.getHandle() + "（" + entry.getToolName() + "）");
        }
        return handles;
    }

    public int size() {
        return byToolCallId.size();
    }

    public long maskedObservations() {
        return maskedObservations.get();
    }

    public long savedChars() {
        return savedChars.get();
    }

    public int readerCalls() {
        return readerCalls.get();
    }

    public void recordReaderCall() {
        readerCalls.incrementAndGet();
    }

    // ==================== 掩码文本格式 ====================

    private String formatMasked(ObservationEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("[观察已压缩 ").append(entry.getHandle()).append("] 工具 ").append(entry.getToolName());
        String args = formatInput(entry.getToolInput());
        if (StrUtil.isNotBlank(args)) {
            sb.append(" 参数: ").append(args);
        }
        sb.append('\n');
        if (entry.getStatus() == ObservationEntry.Status.READY) {
            sb.append("结论：").append(entry.maskBody());
        } else {
            sb.append("结论（提取失败/超时，以下为头尾摘要）：\n").append(entry.maskBody());
        }
        sb.append("\n（完整结果 ").append(entry.getFullText().length())
                .append(" 字已从上下文省略；需要原文或精确核对时调用 observation_reader(handle=\"")
                .append(entry.getHandle()).append("\")）");
        return sb.toString();
    }

    private String formatInput(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : input.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            String value = String.valueOf(e.getValue());
            if (value.length() > ARG_VALUE_MAX_LEN) {
                value = value.substring(0, ARG_VALUE_MAX_LEN) + "…";
            }
            sb.append(e.getKey()).append('=').append(value);
            if (sb.length() > ARGS_MAX_LEN) {
                sb.append(" …");
                break;
            }
        }
        return sb.toString();
    }
}
