package com.byteq.ai.ragstudio.ingestion.strategy.fetcher;

import com.byteq.ai.ragstudio.ingestion.domain.context.IngestionContext;
import com.byteq.ai.ragstudio.ingestion.node.FetcherNode;

/**
 * 文档获取结果记录
 * <p>
 * 封装文档获取操作的结果，包含文档的原始字节内容、MIME 类型以及文件名。
 * 该记录作为 {@link DocumentFetcher#fetch(DocumentSource)} 方法的返回值，
 * 由 {@link FetcherNode} 消费并
 * 写入 {@link IngestionContext}。
 * </p>
 *
 * @param content  获取到的文档原始字节数组
 * @param mimeType 文档的 MIME 类型（如 "application/pdf"、"text/plain"）
 * @param fileName 文档的文件名称（用于后续解析和类型识别）
 */
public record FetchResult(byte[] content, String mimeType, String fileName) {
}
