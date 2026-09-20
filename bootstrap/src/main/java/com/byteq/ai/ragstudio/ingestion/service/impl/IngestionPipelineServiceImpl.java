package com.byteq.ai.ragstudio.ingestion.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.byteq.ai.ragstudio.ingestion.controller.request.IngestionPipelineCreateRequest;
import com.byteq.ai.ragstudio.ingestion.controller.request.IngestionPipelineNodeRequest;
import com.byteq.ai.ragstudio.ingestion.controller.request.IngestionPipelineUpdateRequest;
import com.byteq.ai.ragstudio.ingestion.controller.vo.IngestionPipelineNodeVO;
import com.byteq.ai.ragstudio.ingestion.controller.vo.IngestionPipelineVO;
import com.byteq.ai.ragstudio.ingestion.dao.entity.IngestionPipelineDO;
import com.byteq.ai.ragstudio.ingestion.enums.IngestionErrorCode;
import com.byteq.ai.ragstudio.ingestion.dao.entity.IngestionPipelineNodeDO;
import com.byteq.ai.ragstudio.ingestion.dao.mapper.IngestionPipelineMapper;
import com.byteq.ai.ragstudio.ingestion.dao.mapper.IngestionPipelineNodeMapper;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionNodeType;
import com.byteq.ai.ragstudio.ingestion.domain.graph.IngestionGraph;
import com.byteq.ai.ragstudio.ingestion.domain.graph.IngestionGraphCompiler;
import com.byteq.ai.ragstudio.ingestion.domain.graph.IngestionGraphFactory;
import com.byteq.ai.ragstudio.ingestion.domain.graph.IngestionGraphJson;
import com.byteq.ai.ragstudio.ingestion.domain.graph.IngestionGraphValidator;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.NodeConfig;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.PipelineDefinition;
import com.byteq.ai.ragstudio.ingestion.service.IngestionPipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 数据清洗流水线业务逻辑实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionPipelineServiceImpl implements IngestionPipelineService {

    private final IngestionPipelineMapper pipelineMapper;
    private final IngestionPipelineNodeMapper nodeMapper;
    private final ObjectMapper objectMapper;

    /**
     * 创建数据清洗流水线
     * <p>
     * 流程:
     * 1. 校验请求参数
     * 2. 构建流水线实体并持久化（名称重复时抛出异常）
     * 3. 全量写入节点配置
     * 4. 返回完整流水线视图
     * </p>
     *
     * @param request 创建请求，包含流水线名称、描述及节点配置列表
     * @return 创建成功的流水线视图对象
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public IngestionPipelineVO create(IngestionPipelineCreateRequest request) {
        Assert.notNull(request, () -> new ClientException("请求不能为空"));
        IngestionPipelineDO pipeline = IngestionPipelineDO.builder()
                .name(request.getName())
                .description(request.getDescription())
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        try {
            pipelineMapper.insert(pipeline);
        } catch (DuplicateKeyException dke) {
            throw new ClientException("流水线名称已存在");
        }
        applyGraph(pipeline, request.getGraph(), request.getNodes());
        return toVO(pipeline, fetchNodes(pipeline.getId()));
    }

    /**
     * 更新数据清洗流水线
     * <p>
     * 流程:
     * 1. 查询并校验流水线是否存在
     * 2. 更新名称和描述（仅非空字段）
     * 3. 如果传入了节点列表，全量替换节点配置
     * 4. 返回更新后的流水线视图
     * </p>
     *
     * @param pipelineId 流水线 ID
     * @param request    更新请求体
     * @return 更新后的流水线视图对象
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public IngestionPipelineVO update(String pipelineId, IngestionPipelineUpdateRequest request) {
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线", IngestionErrorCode.PIPELINE_NOT_FOUND));

        if (StringUtils.hasText(request.getName())) {
            pipeline.setName(request.getName());
        }
        if (request.getDescription() != null) {
            pipeline.setDescription(request.getDescription());
        }
        pipeline.setUpdatedBy(UserContext.getUsername());
        pipelineMapper.updateById(pipeline);

        applyGraph(pipeline, request.getGraph(), request.getNodes());
        return toVO(pipeline, fetchNodes(pipeline.getId()));
    }

    /**
     * 根据 ID 查询流水线详情，包含完整的节点配置列表
     *
     * @param pipelineId 流水线 ID
     * @return 流水线视图对象
     */
    @Override
    public IngestionPipelineVO get(String pipelineId) {
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线", IngestionErrorCode.PIPELINE_NOT_FOUND));
        return toVO(pipeline, fetchNodes(pipeline.getId()));
    }

    /**
     * 分页查询流水线列表，支持按名称关键字模糊搜索，按更新时间倒序排列
     *
     * @param page    分页参数
     * @param keyword 搜索关键字（可选）
     * @return 流水线分页结果
     */
    @Override
    public IPage<IngestionPipelineVO> page(Page<IngestionPipelineVO> page, String keyword) {
        Page<IngestionPipelineDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        LambdaQueryWrapper<IngestionPipelineDO> qw = new LambdaQueryWrapper<IngestionPipelineDO>()
                .eq(IngestionPipelineDO::getDeleted, 0)
                .like(StringUtils.hasText(keyword), IngestionPipelineDO::getName, keyword)
                .orderByDesc(IngestionPipelineDO::getUpdateTime);
        IPage<IngestionPipelineDO> result = pipelineMapper.selectPage(mpPage, qw);
        Page<IngestionPipelineVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream()
                .map(each -> toVO(each, fetchNodes(each.getId())))
                .toList());
        return voPage;
    }

    /**
     * 逻辑删除流水线及其关联的所有节点配置
     *
     * @param pipelineId 流水线 ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String pipelineId) {
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线", IngestionErrorCode.PIPELINE_NOT_FOUND));
        pipeline.setDeleted(1);
        pipeline.setUpdatedBy(UserContext.getUsername());
        pipelineMapper.deleteById(pipeline);

        LambdaQueryWrapper<IngestionPipelineNodeDO> qw = new LambdaQueryWrapper<IngestionPipelineNodeDO>()
                .eq(IngestionPipelineNodeDO::getPipelineId, pipeline.getId());
        nodeMapper.delete(qw);
    }

    @Override
    public IngestionGraphValidator.ValidationResult validateGraph(IngestionGraph graph) {
        return IngestionGraphValidator.validate(graph);
    }

    /**
     * 获取流水线完整定义（供引擎执行使用）
     * <p>
     * 将流水线元数据和所有节点配置转换为引擎可执行的 PipelineDefinition 对象。
     * </p>
     *
     * @param pipelineId 流水线 ID
     * @return 流水线定义对象
     */
    @Override
    public PipelineDefinition getDefinition(String pipelineId) {
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线", IngestionErrorCode.PIPELINE_NOT_FOUND));

        List<NodeConfig> nodes = fetchNodes(pipeline.getId()).stream()
                .map(this::toNodeConfig)
                .toList();
        return PipelineDefinition.builder()
                .id(String.valueOf(pipeline.getId()))
                .name(pipeline.getName())
                .description(pipeline.getDescription())
                .nodes(nodes)
                .build();
    }

    /**
     * 应用图/线性节点变更并持久化 graph_json
     * <p>
     * 优先级：graph 非空 → 校验 + 编译为运行节点；否则 nodes 非空 → 直接使用并生成线性图；
     * 两者都为空（null）→ 不动节点。nodes 显式传空数组视为清空（修复旧实现空数组不清空的问题）。
     * </p>
     */
    private void applyGraph(IngestionPipelineDO pipeline, IngestionGraph graph,
                            List<IngestionPipelineNodeRequest> nodes) {
        List<NodeConfig> configs;
        String graphJson;
        if (graph != null && !graph.isEmpty()) {
            IngestionGraphValidator.ValidationResult validation = IngestionGraphValidator.validate(graph);
            if (validation.hasError()) {
                throw new ClientException("流水线图校验失败：" + String.join("；", validation.errors()));
            }
            configs = IngestionGraphCompiler.compile(graph);
            graphJson = IngestionGraphJson.toJson(graph);
        } else if (nodes != null) {
            configs = nodes.stream()
                    .filter(Objects::nonNull)
                    .map(node -> NodeConfig.builder()
                            .nodeId(node.getNodeId())
                            .nodeType(normalizeNodeType(node.getNodeType()))
                            .settings(node.getSettings())
                            .condition(node.getCondition())
                            .nextNodeId(node.getNextNodeId())
                            .build())
                    .toList();
            graphJson = IngestionGraphJson.toJson(IngestionGraphFactory.fromNodes(configs));
        } else {
            return;
        }

        replaceNodes(pipeline.getId(), configs);
        pipeline.setGraphJson(graphJson);
        pipeline.setUpdatedBy(UserContext.getUsername());
        pipelineMapper.updateById(pipeline);
    }

    // 全量替换流水线的运行节点：先删除旧节点，再逐一插入新节点（含分支）
    private void replaceNodes(String pipelineId, List<NodeConfig> configs) {
        LambdaQueryWrapper<IngestionPipelineNodeDO> qw = new LambdaQueryWrapper<IngestionPipelineNodeDO>()
                .eq(IngestionPipelineNodeDO::getPipelineId, pipelineId);
        nodeMapper.delete(qw);
        for (NodeConfig config : configs) {
            if (config == null) {
                continue;
            }
            IngestionPipelineNodeDO entity = IngestionPipelineNodeDO.builder()
                    .pipelineId(pipelineId)
                    .nodeId(config.getNodeId())
                    .nodeType(normalizeNodeType(config.getNodeType()))
                    .nextNodeId(config.getNextNodeId())
                    .settingsJson(toJson(config.getSettings()))
                    .conditionJson(toJson(config.getCondition()))
                    .branchesJson(config.getBranches() == null ? null : toJson(objectMapper.valueToTree(config.getBranches())))
                    .createdBy(UserContext.getUsername())
                    .updatedBy(UserContext.getUsername())
                    .build();
            nodeMapper.insert(entity);
        }
    }

    // 根据流水线 ID 查询其所有未删除的节点配置
    private List<IngestionPipelineNodeDO> fetchNodes(String pipelineId) {
        LambdaQueryWrapper<IngestionPipelineNodeDO> qw = new LambdaQueryWrapper<IngestionPipelineNodeDO>()
                .eq(IngestionPipelineNodeDO::getPipelineId, pipelineId)
                .eq(IngestionPipelineNodeDO::getDeleted, 0);
        return nodeMapper.selectList(qw);
    }

    // 将流水线 DO 及其节点列表转换为 VO 视图对象
    private IngestionPipelineVO toVO(IngestionPipelineDO pipeline, List<IngestionPipelineNodeDO> nodes) {
        IngestionPipelineVO vo = BeanUtil.toBean(pipeline, IngestionPipelineVO.class);
        vo.setNodes(nodes.stream().map(this::toNodeVO).toList());
        vo.setGraph(resolveGraph(pipeline, nodes));
        return vo;
    }

    /** 读取画布图：优先 graph_json；为空时由运行节点自动生成线性图（旧数据兼容，不落库） */
    private IngestionGraph resolveGraph(IngestionPipelineDO pipeline, List<IngestionPipelineNodeDO> nodes) {
        IngestionGraph graph = IngestionGraphJson.parse(pipeline.getGraphJson());
        if (graph != null && !graph.isEmpty()) {
            return graph;
        }
        List<NodeConfig> configs = (nodes != null ? nodes : List.<IngestionPipelineNodeDO>of()).stream()
                .map(this::toNodeConfig)
                .toList();
        return IngestionGraphFactory.fromNodes(configs);
    }

    // 将节点 DO 转换为 VO，并反序列化 settings 和 condition JSON
    private IngestionPipelineNodeVO toNodeVO(IngestionPipelineNodeDO node) {
        IngestionPipelineNodeVO vo = BeanUtil.toBean(node, IngestionPipelineNodeVO.class);
        vo.setNodeType(normalizeNodeTypeForOutput(node.getNodeType()));
        vo.setSettings(parseJson(node.getSettingsJson()));
        vo.setCondition(parseJson(node.getConditionJson()));
        return vo;
    }

    // 将节点 DO 转换为引擎执行所需的 NodeConfig 对象
    private NodeConfig toNodeConfig(IngestionPipelineNodeDO node) {
        return NodeConfig.builder()
                .nodeId(node.getNodeId())
                .nodeType(normalizeNodeType(node.getNodeType()))
                .settings(parseJson(node.getSettingsJson()))
                .condition(parseJson(node.getConditionJson()))
                .nextNodeId(node.getNextNodeId())
                .branches(parseBranches(node.getBranchesJson()))
                .build();
    }

    // 解析分支 JSON 为 NodeConfig.Branch 列表
    private List<NodeConfig.Branch> parseBranches(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<List<NodeConfig.Branch>>() {
            });
        } catch (Exception e) {
            log.warn("分支 JSON 解析失败: {}", raw, e);
            return null;
        }
    }

    // 将 JsonNode 序列化为字符串，null 返回 null
    private String toJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.toString();
    }

    // 将字符串反序列化为 JsonNode，解析失败返回 null
    private JsonNode parseJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.warn("JSON 解析失败: {}", raw, e);
            return null;
        }
    }

    // 校验并标准化节点类型，未知类型抛出异常
    private String normalizeNodeType(String nodeType) {
        if (!StringUtils.hasText(nodeType)) {
            return nodeType;
        }
        try {
            return IngestionNodeType.fromValue(nodeType).getValue();
        } catch (IllegalArgumentException ex) {
            throw new ClientException("未知节点类型: " + nodeType);
        }
    }

    // 标准化节点类型用于输出展示，未知类型不抛异常而是原样返回
    private String normalizeNodeTypeForOutput(String nodeType) {
        if (!StringUtils.hasText(nodeType)) {
            return nodeType;
        }
        try {
            return IngestionNodeType.fromValue(nodeType).getValue();
        } catch (IllegalArgumentException ex) {
            return nodeType;
        }
    }
}
