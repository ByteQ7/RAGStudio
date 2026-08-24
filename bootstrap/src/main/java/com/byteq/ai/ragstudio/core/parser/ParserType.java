package com.byteq.ai.ragstudio.core.parser;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 文档解析器类型枚举
 */
@Getter
@RequiredArgsConstructor
public enum ParserType {

    /**
     * Tika 解析器（支持 PDF、Word、Excel、PPT 等多种格式）
     */
    TIKA("Tika"),

    /**
     * Markdown 解析器
     */
    MARKDOWN("Markdown"),

    /**
     * MinerU 解析器（本地/远程 MinerU 服务，处理 PDF 公式/表格/扫描件）
     */
    MINERU("MinerU");

    /**
     * 解析器类型名称
     */
    private final String type;
}
