package com.byteq.ai.ragstudio.framework.exception;

import com.byteq.ai.ragstudio.framework.errorcode.IErrorCode;
import lombok.Getter;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 抽象异常基类
 *
 * <p>定义了项目中三类异常体系的共同基类：</p>
 * <ul>
 *   <li>{@link ClientException}：客户端异常，因用户提交参数或操作不当导致</li>
 *   <li>{@link ServiceException}：服务端异常，因业务逻辑执行错误导致</li>
 *   <li>{@link RemoteException}：远程服务调用异常，因调用第三方服务失败导致</li>
 * </ul>
 *
 * <p>每个异常实例包含错误码（errorCode）和错误消息（errorMessage）两个核心属性，
 * 与 {@link IErrorCode} 接口配合使用，便于全局异常处理器统一格式化返回。</p>
 */
@Getter
public abstract class AbstractException extends RuntimeException {

    /**
     * 错误码，用于标识具体的错误类型
     */
    public final String errorCode;

    /**
     * 错误消息描述，用于展示给用户或记录日志
     */
    public final String errorMessage;

    /**
     * 构造抽象异常
     * <p>当传入的 message 为空时，使用 errorCode 中定义的默认错误消息。</p>
     *
     * @param message   异常描述消息（可选，为空时使用 errorCode 的默认消息）
     * @param throwable 原始异常链（可选）
     * @param errorCode 错误码定义，必须提供
     */
    public AbstractException(String message, Throwable throwable, IErrorCode errorCode) {
        super(message, throwable);
        this.errorCode = errorCode.code();
        // 优先使用传入的消息，若为空则使用错误码定义的默认消息
        this.errorMessage = Optional.ofNullable(StringUtils.hasLength(message) ? message : null).orElse(errorCode.message());
    }
}
