package com.byteq.ai.ragstudio.ingestion.engine;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.ingestion.domain.context.IngestionContext;
import com.byteq.ai.ragstudio.ingestion.domain.context.NodeLog;
import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionStatus;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.NodeConfig;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.PipelineDefinition;
import com.byteq.ai.ragstudio.ingestion.domain.result.NodeResult;
import com.byteq.ai.ragstudio.ingestion.node.IngestionNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 流水线执行引擎 - 基于节点连线的链式执行
 * <p>
 * 这是整个文档摄入系统的核心编排引擎，负责按照流水线定义的节点顺序，
 * 链式驱动各处理节点依次执行。引擎采用"链式执行"模式，每个节点通过
 * {@link NodeConfig#getNextNodeId()} 指向下一个处理节点，形成一条
 * 处理链（Linked Pipeline）。
 * </p>
 * <h3>流水线架构说明：</h3>
 * <ol>
 *   <li><b>启动节点查找</b>：通过分析所有节点的引用关系，自动找到
 *   没有被任何其他节点引用的节点作为起始节点</li>
 *   <li><b>依赖验证</b>：执行前检查流水线配置的合法性，包括节点引用
 *   关系的完整性以及是否存在循环依赖（环检测）</li>
 *   <li><b>链式执行</b>：从起始节点开始，按 {@code nextNodeId} 依次
 *   执行后续节点，直到遇到终止节点或执行失败</li>
 *   <li><b>条件执行</b>：支持节点级别的条件判断，满足条件时才执行
 *   当前节点的处理逻辑</li>
 *   <li><b>日志记录</b>：自动记录每个节点的执行耗时、状态和输出信息，
 *   便于监控和问题排查</li>
 *   <li><b>安全终止</b>：内置防死循环机制（执行节点数上限检测）和
 *   失败自动终止策略</li>
 * </ol>
 * <h3>典型流水线节点序列：</h3>
 * <pre>
 * FetcherNode → ParserNode → ChunkerNode → EnricherNode → EnhancerNode → IndexerNode
 * （获取文档）（解析内容）（文本分块）（分块富化）（文档增强）（向量索引）
 * </pre>
 */
@Slf4j
@Component
public class IngestionEngine {

    /**
     * 节点类型与实现的映射表
     * key 为节点类型标识（如 "fetcher"、"parser"），
     * value 为对应的节点处理实例
     */
    private final Map<String, IngestionNode> nodeMap;

    /**
     * 条件评估器，用于判断节点是否满足执行条件
     */
    private final ConditionEvaluator conditionEvaluator;

    /**
     * 节点输出提取器，用于从上下文中提取节点执行后的输出数据
     */
    private final NodeOutputExtractor outputExtractor;

    public IngestionEngine(
            List<IngestionNode> nodes,
            ConditionEvaluator conditionEvaluator,
            NodeOutputExtractor outputExtractor) {
        this.nodeMap = nodes.stream()
                .collect(Collectors.toMap(IngestionNode::getNodeType, n -> n));
        this.conditionEvaluator = conditionEvaluator;
        this.outputExtractor = outputExtractor;
    }

    /**
     * 执行流水线
     * <p>
     * 按照流水线定义的节点配置，从起始节点开始链式执行所有处理节点。
     * 执行过程中会记录每个节点的执行日志，并根据执行结果更新上下文状态。
     * </p>
     *
     * @param pipeline 流水线定义，包含节点配置列表和执行顺序
     * @param context  摄入上下文，承载和传递所有中间数据和状态信息
     * @return 执行完成后的摄入上下文，包含最终状态和处理结果
     * @throws ClientException
     *         如果流水线配置无效（存在环、找不到起始节点等）
     */
    public IngestionContext execute(PipelineDefinition pipeline, IngestionContext context) {
        if (context.getLogs() == null) {
            context.setLogs(new ArrayList<>());
        }
        context.setStatus(IngestionStatus.RUNNING);

        // 构建节点 ID 到节点配置的映射
        Map<String, NodeConfig> nodeConfigMap = buildNodeConfigMap(pipeline.getNodes());

        // 验证流水线配置的合法性（环检测、引用完整性检查）
        validatePipeline(nodeConfigMap);

        // 找到起始节点（没有被任何其他节点引用的节点作为流水线入口）
        String startNodeId = findStartNode(nodeConfigMap);
        if (StrUtil.isBlank(startNodeId)) {
            throw new ClientException("流水线未找到起始节点");
        }

        log.info("流水线从节点开始执行: {}", startNodeId);

        // 预扫描：从 Indexer 节点的 settings 中提取 embeddingModel，供 ChunkerNode 嵌入时使用
        prePopulateContextFromPipeline(pipeline, context);

        // 从起始节点开始链式执行所有节点
        executeChain(startNodeId, nodeConfigMap, context);

        if (context.getStatus() == IngestionStatus.RUNNING) {
            context.setStatus(IngestionStatus.COMPLETED);
        }
        return context;
    }

    /**
     * 预扫描流水线节点，将全局配置信息填充到上下文中
     * <p>
     * 目前从 IndexerNode 的 settings 中提取 embeddingModel，
     * 以便 ChunkerNode 在执行嵌入时可以使用用户指定的模型而非系统默认模型。
     * </p>
     *
     * @param pipeline 流水线定义
     * @param context  摄入上下文
     */
    private void prePopulateContextFromPipeline(PipelineDefinition pipeline, IngestionContext context) {
        if (pipeline.getNodes() == null) return;
        for (NodeConfig node : pipeline.getNodes()) {
            if ("indexer".equals(node.getNodeType()) && node.getSettings() != null) {
                com.fasterxml.jackson.databind.JsonNode emNode = node.getSettings().get("embeddingModel");
                if (emNode != null && emNode.isTextual() && StringUtils.hasText(emNode.asText())) {
                    context.setEmbeddingModel(emNode.asText());
                    log.info("从 IndexerNode 配置中读取 embeddingModel: {}", emNode.asText());
                    break;
                }
            }
        }
    }

    /**
     * 构建节点配置映射
     * <p>
     * 将流水线的节点配置列表转换为以节点 ID 为键的映射，
     * 便于在链式执行过程中快速查找节点配置。
     * </p>
     *
     * @param nodes 节点配置列表
     * @return 节点 ID 到节点配置的映射
     */
    private Map<String, NodeConfig> buildNodeConfigMap(List<NodeConfig> nodes) {
        if (nodes == null) {
            return Collections.emptyMap();
        }
        return nodes.stream()
                .collect(Collectors.toMap(NodeConfig::getNodeId, n -> n));
    }

    /**
     * 验证流水线配置
     * <p>
     * 对流水线的节点配置进行完整性验证：
     * <ul>
     *   <li>环检测（Cycle Detection）：通过 DFS 路径追踪检查是否存在循环引用</li>
     *   <li>引用完整性：确保每个节点引用的下一个节点在配置中存在</li>
     * </ul>
     * </p>
     *
     * @param nodeConfigMap 节点 ID 到节点配置的映射
     * @throws ClientException
     *         如果检测到环或引用不存在的节点
     */
    private void validatePipeline(Map<String, NodeConfig> nodeConfigMap) {
        Set<String> visited = new HashSet<>();

        for (String nodeId : nodeConfigMap.keySet()) {
            if (visited.contains(nodeId)) {
                continue;
            }

            Set<String> path = new HashSet<>();
            String current = nodeId;

            while (current != null) {
                if (path.contains(current)) {
                    throw new ClientException("流水线存在环: " + current);
                }

                path.add(current);
                visited.add(current);

                NodeConfig config = nodeConfigMap.get(current);
                if (config == null) {
                    break;
                }

                String nextId = config.getNextNodeId();
                if (StringUtils.hasText(nextId)) {
                    if (!nodeConfigMap.containsKey(nextId)) {
                        throw new ClientException("找不到下一个节点: " + nextId + "，被节点 " + current + " 引用");
                    }
                    current = nextId;
                } else {
                    break;
                }
            }
        }
    }

    /**
     * 找到起始节点（没有被任何节点引用的节点）
     * <p>
     * 起始节点是流水线的入口节点，特征是不被任何其他节点通过
     * {@code nextNodeId} 引用。通过排除所有被引用的节点来定位起始节点。
     * </p>
     *
     * @param nodeConfigMap 节点 ID 到节点配置的映射
     * @return 起始节点的 ID，如果没有找到则返回 null
     */
    private String findStartNode(Map<String, NodeConfig> nodeConfigMap) {
        Set<String> referencedNodes = nodeConfigMap.values().stream()
                .map(NodeConfig::getNextNodeId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());

        return nodeConfigMap.keySet().stream()
                .filter(nodeId -> !referencedNodes.contains(nodeId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 链式执行节点
     * <p>
     * 从起始节点开始，通过 {@code nextNodeId} 依次驱动后续节点执行。
     * 执行过程中会对以下情况进行处理：
     * <ul>
     *   <li>节点执行失败：标记上下文状态为 FAILED，记录错误信息并终止执行</li>
     *   <li>节点返回终止信号（shouldContinue=false）：正常终止流水线执行</li>
     *   <li>超过最大节点数：触发防死循环保护机制</li>
     * </ul>
     * </p>
     *
     * @param nodeId         起始节点 ID
     * @param nodeConfigMap  节点 ID 到节点配置的映射
     * @param context        摄入上下文，用于在节点间传递数据
     */
    private void executeChain(
            String nodeId,
            Map<String, NodeConfig> nodeConfigMap,
            IngestionContext context) {

        String currentNodeId = nodeId;
        int executedCount = 0;
        final int maxNodes = nodeConfigMap.size();

        while (currentNodeId != null) {
            NodeConfig config = nodeConfigMap.get(currentNodeId);
            if (config == null) {
                log.warn("未找到节点配置: {}", currentNodeId);
                break;
            }

            // 防死循环保护：最多执行节点总数次
            if (executedCount++ >= maxNodes) {
                throw new ClientException("执行节点数超过上限，可能存在死循环");
            }

            log.info("开始执行节点: {}", currentNodeId);
            NodeResult result = executeNode(context, config);

            if (!result.isSuccess()) {
                context.setStatus(IngestionStatus.FAILED);
                context.setError(result.getError());
                log.error("节点 {} 执行失败: {}", currentNodeId, result.getMessage());

                break;
            }

            if (!result.isShouldContinue()) {
                log.info("流水线在节点 {} 停止", currentNodeId);
                break;
            }

            // 移动到下一个节点
            currentNodeId = config.getNextNodeId();
        }

        log.info("流水线执行完成，共执行 {} 个节点", executedCount);
    }

    /**
     * 执行单个节点
     * <p>
     * 执行单个处理节点的核心逻辑，包括：
     * <ol>
     *   <li>根据节点类型从注册表中查找对应的节点处理实现</li>
     *   <li>检查节点条件表达式，条件不满足时跳过该节点</li>
     *   <li>执行节点的处理逻辑并记录执行耗时</li>
     *   <li>记录节点执行日志（包含耗时、状态、输出、错误信息等）</li>
     *   <li>异常捕获与处理，确保异常不会中断引擎运行</li>
     * </ol>
     * </p>
     *
     * @param context   摄入上下文
     * @param nodeConfig 当前节点的配置信息
     * @return 节点执行结果
     */
    private NodeResult executeNode(IngestionContext context, NodeConfig nodeConfig) {
        String nodeType = nodeConfig.getNodeType();
        String nodeId = nodeConfig.getNodeId();

        IngestionNode node = nodeMap.get(nodeType);
        if (node == null) {
            return NodeResult.fail(new IllegalStateException("未找到节点类型: " + nodeType));
        }

        // 条件检查：如果配置了条件表达式且评估结果为 false，则跳过该节点
        if (nodeConfig.getCondition() != null && !nodeConfig.getCondition().isNull()) {
            if (!conditionEvaluator.evaluate(context, nodeConfig.getCondition())) {
                NodeResult skip = NodeResult.skip("条件未满足");
                context.getLogs().add(NodeLog.builder()
                        .nodeId(nodeId)
                        .nodeType(nodeType)
                        .message(skip.getMessage())
                        .durationMs(0)
                        .success(true)
                        .output(outputExtractor.extract(context, nodeConfig))
                        .build());
                return skip;
            }
        }

        // 执行节点并记录耗时
        long start = System.currentTimeMillis();
        try {
            NodeResult result = node.execute(context, nodeConfig);
            long duration = System.currentTimeMillis() - start;

            context.getLogs().add(NodeLog.builder()
                    .nodeId(nodeId)
                    .nodeType(nodeType)
                    .message(result.getMessage())
                    .durationMs(duration)
                    .success(result.isSuccess())
                    .error(result.getError() == null ? null : result.getError().getMessage())
                    .output(outputExtractor.extract(context, nodeConfig))
                    .build());

            log.info("节点 {} 执行完成，耗时 {}ms: {}", nodeId, duration, result.getMessage());
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            context.getLogs().add(NodeLog.builder()
                    .nodeId(nodeId)
                    .nodeType(nodeType)
                    .message(e.getMessage())
                    .durationMs(duration)
                    .success(false)
                    .error(e.getMessage())
                    .output(outputExtractor.extract(context, nodeConfig))
                    .build());
            log.error("节点 {} 执行失败，耗时 {}ms", nodeId, duration, e);
            return NodeResult.fail(e);
        }
    }
}
