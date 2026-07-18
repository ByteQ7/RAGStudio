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
(1831730000000000202, 1821730000000000101, 'qwen3-max',          'qwen3-max',                      'CHAT', 0, 2,  1, 1, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000203, 1821730000000000102, 'glm-4.7',            'Pro/zai-org/GLM-4.7',           'CHAT', 0, 3,  1, 1, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000204, 1821730000000000103, 'deepseek-v4-flash',  'deepseek-v4-flash',              'CHAT', 1, 4,  1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
-- Embedding 模型
(1831730000000000205, 1821730000000000102, 'qwen-emb-8b',        'Qwen/Qwen3-Embedding-8B',       'EMBEDDING', 1, 1,  1, 0, 0, 1536, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
-- Rerank 模型
(1831730000000000206, 1821730000000000101, 'qwen3-rerank',       'qwen3-rerank',                   'RERANK', 1, 1,   1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000207, 1821730000000000104, 'rerank-noop',        'noop',                           'RERANK', 0, 100, 1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000224, 1821730000000000101, 'qwen-vl-plus',       'qwen-vl-plus-latest',            'CHAT', 0, 5,  1, 0, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000225, 1821730000000000101, 'qwen3.5-9B',         'qwen3.5-9b',                     'CHAT', 0, 6,  1, 1, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),

-- ============================================
-- 新增 Provider（12个）
-- ============================================
INSERT INTO t_ai_provider (id, name, display_name, base_url, api_key, endpoints, enabled, icon_url, create_time, update_time, deleted) VALUES
(1821730000000000107, 'openai',       'OpenAI',              'https://api.openai.com',            NULL, '{"chat":"/v1/chat/completions","embedding":"/v1/embeddings"}'::jsonb, 1, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000108, 'openrouter',   'OpenRouter',          'https://openrouter.ai/api',         NULL, '{"chat":"/v1/chat/completions"}'::jsonb, 1, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000109, 'moonshot',     '月之暗面 (Kimi)',      'https://api.moonshot.cn',           NULL, '{"chat":"/v1/chat/completions"}'::jsonb, 1, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000110, 'minimax',      'MiniMax (海螺AI)',     'https://api.minimax.chat',         NULL, '{"chat":"/v1/chat/completions","embedding":"/v1/embeddings"}'::jsonb, 1, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000111, 'zeroone',      '零一万物 (Yi)',         'https://api.lingyiwanwu.com',       NULL, '{"chat":"/v1/chat/completions"}'::jsonb, 1, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000112, 'baidu',        '百度千帆 (ERNIE)',      'https://aip.baidubce.com',          NULL, '{"chat":"/v1/chat/completions"}'::jsonb, 1, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000113, 'tencent',      '腾讯混元',              'https://api.hunyuan.cloud.tencent.com', NULL, '{"chat":"/v1/chat/completions"}'::jsonb, 1, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000114, 'together',     'Together AI',          'https://api.together.xyz',          NULL, '{"chat":"/v1/chat/completions"}'::jsonb, 1, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000115, 'groq',         'Groq',                 'https://api.groq.com',             NULL, '{"chat":"/v1/chat/completions"}'::jsonb, 1, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000116, 'mistral',      'Mistral AI',           'https://api.mistral.ai',           NULL, '{"chat":"/v1/chat/completions"}'::jsonb, 1, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000117, 'stepfun',      '阶跃星辰 (StepFun)',    'https://api.stepfun.com',           NULL, '{"chat":"/v1/chat/completions"}'::jsonb, 1, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000118, 'sensetime',    '商汤 (日日新)',          'https://api.sensetime.com',         NULL, '{"chat":"/v1/chat/completions"}'::jsonb, 1, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- 新增 Model（16个）
-- ============================================
INSERT INTO t_ai_model (id, provider_id, model_id, model_name, capability, is_default, priority, enabled, supports_thinking, supports_multimodal, dimension, custom_url, create_time, update_time, deleted) VALUES
(1831730000000000208, 1821730000000000107, 'gpt-4o',            'gpt-4o',               'CHAT', 0, 1, 1, 0, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000209, 1821730000000000107, 'gpt-4o-mini',       'gpt-4o-mini',          'CHAT', 0, 2, 1, 0, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000210, 1821730000000000107, 'o3-mini',           'o3-mini',              'CHAT', 1, 3, 1, 1, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000211, 1821730000000000107, 'text-embedding-3-small', 'text-embedding-3-small', 'EMBEDDING', 0, 1, 1, 0, 0, 512, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000212, 1821730000000000108, 'openrouter-auto',   'openrouter/auto',      'CHAT', 1, 1, 1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000213, 1821730000000000109, 'kimi-k2',           'kimi-k2',              'CHAT', 1, 1, 1, 1, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000214, 1821730000000000109, 'moonshot-v1-8k',    'moonshot-v1-8k',       'CHAT', 0, 2, 1, 0, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000215, 1821730000000000110, 'minimax-text-01',   'MiniMax-Text-01',      'CHAT', 1, 1, 1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000216, 1821730000000000111, 'yi-lightning',      'yi-lightning',         'CHAT', 1, 1, 1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000217, 1821730000000000112, 'ernie-4.0',         'ernie-4.0',            'CHAT', 1, 1, 1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000218, 1821730000000000113, 'hunyuan-standard',  'hunyuan-standard',     'CHAT', 1, 1, 1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000219, 1821730000000000114, 'together-mix',      'mistralai/Mixtral-8x22B-Instruct-v0.1', 'CHAT', 1, 1, 1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000220, 1821730000000000115, 'groq-llama',        'llama-3.3-70b-versatile', 'CHAT', 1, 1, 1, 0, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000221, 1821730000000000116, 'mistral-large',     'mistral-large-latest', 'CHAT', 1, 1, 1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000222, 1821730000000000117, 'step-2',            'step-2-16k',           'CHAT', 1, 1, 1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000223, 1821730000000000118, 'sensechat-5',       'sensechat-5',          'CHAT', 0, 1, 1, 0, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- 默认模型配置 (场景默认模型)
-- ============================================

INSERT INTO t_default_model_config (id, config_key, model_id, create_time, update_time) VALUES
(1851730000000000001, 'chat',       'deepseek-v4-flash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(1851730000000000002, 'summary',    'deepseek-v4-flash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(1851730000000000003, 'title',      'deepseek-v4-flash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(1851730000000000004, 'multimodal', 'qwen-vl-plus',      CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(1851730000000000005, 'doc_image',  'qwen3.5-9B',        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;
