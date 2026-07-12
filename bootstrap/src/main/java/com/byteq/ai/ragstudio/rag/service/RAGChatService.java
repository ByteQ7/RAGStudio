package com.byteq.ai.ragstudio.rag.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * RAG 对话服务接口
 * <p>
 * 定义 RAG（检索增强生成）流式对话的核心业务接口。
 * 对外暴露流式问答与任务停止能力，屏蔽控制器层之外的实现细节。
 * </p>
 * <p>
 * 整体 RAG 流程说明：
 * <ol>
 *   <li><b>记忆加载</b>：从持久化存储中加载对话历史记录</li>
 *   <li><b>查询改写</b>：对用户问题进行改写和优化，提升检索效果</li>
 *   <li><b>知识检索</b>：根据用户选择的知识库从向量数据库中检索相关文档片段</li>
 *   <li><b>MCP 工具调用</b>：调用实时数据工具（如有注册）获取动态信息</li>
 *   <li><b>Prompt 组装</b>：将历史记录、检索结果和用户问题组装为完整的 Prompt</li>
 *   <li><b>流式输出</b>：调用大语言模型以 SSE 方式流式返回回答</li>
 * </ol>
 * </p>
 */
public interface RAGChatService {

    /**
     * 发起一次 SSE 流式问答
     * <p>
     * 这是 RAG 对话的入口方法，会触发完整的 RAG 处理流程：
     * 记忆加载 → 查询改写 → 知识检索 → MCP 工具调用 → Prompt 组装 → 流式输出。
     * 结果通过 SseEmitter 以 SSE 事件流的形式逐步推送给前端。
     * </p>
     *
     * @param question        用户问题文本
     * @param conversationId  会话 ID（可选，为空时创建新会话）
     * @param deepThinking    是否开启深度思考模式，开启后模型会展示推理过程
     * @param knowledgeBaseIds 用户选定的知识库 ID 列表（可选，为空时不检索知识库）
     * @param emitter         SSE 发射器，用于向客户端推送流式响应
     */
    void streamChat(String question, String conversationId, Integer deepThinkingLevel, List<String> knowledgeBaseIds, List<String> imageUrls, SseEmitter emitter);

    /**
     * 停止指定任务 ID 的流式会话
     * <p>
     * 通过任务 ID 查找并中断正在进行的流式对话任务，
     * 用于用户主动取消或超时取消场景。
     * </p>
     *
     * @param taskId 要停止的任务 ID
     */
    void stopTask(String taskId);
}
