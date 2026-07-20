-- Migration: 新增 Chunk 高亮场景的默认模型配置
-- 使高亮模型可在后管"默认模型设置"页面中配置

INSERT INTO t_default_model_config (id, config_key, model_id, create_time, update_time)
VALUES (1851730000000000006, 'highlight', 'deepseek-v4-flash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

COMMENT ON COLUMN t_default_model_config.config_key IS '配置键: chat/summary/title/multimodal/doc_image/highlight';
