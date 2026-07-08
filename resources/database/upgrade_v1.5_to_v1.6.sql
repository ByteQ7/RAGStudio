-- ============================================
-- RAGStudio V1.5 → V1.6 升级脚本
-- 新增多模态图片识别支持
-- ============================================

-- 1. AI模型配置表：新增 supports_multimodal 字段
ALTER TABLE t_ai_model
    ADD COLUMN supports_multimodal SMALLINT NOT NULL DEFAULT 0;
COMMENT ON COLUMN t_ai_model.supports_multimodal IS '是否支持多模态(图片识别) 1：是 0：否';

-- 2. AI模型配置表：扩展 ID 列长度以兼容雪花算法更长 ID
ALTER TABLE t_ai_model
    ALTER COLUMN id TYPE VARCHAR(64);
ALTER TABLE t_ai_model
    ALTER COLUMN provider_id TYPE VARCHAR(64);

-- 3. AI模型供应商表：扩展 ID 列长度以兼容雪花算法更长 ID
ALTER TABLE t_ai_provider
    ALTER COLUMN id TYPE VARCHAR(64);

-- 4. 会话消息表：新增 image_urls 列，存储多模态图片 URL（JSON 数组）
ALTER TABLE t_message
    ADD COLUMN IF NOT EXISTS image_urls TEXT;
COMMENT ON COLUMN t_message.image_urls IS '图片 URL 列表 JSON（多模态消息）';
