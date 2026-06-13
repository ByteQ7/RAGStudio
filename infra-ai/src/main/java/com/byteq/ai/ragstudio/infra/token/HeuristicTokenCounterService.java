package com.byteq.ai.ragstudio.infra.token;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 启发式 Token 计数服务实现
 * <p>
 * 基于字符类型和比例对文本进行 Token 数量的近似估算，无需依赖具体的模型 Tokenizer。
 * 该实现采用以下估算策略：
 * </p>
 * <ul>
 *   <li><b>ASCII 字符</b>（英文、数字、符号）：约 4 个字符折算为 1 个 Token</li>
 *   <li><b>CJK 字符</b>（中日韩统一表意文字、假名、谚文等）：每个字符约 1 个 Token</li>
 *   <li><b>其他字符</b>（非 ASCII 非 CJK 字符，如表情符号、特殊符号等）：约 2 个字符折算为 1 个 Token</li>
 * </ul>
 *
 * <p>
 * <b>注意：</b>此实现为轻量级估算方案，精度远低于使用模型原生 Tokenizer 的精确计数方案。
 * 适用于对 Token 消耗进行快速粗略估算的场景，如 UI 展示预估费用、提前判断是否可能超出上下文限制等。
 * 在需要精确计费的场景中不建议使用。
 * </p>
 */
@Service
public class HeuristicTokenCounterService implements TokenCounterService {

    @Override
    public Integer countTokens(String text) {
        // 空文本或空白文本直接返回 0
        if (!StringUtils.hasText(text)) {
            return 0;
        }

        int asciiCount = 0;
        int cjkCount = 0;
        int otherCount = 0;
        int whitespaceCount = 0;

        // 逐字符遍历文本，按字符类型进行分类统计
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                // 空白字符以较低比率计入：每 4 个空白字符折算 1 个 Token
                whitespaceCount++;
                continue;
            }
            if (ch <= 0x7F) {
                // ASCII 字符范围（U+0000 ~ U+007F）：英文字母、数字、常见符号
                asciiCount++;
            } else if (isCjk(ch)) {
                // CJK 字符（中日韩统一表意文字、假名、谚文等）
                cjkCount++;
            } else {
                // 其他字符（表情符号、特殊符号、其他非 CJK 的 Unicode 字符等）
                otherCount++;
            }
        }

        // ASCII 字符按约 4 字符 = 1 Token 折算（向上取整）
        int asciiTokens = (asciiCount + 3) / 4;
        // 其他非 CJK 字符按约 2 字符 = 1 Token 折算（向上取整）
        int otherTokens = (otherCount + 1) / 2;
        // 空白字符按约 4 字符 = 1 Token 折算（向上取整）
        int whitespaceTokens = (whitespaceCount + 3) / 4;
        // CJK 字符按 1 字符 = 1 Token 计算
        int total = asciiTokens + cjkCount + otherTokens + whitespaceTokens;
        // 确保即使所有计数都为 0，也至少返回 1（代表空文本的基本开销）
        return Math.max(total, 1);
    }

    /**
     * 判断给定字符是否属于 CJK（中日韩统一表意文字）及相关字符集
     * <p>
     * 涵盖的 Unicode 块包括：
     * </p>
     * <ul>
     *   <li>CJK 统一表意文字（CJK Unified Ideographs）及其扩展 A~F</li>
     *   <li>CJK 兼容表意文字（Compatibility Ideographs）及其增补</li>
     *   <li>CJK 部首补充（Radicals Supplement）</li>
     *   <li>CJK 符号和标点（Symbols and Punctuation）</li>
     *   <li>日文假名（平假名 Hiragana、片假名 Katakana）及片假名语音扩展</li>
     *   <li>韩文谚文（Hangul Syllables、Jamo、Compatibility Jamo）</li>
     * </ul>
     *
     * @param ch 待判断的字符
     * @return 如果字符属于 CJK 相关字符集则返回 true，否则返回 false
     */
    private boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT
                || block == Character.UnicodeBlock.CJK_RADICALS_SUPPLEMENT
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO;
    }
}
