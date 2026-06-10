package com.byteq.ai.ragstudio.knowledge.schedule;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * Cron 表达式工具类
 * <p>
 * 提供基于 Spring CronExpression 的定时计算辅助功能，
 * 包括计算下次执行时间和判断执行间隔是否小于指定阈值。
 * 用于文档定时同步任务的执行时间计算和频率校验。
 * </p>
 */
public final class CronScheduleHelper {

    private CronScheduleHelper() {
    }

    /**
     * 计算指定时间之后的下次 Cron 执行时间
     *
     * @param cron Cron 表达式
     * @param from 起始时间
     * @return 下次执行时间，如果表达式无效或无法计算出有效时间则返回 null
     */
    public static Date nextRunTime(String cron, Date from) {
        if (!StringUtils.hasText(cron) || from == null) {
            return null;
        }
        CronExpression expression = CronExpression.parse(cron.trim());
        LocalDateTime fromTime = LocalDateTime.ofInstant(from.toInstant(), ZoneId.systemDefault());
        LocalDateTime next = expression.next(fromTime);
        if (next == null) {
            return null;
        }
        return Date.from(next.atZone(ZoneId.systemDefault()).toInstant());
    }

    /**
     * 判断 Cron 表达式的执行间隔是否小于指定最小秒数
     * <p>通过计算连续两次执行时间之间的间隔来判断，用于防止用户设置过短的执行频率。</p>
     *
     * @param cron       Cron 表达式
     * @param from       起始参考时间
     * @param minSeconds 最小间隔秒数
     * @return 如果执行间隔小于最小秒数或无法计算则返回 true
     */
    public static boolean isIntervalLessThan(String cron, Date from, long minSeconds) {
        if (!StringUtils.hasText(cron) || from == null) {
            return true;
        }
        CronExpression expression = CronExpression.parse(cron.trim());
        LocalDateTime fromTime = LocalDateTime.ofInstant(from.toInstant(), ZoneId.systemDefault());
        LocalDateTime first = expression.next(fromTime);
        if (first == null) {
            return true;
        }
        LocalDateTime second = expression.next(first);
        if (second == null) {
            return true;
        }
        long diffSeconds = Duration.between(first, second).getSeconds();
        return diffSeconds < minSeconds;
    }
}
