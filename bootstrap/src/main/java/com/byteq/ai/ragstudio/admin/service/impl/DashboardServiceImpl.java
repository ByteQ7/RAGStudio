package com.byteq.ai.ragstudio.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.byteq.ai.ragstudio.admin.controller.vo.DashboardOverviewGroupVO;
import com.byteq.ai.ragstudio.admin.controller.vo.DashboardOverviewKpiVO;
import com.byteq.ai.ragstudio.admin.controller.vo.DashboardOverviewVO;
import com.byteq.ai.ragstudio.admin.controller.vo.DashboardPerformanceVO;
import com.byteq.ai.ragstudio.admin.controller.vo.DashboardTrendPointVO;
import com.byteq.ai.ragstudio.admin.controller.vo.DashboardTrendSeriesVO;
import com.byteq.ai.ragstudio.admin.controller.vo.DashboardTrendsVO;
import com.byteq.ai.ragstudio.admin.service.DashboardService;
import com.byteq.ai.ragstudio.rag.dao.entity.ConversationDO;
import com.byteq.ai.ragstudio.rag.dao.entity.ConversationMessageDO;
import com.byteq.ai.ragstudio.rag.dao.entity.RagTraceRunDO;
import com.byteq.ai.ragstudio.rag.dao.mapper.ConversationMapper;
import com.byteq.ai.ragstudio.rag.dao.mapper.ConversationMessageMapper;
import com.byteq.ai.ragstudio.rag.dao.mapper.RagTraceRunMapper;
import com.byteq.ai.ragstudio.user.dao.entity.UserDO;
import com.byteq.ai.ragstudio.user.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 仪表盘服务实现类
 * <p>
 * 基于 MyBatis-Plus 和数据库统计查询，实现仪表盘各项数据的加载。
 * 通过多维度聚合查询（按天/按小时）、时间窗口对比和百分位计算，
 * 提供系统运行状况的全面可视化数据支持。
 * </p>
 *
 * <p>
 * 核心能力：
 * <ul>
 *   <li>KPI 总览：用户数、会话数、消息数的总量和时段对比</li>
 *   <li>性能分析：平均延迟、P95 延迟、成功率、错误率等</li>
 *   <li>趋势分析：按小时或按天聚合的时间序列数据</li>
 * </ul>
 * </p>
 */
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_ERROR = "ERROR";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String NO_DOC_REPLY = "未检索到与问题相关的文档内容。";
    private static final String GRANULARITY_DAY = "day";
    private static final String GRANULARITY_HOUR = "hour";
    private static final long SLOW_LATENCY_THRESHOLD_MS = 20000L;
    private static final DateTimeFormatter HOUR_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserMapper userMapper;
    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final RagTraceRunMapper traceRunMapper;

    @Override
    public DashboardOverviewVO loadOverview(String window) {
        WindowRange range = resolveWindowRange(window, Duration.ofHours(24));

        long totalUsers = userMapper.selectCount(Wrappers.lambdaQuery(UserDO.class));
        long usersInWindow = countUsers(range.start, range.end);

        long totalSessions = conversationMapper.selectCount(Wrappers.lambdaQuery(ConversationDO.class));
        long sessionsInWindow = countConversations(range.start, range.end);
        long sessionsPrevWindow = countConversations(range.prevStart, range.prevEnd);

        long totalMessages = messageMapper.selectCount(Wrappers.lambdaQuery(ConversationMessageDO.class));
        long messagesInWindow = countMessages(range.start, range.end);
        long messagesPrevWindow = countMessages(range.prevStart, range.prevEnd);

        long activeUsers = countActiveUsers(range.start, range.end);
        long activeUsersPrev = countActiveUsers(range.prevStart, range.prevEnd);

        DashboardOverviewGroupVO group = DashboardOverviewGroupVO.builder()
                .totalUsers(buildKpi(totalUsers, usersInWindow, null))
                .activeUsers(buildKpi(activeUsers, activeUsers - activeUsersPrev, calcPct(activeUsers, activeUsersPrev)))
                .totalSessions(buildKpi(totalSessions, sessionsInWindow, null))
                .sessions24h(buildKpi(sessionsInWindow, sessionsInWindow - sessionsPrevWindow, calcPct(sessionsInWindow, sessionsPrevWindow)))
                .totalMessages(buildKpi(totalMessages, messagesInWindow, null))
                .messages24h(buildKpi(messagesInWindow, messagesInWindow - messagesPrevWindow, calcPct(messagesInWindow, messagesPrevWindow)))
                .build();

        return DashboardOverviewVO.builder()
                .window(range.windowLabel)
                .compareWindow(range.compareLabel)
                .updatedAt(System.currentTimeMillis())
                .kpis(group)
                .build();
    }

    @Override
    public DashboardPerformanceVO loadPerformance(String window) {
        WindowRange range = resolveWindowRange(window, Duration.ofHours(24));
        List<Long> durations = listDurations(range.start, range.end);
        long avgLatency = average(durations);
        long p95Latency = percentile(durations);

        long success = countTraceRuns(range.start, range.end, STATUS_SUCCESS);
        long error = countTraceRuns(range.start, range.end, STATUS_ERROR);
        long total = success + error;
        long assistantCount = countAssistantMessages(range.start, range.end);
        long noDocCount = countNoDocMessages(range.start, range.end);
        long slowCount = durations.stream().filter(duration -> duration > SLOW_LATENCY_THRESHOLD_MS).count();

        double successRate = total == 0 ? 0.0 : round1((success * 100.0) / total);
        double errorRate = total == 0 ? 0.0 : round1((error * 100.0) / total);
        double noDocRate = assistantCount == 0 ? 0.0 : round1((noDocCount * 100.0) / assistantCount);
        double slowRate = durations.isEmpty() ? 0.0 : round1((slowCount * 100.0) / durations.size());

        return DashboardPerformanceVO.builder()
                .window(range.windowLabel)
                .avgLatencyMs(avgLatency)
                .p95LatencyMs(p95Latency)
                .successRate(successRate)
                .errorRate(errorRate)
                .noDocRate(noDocRate)
                .slowRate(slowRate)
                .build();
    }

    @Override
    public DashboardTrendsVO loadTrends(String metric, String window, String granularity) {
        String normalizedMetric = metric == null ? "" : metric.trim().toLowerCase();
        Duration windowDuration = parseWindow(window, Duration.ofDays(7));
        WindowRange range = resolveWindowRange(window, Duration.ofDays(7));
        String resolvedGranularity = resolveTrendGranularity(granularity, windowDuration);
        ZoneId zoneId = ZoneId.systemDefault();
        List<DashboardTrendSeriesVO> series = new ArrayList<>();

        if (GRANULARITY_HOUR.equals(resolvedGranularity)) {
            LocalDateTime endHourExclusive = toLocalDateTime(range.end, zoneId)
                    .truncatedTo(ChronoUnit.HOURS)
                    .plusHours(1);
            LocalDateTime startHour = endHourExclusive.minusHours(Math.max(1, windowDuration.toHours()));

            if ("sessions".equals(normalizedMetric)) {
                Map<LocalDateTime, Long> counts = countConversationsByHour(startHour, endHourExclusive, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("会话数")
                        .data(buildPointsByHour(startHour, endHourExclusive, zoneId, counts))
                        .build());
            } else if ("messages".equals(normalizedMetric)) {
                Map<LocalDateTime, Long> counts = countMessagesByHour(startHour, endHourExclusive, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("消息数")
                        .data(buildPointsByHour(startHour, endHourExclusive, zoneId, counts))
                        .build());
            } else if ("activeusers".equals(normalizedMetric)) {
                Map<LocalDateTime, Long> counts = countActiveUsersByHour(startHour, endHourExclusive, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("活跃用户")
                        .data(buildPointsByHour(startHour, endHourExclusive, zoneId, counts))
                        .build());
            } else if ("avglatency".equals(normalizedMetric)) {
                Map<LocalDateTime, Double> averages = averageLatencyByHour(startHour, endHourExclusive, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("平均响应时间")
                        .data(buildPointsDoubleByHour(startHour, endHourExclusive, zoneId, averages))
                        .build());
            } else if ("quality".equals(normalizedMetric)) {
                Map<LocalDateTime, Long> successMap = countTraceRunsByHour(startHour, endHourExclusive, zoneId, STATUS_SUCCESS);
                Map<LocalDateTime, Long> errorMap = countTraceRunsByHour(startHour, endHourExclusive, zoneId, STATUS_ERROR);
                Map<LocalDateTime, Long> assistantCountMap = countAssistantMessagesByHour(startHour, endHourExclusive, zoneId);
                Map<LocalDateTime, Long> noDocCountMap = countNoDocMessagesByHour(startHour, endHourExclusive, zoneId);
                Map<LocalDateTime, Double> errorRate = new HashMap<>();
                Map<LocalDateTime, Double> noDocRate = new HashMap<>();
                for (LocalDateTime hour = startHour; hour.isBefore(endHourExclusive); hour = hour.plusHours(1)) {
                    long total = successMap.getOrDefault(hour, 0L) + errorMap.getOrDefault(hour, 0L);
                    long assistantCount = assistantCountMap.getOrDefault(hour, 0L);
                    long error = errorMap.getOrDefault(hour, 0L);
                    long noDocCount = noDocCountMap.getOrDefault(hour, 0L);
                    double err = total == 0 ? 0.0 : round1((error * 100.0) / total);
                    double noDoc = assistantCount == 0 ? 0.0 : round1((noDocCount * 100.0) / assistantCount);
                    errorRate.put(hour, err);
                    noDocRate.put(hour, noDoc);
                }
                series.add(DashboardTrendSeriesVO.builder()
                        .name("错误率")
                        .data(buildPointsDoubleByHour(startHour, endHourExclusive, zoneId, errorRate))
                        .build());
                series.add(DashboardTrendSeriesVO.builder()
                        .name("无知识率")
                        .data(buildPointsDoubleByHour(startHour, endHourExclusive, zoneId, noDocRate))
                        .build());
            }
        } else {
            LocalDate startDay = toLocalDate(range.start, zoneId);
            LocalDate endExclusiveDay = toLocalDate(range.end, zoneId).plusDays(1);

            if ("sessions".equals(normalizedMetric)) {
                Map<LocalDate, Long> counts = countConversationsByDay(startDay, endExclusiveDay, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("会话数")
                        .data(buildPoints(startDay, endExclusiveDay, zoneId, counts))
                        .build());
            } else if ("messages".equals(normalizedMetric)) {
                Map<LocalDate, Long> counts = countMessagesByDay(startDay, endExclusiveDay, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("消息数")
                        .data(buildPoints(startDay, endExclusiveDay, zoneId, counts))
                        .build());
            } else if ("activeusers".equals(normalizedMetric)) {
                Map<LocalDate, Long> counts = countActiveUsersByDay(startDay, endExclusiveDay, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("活跃用户")
                        .data(buildPoints(startDay, endExclusiveDay, zoneId, counts))
                        .build());
            } else if ("avglatency".equals(normalizedMetric)) {
                Map<LocalDate, Double> averages = averageLatencyByDay(startDay, endExclusiveDay, zoneId);
                series.add(DashboardTrendSeriesVO.builder()
                        .name("平均响应时间")
                        .data(buildPointsDouble(startDay, endExclusiveDay, zoneId, averages))
                        .build());
            } else if ("quality".equals(normalizedMetric)) {
                Map<LocalDate, Long> successMap = countTraceRunsByDay(startDay, endExclusiveDay, zoneId, STATUS_SUCCESS);
                Map<LocalDate, Long> errorMap = countTraceRunsByDay(startDay, endExclusiveDay, zoneId, STATUS_ERROR);
                Map<LocalDate, Long> assistantCountMap = countAssistantMessagesByDay(startDay, endExclusiveDay, zoneId);
                Map<LocalDate, Long> noDocCountMap = countNoDocMessagesByDay(startDay, endExclusiveDay, zoneId);
                Map<LocalDate, Double> errorRate = new HashMap<>();
                Map<LocalDate, Double> noDocRate = new HashMap<>();
                for (LocalDate day = startDay; day.isBefore(endExclusiveDay); day = day.plusDays(1)) {
                    long total = successMap.getOrDefault(day, 0L) + errorMap.getOrDefault(day, 0L);
                    long assistantCount = assistantCountMap.getOrDefault(day, 0L);
                    long error = errorMap.getOrDefault(day, 0L);
                    long noDocCount = noDocCountMap.getOrDefault(day, 0L);
                    double err = total == 0 ? 0.0 : round1((error * 100.0) / total);
                    double noDoc = assistantCount == 0 ? 0.0 : round1((noDocCount * 100.0) / assistantCount);
                    errorRate.put(day, err);
                    noDocRate.put(day, noDoc);
                }
                series.add(DashboardTrendSeriesVO.builder()
                        .name("错误率")
                        .data(buildPointsDouble(startDay, endExclusiveDay, zoneId, errorRate))
                        .build());
                series.add(DashboardTrendSeriesVO.builder()
                        .name("无知识率")
                        .data(buildPointsDouble(startDay, endExclusiveDay, zoneId, noDocRate))
                        .build());
            }
        }

        return DashboardTrendsVO.builder()
                .metric(metric)
                .window(range.windowLabel)
                .granularity(resolvedGranularity)
                .series(series)
                .build();
    }

    // 统计指定时间范围内新注册的用户数量
    private long countUsers(Date start, Date end) {
        return userMapper.selectCount(Wrappers.lambdaQuery(UserDO.class)
                .ge(UserDO::getCreateTime, start)
                .lt(UserDO::getCreateTime, end));
    }

    // 统计指定时间范围内创建的会话数量
    private long countConversations(Date start, Date end) {
        return conversationMapper.selectCount(Wrappers.lambdaQuery(ConversationDO.class)
                .ge(ConversationDO::getCreateTime, start)
                .lt(ConversationDO::getCreateTime, end));
    }

    // 统计指定时间范围内创建的消息数量
    private long countMessages(Date start, Date end) {
        return messageMapper.selectCount(Wrappers.lambdaQuery(ConversationMessageDO.class)
                .ge(ConversationMessageDO::getCreateTime, start)
                .lt(ConversationMessageDO::getCreateTime, end));
    }

    // 统计指定时间范围内有消息活动的去重用户数
    private long countActiveUsers(Date start, Date end) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("count(distinct user_id) as cnt")
                .ge("create_time", start)
                .lt("create_time", end);
        return extractCount(messageMapper.selectMaps(wrapper));
    }

    // 统计指定时间范围内指定状态的 RAG 追踪运行次数
    private long countTraceRuns(Date start, Date end, String status) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.ge("start_time", start).lt("start_time", end);
        if (status != null) {
            wrapper.eq("status", status);
        }
        return traceRunMapper.selectCount(wrapper);
    }

    // 统计指定时间范围内 assistant 角色的消息数量
    private long countAssistantMessages(Date start, Date end) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.ge("create_time", start)
                .lt("create_time", end)
                .eq("role", ROLE_ASSISTANT);
        return messageMapper.selectCount(wrapper);
    }

    // 统计指定时间范围内无文档命中回复的消息数量
    private long countNoDocMessages(Date start, Date end) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.ge("create_time", start)
                .lt("create_time", end)
                .eq("role", ROLE_ASSISTANT)
                .eq("content", NO_DOC_REPLY);
        return messageMapper.selectCount(wrapper);
    }

    // 获取指定时间范围内所有成功运行的耗时列表（毫秒），过滤掉无效值
    private List<Long> listDurations(Date start, Date end) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("duration_ms")
                .ge("start_time", start)
                .lt("start_time", end)
                .eq("status", STATUS_SUCCESS);
        List<Object> results = traceRunMapper.selectObjs(wrapper);
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> durations = new ArrayList<>();
        for (Object value : results) {
            if (value instanceof Number number) {
                long duration = number.longValue();
                if (duration > 0) {
                    durations.add(duration);
                }
            }
        }
        return durations;
    }

    // 从 selectMaps 查询结果中提取 count 聚合值
    private long extractCount(List<Map<String, Object>> maps) {
        if (maps == null || maps.isEmpty()) {
            return 0L;
        }
        Map<String, Object> firstMap = maps.get(0);
        if (firstMap == null) {
            return 0L;
        }
        Object value = firstMap.get("cnt");
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    // 计算当前值相对于上一时段的百分比变化
    private Double calcPct(long current, long prev) {
        if (prev <= 0) {
            return null;
        }
        return round1(((current - prev) * 100.0) / prev);
    }

    // 构建单个 KPI 指标对象，包含数值、变化量和变化百分比
    private DashboardOverviewKpiVO buildKpi(long value, long delta, Double deltaPct) {
        return DashboardOverviewKpiVO.builder()
                .value(value)
                .delta(delta)
                .deltaPct(deltaPct)
                .build();
    }

    // 按天聚合统计会话数量
    private Map<LocalDate, Long> countConversationsByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD') as d", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("d");
        return mapLongResults(conversationMapper.selectMaps(wrapper));
    }

    // 按天聚合统计消息数量
    private Map<LocalDate, Long> countMessagesByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD') as d", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("d");
        return mapLongResults(messageMapper.selectMaps(wrapper));
    }

    // 按天聚合统计 assistant 角色的消息数量
    private Map<LocalDate, Long> countAssistantMessagesByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD') as d", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .eq("role", ROLE_ASSISTANT)
                .groupBy("d");
        return mapLongResults(messageMapper.selectMaps(wrapper));
    }

    // 按天聚合统计无文档命中回复的消息数量
    private Map<LocalDate, Long> countNoDocMessagesByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD') as d", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .eq("role", ROLE_ASSISTANT)
                .eq("content", NO_DOC_REPLY)
                .groupBy("d");
        return mapLongResults(messageMapper.selectMaps(wrapper));
    }

    // 按天聚合统计活跃用户数（去重）
    private Map<LocalDate, Long> countActiveUsersByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD') as d", "count(distinct user_id) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("d");
        return mapLongResults(messageMapper.selectMaps(wrapper));
    }

    // 按天聚合计算成功运行的平均延迟（毫秒）
    private Map<LocalDate, Double> averageLatencyByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(start_time,'YYYY-MM-DD') as d", "avg(duration_ms) as avg")
                .ge("start_time", toDate(start, zoneId))
                .lt("start_time", toDate(endExclusive, zoneId))
                .eq("status", STATUS_SUCCESS)
                .groupBy("d");
        List<Map<String, Object>> maps = traceRunMapper.selectMaps(wrapper);
        Map<LocalDate, Double> result = new HashMap<>();
        if (maps == null) {
            return result;
        }
        for (Map<String, Object> row : maps) {
            LocalDate date = parseLocalDate(row.get("d"));
            if (date == null) {
                continue;
            }
            Object value = row.get("avg");
            double avg = value instanceof Number number ? number.doubleValue() : 0.0;
            result.put(date, round1(avg));
        }
        return result;
    }

    // 按天聚合统计指定状态的追踪运行次数
    private Map<LocalDate, Long> countTraceRunsByDay(LocalDate start, LocalDate endExclusive, ZoneId zoneId, String status) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(start_time,'YYYY-MM-DD') as d", "count(*) as cnt")
                .ge("start_time", toDate(start, zoneId))
                .lt("start_time", toDate(endExclusive, zoneId));
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.groupBy("d");
        return mapLongResults(traceRunMapper.selectMaps(wrapper));
    }

    // 按小时聚合统计会话数量
    private Map<LocalDateTime, Long> countConversationsByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD HH24:00:00') as h", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("h");
        return mapLongResultsByHour(conversationMapper.selectMaps(wrapper));
    }

    // 按小时聚合统计消息数量
    private Map<LocalDateTime, Long> countMessagesByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD HH24:00:00') as h", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("h");
        return mapLongResultsByHour(messageMapper.selectMaps(wrapper));
    }

    // 按小时聚合统计 assistant 角色的消息数量
    private Map<LocalDateTime, Long> countAssistantMessagesByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD HH24:00:00') as h", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .eq("role", ROLE_ASSISTANT)
                .groupBy("h");
        return mapLongResultsByHour(messageMapper.selectMaps(wrapper));
    }

    // 按小时聚合统计无文档命中回复的消息数量
    private Map<LocalDateTime, Long> countNoDocMessagesByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD HH24:00:00') as h", "count(*) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .eq("role", ROLE_ASSISTANT)
                .eq("content", NO_DOC_REPLY)
                .groupBy("h");
        return mapLongResultsByHour(messageMapper.selectMaps(wrapper));
    }

    // 按小时聚合统计活跃用户数（去重）
    private Map<LocalDateTime, Long> countActiveUsersByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        QueryWrapper<ConversationMessageDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(create_time,'YYYY-MM-DD HH24:00:00') as h", "count(distinct user_id) as cnt")
                .ge("create_time", toDate(start, zoneId))
                .lt("create_time", toDate(endExclusive, zoneId))
                .groupBy("h");
        return mapLongResultsByHour(messageMapper.selectMaps(wrapper));
    }

    // 按小时聚合计算成功运行的平均延迟（毫秒）
    private Map<LocalDateTime, Double> averageLatencyByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(start_time,'YYYY-MM-DD HH24:00:00') as h", "avg(duration_ms) as avg")
                .ge("start_time", toDate(start, zoneId))
                .lt("start_time", toDate(endExclusive, zoneId))
                .eq("status", STATUS_SUCCESS)
                .groupBy("h");
        return mapDoubleResultsByHour(traceRunMapper.selectMaps(wrapper));
    }

    // 按小时聚合统计指定状态的追踪运行次数
    private Map<LocalDateTime, Long> countTraceRunsByHour(LocalDateTime start, LocalDateTime endExclusive, ZoneId zoneId, String status) {
        QueryWrapper<RagTraceRunDO> wrapper = new QueryWrapper<>();
        wrapper.select("to_char(start_time,'YYYY-MM-DD HH24:00:00') as h", "count(*) as cnt")
                .ge("start_time", toDate(start, zoneId))
                .lt("start_time", toDate(endExclusive, zoneId));
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.groupBy("h");
        return mapLongResultsByHour(traceRunMapper.selectMaps(wrapper));
    }

    // 将按天聚合的 selectMaps 结果转换为 LocalDate -> Long 映射
    private Map<LocalDate, Long> mapLongResults(List<Map<String, Object>> maps) {
        Map<LocalDate, Long> result = new HashMap<>();
        if (maps == null) {
            return result;
        }
        for (Map<String, Object> row : maps) {
            LocalDate date = parseLocalDate(row.get("d"));
            if (date == null) {
                continue;
            }
            Long value = toLongValue(row.get("cnt"));
            if (value != null) {
                result.put(date, value);
            }
        }
        return result;
    }

    // 将按小时聚合的 selectMaps 结果转换为 LocalDateTime -> Long 映射
    private Map<LocalDateTime, Long> mapLongResultsByHour(List<Map<String, Object>> maps) {
        Map<LocalDateTime, Long> result = new HashMap<>();
        if (maps == null) {
            return result;
        }
        for (Map<String, Object> row : maps) {
            LocalDateTime dateTime = parseLocalDateTime(row.get("h"));
            if (dateTime == null) {
                continue;
            }
            Long value = toLongValue(row.get("cnt"));
            if (value != null) {
                result.put(dateTime, value);
            }
        }
        return result;
    }

    // 将按小时聚合的平均值结果转换为 LocalDateTime -> Double 映射
    private Map<LocalDateTime, Double> mapDoubleResultsByHour(List<Map<String, Object>> maps) {
        Map<LocalDateTime, Double> result = new HashMap<>();
        if (maps == null) {
            return result;
        }
        for (Map<String, Object> row : maps) {
            LocalDateTime dateTime = parseLocalDateTime(row.get("h"));
            if (dateTime == null) {
                continue;
            }
            Object value = row.get("avg");
            double avg = value instanceof Number number ? number.doubleValue() : 0.0;
            result.put(dateTime, round1(avg));
        }
        return result;
    }

    // 按天填充生成趋势数据点序列，缺失日期补零
    private List<DashboardTrendPointVO> buildPoints(LocalDate start, LocalDate endExclusive, ZoneId zoneId, Map<LocalDate, Long> values) {
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDate cursor = start;
        while (cursor.isBefore(endExclusive)) {
            long value = values.getOrDefault(cursor, 0L);
            points.add(DashboardTrendPointVO.builder()
                    .ts(toDate(cursor, zoneId).getTime())
                    .value((double) value)
                    .build());
            cursor = cursor.plusDays(1);
        }
        return points;
    }

    // 按天填充生成 Double 类型趋势数据点序列，缺失日期补零
    private List<DashboardTrendPointVO> buildPointsDouble(LocalDate start, LocalDate endExclusive, ZoneId zoneId, Map<LocalDate, Double> values) {
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDate cursor = start;
        while (cursor.isBefore(endExclusive)) {
            double value = values.getOrDefault(cursor, 0.0);
            points.add(DashboardTrendPointVO.builder()
                    .ts(toDate(cursor, zoneId).getTime())
                    .value(value)
                    .build());
            cursor = cursor.plusDays(1);
        }
        return points;
    }

    // 按小时填充生成趋势数据点序列，缺失小时补零
    private List<DashboardTrendPointVO> buildPointsByHour(
            LocalDateTime start,
            LocalDateTime endExclusive,
            ZoneId zoneId,
            Map<LocalDateTime, Long> values) {
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDateTime cursor = start;
        while (cursor.isBefore(endExclusive)) {
            long value = values.getOrDefault(cursor, 0L);
            points.add(DashboardTrendPointVO.builder()
                    .ts(toDate(cursor, zoneId).getTime())
                    .value((double) value)
                    .build());
            cursor = cursor.plusHours(1);
        }
        return points;
    }

    // 按小时填充生成 Double 类型趋势数据点序列，缺失小时补零
    private List<DashboardTrendPointVO> buildPointsDoubleByHour(
            LocalDateTime start,
            LocalDateTime endExclusive,
            ZoneId zoneId,
            Map<LocalDateTime, Double> values) {
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDateTime cursor = start;
        while (cursor.isBefore(endExclusive)) {
            double value = values.getOrDefault(cursor, 0.0);
            points.add(DashboardTrendPointVO.builder()
                    .ts(toDate(cursor, zoneId).getTime())
                    .value(value)
                    .build());
            cursor = cursor.plusHours(1);
        }
        return points;
    }

    // 计算数值列表的平均值，四舍五入到整数
    private long average(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (Long value : values) {
            sum += value;
        }
        return Math.round(sum / (double) values.size());
    }

    // 计算数值列表的 P95 百分位值
    private long percentile(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    // 将数值四舍五入保留一位小数
    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    // 将对象解析为 LocalDate，null 安全
    private LocalDate parseLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        return LocalDate.parse(String.valueOf(value));
    }

    // 将对象解析为 LocalDateTime（按小时格式化），null 安全
    private LocalDateTime parseLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        return LocalDateTime.parse(String.valueOf(value), HOUR_FORMATTER);
    }

    // 将对象安全转换为 Long 值，转换失败返回 null
    private Long toLongValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // 将 LocalDate 转换为指定时区的 Date（当天零点）
    private Date toDate(LocalDate date, ZoneId zoneId) {
        return Date.from(date.atStartOfDay(zoneId).toInstant());
    }

    // 将 LocalDateTime 转换为指定时区的 Date
    private Date toDate(LocalDateTime time, ZoneId zoneId) {
        return Date.from(time.atZone(zoneId).toInstant());
    }

    // 将 Date 转换为指定时区的 LocalDate
    private LocalDate toLocalDate(Date date, ZoneId zoneId) {
        return date.toInstant().atZone(zoneId).toLocalDate();
    }

    // 将 Date 转换为指定时区的 LocalDateTime
    private LocalDateTime toLocalDateTime(Date date, ZoneId zoneId) {
        return date.toInstant().atZone(zoneId).toLocalDateTime();
    }

    // 解析时间窗口字符串，计算当前窗口和上一窗口的起止时间
    private WindowRange resolveWindowRange(String window, Duration fallback) {
        Duration duration = parseWindow(window, fallback);
        Instant now = Instant.now();
        Instant start = now.minus(duration);
        Instant prevStart = start.minus(duration);
        return new WindowRange(Date.from(start), Date.from(now), Date.from(prevStart), Date.from(start),
                window == null ? formatDuration(fallback) : window, "prev_" + (window == null ? formatDuration(fallback) : window));
    }

    // 解析时间窗口字符串（如 "24h"、"7d"）为 Duration，解析失败使用默认值
    private Duration parseWindow(String window, Duration fallback) {
        if (window == null || window.isBlank()) {
            return fallback;
        }
        String normalized = window.trim().toLowerCase();
        if (normalized.endsWith("h")) {
            long hours = parseNumber(normalized.substring(0, normalized.length() - 1), fallback.toHours());
            return Duration.ofHours(hours);
        }
        if (normalized.endsWith("d")) {
            long days = parseNumber(normalized.substring(0, normalized.length() - 1), fallback.toDays());
            return Duration.ofDays(days);
        }
        return fallback;
    }

    // 确定趋势数据的聚合粒度，窗口 <=48 小时用小时粒度，否则用天粒度
    private String resolveTrendGranularity(String granularity, Duration windowDuration) {
        if (granularity != null && !granularity.isBlank()) {
            String normalized = granularity.trim().toLowerCase();
            if (GRANULARITY_HOUR.equals(normalized) || GRANULARITY_DAY.equals(normalized)) {
                return normalized;
            }
        }
        return windowDuration.toHours() <= 48 ? GRANULARITY_HOUR : GRANULARITY_DAY;
    }

    // 安全解析字符串为 long 数值，解析失败返回默认值
    private long parseNumber(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    // 将 Duration 格式化为可读字符串（如 "24h" 或 "7d"）
    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        if (hours % 24 == 0) {
            return (hours / 24) + "d";
        }
        return hours + "h";
    }

    // 时间窗口范围数据，包含当前窗口和对比窗口的起止时间及标签
    private static class WindowRange {
        private final Date start;
        private final Date end;
        private final Date prevStart;
        private final Date prevEnd;
        private final String windowLabel;
        private final String compareLabel;

        WindowRange(Date start, Date end, Date prevStart, Date prevEnd, String windowLabel, String compareLabel) {
            this.start = start;
            this.end = end;
            this.prevStart = prevStart;
            this.prevEnd = prevEnd;
            this.windowLabel = windowLabel;
            this.compareLabel = compareLabel;
        }
    }
}
