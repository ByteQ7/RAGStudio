package com.byteq.ai.ragstudio.framework.exception;


import com.byteq.ai.ragstudio.framework.errorcode.BaseErrorCode;
import com.byteq.ai.ragstudio.framework.errorcode.IErrorCode;

import java.util.Optional;

/**
 * 服务端运行异常
 *
 * <p>请求运行过程中出现的不符合业务预期的异常，对应错误码规范中的 {@code B 类错误}（系统执行错误）。
 * 当服务端自身的业务逻辑执行失败时抛出此异常。</p>
 *
 * <p>典型场景：</p>
 * <ul>
 *   <li>业务逻辑校验失败（如库存不足、余额不够）</li>
 *   <li>系统执行超时</li>
 *   <li>数据库操作失败</li>
 *   <li>内部状态异常</li>
 * </ul>
 *
 * @see BaseErrorCode#SERVICE_ERROR
 */
public class ServiceException extends AbstractException {

    /**
     * 使用默认服务错误码构造异常
     *
     * @param message 异常描述消息
     */
    public ServiceException(String message) {
        this(message, null, BaseErrorCode.SERVICE_ERROR);
    }

    /**
     * 仅通过错误码构造异常，消息使用错误码的默认描述
     *
     * @param errorCode 错误码定义
     */
    public ServiceException(IErrorCode errorCode) {
        this(null, errorCode);
    }

    /**
     * 使用自定义消息和错误码构造异常
     *
     * @param message   异常描述消息（如果为空将使用 errorCode 的默认消息）
     * @param errorCode 错误码定义
     */
    public ServiceException(String message, IErrorCode errorCode) {
        this(message, null, errorCode);
    }

    /**
     * 完整构造参数
     *
     * @param message   异常描述消息（如果为空将使用 errorCode 的默认消息）
     * @param throwable 原始异常链
     * @param errorCode 错误码定义
     */
    public ServiceException(String message, Throwable throwable, IErrorCode errorCode) {
        super(Optional.ofNullable(message).orElse(errorCode.message()), throwable, errorCode);
    }

    @Override
    public String toString() {
        return "ServiceException{" +
                "code='" + errorCode + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }
}
