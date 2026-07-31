package com.byteq.ai.ragstudio.rag.core.rewrite;

import cn.hutool.core.util.StrUtil;

/**
 * 弱追问查询检测工具
 * <p>
 * 用于识别"再试试呢"、"继续"、"重新回答"、"详细讲讲"等依赖上下文的追问/重试短语。
 * 这类短语脱离对话历史没有独立检索价值，检测到后应使用改写阶段（或历史）补全的查询。
 * </p>
 */
public final class FollowUpQueryUtil {

    /** 弱追问短语特征词（组合命中即视为弱查询） */
    private static final String WEAK_PATTERN =
            ".*(再|继续|重新|换|试|详|补充|展开|别的|回答|讲讲|说说|解释).*";

    private FollowUpQueryUtil() {
    }

    /**
     * 判断查询文本是否为弱追问短语
     * <ul>
     *   <li>含问号 / 字母数字（编号、英文、代码）→ 视为具体查询，不算弱追问</li>
     *   <li>长度超过阈值 → 视为具体查询（已包含足够信息）</li>
     *   <li>否则命中追问特征词 → 弱追问</li>
     * </ul>
     */
    public static boolean isWeakFollowUp(String text) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        String t = text.trim();
        if (t.length() > 12) {
            return false;
        }
        if (t.contains("?") || t.contains("？")) {
            return false;
        }
        // 包含字母/数字（如产品型号、编号、英文关键词）→ 具体查询
        if (t.matches(".*[A-Za-z0-9].*")) {
            return false;
        }
        return t.matches(WEAK_PATTERN);
    }
}
