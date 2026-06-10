package com.byteq.ai.ragstudio.framework.exception;

import com.byteq.ai.ragstudio.framework.errorcode.BaseErrorCode;
import com.byteq.ai.ragstudio.framework.errorcode.IErrorCode;

/**
 * 客户端异常
 *
 * <p>用户发起调用请求后，因客户端提交的参数错误、操作不当或权限不足等问题导致的异常。
 * 对应错误码规范中的 {@code A 类错误}（用户端错误）。</p>
 *
 * <p>典型场景：</p>
 * <ul>
 *   <li>请求参数校验不通过（如格式错误、必填字段缺失）</li>
 *   <li>用户未登录或登录已过期（配合 Sa-Token 认证框架）</li>
 *   <li>用户无权限执行某操作</li>
 *   <li>重复提交请求</li>
 * </ul>
 *
 * @see BaseErrorCode#CLIENT_ERROR
 */
public class ClientException extends AbstractException {

    /**
     * 使用默认客户端错误码构造异常
     *
     * @param errorCode 错误码定义
     */
    public ClientException(IErrorCode errorCode) {
        this(null, null, errorCode);
    }

    /**
     * 使用自定义错误消息和默认客户端错误码构造异常
     *
     * @param message 异常描述消息
     */
    public ClientException(String message) {
        this(message, null, BaseErrorCode.CLIENT_ERROR);
    }

    /**
     * 使用自定义错误消息和错误码构造异常
     *
     * @param message   异常描述消息
     * @param errorCode 错误码定义
     */
    public ClientException(String message, IErrorCode errorCode) {
        this(message, null, errorCode);
    }

    /**
     * 完整构造参数
     *
     * @param message   异常描述消息
     * @param throwable 原始异常链
     * @param errorCode 错误码定义
     */
    public ClientException(String message, Throwable throwable, IErrorCode errorCode) {
        super(message, throwable, errorCode);
    }

    @Override
    public String toString() {
        return "ClientException{" +
                "code='" + errorCode + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }
}
