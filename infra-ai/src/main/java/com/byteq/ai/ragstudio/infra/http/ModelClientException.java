package com.byteq.ai.ragstudio.infra.http;

import lombok.Getter;

/**
 * 模型客户端自定义异常
 * <p>
 * 用于封装 AI 模型调用过程中发生的各类异常信息，提供统一的异常处理模型。
 * 继承自 {@link RuntimeException}，属于非检查型异常，调用方可以根据需要选择是否捕获。
 * </p>
 *
 * <p>该异常包含以下关键信息：</p>
 * <ul>
 *   <li>{@link #errorType} - 错误类型（未授权、速率限制、服务器错误等），用于分类处理</li>
 *   <li>{@link #statusCode} - HTTP 状态码，保留原始响应的状态信息便于排查</li>
 *   <li>{@link #message} - 人类可读的错误描述信息</li>
 *   <li>{@link #cause} - 原始异常链，保留底层异常信息</li>
 * </ul>
 */
@Getter
public class ModelClientException extends RuntimeException {

    /**
     * 模型客户端错误类型
     * <p>
     * 标识异常的具体类别（如未授权、速率限制、服务器错误等），
     * 上层错误处理逻辑可根据该字段决定重试策略、降级方案或告警级别。
     * </p>
     */
    private final ModelClientErrorType errorType;

    /**
     * 触发异常的 HTTP 状态码
     * <p>
     * 保留原始 HTTP 响应的状态码，便于问题排查和日志记录。
     * 对于非 HTTP 引发的异常（如网络超时），该字段可能为 null。
     * </p>
     */
    private final Integer statusCode;

    /**
     * 构造带原始异常原因的模型客户端异常
     * <p>
     * 适用于捕获到下层异常后重新包装抛出的场景，
     * 保留完整的异常链以便问题追踪。
     * </p>
     *
     * @param message    异常描述消息
     * @param errorType  错误类型，标识异常类别
     * @param statusCode HTTP 状态码（可为 null）
     * @param cause      原始异常原因，保留异常链
     */
    public ModelClientException(String message, ModelClientErrorType errorType, Integer statusCode, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
        this.statusCode = statusCode;
    }

    /**
     * 构造无原始原因的模型客户端异常
     * <p>
     * 适用于在参数校验或业务逻辑中直接抛出的场景，
     * 不需要包装下层异常。
     * </p>
     *
     * @param message    异常描述消息
     * @param errorType  错误类型，标识异常类别
     * @param statusCode HTTP 状态码（可为 null）
     */
    public ModelClientException(String message, ModelClientErrorType errorType, Integer statusCode) {
        super(message);
        this.errorType = errorType;
        this.statusCode = statusCode;
    }
}
