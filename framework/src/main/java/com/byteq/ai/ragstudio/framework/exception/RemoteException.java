package com.byteq.ai.ragstudio.framework.exception;

import com.byteq.ai.ragstudio.framework.errorcode.BaseErrorCode;
import com.byteq.ai.ragstudio.framework.errorcode.IErrorCode;

/**
 * 远程服务调用异常
 *
 * <p>当系统调用第三方远程服务（如支付网关、短信服务、外部 API 等）失败时抛出的异常。
 * 对应错误码规范中的 {@code C 类错误}（第三方服务错误）。</p>
 *
 * <p>典型场景：</p>
 * <ul>
 *   <li>调用支付服务超时或返回失败</li>
 *   <li>调用短信发送服务异常</li>
 *   <li>调用 AI 大模型接口失败</li>
 *   <li>调用其他微服务接口异常</li>
 * </ul>
 *
 * @see BaseErrorCode#REMOTE_ERROR
 */
public class RemoteException extends AbstractException {

    /**
     * 使用默认远程服务错误码构造异常
     *
     * @param message 异常描述消息
     */
    public RemoteException(String message) {
        this(message, null, BaseErrorCode.REMOTE_ERROR);
    }

    /**
     * 使用自定义错误消息和错误码构造异常
     *
     * @param message   异常描述消息
     * @param errorCode 错误码定义
     */
    public RemoteException(String message, IErrorCode errorCode) {
        this(message, null, errorCode);
    }

    /**
     * 完整构造参数
     *
     * @param message   异常描述消息
     * @param throwable 原始异常链
     * @param errorCode 错误码定义
     */
    public RemoteException(String message, Throwable throwable, IErrorCode errorCode) {
        super(message, throwable, errorCode);
    }

    @Override
    public String toString() {
        return "RemoteException{" +
                "code='" + errorCode + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }
}
