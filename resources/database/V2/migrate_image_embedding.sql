-- ============================================
-- Migration: t_knowledge_base 新增 supports_image_embedding 字段
-- ============================================

ALTER TABLE t_knowledge_base ADD COLUMN IF NOT EXISTS supports_image_embedding SMALLINT DEFAULT 0;
COMMENT ON COLUMN t_knowledge_base.supports_image_embedding IS '是否支持图像嵌入: 1-是，0-否。由嵌入模型的多模态能力自动判断';

-- 对已有的具有多模态嵌入模型的知识库，自动标记 supports_image_embedding = 1
UPDATE t_knowledge_base kb
SET supports_image_embedding = 1
FROM t_ai_model am
WHERE kb.embedding_model = am.model_id
  AND am.supports_multimodal = 1
  AND am.capability = 'EMBEDDING';
