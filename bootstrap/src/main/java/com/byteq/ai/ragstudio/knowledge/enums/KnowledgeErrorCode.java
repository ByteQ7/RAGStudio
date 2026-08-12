package com.byteq.ai.ragstudio.knowledge.enums;

import com.byteq.ai.ragstudio.framework.errorcode.IErrorCode;

/**
 * 知识库域错误码
 *
 * <p>码段分配：{@code A001xxx}（客户端）/ {@code B001xxx}（服务端）/ {@code C001xxx}（远程），
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
public enum KnowledgeErrorCode implements IErrorCode {

    KB_NOT_FOUND("A001001", "知识库不存在"),

    KB_NAME_EXIST("A001002", "知识库名称已存在"),

    DOCUMENT_NOT_FOUND("A001003", "文档不存在"),

    CHUNK_NOT_FOUND("A001004", "Chunk 不存在"),

    REMOTE_FILE_TOO_LARGE("A001005", "远程文件大小超过限制"),

    FILE_TOO_LARGE("B001001", "文件大小超过限制"),

    DOCUMENT_PARSE_FAILED("B001002", "文档解析失败"),

    DOCUMENT_PROCESS_FAILED("B001003", "文档处理失败"),

    NETWORK_REQUEST_FAILED("B001004", "网络请求失败"),

    REMOTE_FILE_FETCH_FAILED("C001001", "远程文件获取失败");

    private final String code;

    private final String message;

    KnowledgeErrorCode(String code, String message) {
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
