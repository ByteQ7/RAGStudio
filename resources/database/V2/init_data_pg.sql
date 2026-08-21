-- PostgreSQL Initial Data for RAGStudio
-- ============================================================
-- 说明：本脚本为「全新部署」种子数据。
--   - 供应商：17 家（全部默认禁用，api_key 为 NULL，需在管理后台配置密钥后手动启用）
--   - 模型：46 条（全部默认禁用，与供应商一致：未配置 API Key 前不可用）
--   - 默认模型配置：7 个场景（chat / summary / title / doc_image / multimodal / tool_selector / rerank，
--     指向默认模型，管理员启用对应模型后生效）
--   - 知识库/文档：不预置（Embedding 模型未启用，无法摄入文档）
--   - 示例问题：3 条
--   - MCP Server：3 个（全部默认禁用，headers 含认证信息，需配置后手动启用）
--   - 告警配置：1 条（默认禁用，SMTP 为示例占位值，需配置后手动启用）
--   - 通用文档摄入流水线：1 条（4 节点）
-- 安全策略：api_key、MCP headers、SMTP 密码等敏感字段一律为 NULL，部署后需手动配置。
-- ============================================================

-- ============================================
-- 默认管理员账号 (UserDO: @TableName="t_user")
-- ============================================


INSERT INTO t_user (id, username, password, avatar, role, deleted, create_time, update_time) VALUES
('2001523723396308993', 'admin', 'admin', NULL, 'admin', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- AI Provider（18 家）
-- 全部默认禁用（api_key 均为 NULL，需在管理后台配置密钥后手动启用）
-- ============================================


INSERT INTO t_ai_provider (id, name, display_name, base_url, api_key, endpoints, enabled, icon_url, deleted, api_protocol, create_time, update_time) VALUES
('1821609200841654272', 'zhipu', '智谱 AI', 'https://open.bigmodel.cn', NULL, '{"chat": "/api/paas/v4/chat/completions", "models": "/api/paas/v4/models", "embedding": "/api/paas/v4/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/zhipu.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821609200845848576', 'baidu', '百度千帆 (ERNIE)', 'https://qianfan.baidubce.com', NULL, '{"chat": "/v2/chat/completions", "models": "/v2/models", "embedding": "/v2/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/baidu.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821609200850042880', 'minimax', 'MiniMax (海螺AI)', 'https://api.minimaxi.com', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models", "embedding": "/v1/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/minimax.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821609200854237184', 'moonshot', '月之暗面 (Kimi)', 'https://api.moonshot.cn', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models", "embedding": "/v1/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/moonshot.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821609200862625792', 'openai', 'OpenAI', 'https://api.openai.com', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models", "embedding": "/v1/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/openai.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821609200866820096', 'sensetime', '商汤 (日日新)', 'https://api.sensetime.com', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models", "embedding": "/v1/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/sensetime.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821609200871014400', 'stepfun', '阶跃星辰 (StepFun)', 'https://api.stepfun.com', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models", "embedding": "/v1/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/stepfun.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821609200875208704', 'groq', 'Groq', 'https://api.groq.com', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models"}'::jsonb, 0, 's3://ragstudio/provider-icons/groq.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821609200879403008', 'volcengine', '火山引擎', 'https://ark.cn-beijing.volces.com/api/v3', NULL, '{"chat": "/chat/completions", "models": "/models", "embedding": "/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/volcengine.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821609200883597312', 'mistral', 'Mistral AI', 'https://api.mistral.ai', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models"}'::jsonb, 0, 's3://ragstudio/provider-icons/mistral.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821609200887791616', 'openrouter', 'OpenRouter', 'https://openrouter.ai/api', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models"}'::jsonb, 0, 's3://ragstudio/provider-icons/openrouter.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821609200891985920', 'siliconflow', 'SiliconFlow', 'https://api.siliconflow.cn', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models", "embedding": "/v1/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/siliconflow.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821609200896180224', 'bailian', '百炼 (阿里云)', 'https://dashscope.aliyuncs.com', NULL, '{"chat": "/compatible-mode/v1/chat/completions", "models": "/compatible-mode/v1/models", "rerank": "/api/v1/services/rerank/text-rerank/text-rerank", "embedding": "/compatible-mode/v1/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/bailian.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821609200900374528', 'together', 'Together AI', 'https://api.together.xyz', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models"}'::jsonb, 0, 's3://ragstudio/provider-icons/together.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821609200904568832', 'tencent', '腾讯混元', 'https://api.hunyuan.cloud.tencent.com', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models", "embedding": "/v1/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/tencent.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821609200908763136', 'zeroone', '零一万物 (Yi)', 'https://api.lingyiwanwu.com', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models", "embedding": "/v1/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/zeroone.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821609200912957440', 'deepseek', 'DeepSeek', 'https://api.deepseek.com', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models", "embedding": "/v1/embeddings"}'::jsonb, 0, 's3://ragstudio/provider-icons/deepseek.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821609200917151744', 'anthropic', 'Anthropic (Claude)', 'https://api.anthropic.com', NULL, '{"chat": "/v1/messages", "models": "/v1/models"}'::jsonb, 0, 's3://ragstudio/provider-icons/anthropic.svg', 0, 'anthropic', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- AI Model（47 条）
-- 全部默认禁用（供应商均未配置 API Key，启用前不可用）；is_default 统一置 0
-- ============================================


INSERT INTO t_ai_model (id, provider_id, model_id, model_name, capability, is_default, priority, enabled, supports_thinking, supports_multimodal, dimension, custom_url, deleted, api_protocol, create_time, update_time) VALUES
('1831609201718198272', '1821609200896180224', 'qwen-plus', 'qwen-plus-latest', 'CHAT', 0, 1, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201722392576', '1821609200896180224', 'qwen3-max', 'qwen3-max', 'CHAT', 0, 2, 0, 1, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201726586880', '1821609200912957440', 'deepseek-v4-flash', 'deepseek-v4-flash', 'CHAT', 0, 4, 0, 1, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201730781184', '1821609200896180224', 'qwen-vl-plus', 'qwen-vl-plus-latest', 'CHAT', 0, 5, 0, 0, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079528360321232896', '1821609200896180224', 'qwen3.5-plus', 'qwen3.5-plus', 'CHAT', 0, 100, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201734975488', '1821609200875208704', 'groq-llama', 'llama-3.3-70b-versatile', 'CHAT', 0, 1, 0, 0, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201739169792', '1821609200850042880', 'minimax-text-01', 'MiniMax-Text-01', 'CHAT', 0, 1, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201743364096', '1821609200883597312', 'mistral-large', 'mistral-large-latest', 'CHAT', 0, 1, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201747558400', '1821609200854237184', 'kimi-k2', 'kimi-k2', 'CHAT', 0, 1, 0, 1, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201751752704', '1821609200854237184', 'moonshot-v1-8k', 'moonshot-v1-8k', 'CHAT', 0, 2, 0, 0, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201760141312', '1821609200862625792', 'gpt-4o', 'gpt-4o', 'CHAT', 0, 1, 0, 0, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201764335616', '1821609200862625792', 'gpt-4o-mini', 'gpt-4o-mini', 'CHAT', 0, 2, 0, 0, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201768529920', '1821609200862625792', 'o3-mini', 'o3-mini', 'CHAT', 0, 3, 0, 1, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201772724224', '1821609200862625792', 'text-embedding-3-small', 'text-embedding-3-small', 'EMBEDDING', 0, 1, 0, 0, 0, '[1536]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201776918528', '1821609200887791616', 'openrouter-auto', 'openrouter/auto', 'CHAT', 0, 1, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201781112832', '1821609200866820096', 'sensechat-5', 'sensechat-5', 'CHAT', 0, 1, 0, 0, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201785307136', '1821609200845848576', 'ernie-4.0', 'ernie-4.0', 'CHAT', 0, 1, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201789501440', '1821609200871014400', 'step-2', 'step-2-16k', 'CHAT', 0, 1, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201793695744', '1821609200904568832', 'hunyuan-standard', 'hunyuan-standard', 'CHAT', 0, 1, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201797890048', '1821609200900374528', 'together-mix', 'mistralai/Mixtral-8x22B-Instruct-v0.1', 'CHAT', 0, 1, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201802084352', '1821609200908763136', 'yi-lightning', 'yi-lightning', 'CHAT', 0, 1, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079528066833199104', '1821609200912957440', 'deepseek-v4-pro', 'deepseek-v4-pro', 'CHAT', 0, 100, 0, 1, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079528360262512640', '1821609200896180224', 'qwen3.5-27b', 'qwen3.5-27b', 'CHAT', 0, 100, 0, 0, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079536785260306432', '1821609200891985920', 'zai-org/GLM-5.2', 'zai-org/GLM-5.2', 'CHAT', 0, 100, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079536785319026688', '1821609200891985920', 'deepseek-ai/DeepSeek-V4-Flash', 'deepseek-ai/DeepSeek-V4-Flash', 'CHAT', 0, 100, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079536402521677824', '1821609200891985920', 'Qwen/Qwen3-Embedding-8B', 'Qwen/Qwen3-Embedding-8B', 'EMBEDDING', 0, 100, 0, 0, 0, '[1024,1536,4096]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079536402827862016', '1821609200891985920', 'Qwen/Qwen3-Embedding-4B', 'Qwen/Qwen3-Embedding-4B', 'EMBEDDING', 0, 100, 0, 0, 0, '[1024,1536,4096]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079536402861416448', '1821609200891985920', 'Qwen/Qwen3-Embedding-0.6B', 'Qwen/Qwen3-Embedding-0.6B', 'EMBEDDING', 0, 100, 0, 0, 0, '[1024,1536,4096]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079528241484017664', '1821609200896180224', 'qwen3.7-text-embedding', 'qwen3.7-text-embedding', 'EMBEDDING', 0, 100, 0, 0, 0, '[1024,1536]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201806278656', '1821609200896180224', 'qwen3-rerank', 'Qwen3-Rerank', 'RERANK', 0, 1, 0, 0, 0, NULL, NULL, 0, 'dashscope', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079576579919106048', '1821609200896180224', 'qwen-vl-max', 'qwen-vl-max', 'CHAT', 0, 100, 0, 0, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079576579659059200', '1821609200896180224', 'qwen3-vl-plus', 'qwen3-vl-plus', 'CHAT', 0, 100, 0, 1, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079944277522014208', '1821609200896180224', 'glm-5.2', 'glm-5.2', 'CHAT', 0, 100, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079954231406522368', '1821609200896180224', 'kimi/kimi-k3', 'kimi/kimi-k3', 'CHAT', 0, 100, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079954287371120640', '1821609200896180224', 'qwen3.7-plus', 'qwen3.7-plus', 'CHAT', 0, 100, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079954287400480768', '1821609200896180224', 'ZHIPU/GLM-5', 'ZHIPU/GLM-5', 'CHAT', 0, 100, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079985040964366336', '1821609200891985920', 'Qwen/Qwen3-VL-Embedding-8B', 'Qwen/Qwen3-VL-Embedding-8B', 'EMBEDDING', 0, 100, 0, 0, 1, '[1024,1536,4096]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079985041002115072', '1821609200891985920', 'Pro/BAAI/bge-m3', 'Pro/BAAI/bge-m3', 'EMBEDDING', 0, 100, 0, 0, 0, '[1024]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2080317589754445824', '1821609200891985920', 'BAAI/bge-m3', 'BAAI/bge-m3', 'EMBEDDING', 0, 100, 0, 0, 0, '[1024]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2080329083053383680', '1821609200879403008', 'doubao-embedding-large-text-240915', 'doubao-embedding-large-text-240915', 'EMBEDDING', 0, 100, 0, 0, 0, '[1536]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2080329083158241280', '1821609200879403008', 'doubao-embedding-large-text-250515', 'doubao-embedding-large-text-250515', 'EMBEDDING', 0, 100, 0, 0, 0, '[1536]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2080575030685585408', '1821609200896180224', 'text-embedding-v3', 'Text-Embedding-V3', 'EMBEDDING', 0, 100, 0, 0, 0, '[1024,768,512,256,128,64]', NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2081933747104677888', '1821609200896180224', 'qwen3-vl-embedding', 'Qwen3-VL-Embedding', 'EMBEDDING', 0, 99, 0, 0, 1, '[2048,1536,1024,768,512,256,128,64]', NULL, 0, 'dashscope', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2080340695344721920', '1821609200896180224', 'text-embedding-v4', 'Text-Embedding-V4', 'EMBEDDING', 0, 100, 0, 0, 0, '[2048,1536,1024,768,512,256,128,64]', 'https://llm-nei1m03l1jpqle1c.cn-beijing.maas.aliyuncs.com', 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2083045145763516416', '1821609200891985920', 'Qwen/Qwen3-VL-Reranker-8B', 'Qwen/Qwen3-VL-Reranker-8B', 'RERANK', 0, 2, 0, 0, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2083210690620817408', '1821609200896180224', 'qwen3-vl-rerank', 'Qwen3-VL-Rerank', 'RERANK', 0, 100, 0, 0, 1, NULL, NULL, 0, 'dashscope', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201722392640', '1821609200917151744', 'claude-sonnet-4-5', 'claude-sonnet-4-5', 'CHAT', 0, 1, 0, 1, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201722392641', '1821609200917151744', 'claude-opus-4-1', 'claude-opus-4-1', 'CHAT', 0, 2, 0, 1, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- 默认模型配置（7 个场景，指向当前库实际使用的模型）
-- ============================================


INSERT INTO t_default_model_config (id, config_key, model_id, create_time, update_time) VALUES
('1851609203471286272', 'chat', 'deepseek-v4-flash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1851609203475480576', 'summary', 'deepseek-v4-flash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1851609203479674880', 'title', 'deepseek-v4-flash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1851609203483869184', 'doc_image', 'qwen3.5-27b', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1851609203488063488', 'multimodal', 'qwen3-vl-plus', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2080317483776966656', 'tool_selector', 'qwen3-vl-embedding', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1851609203492257792', 'rerank', 'qwen3-vl-rerank', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- 知识库：不预置（Embedding 模型未启用，无法摄入文档，
-- 由管理员配置供应商 API Key 并启用 Embedding 模型后自行创建）
-- ============================================

-- ============================================
-- 示例问题（3 条）
-- ============================================


INSERT INTO t_sample_question (id, title, description, question, deleted, create_time, update_time) VALUES
('2079548151316643840', '天气查询', '查询用户本地今日天气', '今天天气怎么样？', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079548278735405056', '知识库查询', '查询知识库示例', '公司的薪资福利待遇怎么样？', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079548451087745024', 'SKILL示例', 'SKILL使用示例', '最近有什么值得关注的大事件或新闻吗？', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- 告警配置（1 条，默认关闭：SMTP 为示例占位值，需在管理后台配置后手动启用）
-- ============================================


INSERT INTO t_alert_config (id, enabled, smtp_host, smtp_port, smtp_username, smtp_password, from_address, to_address, time_window_hours, failure_threshold, deleted, create_time, update_time) VALUES
('default', 0, 'smtp.qq.com', 465, NULL, NULL, 'RAGStudio <example@qq.com>', NULL, 5, 3, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- MCP Server（3 个，默认关闭：headers 含认证信息，需在管理后台补充配置后手动启用。
--   last_status 置为 disconnected 且不携带失败错误，避免误导为已连接）
-- ============================================


INSERT INTO t_mcp_server (id, name, url, description, enabled, transport_type, headers, last_status, last_error, created_by, updated_by, deleted, create_time, last_check_time, update_time) VALUES
('2079547856670982144', '千问-图像生成', 'https://dashscope.aliyuncs.com/api/v1/mcps/QwenImage/mcp', '阿里云百炼官方图像生成 MCP 服务，基于千问系列图像生成模型封装，包括文生图、图像编辑工具，按模型调用量计费。', 0, 'streamable_http', NULL, 'disconnected', NULL, 'admin', 'admin', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079547705776701440', '天气预报', 'https://dashscope.aliyuncs.com/api/v1/mcps/market-cmapi033617/mcp', '天气预报查询是万维易源提供的一个通过输入坐标、IP、地名、区号/邮编、景点名称，查询天气情况（天气状况包括：湿度、天气图标、当前温度、风向、风级、紫外线、穿衣指南、空气指数）等信息。可查询到当前天气、未来24小时、7天、15天、40天内天气预报和过往的历史天气情况，通过 MCP 工具获取所需服务。', 0, 'streamable_http', NULL, 'disconnected', NULL, 'admin', 'admin', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('2079547963990638592', '万相-图像生成', 'https://dashscope.aliyuncs.com/api/v1/mcps/WanImage/mcp', '阿里云百炼官方图像生成 MCP 服务，基于万相系列图像生成模型封装，包括文生图、图像编辑、风格迁移等工具，按模型调用量计费。', 0, 'streamable_http', NULL, 'disconnected', NULL, 'admin', 'admin', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
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
