package com.byteq.ai.ragstudio.framework.convention;

/**
 * Chunk 类型枚举 — 管控整个检索/处理流程中的分块类型分发
 * <p>
 * 新增类型时只需加枚举值，下游处理器通过 {@link #isTextLike()} / {@link #isImageLike()}
 * 自动适配，无需在各处理器中硬编码类型判断。
 */
public enum ChunkType {

    /** 纯文本 Chunk，参与裁剪、重排序、关键词匹配 */
    TEXT(true, false),

    /** 图片 Chunk，跳过裁剪和重排序，检索后转为 Base64 传给多模态 LLM */
    IMAGE(false, true);

    private final boolean textLike;
    private final boolean imageLike;

    ChunkType(boolean textLike, boolean imageLike) {
        this.textLike = textLike;
        this.imageLike = imageLike;
    }

    public boolean isTextLike() { return textLike; }
    public boolean isImageLike() { return imageLike; }

    /** 从字符串解析，不区分大小写，未知类型回退到 TEXT */
    public static ChunkType from(String s) {
        if (s == null || s.isBlank()) return TEXT;
        for (ChunkType t : values()) {
            if (t.name().equalsIgnoreCase(s.trim())) return t;
        }
        return TEXT;
    }
}
