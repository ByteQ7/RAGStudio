package com.byteq.ai.ragstudio.ingestion.strategy.fetcher;

import com.byteq.ai.ragstudio.ingestion.domain.context.DocumentSource;
import com.byteq.ai.ragstudio.ingestion.domain.enums.SourceType;
import com.byteq.ai.ragstudio.ingestion.node.FetcherNode;

/**
 * 文档获取器接口
 * <p>
 * 定义文档获取的统一契约，采用策略模式（Strategy Pattern）实现。
 * 不同的文档来源类型通过实现该接口来提供各自的获取逻辑，
 * 由 {@link FetcherNode} 根据
 * 文档源类型动态路由到对应的实现。
 * </p>
 * <p>
 * 系统内置了以下实现：
 * <ul>
 *   <li>{@link LocalFileFetcher} - 本地文件系统获取</li>
 *   <li>{@link HttpUrlFetcher} - HTTP/HTTPS 网络资源获取</li>
 *   <li>{@link S3Fetcher} - S3 兼容对象存储获取</li>
 *   <li>{@link FeishuFetcher} - 飞书文档平台获取</li>
 * </ul>
 * </p>
 *
 * @see SourceType
 * @see FetchResult
 */
public interface DocumentFetcher {

    /**
     * 获取当前获取器支持的文档源类型
     *
     * @return 对应的文档源类型枚举值
     */
    SourceType supportedType();

    /**
     * 从指定的文档源中获取文档内容
     * <p>
     * 根据文档源的 location 和 credentials 信息，从对应的存储介质中
     * 读取文档的原始字节数据，并返回包含内容、MIME 类型和文件名的结果。
     * </p>
     *
     * @param source 文档数据源，包含源类型、访问位置和凭证信息
     * @return 获取结果，包含文档的原始字节内容、MIME 类型和文件名
     */
    FetchResult fetch(DocumentSource source);
}
