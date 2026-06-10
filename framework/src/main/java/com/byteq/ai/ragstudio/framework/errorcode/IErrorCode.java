package com.byteq.ai.ragstudio.framework.errorcode;

import com.byteq.ai.ragstudio.framework.exception.AbstractException;

/**
 * 平台错误码接口
 *
 * <p>定义错误码的抽象规范，所有具体错误码实现（如枚举类或常量类）需实现此接口。
 * 通过接口统一了错误码和错误消息的获取方式，便于在异常处理链中统一处理。</p>
 *
 * <p>设计说明：</p>
 * <ul>
 *   <li>采用接口而非具体类，允许使用枚举或常量类等不同实现方式</li>
 *   <li>与 {@link AbstractException} 配合，
 *       构成完整的错误码-异常体系</li>
 * </ul>
 *
 * @see BaseErrorCode
 * @see AbstractException
 */
public interface IErrorCode {

    /**
     * 获取错误码
     * <p>返回字符串格式的错误码，如 {@code "A000001"}。
     * 错误码按错误类型分级，方便快速定位问题。</p>
     *
     * @return 字符串形式的错误码
     */
    String code();

    /**
     * 获取错误信息描述
     * <p>返回人类可读的错误描述，用于日志记录和用户提示。</p>
     *
     * @return 错误信息描述文本
     */
    String message();
}
