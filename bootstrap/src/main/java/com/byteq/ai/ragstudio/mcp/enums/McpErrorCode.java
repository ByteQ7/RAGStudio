package com.byteq.ai.ragstudio.mcp.enums;

import com.byteq.ai.ragstudio.framework.errorcode.IErrorCode;

/**
 * MCP/技能域错误码
 *
 * <p>码段分配：{@code A004xxx}（客户端）/ {@code B004xxx}（服务端）/ {@code C004xxx}（远程），
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
public enum McpErrorCode implements IErrorCode {

    SERVER_NOT_FOUND("A004001", "MCP Server 不存在"),

    NAME_EXIST("A004002", "MCP Server 名称已存在"),

    CONNECT_FAILED("C004001", "MCP 连接失败"),

    TOOL_INVOKE_FAILED("C004002", "MCP 工具调用失败");

    private final String code;

    private final String message;

    McpErrorCode(String code, String message) {
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
