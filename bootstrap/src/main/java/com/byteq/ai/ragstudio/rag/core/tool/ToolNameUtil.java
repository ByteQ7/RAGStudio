package com.byteq.ai.ragstudio.rag.core.tool;

import cn.hutool.core.util.StrUtil;

import java.util.regex.Pattern;

/**
 * 工具名规范化工具
 * <p>
 * 将任意来源（MCP / SKILL / 内置）的工具名规范化为 OpenAI 兼容协议合法函数名，
 * 供 Agent 注册与 tool_reader 展示统一使用。下沉到 tool 包，避免 agent ↔ skill 循环依赖。
 * </p>
 */
public final class ToolNameUtil {

    /** OpenAI 兼容协议函数名约束：^[a-zA-Z0-9_-]+$，最大 64 字符（DeepSeek 等厂商严格校验，不符直接 400） */
    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

    public static final int MAX_TOOL_NAME_LENGTH = 64;

    /** 清洗后有效名称的最小长度阈值：过短视为语义不可用，回退哈希名 */
    private static final int MIN_MEANINGFUL_NAME_LENGTH = 6;

    private ToolNameUtil() {}

    /**
     * 将工具名规范化为 OpenAI 兼容协议合法函数名：
     * 非法字符替换为下划线、折叠连续下划线、去首尾下划线、超长截断；
     * 清洗后无可读字符或有效字符过少（如"地名查询对应code"只剩"code"）时，
     * 回退为 tool_ + 原始名哈希，避免语义误导与碰撞。
     */
    public static String sanitize(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "tool";
        }
        if (TOOL_NAME_PATTERN.matcher(raw).matches() && raw.length() <= MAX_TOOL_NAME_LENGTH) {
            return raw;
        }
        String sanitized = raw.replaceAll("[^a-zA-Z0-9_-]", "_");
        sanitized = sanitized.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        boolean tooShort = sanitized.length() < MIN_MEANINGFUL_NAME_LENGTH;
        if (sanitized.isEmpty() || tooShort || !sanitized.matches(".*[a-zA-Z0-9].*")) {
            sanitized = "tool_" + Integer.toHexString(raw.hashCode());
        }
        if (sanitized.length() > MAX_TOOL_NAME_LENGTH) {
            sanitized = sanitized.substring(0, MAX_TOOL_NAME_LENGTH);
        }
        return sanitized;
    }
}
