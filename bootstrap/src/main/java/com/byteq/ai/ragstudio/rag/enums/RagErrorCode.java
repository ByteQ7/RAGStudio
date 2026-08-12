package com.byteq.ai.ragstudio.rag.enums;

import com.byteq.ai.ragstudio.framework.errorcode.IErrorCode;

/**
 * 问答/检索/Agent 域错误码
 *
 * <p>码段分配：{@code A002xxx}（客户端）/ {@code B002xxx}（服务端）/ {@code C002xxx}（远程），
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
public enum RagErrorCode implements IErrorCode {

    MESSAGE_ID_EMPTY("A002001", "消息ID不能为空"),

    CONVERSATION_NOT_FOUND("A002002", "会话不存在"),

    SAMPLE_QUESTION_NOT_FOUND("A002003", "示例问题不存在"),

    QUERY_TERM_MAPPING_NOT_FOUND("A002004", "查询词映射不存在"),

    SEARCH_FAILED("B002001", "检索失败"),

    AGENT_LOOP_FAILED("B002002", "Agent 循环执行失败"),

    STREAM_SEND_FAILED("B002003", "流式响应发送失败"),

    RERANK_FAILED("C002001", "重排序调用失败"),

    EMBEDDING_FAILED("C002002", "向量化调用失败"),

    LLM_CALL_FAILED("C002003", "大模型调用失败");

    private final String code;

    private final String message;

    RagErrorCode(String code, String message) {
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
