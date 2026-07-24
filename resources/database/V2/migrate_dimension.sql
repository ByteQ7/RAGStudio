-- ============================================
-- Migration: 向量维度重构（INTEGER → JSON 数组）
-- ============================================

-- 1. t_ai_model.dimension 改为 TEXT，旧值自动转为 JSON 数组
ALTER TABLE t_ai_model ALTER COLUMN dimension TYPE TEXT;
UPDATE t_ai_model SET dimension = '[' || dimension || ']' WHERE dimension IS NOT NULL AND dimension NOT LIKE '[%';

-- 2. 更新已知 embedding 模型的维度列表
UPDATE t_ai_model SET dimension = '[1024,1536,4096]' WHERE model_id = 'qwen-emb-8b';
UPDATE t_ai_model SET dimension = '[1536]' WHERE model_id = 'text-embedding-3-small';

-- 3. 补齐供应商图标
UPDATE t_ai_provider SET icon_url = 's3://ragstudio/provider-icons/openai.svg'     WHERE name = 'openai'     AND icon_url IS NULL;
UPDATE t_ai_provider SET icon_url = 's3://ragstudio/provider-icons/openrouter.svg'  WHERE name = 'openrouter'  AND icon_url IS NULL;
UPDATE t_ai_provider SET icon_url = 's3://ragstudio/provider-icons/moonshot.svg'    WHERE name = 'moonshot'    AND icon_url IS NULL;
UPDATE t_ai_provider SET icon_url = 's3://ragstudio/provider-icons/minimax.svg'     WHERE name = 'minimax'     AND icon_url IS NULL;
UPDATE t_ai_provider SET icon_url = 's3://ragstudio/provider-icons/zeroone.svg'     WHERE name = 'zeroone'     AND icon_url IS NULL;
UPDATE t_ai_provider SET icon_url = 's3://ragstudio/provider-icons/baidu.svg'       WHERE name = 'baidu'       AND icon_url IS NULL;
UPDATE t_ai_provider SET icon_url = 's3://ragstudio/provider-icons/tencent.svg'     WHERE name = 'tencent'     AND icon_url IS NULL;
UPDATE t_ai_provider SET icon_url = 's3://ragstudio/provider-icons/together.svg'    WHERE name = 'together'    AND icon_url IS NULL;
UPDATE t_ai_provider SET icon_url = 's3://ragstudio/provider-icons/groq.svg'        WHERE name = 'groq'        AND icon_url IS NULL;
UPDATE t_ai_provider SET icon_url = 's3://ragstudio/provider-icons/mistral.svg'     WHERE name = 'mistral'     AND icon_url IS NULL;
UPDATE t_ai_provider SET icon_url = 's3://ragstudio/provider-icons/stepfun.svg'     WHERE name = 'stepfun'     AND icon_url IS NULL;
UPDATE t_ai_provider SET icon_url = 's3://ragstudio/provider-icons/sensetime.svg'   WHERE name = 'sensetime'   AND icon_url IS NULL;

-- 4. 创建 1024 维向量表（跟原有 1536 维表同级）
CREATE TABLE IF NOT EXISTS t_knowledge_vector_1024 (
    id        VARCHAR(64) PRIMARY KEY,
    content   TEXT,
    metadata  JSONB,
    embedding vector(1024)
);
CREATE INDEX IF NOT EXISTS idx_kv_1024_metadata ON t_knowledge_vector_1024 USING gin(metadata);
CREATE INDEX IF NOT EXISTS idx_kv_1024_embedding ON t_knowledge_vector_1024 USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_kv_1024_content_trgm ON t_knowledge_vector_1024 USING gin (content gin_trgm_ops);
COMMENT ON TABLE t_knowledge_vector_1024 IS '知识库向量存储表（1024维）';
