package com.byteq.ai.ragstudio.framework.convention;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 检索命中结果
 *
 * <p>表示一次向量检索或相关性搜索命中的单条记录，用于 RAG（检索增强生成）流程中
 * 传递检索到的文档片段及其相关性信息。</p>
 *
 * <p>包含三个核心字段：</p>
 * <ul>
 *   <li>{@code id}：命中记录的唯一标识，对应向量库中的主键或文档段落 ID</li>
 *   <li>{@code text}：命中的文本内容，一般是切分后的文档片段或段落</li>
 *   <li>{@code score}：相关性得分，数值越大表示与查询的相关性越高</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetrievedChunk {

    /**
     * 命中记录的唯一标识
     * 比如向量库中的 primary key 或文档 id
     */
    private String id;

    /**
     * 命中的文本内容
     * 一般是被切分后的文档片段或段落
     */
    private String text;

    /**
     * 命中得分
     * 数值越大表示与查询的相关性越高
     */
    private Float score;

    /**
     * 所属知识库名称
     */
    private String kbName;

    /**
     * 所属文档名称
     */
    private String docName;

    /**
     * 附加元数据（从向量库 metadata JSON 中提取）
     */
    private java.util.Map<String, Object> metadata;

    /**
     * 内容类型: TEXT / IMAGE
     */
    private String contentType;

    /**
     * 引用来源类型: KB（知识库文档，默认）/ WEB（网络搜索结果）
     */
    private String sourceType;

    /**
     * WEB 来源链接（sourceType=WEB 时有值）
     */
    private String url;

    /**
     * WEB 结果标题（sourceType=WEB 时有值）
     */
    private String title;

    /**
     * WEB 来源搜索引擎（如 google、bing）
     */
    private String engine;

    /**
     * 精确标识符匹配标记：chunk 内容精确包含查询中的强 ID token
     * （统一社会信用代码/订单号等）时置位。
     * <p>
     * 该标记代表可证明的客观相关性，检索链路中的 rerank 底线过滤（rerank-min-score）
     * 不得丢弃此类 chunk——cross-encoder 对随机 ID 串普遍打分偏低，
     * 若无保护会漏检实体 ID 查询的唯一命中。
     * </p>
     */
    @Builder.Default
    private boolean exactMatch = false;

    public boolean isWebSource() {
        return "WEB".equalsIgnoreCase(sourceType);
    }

    public ChunkType getType() {
        return ChunkType.from(contentType);
    }

    public boolean isType(ChunkType type) {
        return getType() == type;
    }

    public boolean isImage() { return getType() == ChunkType.IMAGE; }
}
