package com.byteq.ai.ragstudio.framework.trace;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * RAG Trace 普通节点注解
 *
 * <p>标记一个方法作为 RAG 链路追踪中的普通节点，用于记录 RAG 流程中的各步骤。
 * 被标注的方法通常是 Service 层的 RAG 处理环节，如检索、重排序、生成等。</p>
 *
 * <p>节点层级关系：</p>
 * <ul>
 *   <li>根节点（{@link RagTraceRoot}）：一次完整请求的入口</li>
 *   <li>普通节点（{@link RagTraceNode}）：请求中的各处理环节</li>
 * </ul>
 *
 * <p>节点支持名称和类型两个维度，方便在前端做不同的可视化展示和统计聚合。</p>
 *
 * @see RagTraceRoot
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RagTraceNode {

    /**
     * 节点名称
     * <p>用于在前端链路图中展示的节点标签，如 {@code "向量检索"}、{@code "LLM 生成"}。</p>
     */
    String name() default "";

    /**
     * 节点类型
     * <p>用于对节点进行分组统计和样式分类，如 {@code "RETRIEVAL"}、{@code "GENERATION"}、{@code "METHOD"}。</p>
     */
    String type() default "METHOD";
}
