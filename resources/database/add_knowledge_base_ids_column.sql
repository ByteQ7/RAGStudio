-- 为关键词映射表新增关联知识库ID列
-- 该列存储 JSON 数组格式的知识库 ID 列表，用于按知识库过滤归一化规则

ALTER TABLE t_query_term_mapping ADD COLUMN IF NOT EXISTS knowledge_base_ids TEXT;
COMMENT ON COLUMN t_query_term_mapping.knowledge_base_ids IS '关联知识库ID列表（JSON数组）';
