-- ============================================
-- 升级脚本 V1.3 to V1.4
-- 新增：t_message.citations 列，存储引用溯源数据
-- ============================================

ALTER TABLE t_message ADD COLUMN IF NOT EXISTS citations TEXT;
COMMENT ON COLUMN t_message.citations IS '引用溯源 JSON，格式 [{id, text, score}]';
