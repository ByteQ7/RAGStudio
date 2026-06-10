package com.byteq.ai.ragstudio.ingestion.domain.result;

import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 摄入任务结果实体类
 * <p>
 * 表示文档摄入任务执行完成后的结果信息，包含任务 ID、状态、分块数量等概要数据。
 * 该结果由服务层返回给控制层，最终以 RESTful API 的形式返回给调用方。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestionResult {

    /**
     * 摄入任务的唯一标识符
     */
    private String taskId;

    /**
     * 执行本次摄入的流水线 ID
     */
    private String pipelineId;

    /**
     * 摄入任务的最终状态
     * 参见 {@link IngestionStatus} 枚举：pending、running、completed、failed
     */
    private IngestionStatus status;

    /**
     * 文档被切分成的块数量
     * 如果任务失败，该值为空或 0
     */
    private Integer chunkCount;

    /**
     * 执行结果的消息说明
     * 成功时为处理概要信息（如"已写入 10 个分块"），失败时为错误原因描述
     */
    private String message;
}
