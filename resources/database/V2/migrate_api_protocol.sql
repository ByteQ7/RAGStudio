-- ============================================
-- Migration: 新增 api_protocol 字段支持多协议
-- 注意：字段的数据值（dashscope 覆盖）在 init_data_pg.sql 末尾设置，
--       保证与种子数据同一批次导入（本脚本在 init 之前执行时 UPDATE 不生效）。
-- ============================================

-- 1. t_ai_provider 新增 api_protocol
ALTER TABLE t_ai_provider ADD COLUMN IF NOT EXISTS api_protocol VARCHAR(32) DEFAULT 'openai';
COMMENT ON COLUMN t_ai_provider.api_protocol IS 'API 协议类型: openai / dashscope / anthropic';

-- 2. t_ai_model 新增 api_protocol（可覆盖供应商级协议）
ALTER TABLE t_ai_model ADD COLUMN IF NOT EXISTS api_protocol VARCHAR(32);
COMMENT ON COLUMN t_ai_model.api_protocol IS 'API 协议类型覆盖: openai / dashscope / anthropic，NULL=继承供应商';
