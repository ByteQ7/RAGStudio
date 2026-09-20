package com.byteq.ai.ragstudio.rag.workflow.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 工作流实体
 * <p>映射 t_workflow。工作流是 Agent 从对话中沉淀的可复用流程：
 * 名称 + 描述（召回注入用）+ 结构化步骤（选中后加载）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_workflow")
public class WorkflowDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 唯一标识（kebab-case） */
    private String name;

    /** 展示名（中文可） */
    private String title;

    /** 何时使用（召回注入的核心文本） */
    private String description;

    /** 结构化步骤 JSON：{"steps":[...],"notes":"..."}（运行态，由 graph 编译或直接提供） */
    private String steps;

    /** 画布图 JSON（编辑态事实源，见 docs/workflow-canvas-design.md） */
    private String graphJson;

    /** 来源：EXTRACTED（对话提取）/ MANUAL（后管手工创建） */
    private String source;

    /** 停用后不参与召回 */
    private Boolean enabled;

    /** "名称+描述"的向量缓存（JSON 数组字符串，避免重复远程 embedding） */
    private String embedding;

    /** 向量对应的模型 ID（模型切换后自动重建） */
    private String embeddingModel;

    /** 向量对应的文本指纹（标题/描述变更后自动重算） */
    private String embeddingTextHash;

    /** 最近一次变更说明 */
    private String changeLog;

    private String updatedBy;

    private Date createTime;

    private Date updateTime;
}
