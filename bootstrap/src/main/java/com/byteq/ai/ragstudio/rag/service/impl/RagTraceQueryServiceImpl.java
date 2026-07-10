package com.byteq.ai.ragstudio.rag.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.byteq.ai.ragstudio.rag.controller.request.RagTraceRunPageRequest;
import com.byteq.ai.ragstudio.rag.controller.vo.RagTraceDetailVO;
import com.byteq.ai.ragstudio.rag.controller.vo.RagTraceNodeVO;
import com.byteq.ai.ragstudio.rag.controller.vo.RagTraceRunStatsVO;
import com.byteq.ai.ragstudio.rag.controller.vo.RagTraceRunVO;
import com.byteq.ai.ragstudio.rag.dao.entity.RagTraceNodeDO;
import com.byteq.ai.ragstudio.rag.dao.entity.RagTraceRunDO;
import com.byteq.ai.ragstudio.rag.dao.mapper.RagTraceNodeMapper;
import com.byteq.ai.ragstudio.rag.dao.mapper.RagTraceRunMapper;
import com.byteq.ai.ragstudio.rag.service.RagTraceQueryService;
import com.byteq.ai.ragstudio.user.dao.entity.UserDO;
import com.byteq.ai.ragstudio.user.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RAG Trace 查询服务实现
 */
@Service
@RequiredArgsConstructor
public class RagTraceQueryServiceImpl implements RagTraceQueryService {

    private final RagTraceRunMapper runMapper;
    private final RagTraceNodeMapper nodeMapper;
    private final UserMapper userMapper;

    // 分页查询链路运行记录:
    // 1. 根据过滤条件构建查询（traceId、conversationId、taskId、status）
    // 2. 分页查询并加载用户名映射
    // 3. 将 DO 转换为 VO 返回
    @Override
    public IPage<RagTraceRunVO> pageRuns(RagTraceRunPageRequest request) {
        LambdaQueryWrapper<RagTraceRunDO> wrapper = Wrappers.lambdaQuery(RagTraceRunDO.class)
                .orderByDesc(RagTraceRunDO::getStartTime);

        if (StrUtil.isNotBlank(request.getTraceId())) {
            wrapper.eq(RagTraceRunDO::getTraceId, request.getTraceId());
        }
        if (StrUtil.isNotBlank(request.getConversationId())) {
            wrapper.eq(RagTraceRunDO::getConversationId, request.getConversationId());
        }
        if (StrUtil.isNotBlank(request.getTaskId())) {
            wrapper.eq(RagTraceRunDO::getTaskId, request.getTaskId());
        }
        if (StrUtil.isNotBlank(request.getStatus())) {
            wrapper.eq(RagTraceRunDO::getStatus, request.getStatus());
        }

        IPage<RagTraceRunDO> pageResult = runMapper.selectPage(request, wrapper);
        Map<String, String> usernameMap = loadUsernameMap(pageResult.getRecords());
        return pageResult.convert(run -> toRunVO(run, usernameMap));
    }

    // 查询链路详情，包含运行记录和所有子节点信息
    @Override
    public RagTraceDetailVO detail(String traceId) {
        RagTraceRunDO run = runMapper.selectOne(Wrappers.lambdaQuery(RagTraceRunDO.class)
                .eq(RagTraceRunDO::getTraceId, traceId)
                .last("limit 1"));
        if (run == null) {
            return null;
        }
        Map<String, String> usernameMap = loadUsernameMap(List.of(run));
        return RagTraceDetailVO.builder()
                .run(toRunVO(run, usernameMap))
                .nodes(listNodes(traceId))
                .build();
    }

    // 查询指定链路的所有节点，按开始时间和 ID 升序排列
    @Override
    public List<RagTraceNodeVO> listNodes(String traceId) {
        List<RagTraceNodeDO> nodes = nodeMapper.selectList(Wrappers.lambdaQuery(RagTraceNodeDO.class)
                .eq(RagTraceNodeDO::getTraceId, traceId)
                .orderByAsc(RagTraceNodeDO::getStartTime)
                .orderByAsc(RagTraceNodeDO::getId));
        return nodes.stream().map(this::toNodeVO).toList();
    }

    // 将链路运行记录 DO 转换为 VO，填充用户名
    private RagTraceRunVO toRunVO(RagTraceRunDO run, Map<String, String> usernameMap) {
        String username = resolveUsername(run.getUserId(), usernameMap);
        return RagTraceRunVO.builder()
                .traceId(run.getTraceId())
                .traceName(run.getTraceName())
                .entryMethod(run.getEntryMethod())
                .conversationId(run.getConversationId())
                .taskId(run.getTaskId())
                .userId(run.getUserId())
                .username(username)
                .status(run.getStatus())
                .errorMessage(run.getErrorMessage())
                .durationMs(run.getDurationMs())
                .startTime(run.getStartTime())
                .endTime(run.getEndTime())
                .build();
    }

    // 批量加载运行记录中涉及的用户 ID 到用户名的映射
    private Map<String, String> loadUsernameMap(List<RagTraceRunDO> runs) {
        if (runs == null || runs.isEmpty()) {
            return Collections.emptyMap();
        }

        Set<String> userIds = runs.stream()
                .map(RagTraceRunDO::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<UserDO> users = userMapper.selectList(Wrappers.lambdaQuery(UserDO.class)
                .in(UserDO::getId, userIds)
                .select(UserDO::getId, UserDO::getUsername));
        if (users == null || users.isEmpty()) {
            return Collections.emptyMap();
        }

        return users.stream().collect(Collectors.toMap(
                user -> String.valueOf(user.getId()),
                UserDO::getUsername,
                (left, right) -> left
        ));
    }

    // 从用户名映射中查找指定用户 ID 对应的用户名
    private String resolveUsername(String userId, Map<String, String> usernameMap) {
        if (StrUtil.isBlank(userId) || usernameMap == null || usernameMap.isEmpty()) {
            return null;
        }
        return usernameMap.get(userId);
    }

    // 将链路节点 DO 转换为 VO
    private RagTraceNodeVO toNodeVO(RagTraceNodeDO node) {
        return RagTraceNodeVO.builder()
                .traceId(node.getTraceId())
                .nodeId(node.getNodeId())
                .parentNodeId(node.getParentNodeId())
                .depth(node.getDepth())
                .nodeType(node.getNodeType())
                .nodeName(node.getNodeName())
                .className(node.getClassName())
                .methodName(node.getMethodName())
                .status(node.getStatus())
                .errorMessage(node.getErrorMessage())
                .durationMs(node.getDurationMs())
                .startTime(node.getStartTime())
                .endTime(node.getEndTime())
                .build();
    }

    // 查询链路运行全量统计（含 P95、平均耗时等），不参与分页
    @Override
    public RagTraceRunStatsVO stats(RagTraceRunPageRequest request) {
        // 构建过滤条件（与 pageRuns 一致，但不分页、不排序）
        LambdaQueryWrapper<RagTraceRunDO> wrapper = Wrappers.lambdaQuery(RagTraceRunDO.class);

        if (StrUtil.isNotBlank(request.getTraceId())) {
            wrapper.eq(RagTraceRunDO::getTraceId, request.getTraceId());
        }
        if (StrUtil.isNotBlank(request.getConversationId())) {
            wrapper.eq(RagTraceRunDO::getConversationId, request.getConversationId());
        }
        if (StrUtil.isNotBlank(request.getTaskId())) {
            wrapper.eq(RagTraceRunDO::getTaskId, request.getTaskId());
        }
        if (StrUtil.isNotBlank(request.getStatus())) {
            wrapper.eq(RagTraceRunDO::getStatus, request.getStatus());
        }

        // 查询所有符合条件的记录（只取状态和耗时字段）
        wrapper.select(RagTraceRunDO::getStatus, RagTraceRunDO::getDurationMs);
        List<RagTraceRunDO> allRuns = runMapper.selectList(wrapper);

        long totalCount = allRuns.size();
        long successCount = allRuns.stream()
                .filter(r -> "SUCCESS".equalsIgnoreCase(r.getStatus()))
                .count();
        long failCount = allRuns.stream()
                .filter(r -> "ERROR".equalsIgnoreCase(r.getStatus()))
                .count();
        double successRate = totalCount > 0 ? Math.round((double) successCount / totalCount * 1000.0) / 10.0 : 0.0;

        // 提取成功记录的耗时
        List<Long> durations = allRuns.stream()
                .filter(r -> "SUCCESS".equalsIgnoreCase(r.getStatus()))
                .map(RagTraceRunDO::getDurationMs)
                .filter(Objects::nonNull)
                .filter(d -> d > 0)
                .sorted()
                .toList();

        long avgDurationMs = durations.isEmpty() ? 0L : (long) durations.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);

        long p95DurationMs = 0L;
        if (!durations.isEmpty()) {
            int index = (int) Math.ceil(durations.size() * 0.95) - 1;
            index = Math.max(0, Math.min(index, durations.size() - 1));
            p95DurationMs = durations.get(index);
        }

        return RagTraceRunStatsVO.builder()
                .totalCount(totalCount)
                .successCount(successCount)
                .failCount(failCount)
                .successRate(successRate)
                .avgDurationMs(avgDurationMs)
                .p95DurationMs(p95DurationMs)
                .build();
    }
}
