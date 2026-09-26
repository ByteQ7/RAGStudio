package com.byteq.ai.ragstudio.rag.core.harness.observation;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单次工具观察（Observation Mask 单元）
 * <p>
 * 保存一次工具调用的完整结果与句柄（指针），并承载「结论提取」的状态机：
 * <ul>
 *   <li>{@link Status#PENDING}：登记后异步提取结论中，此阶段不参与压缩；</li>
 *   <li>{@link Status#READY}：轻量模型结论已就绪；</li>
 *   <li>{@link Status#DIGEST}：提取失败/超时，使用头尾摘要兜底。</li>
 * </ul>
 * 一旦被压缩，掩码文本即冻结（KV-cache 前缀稳定），后续结论就绪也不再改写。
 */
@Getter
public class ObservationEntry {

    /** 结论提取状态 */
    public enum Status {
        PENDING,
        READY,
        DIGEST
    }

    /** 头尾摘要兜底：头部保留字符数 */
    private static final int DIGEST_HEAD = 240;
    /** 头尾摘要兜底：尾部保留字符数 */
    private static final int DIGEST_TAIL = 120;

    /** 短句柄（如 obs_1），模型据此回读 */
    private final String handle;

    /** AgentScope 工具调用 ID（用于匹配上下文中的 ToolResultBlock） */
    private final String toolCallId;

    private final String toolName;

    private final Map<String, Object> toolInput;

    /** 工具结果完整文本（压缩后仍保留于请求内存，供回读） */
    private final String fullText;

    /** 产生该观察的模型调用轮次（与 AgentStep.iteration 同源） */
    private final int iteration;

    /** 工具调用是否成功 */
    private final boolean success;

    private final long createdAt = System.currentTimeMillis();

    private final AtomicReference<Status> status = new AtomicReference<>(Status.PENDING);

    private volatile String conclusion;

    private volatile String digest;

    /** 已冻结的掩码文本（首次压缩时生成，之后不再变化） */
    private volatile String maskedText;

    public ObservationEntry(String handle, String toolCallId, String toolName,
                            Map<String, Object> toolInput, String fullText,
                            int iteration, boolean success) {
        this.handle = handle;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.toolInput = toolInput != null ? Map.copyOf(toolInput) : Map.of();
        this.fullText = fullText;
        this.iteration = iteration;
        this.success = success;
    }

    /** 结论是否已就绪（READY/DIGEST 均可压缩；PENDING 保持原文等待） */
    public boolean isConclusive() {
        return status.get() != Status.PENDING;
    }

    /** 当前提取状态（Lombok 不会为 AtomicReference 字段生成此显式方法） */
    public Status getStatus() {
        return status.get();
    }

    /** 提取成功：写入结论并置 READY（仅在 PENDING 时生效） */
    public boolean markReady(String conclusionText) {
        if (StrUtil.isBlank(conclusionText)) {
            return markDigest();
        }
        this.conclusion = conclusionText.trim();
        return status.compareAndSet(Status.PENDING, Status.READY);
    }

    /** 提取失败/超时：生成头尾摘要并置 DIGEST（仅在 PENDING 时生效） */
    public boolean markDigest() {
        if (digest == null) {
            digest = buildDigest();
        }
        return status.compareAndSet(Status.PENDING, Status.DIGEST);
    }

    /** 掩码正文：READY 用结论，DIGEST 用头尾摘要 */
    public String maskBody() {
        if (status.get() == Status.READY && StrUtil.isNotBlank(conclusion)) {
            return conclusion;
        }
        if (digest == null) {
            digest = buildDigest();
        }
        return digest;
    }

    /** 冻结掩码文本：并发下只有第一个候选生效 */
    public String freezeMaskedText(String candidate) {
        synchronized (this) {
            if (maskedText == null) {
                maskedText = candidate;
            }
            return maskedText;
        }
    }

    private String buildDigest() {
        if (fullText == null) {
            return "";
        }
        if (fullText.length() <= DIGEST_HEAD + DIGEST_TAIL + 40) {
            return fullText;
        }
        int omitted = fullText.length() - DIGEST_HEAD - DIGEST_TAIL;
        return fullText.substring(0, DIGEST_HEAD)
                + "\n……（中间省略 " + omitted + " 字）……\n"
                + fullText.substring(fullText.length() - DIGEST_TAIL);
    }
}
