-- ============================================
-- 升级脚本 V1.2 to V1.3
-- 新增：tsvector GIN 索引，支持关键词全文检索
-- ============================================

-- 为 t_knowledge_vector.content 列建立全文检索索引
-- 使用 simple 词典（不做词干化），兼容中英文
CREATE INDEX IF NOT EXISTS idx_kv_tsvector ON t_knowledge_vector
  USING gin(to_tsvector('simple', coalesce(content, '')));
