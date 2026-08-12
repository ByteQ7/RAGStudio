package com.byteq.ai.ragstudio.core.enums;

import com.byteq.ai.ragstudio.framework.errorcode.IErrorCode;

/**
 * 文档解析/分块域错误码
 *
 * <p>码段分配：{@code A006xxx}（客户端）/ {@code B006xxx}（服务端）/ {@code C006xxx}（远程），
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
public enum CoreErrorCode implements IErrorCode {

    DOCUMENT_PARSE_FAILED("B006001", "文档解析失败"),

    EMBEDDING_RESULT_MISMATCH("B006002", "Embedding 结果数量与输入不匹配");

    private final String code;

    private final String message;

    CoreErrorCode(String code, String message) {
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
