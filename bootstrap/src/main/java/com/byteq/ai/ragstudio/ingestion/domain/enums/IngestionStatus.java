package com.byteq.ai.ragstudio.ingestion.domain.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 摄入任务状态枚举
 * <p>
 * 定义文档摄入任务的执行状态，用于追踪任务在整个生命周期中的状态变化。
 * 状态流转如下：
 * <pre>
 * PENDING → RUNNING → COMPLETED
 *                    ↘ FAILED
 * </pre>
 * </p>
 * <p>
 * 状态值使用小写 snake_case，如 pending、running、completed、failed。
 * 支持 JSON 序列化/反序列化。
 * </p>
 */
@Getter
@RequiredArgsConstructor
public enum IngestionStatus {

    /**
     * 等待中
     * 任务已创建但尚未开始执行，等待引擎调度
     */
    PENDING("pending"),

    /**
     * 运行中
     * 任务正在执行中，流水线引擎正在按顺序处理各个节点
     */
    RUNNING("running"),

    /**
     * 失败
     * 任务执行失败，流水线中某个节点处理出错导致整体任务终止
     */
    FAILED("failed"),

    /**
     * 已完成
     * 任务执行成功完成，所有节点均已处理完毕
     */
    COMPLETED("completed");

    /**
     * 状态值（小写 snake_case）
     * 用于 JSON 序列化和配置解析
     */
    private final String value;

    /**
     * 根据字符串值解析任务状态
     * <p>
     * 支持两种格式的输入：
     * <ul>
     *   <li>小写值：如 "pending"、"running"、"completed"、"failed"</li>
     *   <li>枚举名称：如 "PENDING"、"RUNNING"、"COMPLETED"、"FAILED"</li>
     * </ul>
     * </p>
     *
     * @param value 状态字符串
     * @return 对应的枚举值，如果输入为 null 则返回 null
     * @throws IllegalArgumentException 如果无法匹配任何已知状态
     */
    @JsonCreator
    public static IngestionStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = normalize(value);
        for (IngestionStatus status : values()) {
            if (status.value.equalsIgnoreCase(normalized) || status.name().equalsIgnoreCase(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown ingestion status: " + value);
    }

    /**
     * 规范化输入字符串
     * 去除首尾空白、转小写、将连字符替换为下划线
     */
    private static String normalize(String value) {
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase();
        return lower.replace('-', '_');
    }

    /**
     * 获取 JSON 序列化值
     *
     * @return 小写 snake_case 格式的状态值
     */
    @JsonValue
    public String getValue() {
        return value;
    }
}
