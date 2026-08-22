package com.byteq.ai.ragstudio.graph.extract;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 实体规范化器
 * <p>将 LLM 抽取的实体名称清洗为可合并的 canonical 键，并生成别名集合。
 * 清洗规则：去首尾空白、全角转半角、统一引号、折叠空白、剥离括号后缀。
 * 不做基于 embedding 的模糊合并（避免错误合并污染图谱），近似名保留为独立节点由人工合并。</p>
 */
public final class GraphEntityNormalizer {

    /** 已知实体类型集合（提示词约束的类型枚举） */
    private static final Set<String> KNOWN_TYPES = Set.of(
            "PERSON", "ORG", "DEPT", "ROLE", "PRODUCT", "PROCESS", "SYSTEM", "DOC", "OTHER"
    );

    private GraphEntityNormalizer() {
    }

    /**
     * 规范化实体名称（合并键）
     */
    public static String normalizeName(String raw) {
        if (raw == null) {
            return "";
        }
        String name = raw.trim();
        if (name.isEmpty()) {
            return "";
        }
        // 全角 → 半角（字母、数字、标点）
        StringBuilder sb = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.append((char) (c - 0xFEE0));
            } else if (c == '\u3000') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        String normalized = sb.toString();
        // 统一引号
        normalized = normalized.replace('“', '"').replace('”', '"')
                .replace('‘', '\'').replace('’', '\'');
        // 折叠空白
        normalized = normalized.replaceAll("\\s+", " ").trim();
        // 剥离括号后缀（如 "人力资源部（部门）" → "人力资源部"），同时保留括号内容为别名
        return stripBracketSuffix(normalized);
    }

    /**
     * 生成实体别名集合：[原文, 规范化名] + 括号内内容（如"人力资源部（HR）"中的 HR）
     */
    public static Set<String> buildAliases(String raw, String normalized) {
        Set<String> aliases = new LinkedHashSet<>();
        if (raw != null && !raw.isBlank()) {
            aliases.add(raw.trim());
        }
        if (normalized != null && !normalized.isBlank()) {
            aliases.add(normalized);
        }
        if (raw != null) {
            for (String inner : extractBracketContents(raw)) {
                String alias = normalizeName(inner);
                if (!alias.isEmpty()) {
                    aliases.add(alias);
                }
            }
        }
        return aliases;
    }

    /**
     * 规范化实体类型：大写化并映射到已知枚举，未知类型归为 OTHER
     */
    public static String normalizeType(String raw) {
        if (raw == null) {
            return "OTHER";
        }
        String type = raw.trim().toUpperCase().replaceAll("[^A-Z0-9_]", "");
        if (type.isEmpty()) {
            return "OTHER";
        }
        // 常见别名映射
        if (type.startsWith("PERSON") || type.startsWith("HUMAN") || type.startsWith("EMPLOYEE")) {
            return "PERSON";
        }
        if (type.startsWith("ORGANIZATION") || type.startsWith("ORG") || type.startsWith("COMPANY")) {
            return "ORG";
        }
        if (type.startsWith("DEPT") || type.startsWith("DEPARTMENT")) {
            return "DEPT";
        }
        if (type.startsWith("ROLE") || type.startsWith("POSITION") || type.startsWith("JOB")) {
            return "ROLE";
        }
        if (type.startsWith("PRODUCT") || type.startsWith("SERVICE")) {
            return "PRODUCT";
        }
        if (type.startsWith("PROCESS") || type.startsWith("FLOW")) {
            return "PROCESS";
        }
        if (type.startsWith("SYSTEM") || type.startsWith("PLATFORM") || type.startsWith("SOFTWARE")) {
            return "SYSTEM";
        }
        if (type.startsWith("DOC") || type.startsWith("DOCUMENT")) {
            return "DOC";
        }
        return KNOWN_TYPES.contains(type) ? type : "OTHER";
    }

    /**
     * 谓词合法性校验：2-20 个中英文/数字字符，排除名词化后缀（关系/属性/关联）
     */
    public static boolean isValidPredicate(String predicate) {
        if (predicate == null) {
            return false;
        }
        String p = predicate.trim();
        if (p.length() < 2 || p.length() > 20) {
            return false;
        }
        if (!p.matches("^[\\u4e00-\\u9fa5A-Za-z0-9]{2,20}$")) {
            return false;
        }
        // 排除名词化谓词："X关系"、"Y属性" 等无意义边
        return !p.endsWith("关系") && !p.endsWith("属性") && !p.endsWith("关联");
    }

    /**
     * 剥离括号后缀：取括号前的部分作为主名
     */
    private static String stripBracketSuffix(String normalized) {
        int idx = normalized.indexOf('（');
        if (idx > 0) {
            return normalized.substring(0, idx).trim();
        }
        idx = normalized.indexOf('(');
        if (idx > 0) {
            return normalized.substring(0, idx).trim();
        }
        return normalized;
    }

    /**
     * 提取括号内容（生成别名用）
     */
    private static java.util.List<String> extractBracketContents(String raw) {
        java.util.List<String> contents = new java.util.ArrayList<>();
        int idx = 0;
        while (idx < raw.length()) {
            int open = raw.indexOf('（', idx);
            if (open < 0) {
                open = raw.indexOf('(', idx);
            }
            if (open < 0) {
                break;
            }
            int close = raw.indexOf('）', open);
            if (close < 0) {
                close = raw.indexOf(')', open);
            }
            if (close < 0) {
                break;
            }
            contents.add(raw.substring(open + 1, close));
            idx = close + 1;
        }
        return contents;
    }
}