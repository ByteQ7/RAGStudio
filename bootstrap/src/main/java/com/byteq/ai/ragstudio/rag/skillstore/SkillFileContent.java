package com.byteq.ai.ragstudio.rag.skillstore;

/**
 * SKILL 版本文件内容（仅文本文件；二进制/超大文件拒绝返回内容）
 */
public record SkillFileContent(String path, Boolean isBinary, Long size, String content) {
}
