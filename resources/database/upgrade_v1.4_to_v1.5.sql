-- ============================================
-- 升级脚本 V1.4 to V1.5
-- 新增：pg_trgm 扩展 + GIN 索引，替换 tsvector 关键词索引
-- 原因：PostgreSQL 默认分词器不拆分中文词（无空格分割），
--       tsvector/tsquery 对中文关键词检索基本失效。
--       pg_trgm 将文本拆解为 3 字符片段（trigram），
--       配合 GIN 索引使 ILIKE '%keyword%' 走索引，毫秒级返回。
-- ============================================

-- 1. 启用 pg_trgm 扩展（如未启用）
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 2. 删除旧的关键词索引（tsvector，对中文无效）
DROP INDEX IF EXISTS idx_kv_tsvector;

-- 3. 新建 pg_trgm GIN 索引（支持 ILIKE 子串匹配）
CREATE INDEX idx_kv_content_trgm ON t_knowledge_vector USING gin (content gin_trgm_ops);

-- 4. 新的建表语句（仅用于参考，实际由 schema_pg.sql 管理）
COMMENT ON INDEX idx_kv_content_trgm IS 'pg_trgm GIN 索引，加速 content ILIKE 子串匹配（兼容中文）';
