-- PostgreSQL Initial Data for RAGStudio
-- ============================================================
-- 说明：本脚本为「全新部署」种子数据，同步自当前数据库实际状态（2026-08-04）：
--   - 供应商：18 家（仅 4 家启用，其余因未配置 API Key 默认禁用）
--   - 模型：47 条有效（已剔除库中逻辑删除的 18 条旧模型）
--   - 默认模型配置：7 个场景（含 rerank / tool_selector 语义选择）
--   - 知识库：9 个（8 个部门知识库 + 1 个保险系统知识库，真实雪花 ID）+ 45 条有效文档 + 3 个示例问题
--     （已剔除 9 条引用已删除知识库的历史残留文档）
--   - MCP Server：3 个 / 告警配置：1 个 / 通用文档摄入流水线：1 条（4 节点）
-- 安全策略：api_key、MCP headers、SMTP 密码等敏感字段一律为 NULL，部署后需手动配置。
-- 文档状态：全新部署未摄入，统一为 pending + chunk_count=0（生产库为 success，
--           依赖 chunk/向量数据，不能带入）。
-- ============================================================

-- ============================================
-- 默认管理员账号 (UserDO: @TableName="t_user")
-- ============================================


INSERT INTO t_user (id, username, password, avatar, role, deleted, create_time, update_time) VALUES
('2001523723396308993', 'admin', 'admin', 's3://ragstudio/user-img/875940b025b9482da7bd3fee43e7849d.webp', 'admin', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- AI Provider（18 家）
-- 启用状态: bailian / siliconflow / deepseek / volcengine 已启用，
-- 其余 14 家默认禁用（未配置 API Key，避免出现在前端可用模型列表中）
-- ============================================


INSERT INTO t_ai_provider (id, name, display_name, base_url, api_key, endpoints, enabled, icon_url, deleted, api_protocol, create_time, update_time) VALUES
('1821730000000000105', 'zhipu', '智谱 AI', 'https://open.bigmodel.cn', NULL, '{"chat": "/api/paas/v4/chat/completions", "models": "/api/paas/v4/models", "embedding": "/api/paas/v4/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/zhipu.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821730000000000112', 'baidu', '百度千帆 (ERNIE)', 'https://qianfan.baidubce.com', NULL, '{"chat": "/v2/chat/completions", "models": "/v2/models", "embedding": "/v2/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/baidu.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821730000000000110', 'minimax', 'MiniMax (海螺AI)', 'https://api.minimaxi.com', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models", "embedding": "/v1/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/minimax.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821730000000000109', 'moonshot', '月之暗面 (Kimi)', 'https://api.moonshot.cn', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models", "embedding": "/v1/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/moonshot.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821730000000000104', 'noop', 'NOOP (占位)', 'http://localhost', NULL, '{}'::jsonb, 0, NULL, 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821730000000000107', 'openai', 'OpenAI', 'https://api.openai.com', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models", "embedding": "/v1/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/openai.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821730000000000118', 'sensetime', '商汤 (日日新)', 'https://api.sensetime.com', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models", "embedding": "/v1/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/sensetime.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821730000000000117', 'stepfun', '阶跃星辰 (StepFun)', 'https://api.stepfun.com', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models", "embedding": "/v1/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/stepfun.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821730000000000115', 'groq', 'Groq', 'https://api.groq.com', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models"}'::jsonb, 0, 's3://ragstudio/provider-icons/groq.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821730000000000106', 'volcengine', '火山引擎', 'https://ark.cn-beijing.volces.com/api/v3', NULL, '{"models": "/models", "embedding": "/embeddings"}'::jsonb, 1, 's3://ragstudio/provider-icons/volcengine.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821730000000000116', 'mistral', 'Mistral AI', 'https://api.mistral.ai', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models"}'::jsonb, 0, 's3://ragstudio/provider-icons/mistral.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821730000000000108', 'openrouter', 'OpenRouter', 'https://openrouter.ai/api', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models"}'::jsonb, 0, 's3://ragstudio/provider-icons/openrouter.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821730000000000102', 'siliconflow', 'SiliconFlow', 'https://api.siliconflow.cn', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models", "embedding": "/v1/embeddings"}'::jsonb, 1, 's3://ragstudio/provider-icons/siliconflow.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821730000000000101', 'bailian', '百炼 (阿里云)', 'https://dashscope.aliyuncs.com', NULL, '{"chat": "/compatible-mode/v1/chat/completions", "models": "/compatible-mode/v1/models", "rerank": "/api/v1/services/rerank/text-rerank/text-rerank", "embedding": "/compatible-mode/v1/embeddings"}'::jsonb, 1, 's3://ragstudio/provider-icons/bailian.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821730000000000114', 'together', 'Together AI', 'https://api.together.xyz', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models"}'::jsonb, 0, 's3://ragstudio/provider-icons/together.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821730000000000113', 'tencent', '腾讯混元', 'https://api.hunyuan.cloud.tencent.com', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models", "embedding": "/v1/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/tencent.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821730000000000111', 'zeroone', '零一万物 (Yi)', 'https://api.lingyiwanwu.com', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models", "embedding": "/v1/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/zeroone.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821730000000000103', 'deepseek', 'DeepSeek', 'https://api.deepseek.com', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models", "embedding": "/v1/embeddings"}'::jsonb, 1, 's3://ragstudio/provider-icons/deepseek.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- AI Model（47 条有效，已剔除逻辑删除的 18 条）
-- 注意：text-embedding-v4 的 custom_url 为部署环境内网地址，部署后需按实际环境修改或删除。
-- ============================================


INSERT INTO t_ai_model (id, provider_id, model_id, model_name, capability, is_default, priority, enabled, supports_thinking, supports_multimodal, dimension, custom_url, deleted, api_protocol, create_time, update_time) VALUES
('1831730000000000201', '1821730000000000101', 'qwen-plus', 'qwen-plus-latest', 'CHAT', 0, 1, 1, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831730000000000202', '1821730000000000101', 'qwen3-max', 'qwen3-max', 'CHAT', 0, 2, 1, 1, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831730000000000204', '1821730000000000103', 'deepseek-v4-flash', 'deepseek-v4-flash', 'CHAT', 1, 4, 1, 1, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831730000000000224', '1821730000000000101', 'qwen-vl-plus', 'qwen-vl-plus-latest', 'CHAT', 0, 5, 1, 0, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079528360321232896', '1821730000000000101', 'qwen3.5-plus', 'qwen3.5-plus', 'CHAT', 0, 100, 1, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831730000000000220', '1821730000000000115', 'groq-llama', 'llama-3.3-70b-versatile', 'CHAT', 1, 1, 0, 0, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831730000000000215', '1821730000000000110', 'minimax-text-01', 'MiniMax-Text-01', 'CHAT', 1, 1, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831730000000000221', '1821730000000000116', 'mistral-large', 'mistral-large-latest', 'CHAT', 1, 1, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831730000000000213', '1821730000000000109', 'kimi-k2', 'kimi-k2', 'CHAT', 1, 1, 0, 1, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831730000000000214', '1821730000000000109', 'moonshot-v1-8k', 'moonshot-v1-8k', 'CHAT', 0, 2, 0, 0, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831730000000000207', '1821730000000000104', 'rerank-noop', 'noop', 'RERANK', 0, 100, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831730000000000208', '1821730000000000107', 'gpt-4o', 'gpt-4o', 'CHAT', 0, 1, 0, 0, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831730000000000209', '1821730000000000107', 'gpt-4o-mini', 'gpt-4o-mini', 'CHAT', 0, 2, 0, 0, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831730000000000210', '1821730000000000107', 'o3-mini', 'o3-mini', 'CHAT', 1, 3, 0, 1, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831730000000000211', '1821730000000000107', 'text-embedding-3-small', 'text-embedding-3-small', 'EMBEDDING', 0, 1, 0, 0, 0, '[1536]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831730000000000212', '1821730000000000108', 'openrouter-auto', 'openrouter/auto', 'CHAT', 1, 1, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831730000000000223', '1821730000000000118', 'sensechat-5', 'sensechat-5', 'CHAT', 0, 1, 0, 0, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831730000000000217', '1821730000000000112', 'ernie-4.0', 'ernie-4.0', 'CHAT', 1, 1, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831730000000000222', '1821730000000000117', 'step-2', 'step-2-16k', 'CHAT', 1, 1, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831730000000000218', '1821730000000000113', 'hunyuan-standard', 'hunyuan-standard', 'CHAT', 1, 1, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831730000000000219', '1821730000000000114', 'together-mix', 'mistralai/Mixtral-8x22B-Instruct-v0.1', 'CHAT', 1, 1, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831730000000000216', '1821730000000000111', 'yi-lightning', 'yi-lightning', 'CHAT', 1, 1, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079528066833199104', '1821730000000000103', 'deepseek-v4-pro', 'deepseek-v4-pro', 'CHAT', 0, 100, 1, 1, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079528360262512640', '1821730000000000101', 'qwen3.5-27b', 'qwen3.5-27b', 'CHAT', 0, 100, 1, 0, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079536785260306432', '1821730000000000102', 'zai-org/GLM-5.2', 'zai-org/GLM-5.2', 'CHAT', 0, 100, 1, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079536785319026688', '1821730000000000102', 'deepseek-ai/DeepSeek-V4-Flash', 'deepseek-ai/DeepSeek-V4-Flash', 'CHAT', 0, 100, 1, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079536402521677824', '1821730000000000102', 'Qwen/Qwen3-Embedding-8B', 'Qwen/Qwen3-Embedding-8B', 'EMBEDDING', 0, 100, 1, 0, 0, '[1024,1536,4096]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079536402827862016', '1821730000000000102', 'Qwen/Qwen3-Embedding-4B', 'Qwen/Qwen3-Embedding-4B', 'EMBEDDING', 0, 100, 1, 0, 0, '[1024,1536,4096]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079536402861416448', '1821730000000000102', 'Qwen/Qwen3-Embedding-0.6B', 'Qwen/Qwen3-Embedding-0.6B', 'EMBEDDING', 0, 100, 1, 0, 0, '[1024,1536,4096]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079528241484017664', '1821730000000000101', 'qwen3.7-text-embedding', 'qwen3.7-text-embedding', 'EMBEDDING', 0, 100, 1, 0, 0, '[1024,1536]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831730000000000206', '1821730000000000101', 'qwen3-rerank', 'Qwen3-Rerank', 'RERANK', 1, 1, 1, 0, 0, NULL, NULL, 0, 'dashscope', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079576579919106048', '1821730000000000101', 'qwen-vl-max', 'qwen-vl-max', 'CHAT', 0, 100, 1, 0, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079576579659059200', '1821730000000000101', 'qwen3-vl-plus', 'qwen3-vl-plus', 'CHAT', 0, 100, 1, 1, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079944277522014208', '1821730000000000101', 'glm-5.2', 'glm-5.2', 'CHAT', 0, 100, 1, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079954231406522368', '1821730000000000101', 'kimi/kimi-k3', 'kimi/kimi-k3', 'CHAT', 0, 100, 1, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079954287371120640', '1821730000000000101', 'qwen3.7-plus', 'qwen3.7-plus', 'CHAT', 0, 100, 1, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079954287400480768', '1821730000000000101', 'ZHIPU/GLM-5', 'ZHIPU/GLM-5', 'CHAT', 0, 100, 1, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079985040964366336', '1821730000000000102', 'Qwen/Qwen3-VL-Embedding-8B', 'Qwen/Qwen3-VL-Embedding-8B', 'EMBEDDING', 0, 100, 1, 0, 1, '[1024,1536,4096]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079985041002115072', '1821730000000000102', 'Pro/BAAI/bge-m3', 'Pro/BAAI/bge-m3', 'EMBEDDING', 0, 100, 1, 0, 0, '[1024]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2080317589754445824', '1821730000000000102', 'BAAI/bge-m3', 'BAAI/bge-m3', 'EMBEDDING', 0, 100, 1, 0, 0, '[1024]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2080329083053383680', '1821730000000000106', 'doubao-embedding-large-text-240915', 'doubao-embedding-large-text-240915', 'EMBEDDING', 0, 100, 1, 0, 0, '[1536]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2080329083158241280', '1821730000000000106', 'doubao-embedding-large-text-250515', 'doubao-embedding-large-text-250515', 'EMBEDDING', 0, 100, 1, 0, 0, '[1536]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2080575030685585408', '1821730000000000101', 'text-embedding-v3', 'Text-Embedding-V3', 'EMBEDDING', 0, 100, 1, 0, 0, '[1024,768,512,256,128,64]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2081933747104677888', '1821730000000000101', 'qwen3-vl-embedding', 'Qwen3-VL-Embedding', 'EMBEDDING', 0, 99, 1, 0, 1, '[2048,1536,1024,768,512,256,128,64]', NULL, 0, 'dashscope', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2080340695344721920', '1821730000000000101', 'text-embedding-v4', 'Text-Embedding-V4', 'EMBEDDING', 0, 100, 1, 0, 0, '[2048,1536,1024,768,512,256,128,64]', 'https://llm-nei1m03l1jpqle1c.cn-beijing.maas.aliyuncs.com', 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2083045145763516416', '1821730000000000102', 'Qwen/Qwen3-VL-Reranker-8B', 'Qwen/Qwen3-VL-Reranker-8B', 'RERANK', 0, 2, 1, 0, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2083210690620817408', '1821730000000000101', 'qwen3-vl-rerank', 'Qwen3-VL-Rerank', 'RERANK', 0, 100, 1, 0, 1, NULL, NULL, 0, 'dashscope', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- 默认模型配置（7 个场景，指向当前库实际使用的模型）
-- ============================================


INSERT INTO t_default_model_config (id, config_key, model_id, create_time, update_time) VALUES
('1851730000000000001', 'chat', 'deepseek-v4-flash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1851730000000000002', 'summary', 'deepseek-v4-flash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1851730000000000003', 'title', 'deepseek-v4-flash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1851730000000000005', 'doc_image', 'qwen3.5-27b', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1851730000000000004', 'multimodal', 'qwen3-vl-plus', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2080317483776966656', 'tool_selector', 'qwen3-vl-embedding', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1851730000000000006', 'rerank', 'qwen3-vl-rerank', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- 知识库（9 个：8 个部门知识库 + 保险系统知识库，真实雪花 ID）
-- ============================================


INSERT INTO t_knowledge_base (id, name, description, embedding_provider, embedding_model, dimension, collection_name, created_by, updated_by, supports_image_embedding, create_time, update_time) VALUES
('2079550081669562368', 'HR部门知识库', 'HR部门的知识库，包括人事制度、员工培训、招聘信息、薪资与福利政策等等。', 'siliconflow', 'Qwen/Qwen3-Embedding-8B', 1536, 'hr-group-document', 'admin', 'admin', '0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079555154697031680', 'IT部门知识库', 'IT部门的知识库，包括IT支持、信息系统权限管理制度、数据库管理规范、部署运维手册、网络安全管理制度等。', 'siliconflow', 'Qwen/Qwen3-Embedding-8B', 1536, 'it-group-document', 'admin', 'admin', '0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079555656021217280', '开票信息库', '存储开票信息的知识库，当用户查询开票信息、纳税人识别号等都需要优先检索此知识库。', 'siliconflow', 'Qwen/Qwen3-Embedding-8B', 1536, 'invoice-document', 'admin', 'admin', '0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079556280884432896', '财务部门知识库', '财务部门的知识库，包括预算管理制度、合同管理制度、员工报销管理制度及流程、印章管理制度等。', 'siliconflow', 'Qwen/Qwen3-Embedding-8B', 1536, 'finance-group-document', 'admin', 'admin', '0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079556733579857920', 'OA系统知识库', '这是OA系统的知识库，包括OA系统数据安全规范、销售管理制度、OA系统使用指南、办公用品申领指南等。', 'siliconflow', 'Qwen/Qwen3-Embedding-8B', 1536, 'oa-system-document', 'admin', 'admin', '0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2082812310922301440', '战略发展部门知识库', '战略发展部门的知识库，包括战略经营分析报告，战略规划白皮书，战略投资评估报告等等。', 'bailian', 'qwen3-vl-embedding', 1024, 'strategy-group-document', 'admin', 'admin', '1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2082802790422646784', '市场部知识库', '市场部知识库，包括品牌传播策略，市场营销战略，数字营销执行手册，竞品分析报告等等。', 'bailian', 'text-embedding-v4', 1536, 'market-group-document', 'admin', 'system-fix', '0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2082811647886725120', '研发部门知识库', '研发部门的知识库，包括RAGStudio的API接口文档，RAGStudio的后端规范，RAGStudio的系统架构设计等等。', 'bailian', 'text-embedding-v4', 1536, 'rd-group-document', 'admin', 'system-fix', '0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2085190809367523328', '保险系统知识库', '保险系统的知识库，包括保险业务产品手册（企业财产险、公众责任险、团体意外险、旅行意外险）、互联网保险系统数据安全规范等。', 'bailian', 'qwen3.7-text-embedding', 1536, 'ins-system-document', 'admin', 'admin', '0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- 知识库文档（45 条，仅归属有效知识库）
-- 说明：已剔除 9 条引用已删除知识库的历史残留文档；
--       状态统一为 pending 且 chunk_count=0（全新部署未摄入，需手动触发分块），
--       而非生产库的 success 状态（success 依赖已导入的 chunk 与向量数据）。
-- ============================================


INSERT INTO t_knowledge_document (id, kb_id, doc_name, source_type, source_location, schedule_enabled, schedule_cron, enabled, chunk_count, file_url, file_type, file_size, process_mode, chunk_strategy, chunk_config, pipeline_id, status, created_by, updated_by, create_time, update_time) VALUES
('2083121814394281984', '2079550081669562368', '公司规章制度.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/hr-group-document/cddda97a30c148e89028281ec577345e.md', 'markdown', 8513, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2083121815275085824', '2079550081669562368', '人事制度.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/hr-group-document/32bd884d20504bbead32c25edb672ce7.md', 'markdown', 21909, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2082802998145552384', '2082802790422646784', '竞品分析报告.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/market-group-document/5d4c7a1329c24457879411359f25ec10.md', 'markdown', 6008, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2083121816227192832', '2079550081669562368', 'employee-attendance.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/hr-group-document/b1ce919a9061454e9311c3246bd476fd.md', 'markdown', 2147, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2083121816336244736', '2079550081669562368', 'performance-review.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/hr-group-document/bb20bb93cac54a38a5c93552b5607250.md', 'markdown', 1257, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2083121816432713728', '2079550081669562368', 'recruitment-policy.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/hr-group-document/325e5c7fec0a47d08b8507d15eeae218.md', 'markdown', 2043, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2082802998258798592', '2082802790422646784', '品牌传播策略.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/market-group-document/ffe72a4f1cc7491982fa012cff511907.md', 'markdown', 6064, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2083121815648378880', '2079550081669562368', '员工培训.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/hr-group-document/d089863a85014611a0e10ab5cfe0844d.md', 'markdown', 9777, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2083121815744847872', '2079550081669562368', '招聘信息.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/hr-group-document/1eb1b65730864065bc5092aa22b6a9d1.md', 'markdown', 12501, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2083121816529182720', '2079550081669562368', 'training-policy.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/hr-group-document/e6276836e7174d968eda54e8dd0c0d36.md', 'markdown', 1331, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2083121815837122560', '2079550081669562368', 'asset-management.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/hr-group-document/8f046de4d8264bffbd384e68313bc416.md', 'markdown', 1730, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2083121815933591552', '2079550081669562368', 'compensation-policy.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/hr-group-document/f74cc7f0083f44cc9563dc627fa7978e.md', 'markdown', 1574, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2083121816034254848', '2079550081669562368', 'confidentiality-agreement.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/hr-group-document/b1a4c00dd1e744a380a9fb6a37527a11.md', 'markdown', 1516, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2083121815543521280', '2079550081669562368', '薪资与福利政策.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/hr-group-document/8d4862b16f51448fa19aa31ffa91ade5.md', 'markdown', 9609, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2083121816134918144', '2079550081669562368', 'discipline-policy.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/hr-group-document/2fe26113cce44b9bbe94d4692c28c407.md', 'markdown', 1642, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2082802998363656192', '2082802790422646784', '市场营销年度规划.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/market-group-document/6e8838fabd86431b9676b4a3f7b30bbc.md', 'markdown', 4954, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2082802998472708096', '2082802790422646784', '数字营销执行手册.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/market-group-document/8c8bf38f3d324e4ba5da34d339912b72.md', 'markdown', 6869, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079555251073748992', '2079555154697031680', 'db-standards.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/it-group-document/896ae45d7de04d71a23376d6fe4bb077.md', 'markdown', 1730, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079555251178606592', '2079555154697031680', 'deployment-guide.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/it-group-document/ebbddfbbc0694d13a9dcd910195925d5.md', 'markdown', 1761, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079555250931142656', '2079555154697031680', 'access-control.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/it-group-document/fbdc9ada2fe24fc9bbbc3e3de89ab572.md', 'markdown', 1796, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2082811801486331904', '2082811647886725120', '后端开发规范.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/rd-group-document/4c1cce673c124afca439bd4c52699d10.md', 'markdown', 7289, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2082812391020924928', '2082812310922301440', '行业发展趋势与机会洞察报告.docx', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/strategy-group-document/c8589f78d57a44b9abb94ee22ee4bb34.docx', 'docx', 174738, 'chunk', 'fixed_size', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2082811801742184448', '2082811647886725120', 'API接口文档.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/rd-group-document/a0823ec746134402a30b8a9e9f8077fe.md', 'markdown', 7276, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079555251275075584', '2079555154697031680', 'dev-standards.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/it-group-document/1d1ef83e610b4145ad4380355082fa96.md', 'markdown', 1596, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2082812390681186304', '2082812310922301440', '2026年H1战略经营分析报告.pdf', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/strategy-group-document/e5c0ca8ab9df48ff815054e44b2df46a.pdf', 'pdf', 329958, 'chunk', 'fixed_size', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079556341278216192', '2079556280884432896', 'seal-management.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/finance-group-document/20ddfa8083a74f009855cbfa3a4936c2.md', 'markdown', 11590, 'pipeline', NULL, NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2082811801641521152', '2082811647886725120', '系统架构设计.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/rd-group-document/ac199186cbc64692bd668bb3c16eab12.md', 'markdown', 10892, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079555251493179392', '2079555154697031680', 'IT支持.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/it-group-document/06808ce5ed34415286ff3c52e96b654f.md', 'markdown', 28879, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079555251388321792', '2079555154697031680', 'it-support.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/it-group-document/9d364cf419e545428bfa89c3bc336ad6.md', 'markdown', 2422, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079556341064306688', '2079556280884432896', 'contract-management.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/finance-group-document/be3d478d69f044f9834efb348aa5420d.md', 'markdown', 12521, 'pipeline', NULL, NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079556340930088960', '2079556280884432896', 'budget-management.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/finance-group-document/d2ee1b6d9d934481963bbca822bb07a6.md', 'markdown', 17475, 'pipeline', NULL, NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2082812390567940096', '2082812310922301440', '2026年度战略规划白皮书.pdf', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/strategy-group-document/993739c47e6d4e00a7668128d5b15e22.pdf', 'pdf', 159061, 'chunk', 'fixed_size', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079556797425553408', '2079556733579857920', 'oa-guide.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/oa-system-document/7874583e5780404b8212d2570efec019.md', 'markdown', 2453, 'pipeline', NULL, NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079556341395656704', '2079556280884432896', 'tax-policy.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/finance-group-document/4a49bd065df74953899c160bff9e513b.md', 'markdown', 13740, 'pipeline', NULL, NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2082812391134171136', '2082812310922301440', '战略投资评估报告.docx', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/strategy-group-document/96d7986469fd4045bcca049ed93c1dde.docx', 'docx', 300147, 'chunk', 'fixed_size', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079556341177552896', '2079556280884432896', 'expense-policy.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/finance-group-document/69f8d1bfafba4155b83ade9e7347724c.md', 'markdown', 15946, 'pipeline', NULL, NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079556797522022400', '2079556733579857920', 'OA系统数据安全规范文档.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/oa-system-document/cdf27135ddba458e84c6dc8a37a99dde.md', 'markdown', 13161, 'pipeline', NULL, NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079555251686117376', '2079555154697031680', 'security-incident-response.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/it-group-document/f5eec088578e4cb0a53fe5c99e0b8b19.md', 'markdown', 1993, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079555251598036992', '2079555154697031680', 'network-security.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/it-group-document/b7ba6d9d425d4792923a1b1d34902652.md', 'markdown', 2174, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079556797173895168', '2079556733579857920', 'business-hospitality.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/oa-system-document/9396a0c1db6d4b329e978fcb4b93ff23.md', 'markdown', 1197, 'pipeline', NULL, NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079555744164515840', '2079555656021217280', '开票信息.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/invoice-document/8fcb98ca7fc04f5ab345b5045f97272d.md', 'markdown', 5148, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079556797308112896', '2079556733579857920', 'customer-service.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/oa-system-document/9fbabafede0a485d9a2d98d475da6d13.md', 'markdown', 1498, 'pipeline', NULL, NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079556797635268608', '2079556733579857920', 'sales-management.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/oa-system-document/716363018c4e4827b1afe48fedec666b.md', 'markdown', 1320, 'pipeline', NULL, NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2085190963185233920', '2085190809367523328', 'insurance-products.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/ins-system-document/e82098e0c79e4b4e92c589531cf2a451.md', 'markdown', 2800, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2085190962916798464', '2085190809367523328', '互联网保险系统数据安全规范.md', 'file', NULL, 0, NULL, 1, 0, 's3://ragstudio/document/ins-system-document/bcd3b44b16ab4172b5bbffea63b69fde.md', 'markdown', 15984, 'chunk', 'structure_aware', NULL, NULL, 'pending', 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- 示例问题（3 条）
-- ============================================


INSERT INTO t_sample_question (id, title, description, question, deleted, create_time, update_time) VALUES
('2079548151316643840', '天气查询', '查询用户本地今日天气', '今天天气怎么样？', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079548278735405056', '知识库查询', '查询知识库示例', '公司的薪资福利待遇怎么样？', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079548451087745024', 'SKILL示例', 'SKILL使用示例', '最近有什么值得关注的大事件或新闻吗？', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- 告警配置（1 条，SMTP 密码部署后手动配置）
-- ============================================


INSERT INTO t_alert_config (id, enabled, smtp_host, smtp_port, smtp_username, smtp_password, from_address, to_address, time_window_hours, failure_threshold, deleted, create_time, update_time) VALUES
('default', 1, 'smtp.qq.com', 465, '1481433353@qq.com', NULL, 'RAGStudio <1481433353@qq.com>', '3357841161@qq.com', 5, 3, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- MCP Server（3 个，headers 含认证信息，部署后手动配置）
-- ============================================


INSERT INTO t_mcp_server (id, name, url, description, enabled, transport_type, headers, last_status, last_error, created_by, updated_by, deleted, create_time, last_check_time, update_time) VALUES
('2079547856670982144', '千问-图像生成', 'https://dashscope.aliyuncs.com/api/v1/mcps/QwenImage/mcp', '阿里云百炼官方图像生成 MCP 服务，基于千问系列图像生成模型封装，包括文生图、图像编辑工具，按模型调用量计费。', 1, 'streamable_http', NULL, 'connected', 'Client failed to initialize by explicit API call', 'admin', 'admin', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079547705776701440', '天气预报', 'https://dashscope.aliyuncs.com/api/v1/mcps/market-cmapi033617/mcp', '天气预报查询是万维易源提供的一个通过输入坐标、IP、地名、区号/邮编、景点名称，查询天气情况（天气状况包括：湿度、天气图标、当前温度、风向、风级、紫外线、穿衣指南、空气指数）等信息。可查询到当前天气、未来24小时、7天、15天、40天内天气预报和过往的历史天气情况，通过 MCP 工具获取所需服务。', 1, 'streamable_http', NULL, 'connected', 'Client failed to initialize by explicit API call', 'admin', 'admin', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079547963990638592', '万相-图像生成', 'https://dashscope.aliyuncs.com/api/v1/mcps/WanImage/mcp', '阿里云百炼官方图像生成 MCP 服务，基于万相系列图像生成模型封装，包括文生图、图像编辑、风格迁移等工具，按模型调用量计费。', 1, 'streamable_http', NULL, 'connected', 'Client failed to initialize by explicit API call', 'admin', 'admin', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- 通用文档摄入流水线（1 条 + 4 节点）
-- ============================================


INSERT INTO t_ingestion_pipeline (id, name, description, created_by, updated_by, deleted, create_time, update_time) VALUES
('2079548908929581056', '通用文档通道', '适用于大部分的简单文档，例如MarkDown文档，HTML文档等', 'admin', 'admin', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;



INSERT INTO t_ingestion_pipeline_node (id, pipeline_id, node_id, node_type, next_node_id, settings_json, condition_json, created_by, updated_by, deleted, create_time, update_time) VALUES
('2079548908988301312', '2079548908929581056', 'step_1', 'fetcher', 'step_2', NULL, NULL, 'admin', 'admin', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079548909017661440', '2079548908929581056', 'step_2', 'parser', 'step_3', '{"rules": [{"mimeType": "text/plain"}, {"mimeType": "text/markdown"}, {"mimeType": "text/html"}]}'::jsonb, NULL, 'admin', 'admin', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079548909021855744', '2079548908929581056', 'step_3', 'chunker', 'step_4', '{"strategy": "structure_aware", "chunkSize": 512, "overlapSize": 18}'::jsonb, NULL, 'admin', 'admin', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079548909026050048', '2079548908929581056', 'step_4', 'indexer', NULL, '{"embeddingModel": "Qwen/Qwen3-Embedding-8B"}'::jsonb, NULL, 'admin', 'admin', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;
