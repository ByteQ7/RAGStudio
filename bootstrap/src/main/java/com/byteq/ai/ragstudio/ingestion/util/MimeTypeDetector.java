package com.byteq.ai.ragstudio.ingestion.util;

import org.apache.tika.Tika;

/**
 * MimeType 探测器，用于识别文件或字节数组的媒体类型
 */
public final class MimeTypeDetector {

    private static final Tika TIKA = new Tika();

    private MimeTypeDetector() {
    }

    /**
     * 检测字节数组的 MIME 类型
     * <p>
     * 基于 Apache Tika 进行内容检测，如果提供了文件名则结合文件后缀辅助判断。
     * </p>
     *
     * @param bytes    待检测的字节数组
     * @param fileName 文件名（可选），用于辅助类型识别
     * @return 检测到的 MIME 类型字符串，如果字节数组为空则返回 null
     */
    public static String detect(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        if (fileName == null) {
            return TIKA.detect(bytes);
        }
        return TIKA.detect(bytes, fileName);
    }
}
