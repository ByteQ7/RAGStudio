package com.byteq.ai.ragstudio.framework.convention;

import com.byteq.ai.ragstudio.framework.web.Results;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 全局统一返回结果对象
 *
 * <p>用于规范化所有 API 接口的返回格式，确保前后端交互的一致性。
 * 所有接口返回都应使用此对象包装，避免不同开发人员定义不一致的返回结构。</p>
 *
 * <p>响应格式包含四个字段：</p>
 * <ul>
 *   <li>{@code code}：状态码，{@code "0"} 表示成功，其他值表示各类错误</li>
 *   <li>{@code message}：响应消息，成功时为成功提示，失败时为错误原因</li>
 *   <li>{@code data}：业务响应数据，由泛型 T 指定具体类型</li>
 *   <li>{@code requestId}：请求追踪 ID，用于链路追踪和问题排查</li>
 * </ul>
 *
 * <p>推荐通过 {@link Results} 工具类创建实例。</p>
 *
 * @param <T> 响应数据的类型
 */
@Data
@Accessors(chain = true)
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 5679018624309023727L;

    /**
     * 成功状态码
     * <p>
     * 当接口请求成功时，返回此状态码
     * </p>
     */
    public static final String SUCCESS_CODE = "0";

    /**
     * 状态码
     * <p>
     * 标识请求的处理结果，{@code "0"} 表示成功，其他值表示各类错误或异常情况
     * </p>
     */
    private String code;

    /**
     * 响应消息
     * <p>
     * 对本次请求结果的文字描述，成功时可为成功提示，失败时为错误原因说明
     * </p>
     */
    private String message;

    /**
     * 响应数据
     * <p>
     * 接口返回的业务数据，类型由泛型 T 指定。请求失败时可能为 {@code null}
     * </p>
     */
    private T data;

    /**
     * 请求追踪 ID
     * <p>
     * 用于链路追踪和问题排查，每个请求具有唯一的标识符
     * </p>
     */
    private String requestId;

    /**
     * 判断请求是否成功
     *
     * @return 如果状态码为 {@link #SUCCESS_CODE}，返回 {@code true}；否则返回 {@code false}
     */
    public boolean isSuccess() {
        return SUCCESS_CODE.equals(code);
    }
}
