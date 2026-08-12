package com.byteq.ai.ragstudio.aimodel.enums;

import com.byteq.ai.ragstudio.framework.errorcode.IErrorCode;

/**
 * AI 模型配置域错误码
 *
 * <p>码段分配：{@code A003xxx}（客户端）/ {@code B003xxx}（服务端）/ {@code C003xxx}（远程），
 * 与 {@link com.byteq.ai.ragstudio.framework.errorcode.BaseErrorCode} 的 {@code A000xxx/B000xxx/C000xxx}
 * 段互不重叠，新增错误码不会影响原有错误码体系。</p>
 *
 * <p>扩展规则：</p>
 * <ul>
 *   <li>本域新增错误码：取本枚举内下一可用序号，禁止改动已发布错误码的 code 或 message</li>
 *   <li>新增业务域：分配新的码段（如 A008xxx），禁止复用到既有域的码段</li>
 *   <li>全局唯一性由 {@code ErrorCodeRegistryTest} 反射扫描自动校验，撞码会导致构建失败</li>
 * </ul>
 *
 * @see com.byteq.ai.ragstudio.framework.errorcode.BaseErrorCode
 * @see com.byteq.ai.ragstudio.framework.errorcode.IErrorCode
 */
public enum AiModelErrorCode implements IErrorCode {

    PROVIDER_NOT_FOUND("A003001", "供应商不存在"),

    MODEL_NOT_FOUND("A003002", "模型不存在"),

    PROVIDER_NAME_EXIST("A003003", "供应商名称已存在"),

    MODEL_NAME_EXIST("A003004", "模型标识已存在"),

    DEFAULT_MODEL_NOT_FOUND("A003005", "默认模型不存在"),

    EMBEDDING_MODEL_ID_EMPTY("B003001", "Embedding 模型ID不能为空"),

    EMBEDDING_MODEL_UNAVAILABLE("B003002", "无可用的 Embedding 模型"),

    MODEL_FETCH_FAILED("C003001", "模型列表拉取失败");

    private final String code;

    private final String message;

    AiModelErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
