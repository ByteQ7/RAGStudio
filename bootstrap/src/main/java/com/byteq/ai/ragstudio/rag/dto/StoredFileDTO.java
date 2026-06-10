package com.byteq.ai.ragstudio.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 已存储文件信息 DTO
 * <p>
 * 表示上传并存储后的文件信息，包含访问 URL、检测到的文件类型、文件大小和原始文件名。
 * 用于在消息中引用用户上传的文件。
 * </p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StoredFileDTO {

    /** 文件访问 URL */
    private String url;

    /** 检测到的文件 MIME 类型 */
    private String detectedType;

    /** 文件大小（字节） */
    private Long size;

    /** 原始文件名 */
    private String originalFilename;
}
