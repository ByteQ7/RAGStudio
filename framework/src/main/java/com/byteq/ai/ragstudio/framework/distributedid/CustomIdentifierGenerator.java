package com.byteq.ai.ragstudio.framework.distributedid;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import org.springframework.stereotype.Component;

/**
 * 自定义 ID 生成器
 *
 * <p>基于 Hutool 的 Snowflake（雪花算法）实现，替换 MyBatis-Plus 默认的分布式 ID 生成策略。
 * 在分布式环境下，可生成全局唯一、趋势递增的数值型 ID，适用于数据库主键场景。</p>
 *
 * <p>优势：</p>
 * <ul>
 *   <li>ID 为数值类型（Long），数据库索引效率高</li>
 *   <li>ID 趋势递增，有利于数据库 B+ 树索引的写入性能</li>
 *   <li>无需中心化节点，性能高，适合高并发场景</li>
 *   <li>通过 Redis 统一分配 WorkerId 和 DataCenterId，避免冲突</li>
 * </ul>
 *
 * @see SnowflakeIdInitializer
 */
@Component
public class CustomIdentifierGenerator implements IdentifierGenerator {

    /**
     * 生成下一个分布式 ID（Long 类型）
     * <p>调用 Hutool 的 {@link cn.hutool.core.lang.Snowflake#nextId()} 方法生成 ID。
     * 雪花算法生成的 ID 由 1 位符号位 + 41 位时间戳 + 10 位工作机器 ID + 12 位序列号组成。</p>
     *
     * @param entity MyBatis-Plus 实体对象，当前实现中未使用该参数
     * @return 全局唯一的 Long 型 ID
     */
    @Override
    public Number nextId(Object entity) {
        return IdUtil.getSnowflakeNextId();
    }

    /**
     * 生成下一个分布式 ID（字符串类型）
     * <p>将 Long 型的雪花 ID 转换为字符串返回，适用于需要字符串主键的场景。</p>
     *
     * @param entity MyBatis-Plus 实体对象，当前实现中未使用该参数
     * @return 全局唯一的字符串型 ID
     */
    @Override
    public String nextUUID(Object entity) {
        return IdUtil.getSnowflakeNextIdStr();
    }
}
