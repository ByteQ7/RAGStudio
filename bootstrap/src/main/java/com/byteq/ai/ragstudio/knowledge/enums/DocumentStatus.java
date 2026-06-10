package com.byteq.ai.ragstudio.knowledge.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 文档处理状态枚举
 * <p>
 * 表示文档在分块和向量化处理过程中可能处于的各种生命周期状态。
 * 状态流转：PENDING（待处理）→ RUNNING（处理中）→ SUCCESS（成功）/ FAILED（失败）
 */
@Getter
@RequiredArgsConstructor
public enum DocumentStatus {

    /**
     * 待处理：文档已上传，等待分块和向量化
     */
    PENDING("pending"),

    /**
     * 处理中：文档正在执行文本提取、分块、向量嵌入等操作
     */
    RUNNING("running"),

    /**
     * 处理失败：文档在分块或向量化过程中发生错误
     */
    FAILED("failed"),

    /**
     * 处理成功：文档已完成分块和向量化，可参与 RAG 检索
     */
    SUCCESS("success");

    /**
     * 状态码（对应数据库中的存储值）
     */
    private final String code;
}
