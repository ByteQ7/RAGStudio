-- ============================================================
-- 新增默认 AI 供应商升级脚本（存量库执行；全新部署直接执行 schema_all.sql，无需本文件）
-- 新增 4 家：小米 (MiMo) / 讯飞星火 / 360智脑 / xAI (Grok)
-- 内容与 schema_all.sql 中「AI Provider / AI Model」种子段落一致
-- 可重复执行：供应商按 name 防重、模型按 model_id 防重（uk_ai_model_model_id）
-- 前置：先执行 ./scripts/upload-provider-icons.sh 上传配套图标（xiaomi/spark/ai360/xai.svg）
-- ============================================================

INSERT INTO t_ai_provider (id, name, display_name, base_url, api_key, endpoints, enabled, icon_url, deleted, api_protocol, create_time, update_time)
SELECT '1821609200921346048', 'xiaomi', '小米 (MiMo)', 'https://api.xiaomimimo.com', NULL,
       '{"chat": "/v1/chat/completions", "models": "/v1/models"}'::jsonb, 0,
       's3://ragstudio/provider-icons/xiaomi.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM t_ai_provider WHERE name = 'xiaomi' AND deleted = 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_ai_provider (id, name, display_name, base_url, api_key, endpoints, enabled, icon_url, deleted, api_protocol, create_time, update_time)
SELECT '1821609200925540352', 'spark', '讯飞星火', 'https://spark-api-open.xf-yun.com', NULL,
       '{"chat": "/v1/chat/completions"}'::jsonb, 0,
       's3://ragstudio/provider-icons/spark.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM t_ai_provider WHERE name = 'spark' AND deleted = 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_ai_provider (id, name, display_name, base_url, api_key, endpoints, enabled, icon_url, deleted, api_protocol, create_time, update_time)
SELECT '1821609200929734656', 'ai360', '360智脑', 'https://api.360.cn', NULL,
       '{"chat": "/v1/chat/completions", "models": "/v1/models"}'::jsonb, 0,
       's3://ragstudio/provider-icons/ai360.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM t_ai_provider WHERE name = 'ai360' AND deleted = 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_ai_provider (id, name, display_name, base_url, api_key, endpoints, enabled, icon_url, deleted, api_protocol, create_time, update_time)
SELECT '1821609200933928960', 'xai', 'xAI (Grok)', 'https://api.x.ai', NULL,
       '{"chat": "/v1/chat/completions", "models": "/v1/models"}'::jsonb, 0,
       's3://ragstudio/provider-icons/xai.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM t_ai_provider WHERE name = 'xai' AND deleted = 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_ai_model (id, provider_id, model_id, model_name, capability, is_default, priority, enabled, supports_thinking, supports_multimodal, dimension, custom_url, deleted, api_protocol, create_time, update_time)
SELECT '1831609201722392642', '1821609200921346048', 'mimo-v2.5', 'mimo-v2.5', 'CHAT', 0, 1, 0, 1, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM t_ai_model WHERE model_id = 'mimo-v2.5' AND deleted = 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_ai_model (id, provider_id, model_id, model_name, capability, is_default, priority, enabled, supports_thinking, supports_multimodal, dimension, custom_url, deleted, api_protocol, create_time, update_time)
SELECT '1831609201722392643', '1821609200921346048', 'mimo-v2.5-pro', 'mimo-v2.5-pro', 'CHAT', 0, 2, 0, 1, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM t_ai_model WHERE model_id = 'mimo-v2.5-pro' AND deleted = 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_ai_model (id, provider_id, model_id, model_name, capability, is_default, priority, enabled, supports_thinking, supports_multimodal, dimension, custom_url, deleted, api_protocol, create_time, update_time)
SELECT '1831609201722392644', '1821609200925540352', '4.0Ultra', '4.0Ultra', 'CHAT', 0, 1, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM t_ai_model WHERE model_id = '4.0Ultra' AND deleted = 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_ai_model (id, provider_id, model_id, model_name, capability, is_default, priority, enabled, supports_thinking, supports_multimodal, dimension, custom_url, deleted, api_protocol, create_time, update_time)
SELECT '1831609201722392645', '1821609200925540352', 'generalv3.5', 'generalv3.5 (Max)', 'CHAT', 0, 2, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM t_ai_model WHERE model_id = 'generalv3.5' AND deleted = 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_ai_model (id, provider_id, model_id, model_name, capability, is_default, priority, enabled, supports_thinking, supports_multimodal, dimension, custom_url, deleted, api_protocol, create_time, update_time)
SELECT '1831609201722392646', '1821609200929734656', '360gpt-pro', '360gpt-pro', 'CHAT', 0, 1, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM t_ai_model WHERE model_id = '360gpt-pro' AND deleted = 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_ai_model (id, provider_id, model_id, model_name, capability, is_default, priority, enabled, supports_thinking, supports_multimodal, dimension, custom_url, deleted, api_protocol, create_time, update_time)
SELECT '1831609201722392647', '1821609200933928960', 'grok-4', 'grok-4', 'CHAT', 0, 1, 0, 1, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM t_ai_model WHERE model_id = 'grok-4' AND deleted = 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_ai_model (id, provider_id, model_id, model_name, capability, is_default, priority, enabled, supports_thinking, supports_multimodal, dimension, custom_url, deleted, api_protocol, create_time, update_time)
SELECT '1831609201722392648', '1821609200933928960', 'grok-3', 'grok-3', 'CHAT', 0, 2, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM t_ai_model WHERE model_id = 'grok-3' AND deleted = 0)
ON CONFLICT (id) DO NOTHING;
