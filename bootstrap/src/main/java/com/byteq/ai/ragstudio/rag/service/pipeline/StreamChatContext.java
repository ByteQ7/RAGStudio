package com.byteq.ai.ragstudio.rag.service.pipeline;

import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.rag.core.rewrite.RewriteResult;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 流式对话上下文
 * <p>
 * 承载流式对话流水线（StreamChatPipeline）执行过程中所需的全部数据。
 * 包含两部分内容：
 * <ol>
 *   <li><b>不可变输入参数</b>：由调用方在构建时传入，在整个流水线中只读</li>
 *   <li><b>中间状态</b>：在流水线各阶段逐步填充，传递到后续阶段使用</li>
 * </ol>
 * </p>
 */
@Getter
@Builder
public class StreamChatContext {

    // ==================== 不可变输入参数 ====================

    /** 用户问题文本 */
    private final String question;

    /** 会话 ID，为空时将创建新会话 */
    private final String conversationId;

    /** 任务 ID，用于追踪和取消任务 */
    private final String taskId;

    /** 是否开启深度思考模式 */
    private final boolean deepThinking;

    /** 当前用户 ID */
    private final String userId;

    /** SSE 流式回调，用于向客户端推送结果 */
    private final StreamCallback callback;

    /** 用户选择的知识库 ID 列表 */
    private final List<String> knowledgeBaseIds;

    /** 图片 S3 URL 列表（用于多模态识别） */
    private final List<String> imageUrls;

    // ==================== 管道中填充的中间状态 ====================

    /** 对话历史消息列表（包含当前用户问题） */
    @Setter
    private List<ChatMessage> history;

    /** 查询改写结果，包含改写后的问题和子问题列表 */
    @Setter
    private RewriteResult rewriteResult;

    /** MCP 工具决策结果：非空表示需要调用该工具 */
    @Setter
    private String mcpToolId;

    /** Agent 检索过程中获取的 Chunk，用于引用溯源 */
    private List<RetrievedChunk> retrievedChunks;

    /**
     * 设置检索到的 Chunk 列表
     */
    public void setRetrievedChunks(List<RetrievedChunk> chunks) {
        this.retrievedChunks = chunks;
    }

    /**
     * 追加检索到的 Chunk（Agent 可能多次调用 rag_search，每次追加而非覆盖）
     */
    public void addRetrievedChunks(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return;
        if (this.retrievedChunks == null || this.retrievedChunks.isEmpty()) {
            this.retrievedChunks = new java.util.ArrayList<>(chunks);
        } else {
            this.retrievedChunks.addAll(chunks);
        }
    }

    /**
     * 获取改写后的问题，如果改写结果为空则返回原始问题
     */
    public String getRewrittenQuestionOrOriginal() {
        return rewriteResult != null ? rewriteResult.rewrittenQuestion() : question;
    }

    /**
     * 安全获取 RewriteResult，如果为空则使用原始问题构造默认结果
     */
    public RewriteResult getRewriteResultOrDefault() {
        if (rewriteResult != null) {
            return rewriteResult;
        }
        return new RewriteResult(question, List.of(question));
    }
}
