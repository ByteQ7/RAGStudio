package com.byteq.ai.ragstudio.framework.web;

import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.errorcode.BaseErrorCode;
import com.byteq.ai.ragstudio.framework.exception.AbstractException;

import java.util.Optional;

/**
 * 全局返回对象构造器
 *
 * <p>提供一系列静态工厂方法，方便开发者快速构建统一格式的 API 响应对象。
 * 所有方法的返回值均为 {@link Result} 类型，确保整个系统的响应格式一致。</p>
 *
 * <p>支持两类响应：</p>
 * <ul>
 *   <li><b>成功响应</b>：通过 {@link #success()} 或 {@link #success(Object)} 构建</li>
 *   <li><b>失败响应</b>：通过 {@link #failure()}、{@link #failure(AbstractException)} 或
 *       {@link #failure(String, String)} 构建</li>
 * </ul>
 *
 * @see Result
 */
public final class Results {

    /**
     * 构造空成功的响应
     * <p>适用于不需要返回数据的成功场景（如删除操作）。</p>
     *
     * @return 成功响应，code 为 {@link Result#SUCCESS_CODE}，data 为 null
     */
    public static Result<Void> success() {
        return new Result<Void>()
                .setCode(Result.SUCCESS_CODE);
    }

    /**
     * 构造带返回数据的成功响应
     * <p>适用于需要返回业务数据的成功场景（如查询操作）。</p>
     *
     * @param data 响应数据
     * @param <T>  数据类型
     * @return 成功响应，code 为 {@link Result#SUCCESS_CODE}，data 为传入的业务数据
     */
    public static <T> Result<T> success(T data) {
        return new Result<T>()
                .setCode(Result.SUCCESS_CODE)
                .setData(data);
    }

    /**
     * 构建默认的服务端失败响应
     * <p>使用定义的服务端通用错误码 {@link BaseErrorCode#SERVICE_ERROR}。</p>
     *
     * @return 失败响应，code 为服务端错误码，message 为默认错误描述
     */
    public static Result<Void> failure() {
        return new Result<Void>()
                .setCode(BaseErrorCode.SERVICE_ERROR.code())
                .setMessage(BaseErrorCode.SERVICE_ERROR.message());
    }

    /**
     * 通过应用自定义异常构建失败响应
     * <p>从异常中提取错误码和错误消息，如果异常中未携带则使用默认值兜底。</p>
     *
     * @param abstractException 应用自定义异常实例
     * @return 失败响应，code 和 message 从异常中提取
     */
    static Result<Void> failure(AbstractException abstractException) {
        String errorCode = Optional.ofNullable(abstractException.getErrorCode())
                .orElse(BaseErrorCode.SERVICE_ERROR.code());
        String errorMessage = Optional.ofNullable(abstractException.getErrorMessage())
                .orElse(BaseErrorCode.SERVICE_ERROR.message());
        return new Result<Void>()
                .setCode(errorCode)
                .setMessage(errorMessage);
    }

    /**
     * 通过指定的错误码和错误消息构建失败响应
     * <p>适用于需要自定义错误信息的场景，如参数校验失败。</p>
     *
     * @param errorCode    错误码
     * @param errorMessage 错误消息描述
     * @return 失败响应，包含指定的错误码和错误消息
     */
    static Result<Void> failure(String errorCode, String errorMessage) {
        return new Result<Void>()
                .setCode(errorCode)
                .setMessage(errorMessage);
    }
}
