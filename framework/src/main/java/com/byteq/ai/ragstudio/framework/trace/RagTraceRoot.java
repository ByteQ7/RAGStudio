package com.byteq.ai.ragstudio.framework.trace;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * RAG Trace 根节点注解
 *
 * <p>标记一个方法作为 RAG 链路追踪的根节点，表示一次完整的 RAG 请求入口。
 * 被标注的方法通常是 Controller 层的对话接口。</p>
 *
 * <p>功能说明：</p>
 * <ul>
 *   <li>自动生成全局唯一的 Trace ID，标识整条调用链路</li>
 *   <li>从方法参数中提取会话 ID（conversationId）和任务 ID（taskId）</li>
 *   <li>在请求结束时负责清理 Trace 上下文，避免内存泄漏</li>
 * </ul>
 *
 * @see RagTraceNode
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RagTraceRoot {

    /**
     * 链路名称
     * <p>用于展示和识别该 RAG 链路的业务含义，如 {@code "文档问答"}。</p>
     */
    String name() default "";

    /**
     * 会话 ID 参数名
     * <p>指定方法参数中用于标识会话的参数名称，从该参数提取会话 ID 注入 Trace 上下文。</p>
     */
    String conversationIdArg() default "conversationId";

    /**
     * 任务 ID 参数名
     * <p>指定方法参数中用于标识任务的参数名称，从该参数提取任务 ID 注入 Trace 上下文。</p>
     */
    String taskIdArg() default "taskId";
}
