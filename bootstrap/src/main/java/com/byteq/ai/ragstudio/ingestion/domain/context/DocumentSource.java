package com.byteq.ai.ragstudio.ingestion.domain.context;

import com.byteq.ai.ragstudio.ingestion.strategy.fetcher.DocumentFetcher;
import com.byteq.ai.ragstudio.ingestion.domain.enums.SourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 文档源实体类
 * <p>
 * 描述文档的来源信息，包括源类型、访问位置、文件名称以及访问凭证等。
 * 由 {@link DocumentFetcher} 根据
 * 源类型选择合适的获取策略来读取文档内容。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentSource {

    /**
     * 文档源类型
     * 标识文档的来源类型，决定使用哪种获取器来获取文档。
     * 支持的类型包括：file（本地文件）、url（网络链接）、feishu（飞书文档）、s3（对象存储）
     */
    private SourceType type;

    /**
     * 文档的访问位置
     * 可以是文件路径（如 /data/docs/report.pdf）、URL 地址（如 https://example.com/doc.pdf）、
     * 或第三方平台的资源标识（如飞书文档 URL、S3 路径等）
     */
    private String location;

    /**
     * 文档的文件名
     * 用于后续解析器的类型识别和日志记录。如果未指定，获取器会尝试从 location 中提取。
     */
    private String fileName;

    /**
     * 访问文档所需的凭证信息
     * 键值对格式，如 API Token、Bearer Token、App ID/Secret 等。
     * 不同获取器对凭证的要求不同，参见具体的 Fetcher 实现。
     */
    private Map<String, String> credentials;
}
