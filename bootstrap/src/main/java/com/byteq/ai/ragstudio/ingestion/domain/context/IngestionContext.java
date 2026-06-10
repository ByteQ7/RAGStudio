package com.byteq.ai.ragstudio.ingestion.domain.context;

import com.byteq.ai.ragstudio.core.chunk.VectorChunk;
import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionStatus;
import com.byteq.ai.ragstudio.rag.core.vector.VectorSpaceId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 文档摄入上下文实体类
 * <p>
 * 在文档摄入流水线执行过程中，承载和传递所有中间数据和状态信息。
 * 该上下文是流水线执行的核心数据结构，贯穿整个处理流程：
 * <ol>
 *   <li>FetcherNode 写入 {@code rawBytes}（原始字节）和 {@code mimeType}</li>
 *   <li>ParserNode 读取 {@code rawBytes}，写入 {@code rawText} 和 {@code document}</li>
 *   <li>EnhancerNode 读取 {@code rawText}，写入 {@code enhancedText}、{@code keywords} 等</li>
 *   <li>ChunkerNode 读取文本，写入 {@code chunks}（文本块列表）</li>
 *   <li>EnricherNode 读取 {@code chunks}，富化每个文本块的元数据</li>
 *   <li>IndexerNode 读取 {@code chunks}，写入向量数据库</li>
 * </ol>
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestionContext {

    /**
     * 摄入任务的唯一标识符
     * 用于关联任务记录和日志
     */
    private String taskId;

    /**
     * 执行本次摄入的流水线 ID
     * 标识使用哪条流水线定义来处理文档
     */
    private String pipelineId;

    /**
     * 文档源信息
     * 描述文档的来源类型、访问位置、文件名称和凭证信息
     */
    private DocumentSource source;

    /**
     * 文档的原始字节数据
     * 由 FetcherNode 从文档源获取的原始二进制内容
     */
    private byte[] rawBytes;

    /**
     * 文档的 MIME 类型
     * 用于识别文档格式（如 application/pdf、text/plain 等），辅助解析器选择
     */
    private String mimeType;

    /**
     * 解析后的原始文本内容
     * 由 ParserNode 将原始字节解析为纯文本后的结果
     */
    private String rawText;

    /**
     * 结构化解析后的文档对象
     * 包含文本内容、章节结构和表格等结构化信息
     */
    private StructuredDocument document;

    /**
     * 文档切分后的文本块列表
     * 由 ChunkerNode 生成，每个文本块包含文本内容和向量嵌入
     */
    private List<VectorChunk> chunks;

    /**
     * 经过增强处理后的文本内容
     * 由 EnhancerNode 在原始文本基础上进行上下文增强后的结果
     */
    private String enhancedText;

    /**
     * 从文档中提取的关键词列表
     * 由 EnhancerNode 通过 LLM 提取
     */
    private List<String> keywords;

    /**
     * 基于文档内容生成的问题列表
     * 由 EnhancerNode 通过 LLM 生成，可用于后续的 QA 检索
     */
    private List<String> questions;

    /**
     * 摄入过程中的元数据信息
     * 包含文档级别的元数据键值对，如作者、日期、来源等
     */
    private Map<String, Object> metadata;

    /**
     * 向量空间 ID
     * 指定向量数据写入的目标集合名称，如果不指定则使用默认的向量空间
     */
    private VectorSpaceId vectorSpaceId;

    /**
     * 当前摄入任务的状态
     * 参见 {@link IngestionStatus} 枚举
     */
    private IngestionStatus status;

    /**
     * 流水线执行过程中的节点日志列表
     * 记录每个节点的执行耗时、状态、输出和错误信息，用于监控和排查
     */
    private List<NodeLog> logs;

    /**
     * 摄入过程中发生的异常信息
     * 当任务状态为 FAILED 时，此字段记录导致失败的异常
     */
    private Throwable error;

    /**
     * 是否跳过 IndexerNode 的向量写入
     * 为 true 时，IndexerNode 仅做校验和数据准备工作（构建行数据、分配 chunkId），
     * 不执行实际的向量数据库写入，由调用方统一在事务中完成向量持久化
     */
    @Builder.Default
    private boolean skipIndexerWrite = false;
}
