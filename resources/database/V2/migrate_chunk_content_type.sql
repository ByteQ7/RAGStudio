-- ============================================
-- Migration: t_knowledge_chunk 新增 content_type 和 image_url 字段
-- 用于多模态知识库的 enable/re-enable 流程中保持 IMAGE chunk 的类型信息
-- ============================================

ALTER TABLE t_knowledge_chunk ADD COLUMN IF NOT EXISTS content_type VARCHAR(32) DEFAULT 'TEXT';
COMMENT ON COLUMN t_knowledge_chunk.content_type IS '分块类型: TEXT/IMAGE';

ALTER TABLE t_knowledge_chunk ADD COLUMN IF NOT EXISTS image_url VARCHAR(1024);
COMMENT ON COLUMN t_knowledge_chunk.image_url IS '图片 S3 URL（仅 IMAGE 类型 chunk）';

-- 同时更新 schema_pg.sql 中的建表语句
