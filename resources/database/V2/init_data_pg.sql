-- PostgreSQL Initial Data for RAGStudio V2
-- Entity: UserDO (@TableName="t_user", @TableId=ASSIGN_ID, @TableLogic)

INSERT INTO t_user (id, username, password, role, avatar, create_time, update_time, deleted)
VALUES (2001523723396308993, 'admin', 'admin', 'admin',
        'https://t.alcy.cc/ysmp',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

-- ============================================
-- AI Provider 初始数据
-- ============================================

INSERT INTO t_ai_provider (id, name, display_name, base_url, api_key, endpoints, enabled, icon_url, create_time, update_time, deleted)
VALUES
(1821730000000000101, 'bailian', '百炼 (阿里云)', 'https://dashscope.aliyuncs.com', NULL,
 '{"chat":"/compatible-mode/v1/chat/completions","rerank":"/api/v1/services/rerank/text-rerank/text-rerank"}'::jsonb,
 1, 's3://ragstudio/provider-icons/bailian.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000102, 'siliconflow', 'SiliconFlow', 'https://api.siliconflow.cn', NULL,
 '{"chat":"/v1/chat/completions","embedding":"/v1/embeddings"}'::jsonb,
 1, 's3://ragstudio/provider-icons/siliconflow.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000103, 'deepseek', 'DeepSeek', 'https://api.deepseek.com', NULL,
 '{"chat":"/v1/chat/completions"}'::jsonb,
 1, 's3://ragstudio/provider-icons/deepseek.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000104, 'noop', 'NOOP (占位)', 'http://localhost', NULL,
 '{}'::jsonb,
 1, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000105, 'zhipu', '智谱 AI', 'https://open.bigmodel.cn/api/paas/v4/', NULL,
 '{}'::jsonb,
 1, 's3://ragstudio/provider-icons/zhipu.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000106, 'volcengine', '火山引擎', 'https://ark.cn-beijing.volces.com/api/v3/', NULL,
 '{}'::jsonb,
 1, 's3://ragstudio/provider-icons/volcengine.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

-- ============================================
-- AI Model 初始数据 - Chat
-- ============================================

INSERT INTO t_ai_model (id, provider_id, model_id, model_name, capability, is_default, priority, enabled, supports_thinking, supports_multimodal, dimension, custom_url, create_time, update_time, deleted)
VALUES
-- Chat 模型
(1831730000000000201, 1821730000000000101, 'qwen-plus',          'qwen-plus-latest',               'CHAT', 0, 1,  1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000202, 1821730000000000101, 'qwen3-max',          'qwen3-max',                      'CHAT', 0, 2,  1, 1, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000203, 1821730000000000102, 'glm-4.7',            'Pro/zai-org/GLM-4.7',           'CHAT', 0, 3,  1, 1, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000204, 1821730000000000103, 'deepseek-v4-flash',  'deepseek-v4-flash',              'CHAT', 1, 4,  1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
-- Embedding 模型
(1831730000000000205, 1821730000000000102, 'qwen-emb-8b',        'Qwen/Qwen3-Embedding-8B',       'EMBEDDING', 1, 1,  1, 0, 0, 1536, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
-- Rerank 模型
(1831730000000000206, 1821730000000000101, 'qwen3-rerank',       'qwen3-rerank',                   'RERANK', 1, 1,   1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000207, 1821730000000000104, 'rerank-noop',        'noop',                           'RERANK', 0, 100, 1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);
