-- PostgreSQL Initial Data for RAGStudio V2
-- ============================================================
-- 说明：本脚本为「全新部署」种子数据，同步自生产库实际状态（2026-07-31）：
--   - 供应商：18 家（仅 4 家启用，其余因未配置 API Key 默认禁用）
--   - 模型：46 条（删除了生产库中已逻辑删除的旧模型，如 glm-4.7 / qwen-emb-8b / qwen3.5-9B 等）
--   - 默认模型配置：7 个场景（含 rerank 与 tool_selector 语义选择）
-- 注意：api_key 一律为 NULL，部署后需在「模型管理」中手动配置。
-- ============================================================

-- ============================================
-- 默认管理员账号 (UserDO: @TableName="t_user")
-- ============================================

INSERT INTO t_user (id, username, password, role, avatar, create_time, update_time, deleted)
VALUES (2001523723396308993, 'admin', 'admin', 'admin',
        'https://t.alcy.cc/ysmp',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- AI Provider（18 家）
-- 启用状态: bailian / siliconflow / deepseek / volcengine 已启用，
-- 其余 14 家默认禁用（未配置 API Key，避免出现在前端可用模型列表中）
-- ============================================

INSERT INTO t_ai_provider (id, name, display_name, base_url, api_key, endpoints, enabled, icon_url, create_time, update_time, deleted)
VALUES
(1821730000000000101, 'bailian',     '百炼 (阿里云)',      'https://dashscope.aliyuncs.com',           NULL,
 '{"chat":"/compatible-mode/v1/chat/completions","embedding":"/compatible-mode/v1/embeddings","rerank":"/api/v1/services/rerank/text-rerank/text-rerank","models":"/compatible-mode/v1/models"}'::jsonb,
 1, 's3://ragstudio/provider-icons/bailian.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000102, 'siliconflow', 'SiliconFlow',         'https://api.siliconflow.cn',               NULL,
 '{"chat":"/v1/chat/completions","embedding":"/v1/embeddings","models":"/v1/models"}'::jsonb,
 1, 's3://ragstudio/provider-icons/siliconflow.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000103, 'deepseek',    'DeepSeek',            'https://api.deepseek.com',                 NULL,
 '{"chat":"/v1/chat/completions","embedding":"/v1/embeddings","models":"/v1/models"}'::jsonb,
 1, 's3://ragstudio/provider-icons/deepseek.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000104, 'noop',        'NOOP (占位)',          'http://localhost',                        NULL,
 '{}'::jsonb,
 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000105, 'zhipu',       '智谱 AI',              'https://open.bigmodel.cn',                NULL,
 '{"chat":"/api/paas/v4/chat/completions","embedding":"/api/paas/v4/embeddings","models":"/api/paas/v4/models"}'::jsonb,
 0, 's3://ragstudio/provider-icons/zhipu.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000106, 'volcengine',  '火山引擎',             'https://ark.cn-beijing.volces.com/api/v3', NULL,
 '{"models":"/models","embedding":"/embeddings"}'::jsonb,
 1, 's3://ragstudio/provider-icons/volcengine.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000107, 'openai',      'OpenAI',              'https://api.openai.com',                  NULL,
 '{"chat":"/v1/chat/completions","embedding":"/v1/embeddings","models":"/v1/models"}'::jsonb,
 0, 's3://ragstudio/provider-icons/openai.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000108, 'openrouter',  'OpenRouter',          'https://openrouter.ai/api',               NULL,
 '{"chat":"/v1/chat/completions","models":"/v1/models"}'::jsonb,
 0, 's3://ragstudio/provider-icons/openrouter.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000109, 'moonshot',    '月之暗面 (Kimi)',      'https://api.moonshot.cn',                 NULL,
 '{"chat":"/v1/chat/completions","embedding":"/v1/embeddings","models":"/v1/models"}'::jsonb,
 0, 's3://ragstudio/provider-icons/moonshot.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000110, 'minimax',     'MiniMax (海螺AI)',     'https://api.minimaxi.com',                NULL,
 '{"chat":"/v1/chat/completions","embedding":"/v1/embeddings","models":"/v1/models"}'::jsonb,
 0, 's3://ragstudio/provider-icons/minimax.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000111, 'zeroone',     '零一万物 (Yi)',         'https://api.lingyiwanwu.com',             NULL,
 '{"chat":"/v1/chat/completions","embedding":"/v1/embeddings","models":"/v1/models"}'::jsonb,
 0, 's3://ragstudio/provider-icons/zeroone.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000112, 'baidu',       '百度千帆 (ERNIE)',      'https://qianfan.baidubce.com',            NULL,
 '{"chat":"/v2/chat/completions","embedding":"/v2/embeddings","models":"/v2/models"}'::jsonb,
 0, 's3://ragstudio/provider-icons/baidu.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000113, 'tencent',     '腾讯混元',              'https://api.hunyuan.cloud.tencent.com',   NULL,
 '{"chat":"/v1/chat/completions","embedding":"/v1/embeddings","models":"/v1/models"}'::jsonb,
 0, 's3://ragstudio/provider-icons/tencent.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000114, 'together',    'Together AI',          'https://api.together.xyz',                NULL,
 '{"chat":"/v1/chat/completions","models":"/v1/models"}'::jsonb,
 0, 's3://ragstudio/provider-icons/together.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000115, 'groq',        'Groq',                 'https://api.groq.com',                    NULL,
 '{"chat":"/v1/chat/completions","models":"/v1/models"}'::jsonb,
 0, 's3://ragstudio/provider-icons/groq.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000116, 'mistral',     'Mistral AI',           'https://api.mistral.ai',                  NULL,
 '{"chat":"/v1/chat/completions","models":"/v1/models"}'::jsonb,
 0, 's3://ragstudio/provider-icons/mistral.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000117, 'stepfun',     '阶跃星辰 (StepFun)',    'https://api.stepfun.com',                 NULL,
 '{"chat":"/v1/chat/completions","embedding":"/v1/embeddings","models":"/v1/models"}'::jsonb,
 0, 's3://ragstudio/provider-icons/stepfun.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1821730000000000118, 'sensetime',   '商汤 (日日新)',          'https://api.sensetime.com',               NULL,
 '{"chat":"/v1/chat/completions","embedding":"/v1/embeddings","models":"/v1/models"}'::jsonb,
 0, 's3://ragstudio/provider-icons/sensetime.svg', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- AI Model（46 条，均为生产库中启用状态）
-- 说明：
--   - 基础模型沿用固定 ID（1831730000000000xxx）
--   - 后期通过管理员界面拉取的模型使用雪花 ID（2079.../2080...）
--   - api_protocol 覆盖（dashscope 等）由 V2/migrate_api_protocol.sql 负责，这里不写
-- ============================================

INSERT INTO t_ai_model (id, provider_id, model_id, model_name, capability, is_default, priority, enabled, supports_thinking, supports_multimodal, dimension, custom_url, create_time, update_time, deleted)
VALUES
-- ============ Chat 模型（29 条） ============
(1831730000000000201, 1821730000000000101, 'qwen-plus',                   'qwen-plus-latest',                    'CHAT', 0, 1,   1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000208, 1821730000000000107, 'gpt-4o',                      'gpt-4o',                              'CHAT', 0, 1,   0, 0, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000212, 1821730000000000108, 'openrouter-auto',             'openrouter/auto',                     'CHAT', 1, 1,   0, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000213, 1821730000000000109, 'kimi-k2',                     'kimi-k2',                             'CHAT', 1, 1,   0, 1, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000215, 1821730000000000110, 'minimax-text-01',             'MiniMax-Text-01',                     'CHAT', 1, 1,   0, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000216, 1821730000000000111, 'yi-lightning',                'yi-lightning',                        'CHAT', 1, 1,   0, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000217, 1821730000000000112, 'ernie-4.0',                   'ernie-4.0',                           'CHAT', 1, 1,   0, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000218, 1821730000000000113, 'hunyuan-standard',            'hunyuan-standard',                    'CHAT', 1, 1,   0, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000219, 1821730000000000114, 'together-mix',                'mistralai/Mixtral-8x22B-Instruct-v0.1', 'CHAT', 1, 1, 0, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000220, 1821730000000000115, 'groq-llama',                  'llama-3.3-70b-versatile',             'CHAT', 1, 1,   0, 0, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000221, 1821730000000000116, 'mistral-large',               'mistral-large-latest',                'CHAT', 1, 1,   0, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000222, 1821730000000000117, 'step-2',                      'step-2-16k',                          'CHAT', 1, 1,   0, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000223, 1821730000000000118, 'sensechat-5',                 'sensechat-5',                         'CHAT', 0, 1,   0, 0, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000202, 1821730000000000101, 'qwen3-max',                   'qwen3-max',                           'CHAT', 0, 2,   1, 1, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000209, 1821730000000000107, 'gpt-4o-mini',                 'gpt-4o-mini',                         'CHAT', 0, 2,   0, 0, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000214, 1821730000000000109, 'moonshot-v1-8k',              'moonshot-v1-8k',                      'CHAT', 0, 2,   0, 0, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000210, 1821730000000000107, 'o3-mini',                     'o3-mini',                             'CHAT', 1, 3,   0, 1, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000204, 1821730000000000103, 'deepseek-v4-flash',           'deepseek-v4-flash',                   'CHAT', 1, 4,   1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000224, 1821730000000000101, 'qwen-vl-plus',                'qwen-vl-plus-latest',                 'CHAT', 0, 5,   1, 0, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2079528066833199104, 1821730000000000103, 'deepseek-v4-pro',             'deepseek-v4-pro',                     'CHAT', 0, 100, 1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2079528360262512640, 1821730000000000101, 'qwen3.5-27b',                 'qwen3.5-27b',                         'CHAT', 0, 100, 1, 0, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2079528360321232896, 1821730000000000101, 'qwen3.5-plus',                'qwen3.5-plus',                        'CHAT', 0, 100, 1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2079536785260306432, 1821730000000000102, 'zai-org/GLM-5.2',             'zai-org/GLM-5.2',                     'CHAT', 0, 100, 1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2079536785319026688, 1821730000000000102, 'deepseek-ai/DeepSeek-V4-Flash', 'deepseek-ai/DeepSeek-V4-Flash',      'CHAT', 0, 100, 1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2079576579659059200, 1821730000000000101, 'qwen3-vl-plus',               'qwen3-vl-plus',                       'CHAT', 0, 100, 1, 1, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2079576579919106048, 1821730000000000101, 'qwen-vl-max',                 'qwen-vl-max',                         'CHAT', 0, 100, 1, 0, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2079944277522014208, 1821730000000000101, 'glm-5.2',                     'glm-5.2',                             'CHAT', 0, 100, 1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2079954231406522368, 1821730000000000101, 'kimi/kimi-k3',                'kimi/kimi-k3',                        'CHAT', 0, 100, 1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2079954287371120640, 1821730000000000101, 'qwen3.7-plus',                'qwen3.7-plus',                        'CHAT', 0, 100, 1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2079954287400480768, 1821730000000000101, 'ZHIPU/GLM-5',                 'ZHIPU/GLM-5',                         'CHAT', 0, 100, 1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),

-- ============ Embedding 模型（15 条） ============
(1831730000000000211, 1821730000000000107, 'text-embedding-3-small',        'text-embedding-3-small',        'EMBEDDING', 0, 1,   0, 0, 0, '[1536]', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2081933747104677888, 1821730000000000101, 'qwen3-vl-embedding',            'Qwen3-VL-Embedding',           'EMBEDDING', 0, 99,  1, 0, 1, '[2048,1536,1024,768,512,256,128,64]', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2079528241484017664, 1821730000000000101, 'qwen3.7-text-embedding',        'qwen3.7-text-embedding',       'EMBEDDING', 0, 100, 1, 0, 0, '[1024,1536]', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2079536402521677824, 1821730000000000102, 'Qwen/Qwen3-Embedding-8B',       'Qwen/Qwen3-Embedding-8B',      'EMBEDDING', 0, 100, 1, 0, 0, '[1024,1536,4096]', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2079536402827862016, 1821730000000000102, 'Qwen/Qwen3-Embedding-4B',       'Qwen/Qwen3-Embedding-4B',      'EMBEDDING', 0, 100, 1, 0, 0, '[1024,1536,4096]', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2079536402861416448, 1821730000000000102, 'Qwen/Qwen3-Embedding-0.6B',     'Qwen/Qwen3-Embedding-0.6B',    'EMBEDDING', 0, 100, 1, 0, 0, '[1024,1536,4096]', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2079985040964366336, 1821730000000000102, 'Qwen/Qwen3-VL-Embedding-8B',    'Qwen/Qwen3-VL-Embedding-8B',   'EMBEDDING', 0, 100, 1, 0, 1, '[1024,1536,4096]', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2079985041002115072, 1821730000000000102, 'Pro/BAAI/bge-m3',               'Pro/BAAI/bge-m3',              'EMBEDDING', 0, 100, 1, 0, 0, '[1024]', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2080317589754445824, 1821730000000000102, 'BAAI/bge-m3',                   'BAAI/bge-m3',                  'EMBEDDING', 0, 100, 1, 0, 0, '[1024]', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2080329083053383680, 1821730000000000106, 'doubao-embedding-large-text-240915', 'doubao-embedding-large-text-240915', 'EMBEDDING', 0, 100, 1, 0, 0, '[1536]', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2080329083158241280, 1821730000000000106, 'doubao-embedding-large-text-250515', 'doubao-embedding-large-text-250515', 'EMBEDDING', 0, 100, 1, 0, 0, '[1536]', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2080340695344721920, 1821730000000000101, 'text-embedding-v4',             'Text-Embedding-V4',            'EMBEDDING', 0, 100, 1, 0, 0, '[2048,1536,1024,768,512,256,128,64]', 'https://llm-nei1m03l1jpqle1c.cn-beijing.maas.aliyuncs.com', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2080575030685585408, 1821730000000000101, 'text-embedding-v3',             'Text-Embedding-V3',            'EMBEDDING', 0, 100, 1, 0, 0, '[1024,768,512,256,128,64]', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),

-- ============ Rerank 模型（3 条） ============
(1831730000000000206, 1821730000000000101, 'qwen3-rerank',                  'qwen3-rerank',                  'RERANK', 1, 1,   1, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2083045145763516416, 1821730000000000102, 'Qwen/Qwen3-VL-Reranker-8B',     'Qwen/Qwen3-VL-Reranker-8B',     'RERANK', 0, 2,   1, 0, 1, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1831730000000000207, 1821730000000000104, 'rerank-noop',                   'noop',                          'RERANK', 0, 100, 0, 0, 0, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (id) DO NOTHING;

-- 说明：text-embedding-v4 配置了自定义端点（部署环境相关），部署后可按需修改或删除。

-- ============================================
-- 默认模型配置（7 个场景）
-- 注意：rerank 与 tool_selector 指向的模型均为后期拉取的雪花 ID 模型，
--       必须与上方 t_ai_model 种子数据配套导入，否则配置失效。
-- ============================================

INSERT INTO t_default_model_config (id, config_key, model_id, create_time, update_time) VALUES
(1851730000000000001, 'chat',          'deepseek-v4-flash',           CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(1851730000000000002, 'summary',       'deepseek-v4-flash',           CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(1851730000000000003, 'title',         'deepseek-v4-flash',           CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(1851730000000000004, 'multimodal',    'qwen3-vl-plus',               CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(1851730000000000005, 'doc_image',     'qwen3.5-27b',                 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(1851730000000000006, 'rerank',        'Qwen/Qwen3-VL-Reranker-8B',   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(1851730000000000007, 'tool_selector', 'Qwen/Qwen3-VL-Embedding-8B',  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- api_protocol 覆盖（依赖 migrate_api_protocol.sql 的列定义）
-- 供应商不设 dashscope，只在模型级覆盖（与生产库状态保持一致）：
--  - qwen3-vl-embedding: 多模态嵌入走 DashScope 原生 API
--  - qwen3-rerank: 重排序走 DashScope 原生 Rerank API
-- ============================================

UPDATE t_ai_model SET api_protocol = 'dashscope'
WHERE model_id = 'qwen3-vl-embedding' AND capability = 'EMBEDDING';

UPDATE t_ai_model SET api_protocol = 'dashscope'
WHERE model_id = 'qwen3-rerank' AND capability = 'RERANK';
