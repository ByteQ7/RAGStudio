package com.byteq.ai.ragstudio.ingestion.node;

import com.byteq.ai.ragstudio.ingestion.strategy.fetcher.*;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.ingestion.domain.context.DocumentSource;
import com.byteq.ai.ragstudio.ingestion.domain.context.IngestionContext;
import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionNodeType;
import com.byteq.ai.ragstudio.ingestion.domain.enums.SourceType;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.NodeConfig;
import com.byteq.ai.ragstudio.ingestion.domain.result.NodeResult;
import com.byteq.ai.ragstudio.ingestion.util.MimeTypeDetector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文档获取节点（FetcherNode）
 * <p>
 * 数据摄入流水线的第一个处理节点，负责从多元化的存储介质中检索并载入文档的原始字节流。
 * 支持的文档来源类型包括：
 * <ul>
 *   <li>本地文件系统（FILE）- 通过 {@link LocalFileFetcher} 实现</li>
 *   <li>HTTP/HTTPS 链接（URL）- 通过 {@link HttpUrlFetcher} 实现</li>
 *   <li>S3 兼容对象存储（S3）- 通过 {@link S3Fetcher} 实现</li>
 *   <li>飞书文档（FEISHU）- 通过 {@link FeishuFetcher} 实现</li>
 * </ul>
 * </p>
 * <p>
 * 核心逻辑采用策略模式（Strategy Pattern），根据 {@link SourceType} 动态路由至具体的
 * {@link DocumentFetcher} 实现。
 * 同时具备幂等性检查机制：若上下文中已预置原始字节数据，则自动跳过获取流程，避免重复 I/O。
 * </p>
 */
@Component
public class FetcherNode implements IngestionNode {

    /**
     * 文档获取器策略映射表
     * key 为文档源类型，value 为对应的文档获取器实现
     */
    private final Map<SourceType, DocumentFetcher> fetchers;

    // 将所有文档获取器按支持的来源类型注册到策略映射表中，便于后续按类型路由
    public FetcherNode(List<DocumentFetcher> fetchers) {
        this.fetchers = fetchers.stream()
                .collect(Collectors.toMap(DocumentFetcher::supportedType, Function.identity()));
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.FETCHER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        // 文档获取流程:
        // 1. 幂等性检查：上下文中已有原始字节则跳过获取，补充 MIME 类型检测
        // 2. 校验文档来源和来源类型的有效性
        // 3. 根据来源类型从策略映射表中路由到对应的 DocumentFetcher
        // 4. 执行获取操作，将字节内容、MIME 类型和文件名写回上下文

        // 幂等性检查：如果上下文中已有原始字节数据，则跳过获取流程
        if (context.getRawBytes() != null && context.getRawBytes().length > 0) {
            if (!StringUtils.hasText(context.getMimeType())) {
                String fileName = context.getSource() == null ? null : context.getSource().getFileName();
                context.setMimeType(MimeTypeDetector.detect(context.getRawBytes(), fileName));
            }
            return NodeResult.ok("已跳过获取器：原始字节已存在");
        }

        DocumentSource source = context.getSource();
        if (source == null || source.getType() == null) {
            return NodeResult.fail(new ClientException("文档来源不能为空"));
        }

        DocumentFetcher fetcher = fetchers.get(source.getType());
        if (fetcher == null) {
            return NodeResult.fail(new ClientException("不支持的来源类型: " + source.getType()));
        }

        FetchResult result = fetcher.fetch(source);
        context.setRawBytes(result.content());
        if (StringUtils.hasText(result.mimeType())) {
            context.setMimeType(result.mimeType());
        }
        if (StringUtils.hasText(result.fileName())) {
            source.setFileName(result.fileName());
        }
        return NodeResult.ok("已获取 " + (result.content() == null ? 0 : result.content().length) + " 字节");
    }
}
