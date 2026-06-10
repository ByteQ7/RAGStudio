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
}
