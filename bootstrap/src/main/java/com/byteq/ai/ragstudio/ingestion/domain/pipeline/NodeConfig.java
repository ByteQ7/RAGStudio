package com.byteq.ai.ragstudio.ingestion.domain.pipeline;

import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionNodeType;
import com.byteq.ai.ragstudio.ingestion.engine.ConditionEvaluator;
import com.byteq.ai.ragstudio.ingestion.engine.IngestionEngine;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 流水线节点配置实体类
 * <p>
 * 定义摄入流水线中单个节点的配置信息，包括节点标识、类型、参数设置以及执行条件等。
 * 节点之间通过 {@code nextNodeId} 形成链式执行关系，由
 * {@link IngestionEngine} 编排执行。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NodeConfig {

    /**
     * 节点的唯一标识符
     * 用于在流水线中唯一标识一个节点
     */
    private String nodeId;

    /**
     * 节点类型
     * 对应 {@link IngestionNodeType} 中的枚举值，
     * 如 "fetcher"、"parser"、"chunker" 等
     */
    private String nodeType;

    /**
     * 节点的配置参数
     * 不同类型的节点有不同的配置结构，以 JSON 格式存储。
     * 例如解析器节点包含 MIME 类型规则，分块节点包含分块大小和重叠大小等。
     */
    private JsonNode settings;

    /**
     * 节点执行的条件表达式
     * 当条件满足时才会执行该节点，条件不满足时自动跳过。
     * 以 JSON 格式存储，由 {@link ConditionEvaluator} 评估。
     */
    private JsonNode condition;

    /**
     * 下一个节点 ID（无条件后继）
     * <p>
     * 无分支时指向唯一的后继节点；存在分支时作为"兜底分支"（所有条件均不命中时走这里），
     * 为空表示所有条件不命中时流水线正常结束。
     */
    private String nextNodeId;

    /**
     * 条件分支列表（排他分支，可空）
     * <p>
     * 执行完当前节点后按顺序求值：首个命中的分支作为后继；
     * 全部不命中则走 {@link #nextNodeId}（兜底），为空则结束。
     */
    private List<Branch> branches;

    /**
     * 单个条件分支
     * <p>
     * 由画布编译产生：条件挂在连线上，{@code condition} 为空表示兜底分支。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Branch {

        /** 分支条件（JSON，格式见 {@link ConditionEvaluator}）；null = 兜底分支 */
        private JsonNode condition;

        /** 命中后的后继节点 ID */
        private String nextNodeId;
    }
}
