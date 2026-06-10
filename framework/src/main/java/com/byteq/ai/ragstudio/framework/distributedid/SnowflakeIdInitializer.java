package com.byteq.ai.ragstudio.framework.distributedid;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Singleton;
import cn.hutool.core.lang.Snowflake;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 分布式 Snowflake（雪花算法）初始化器
 *
 * <p>通过执行 Lua 脚本从 Redis 中获取全局唯一的 WorkerId 和 DataCenterId，
 * 然后使用这两个 ID 创建 Hutool 的 {@link Snowflake} 实例并注册到全局单例管理中。
 * 后续通过 {@link CustomIdentifierGenerator}
 * 生成的 ID 将使用此处注册的雪花算法实例。</p>
 *
 * <p>设计说明：</p>
 * <ul>
 *   <li>每个应用实例启动时通过 Redis 原子操作分配唯一的工作节点 ID</li>
 *   <li>WorkerId 和 DataCenterId 各自范围 0～31（共 1024 个组合），覆盖 5 位工作机器 ID</li>
 *   <li>当所有 ID 用尽后自动回绕（wrap around）复用</li>
 *   <li>使用 Lua 脚本保证 Redis 操作的原子性</li>
 * </ul>
 *
 * @see CustomIdentifierGenerator
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnowflakeIdInitializer {

    /**
     * Redis 操作模板，用于执行 Lua 脚本获取工作节点 ID
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 初始化方法，在 Bean 构造完成后自动执行
     * <p>执行流程：</p>
     * <ol>
     *   <li>加载 {@code lua/snowflake_init.lua} 脚本</li>
     *   <li>在 Redis 中执行脚本获取 WorkerId 和 DataCenterId</li>
     *   <li>使用获取到的 ID 创建 {@link Snowflake} 实例</li>
     *   <li>将实例注册到 Hutool 的全局 {@link Singleton} 中</li>
     * </ol>
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @PostConstruct
    public void init() {
        // 加载 Lua 脚本资源
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/snowflake_init.lua")));
        // 设置脚本返回类型为 List，用于接收 workerId 和 datacenterId 两个返回值
        script.setResultType(List.class);

        try {
            // 在 Redis 中原子性执行 Lua 脚本，获取工作节点 ID 组合
            List<Long> result = stringRedisTemplate.execute(script, Collections.emptyList());

            // 校验返回结果，必须包含两个元素
            if (CollUtil.isEmpty(result) || result.size() != 2) {
                throw new RuntimeException("从Redis获取WorkerId和DataCenterId失败");
            }

            // 分别获取工作机器 ID 和数据中心 ID
            Long workerId = result.get(0);
            Long datacenterId = result.get(1);

            // 使用获取到的 ID 创建雪花算法实例，并注册到 Hutool 全局单例
            Snowflake snowflake = new Snowflake(workerId, datacenterId);
            Singleton.put(snowflake);

            log.info("分布式Snowflake初始化完成, workerId: {}, datacenterId: {}", workerId, datacenterId);
        } catch (Exception e) {
            // 初始化失败时抛出运行时异常，阻止应用启动，避免 ID 冲突
            throw new RuntimeException("分布式Snowflake初始化失败", e);
        }
    }
}
