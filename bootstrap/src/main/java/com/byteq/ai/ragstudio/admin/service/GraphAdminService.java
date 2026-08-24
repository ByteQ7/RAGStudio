package com.byteq.ai.ragstudio.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.byteq.ai.ragstudio.admin.controller.vo.GraphBuildLogVO;
import com.byteq.ai.ragstudio.admin.controller.vo.GraphEntityVO;
import com.byteq.ai.ragstudio.admin.controller.vo.GraphKbStatusVO;
import com.byteq.ai.ragstudio.admin.controller.vo.GraphOverviewVO;
import com.byteq.ai.ragstudio.admin.controller.vo.GraphRelationVO;
import com.byteq.ai.ragstudio.admin.controller.vo.GraphSubgraphVO;

import java.util.List;

/**
 * 图谱管理服务
 * <p>提供知识库图谱的统计概览、实体/关系查询、实体合并、子图可视化数据与构建日志查询。</p>
 */
public interface GraphAdminService {

    /**
     * 图谱统计概览
     */
    GraphOverviewVO overview(String kbId);

    /**
     * 全部知识库的图谱状态列表（Graph RAG 总览页）
     */
    List<GraphKbStatusVO> kbsStatus();

    /**
     * 实体分页查询（支持关键词与类型过滤）
     */
    IPage<GraphEntityVO> pageEntities(String kbId, String keyword, String entityType, long current, long size);

    /**
     * 实体详情（含全部关系）
     */
    GraphEntityVO getEntity(String entityId);

    /**
     * 合并实体：将 mergeEntityIds 指向的关系迁移到 keepEntityId，并删除被合并实体
     */
    void mergeEntities(String kbId, String keepEntityId, List<String> mergeEntityIds);

    /**
     * 子图可视化数据（mermaid 渲染，定点展开或取高频实体）
     */
    GraphSubgraphVO subgraph(String kbId, String focusEntityId, int maxNodes);

    /**
     * 构建日志分页
     */
    IPage<GraphBuildLogVO> pageBuildLogs(String kbId, long current, long size);
}