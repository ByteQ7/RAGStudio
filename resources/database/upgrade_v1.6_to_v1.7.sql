-- ============================================
-- RAGStudio V1.6 → V1.7 升级脚本
-- AI 供应商图标支持 + 新增供应商
-- ============================================

-- 1. AI模型供应商表：新增 icon_url 字段
ALTER TABLE t_ai_provider
    ADD COLUMN IF NOT EXISTS icon_url VARCHAR(500);
COMMENT ON COLUMN t_ai_provider.icon_url IS '供应商图标 URL（存储在 S3 的 provider-icons 目录）';

-- 2. 新增供应商：智谱 AI
INSERT INTO t_ai_provider (id, name, display_name, base_url, api_key, endpoints, enabled, icon_url, create_time, update_time, deleted)
VALUES (1000000000000000007, 'zhipu', '智谱 AI', 'https://open.bigmodel.cn/api/paas/v4/', NULL,
        '{}'::jsonb, 1, 's3://ragstudio/provider-icons/zhipu.svg',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (name, deleted) WHERE deleted = 0 DO NOTHING;

-- 3. 新增供应商：火山引擎
INSERT INTO t_ai_provider (id, name, display_name, base_url, api_key, endpoints, enabled, icon_url, create_time, update_time, deleted)
VALUES (1000000000000000008, 'volcengine', '火山引擎', 'https://ark.cn-beijing.volces.com/api/v3/', NULL,
        '{}'::jsonb, 1, 's3://ragstudio/provider-icons/volcengine.svg',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (name, deleted) WHERE deleted = 0 DO NOTHING;

-- 4. 更新已有供应商的图标 URL
UPDATE t_ai_provider SET icon_url = 's3://ragstudio/provider-icons/siliconflow.svg' WHERE name = 'siliconflow' AND deleted = 0;
UPDATE t_ai_provider SET icon_url = 's3://ragstudio/provider-icons/deepseek.svg'    WHERE name = 'deepseek'    AND deleted = 0;
UPDATE t_ai_provider SET icon_url = 's3://ragstudio/provider-icons/bailian.svg'     WHERE name = 'bailian'     AND deleted = 0;
