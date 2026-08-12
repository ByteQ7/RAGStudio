package com.byteq.ai.ragstudio.user.enums;

import com.byteq.ai.ragstudio.framework.errorcode.IErrorCode;

/**
 * 用户域错误码
 *
 * <p>码段分配：{@code A005xxx}（客户端）/ {@code B005xxx}（服务端）/ {@code C005xxx}（远程），
 * 与 {@link com.byteq.ai.ragstudio.framework.errorcode.BaseErrorCode} 的 {@code A000xxx/B000xxx/C000xxx}
 * 段互不重叠，新增错误码不会影响原有错误码体系。</p>
 *
 * <p>扩展规则：</p>
 * <ul>
 *   <li>本域新增错误码：取本枚举内下一可用序号（如 A005002 已被占用则用 A005003）</li>
 *   <li>新增业务域：分配新的码段（如 A007xxx），禁止复用到既有域的码段</li>
 *   <li>全局唯一性由 {@code ErrorCodeRegistryTest} 反射扫描自动校验，撞码会导致构建失败</li>
 * </ul>
 *
 * @see com.byteq.ai.ragstudio.framework.errorcode.BaseErrorCode
 * @see com.byteq.ai.ragstudio.framework.errorcode.IErrorCode
 */
public enum UserErrorCode implements IErrorCode {

    USER_NOT_FOUND("A005001", "用户不存在"),

    USERNAME_EXIST("A005002", "用户名已存在"),

    LOGIN_FAILED("A005003", "登录失败"),

    PASSWORD_VERIFY_FAILED("A005004", "用户名或密码错误"),

    TOKEN_INVALID("A005005", "登录凭证无效或已过期"),

    PERMISSION_DENIED("A005006", "无权限执行该操作");

    private final String code;

    private final String message;

    UserErrorCode(String code, String message) {
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
