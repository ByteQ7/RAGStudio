package com.byteq.ai.ragstudio.ingestion.domain.result;

import com.byteq.ai.ragstudio.ingestion.engine.IngestionEngine;
import com.byteq.ai.ragstudio.ingestion.node.IngestionNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 节点执行结果实体类
 * <p>
 * 表示流水线中单个节点执行完成后的结果信息，包含执行状态、是否继续执行后续节点等信息。
 * 该结果由 {@link IngestionNode#execute} 方法返回，
 * 并由 {@link IngestionEngine} 根据结果状态
 * 决定是否继续执行后续节点。
 * </p>
 * <p>
 * 结果类型说明：
 * <ul>
 *   <li>ok - 执行成功，继续后续节点</li>
 *   <li>skip - 跳过当前节点，继续后续节点</li>
 *   <li>fail - 执行失败，终止流水线</li>
 *   <li>terminate - 执行成功，但主动终止流水线</li>
 * </ul>
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NodeResult {

    /**
     * 节点是否执行成功
     * true 表示执行成功或跳过，false 表示执行失败
     */
    private boolean success;

    /**
     * 是否应继续执行后续节点
     * true 表示继续执行下一个节点，false 表示终止流水线执行
     */
    private boolean shouldContinue;

    /**
     * 结果消息说明
     * 成功时为处理概要（如"已获取 1024 字节"），失败时为错误描述
     */
    private String message;

    /**
     * 节点执行失败时的异常信息
     */
    private Throwable error;

    /**
     * 创建成功结果
     * <p>
     * 表示节点执行成功，且流水线应继续执行后续节点。
     * </p>
     *
     * @return 表示执行成功且应继续执行的结果对象
     */
    public static NodeResult ok() {
        return NodeResult.builder().success(true).shouldContinue(true).build();
    }

    /**
     * 创建带消息的成功结果
     * <p>
     * 表示节点执行成功，附带处理概要信息，且流水线应继续执行后续节点。
     * </p>
     *
     * @param message 处理结果消息
     * @return 表示执行成功且应继续执行的结果对象
     */
    public static NodeResult ok(String message) {
        return NodeResult.builder().success(true).shouldContinue(true).message(message).build();
    }

    /**
     * 创建跳过结果
     * <p>
     * 表示节点因条件不满足等原因被跳过，但流水线应继续执行后续节点。
     * </p>
     *
     * @param reason 跳过原因说明
     * @return 表示节点被跳过但应继续执行的结果对象
     */
    public static NodeResult skip(String reason) {
        return NodeResult.builder().success(true).shouldContinue(true).message("Skipped: " + reason).build();
    }

    /**
     * 创建失败结果
     * <p>
     * 表示节点执行失败，流水线应终止执行。
     * </p>
     *
     * @param error 导致失败的异常信息
     * @return 表示执行失败且不应继续执行的结果对象
     */
    public static NodeResult fail(Throwable error) {
        return NodeResult.builder()
                .success(false)
                .shouldContinue(false)
                .error(error)
                .message(error == null ? null : error.getMessage())
                .build();
    }

    /**
     * 创建终止结果
     * <p>
     * 表示节点执行成功，但主动请求终止流水线执行（如配置为仅做校验时使用）。
     * </p>
     *
     * @param reason 终止原因
     * @return 表示执行成功但应终止流水线执行的结果对象
     */
    public static NodeResult terminate(String reason) {
        return NodeResult.builder().success(true).shouldContinue(false).message(reason).build();
    }
}
