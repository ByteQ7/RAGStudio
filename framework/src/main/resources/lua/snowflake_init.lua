-- ============================================================================
-- 分布式雪花算法（Snowflake）工作节点 ID 分配脚本
-- ============================================================================
--
-- 功能说明：
--   通过 Redis 哈希表原子性地为每个应用实例分配全局唯一的工作节点 ID
--   （WorkerId）和数据中心 ID（DataCenterId），用于雪花算法生成分布式
--   唯一 ID。
--
-- 算法说明：
--   1. 如果 Redis 中尚不存在 snowflake_work_id_key 哈希表，则初始化
--      dataCenterId 和 workId 均为 0，并返回 {0, 0}
--   2. 否则，从哈希表中读取当前的 dataCenterId 和 workId
--   3. 如果两者都已达到最大值 31（workId=31 且 dataCenterId=31）：
--      则回绕到 0，重新开始分配（wrap around）
--   4. 如果 workId 未达到最大值 31：workId 自增 1
--   5. 如果 workId 已达到最大值：dataCenterId 自增 1，workId 重置为 0
--   6. 返回 {resultWorkId, resultDataCenterId}
--
-- 设计特点：
--   - 原子性：整个分配过程在 Lua 脚本中完成，不需要分布式锁
--   - 自恢复：当所有 ID 用尽后自动回绕复用
--   - 无中心化依赖：仅依赖 Redis 实现，不需要单独的协调服务
--   - 范围：WorkerId [0, 31]，DataCenterId [0, 31]，共 1024 个组合
-- ============================================================================

-- Redis 哈希表的 Key 名称，存储雪花算法的工作节点 ID 信息
local hashKey = 'snowflake_work_id_key'
-- 数据中心 ID 在哈希表中的字段名
local dataCenterIdKey = 'dataCenterId'
-- 工作机器 ID 在哈希表中的字段名
local workIdKey = 'workId'

-- 如果哈希表不存在，初始化 dataCenterId 和 workId 均为 0
if (redis.call('exists', hashKey) == 0) then
    redis.call('hincrby', hashKey, dataCenterIdKey, 0)
    redis.call('hincrby', hashKey, workIdKey, 0)
    return { 0, 0 }
end

-- 读取当前的 dataCenterId 和 workId
local dataCenterId = tonumber(redis.call('hget', hashKey, dataCenterIdKey))
local workId = tonumber(redis.call('hget', hashKey, workIdKey))

-- 最大 ID 值，对应雪花算法中 5 位二进制能表示的最大值（32 个取值：0~31）
local max = 31
local resultWorkId = 0
local resultDataCenterId = 0

-- 如果两者都已达到最大值，回绕到 0
if (dataCenterId == max and workId == max) then
    redis.call('hset', hashKey, dataCenterIdKey, '0')
    redis.call('hset', hashKey, workIdKey, '0')
    resultWorkId = 0
    resultDataCenterId = 0
-- 如果 workId 未达到最大值，workId 自增 1
elseif (workId ~= max) then
    resultWorkId = redis.call('hincrby', hashKey, workIdKey, 1)
    resultDataCenterId = dataCenterId
-- 如果 workId 已达最大值但 dataCenterId 未达最大值，dataCenterId 自增 1，workId 重置为 0
elseif (dataCenterId ~= max) then
    resultWorkId = 0
    resultDataCenterId = redis.call('hincrby', hashKey, dataCenterIdKey, 1)
    redis.call('hset', hashKey, workIdKey, '0')
end

-- 返回分配的 workId 和 dataCenterId
return { resultWorkId, resultDataCenterId }
