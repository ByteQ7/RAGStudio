-- ============================================
-- Migration: 新增 api_protocol 字段支持多协议
-- ============================================

-- 1. t_ai_provider 新增 api_protocol
ALTER TABLE t_ai_provider ADD COLUMN IF NOT EXISTS api_protocol VARCHAR(32) DEFAULT 'openai';
COMMENT ON COLUMN t_ai_provider.api_protocol IS 'API 协议类型: openai / dashscope / anthropic';

-- 2. t_ai_model 新增 api_protocol（可覆盖供应商级协议）
ALTER TABLE t_ai_model ADD COLUMN IF NOT EXISTS api_protocol VARCHAR(32);
COMMENT ON COLUMN t_ai_model.api_protocol IS 'API 协议类型覆盖: openai / dashscope / anthropic，NULL=继承供应商';

-- 3. 供应商不设 dashscope，只在多模态 Embedding 模型级覆盖
-- （bailian 的普通文本模型仍用 OpenAI 兼容模式）
UPDATE t_ai_model SET api_protocol = 'dashscope'
WHERE model_id = 'qwen3-vl-embedding' AND capability = 'EMBEDDING';
