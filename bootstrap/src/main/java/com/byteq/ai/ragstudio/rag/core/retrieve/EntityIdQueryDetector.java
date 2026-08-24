package com.byteq.ai.ragstudio.rag.core.retrieve;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实体 ID 查询识别器
 * <p>
 * 纯实体 ID（纳税人识别号、订单号、单号、编码等）为随机串，
 * 与知识库名称/描述的向量相似度天然趋近 0，语义选库必然误杀；
 * 且向量检索对其毫无区分度。此类查询应走关键词（BM25）精确匹配。
 * </p>
 * <p>
 * 识别采用「强 ID token 提取」而非整串匹配：用户输入常带标点后缀
 * （如 "91330108MA1K2L3M4N？"）或自然语言前后缀（如"帮我查下xxx的开票抬头"），
 * 整串 ^[A-Za-z0-9]+$ 匹配会在这些形态下全部漏判，
 * 导致跳过改写/跳过语义选库的快速通道失效（曾致开票库检索被语义选库误杀）。
 * </p>
 */
public final class EntityIdQueryDetector {

    private EntityIdQueryDetector() {
    }

    /** 视为实体 ID 的最小长度（避免 "hello"、"abc" 等普通英文词误判） */
    private static final int MIN_ID_LENGTH = 6;

    /** 强 ID token 最小长度：8 位以下的短串区分度不足，不做 token 级提取 */
    private static final int MIN_STRONG_TOKEN_LENGTH = 8;

    /** 纯数字单号型 token 的最小长度（订单号/快递单号等常见 ≥10 位） */
    private static final int MIN_PURE_DIGIT_LENGTH = 10;

    /**
     * 强 ID token 模式：连续字母数字段，同时含数字与字母且总长 ≥8（税号/编码型，如统一社会信用代码），
     * 或纯数字 ≥10 位（单号型）。中文、标点、空白均为天然分隔符。
     */
    private static final Pattern STRONG_ID_TOKEN = Pattern.compile("[A-Za-z0-9]{8,}");

    private static final Pattern PURE_ALNUM =
            java.util.regex.Pattern.compile("[A-Za-z0-9]+");

    /**
     * 提取查询中的强 ID token（去重、按出现顺序）。
     * <ul>
     *   <li>字母数字混合段：长度 ≥8 且同时含数字与字母（如 91330108MA1K2L3M4N、A2026B001 般编码）</li>
     *   <li>纯数字段：长度 ≥10（订单号/单号型）</li>
     * </ul>
     *
     * @param query 用户原始问题
     * @return 强 ID token 列表，无则空列表
     */
    public static List<String> extractStrongIdTokens(String query) {
        if (StrUtil.isBlank(query)) {
            return List.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Matcher m = STRONG_ID_TOKEN.matcher(query);
        while (m.find()) {
            String token = m.group();
            boolean hasDigit = false;
            boolean hasLetter = false;
            for (int i = 0; i < token.length(); i++) {
                char c = token.charAt(i);
                if (c >= '0' && c <= '9') {
                    hasDigit = true;
                } else {
                    hasLetter = true;
                }
            }
            if (hasDigit && hasLetter) {
                tokens.add(token);
            } else if (!hasLetter && token.length() >= MIN_PURE_DIGIT_LENGTH) {
                tokens.add(token);
            }
        }
        return new ArrayList<>(tokens);
    }

    /**
     * 判断查询是否包含强实体 ID token。
     * 用于触发「跳过查询改写 + 跳过语义选库 + 关键词精确匹配优先」的快速通道，
     * 覆盖纯 ID、带标点后缀、自然语言包裹三种形态。
     *
     * @param query 用户原始问题
     * @return true 表示查询包含强实体 ID
     */
    public static boolean containsStrongEntityId(String query) {
        return !extractStrongIdTokens(query).isEmpty();
    }

    /**
     * 判断整条查询是否为纯实体 ID（strip 标点/空白后仅含一个字母数字 token）。
     * 兼容历史调用方语义；尾部问号/句号等标点不再导致误判。
     *
     * @param query 用户原始问题
     * @return true 表示纯实体 ID 查询
     */
    public static boolean isEntityIdQuery(String query) {
        if (StrUtil.isBlank(query)) {
            return false;
        }
        // 剥离标点与空白后判定：全角问号/句号等后缀不应破坏匹配
        String stripped = query.replaceAll("[\\s\\p{Punct}\\uFF01-\\uFF5E【】《》（）“”‘’·…—]+", "");
        return stripped.length() >= MIN_ID_LENGTH && PURE_ALNUM.matcher(stripped).matches();
    }
}
