package com.byteq.ai.ragstudio.rag.util;

import java.util.Locale;
import java.util.Map;

/**
 * 文件类型探测器工具类
 * 用于根据文件名或 MIME 类型识别文件类型
 */
public final class FileTypeDetector {

    private static final Map<String, String> EXTENSION_MAP = Map.ofEntries(
            Map.entry("pdf", "pdf"),
            Map.entry("md", "markdown"),
            Map.entry("markdown", "markdown"),
            Map.entry("doc", "doc"),
            Map.entry("docx", "docx"),
            Map.entry("odt", "odt"),
            Map.entry("ods", "ods"),
            Map.entry("odp", "odp"),
            Map.entry("ppt", "ppt"),
            Map.entry("pptx", "pptx"),
            Map.entry("xls", "xls"),
            Map.entry("xlsx", "xlsx")
    );

    private static final Map<String, String> MIME_MAP = Map.ofEntries(
            Map.entry("application/pdf", "pdf"),
            Map.entry("application/x-pdf", "pdf"),
            Map.entry("text/markdown", "markdown"),
            Map.entry("text/x-markdown", "markdown"),
            Map.entry("application/msword", "doc"),
            Map.entry("application/vnd.ms-word", "doc"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            Map.entry("application/vnd.oasis.opendocument.text", "odt"),
            Map.entry("application/vnd.oasis.opendocument.spreadsheet", "ods"),
            Map.entry("application/vnd.oasis.opendocument.presentation", "odp"),
            Map.entry("application/vnd.ms-powerpoint", "ppt"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
            Map.entry("application/vnd.ms-excel", "xls"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx")
    );

    private FileTypeDetector() {
    }

    /**
     * 仅通过文件名探测文件类型
     *
     * @param fileName 文件名（可包含路径）
     * @return 标准化文件类型标识（如 "pdf"、"markdown"），无法识别时返回原始扩展名
     */
    public static String detectType(String fileName) {
        return detectType(fileName, null);
    }

    /**
     * 通过文件名和 MIME 类型探测文件类型
     * <p>
     * 匹配优先级：文件扩展名 -> MIME 类型 -> 原始扩展名 -> 原始 MIME 类型。
     * </p>
     *
     * @param fileName 文件名（可包含路径）
     * @param mimeType MIME 类型（可为 null）
     * @return 标准化文件类型标识（如 "pdf"、"markdown"、"docx"），无法识别时返回原始扩展名或 MIME
     */
    public static String detectType(String fileName, String mimeType) {
        String extension = extractExtension(fileName);
        String typeByExtension = mapExtension(extension);
        if (typeByExtension != null) {
            return typeByExtension;
        }

        String typeByMime = mapMimeType(mimeType);
        if (typeByMime != null) {
            return typeByMime;
        }

        if (!extension.isBlank()) {
            return extension;
        }

        return mimeType == null ? "" : mimeType;
    }

    // 通过扩展名映射表查找标准化文件类型，未匹配返回 null
    private static String mapExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return null;
        }
        return EXTENSION_MAP.get(extension);
    }

    // 通过 MIME 类型映射表查找标准化文件类型，未匹配返回 null
    private static String mapMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return null;
        }
        String normalized = normalizeMimeType(mimeType);
        return MIME_MAP.get(normalized);
    }

    // 标准化 MIME 类型：转小写并去除 charset 等参数（分号后的部分）
    private static String normalizeMimeType(String mimeType) {
        String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf(';');
        return separator >= 0 ? normalized.substring(0, separator).trim() : normalized;
    }

    // 从文件名中提取扩展名：去除路径分隔符和尾随点号，统一转小写
    private static String extractExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.trim();
        int slashIndex = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slashIndex >= 0 && slashIndex + 1 < name.length()) {
            name = name.substring(slashIndex + 1);
        }
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == name.length() - 1) {
            return "";
        }
        return name.substring(dotIndex + 1).trim().toLowerCase(Locale.ROOT);
    }
}
