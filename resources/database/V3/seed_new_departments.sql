-- ============================================================
-- V3: 新增部门知识库种子数据
-- 部署后需要根据实际生成的 Snowflake ID 更新 KB ID
-- ============================================================

-- 1. 新增知识库定义
-- 注意: KB ID 为 Snowflake 主键，部署时由系统自动生成或手动修改
INSERT INTO t_knowledge_base (id, name, description, embedding_provider, embedding_model, dimension, collection_name, supports_image_embedding, created_by, create_time, deleted)
VALUES
-- 市场部知识库
('2083000000000000001', '市场部知识库',
 '市场营销战略规划、竞品分析报告、品牌传播策略、数字营销执行手册等',
 'siliconflow', 'Qwen/Qwen3-Embedding-8B', 1536,
 'marketing-group-document', 0, 'admin', NOW(), 0),

-- 研发部知识库
('2083000000000000002', '研发部知识库',
 '后端开发规范、系统架构设计、API接口文档等',
 'siliconflow', 'Qwen/Qwen3-Embedding-8B', 1536,
 'rd-group-document', 0, 'admin', NOW(), 0),

-- 战略发展部知识库 (全 PDF + Word 文档，含复杂图表)
('2083000000000000003', '战略发展部知识库',
 '战略经营分析报告、行业趋势洞察、战略规划白皮书、投资评估报告等（全部PDF/Word格式，含图表）',
 'siliconflow', 'Qwen/Qwen3-Embedding-8B', 1536,
 'strategy-group-document', 1, 'admin', NOW(), 0);

-- 2. 文档记录（上传后的 S3 路径）
INSERT INTO t_knowledge_document (id, kb_id, file_name, file_type, file_size, source_url, status, created_by, create_time, deleted)
VALUES
-- 市场部文档 (Markdown)
('2083100000000000001', '2083000000000000001', '市场营销年度规划.md', 'markdown', 8192,
 's3://ragstudio/document/marketing-group-document/2083100000000000001.md',
 'PENDING', 'admin', NOW(), 0),
('2083100000000000002', '2083000000000000001', '竞品分析报告.md', 'markdown', 6553,
 's3://ragstudio/document/marketing-group-document/2083100000000000002.md',
 'PENDING', 'admin', NOW(), 0),
('2083100000000000003', '2083000000000000001', '数字营销执行手册.md', 'markdown', 9216,
 's3://ragstudio/document/marketing-group-document/2083100000000000003.md',
 'PENDING', 'admin', NOW(), 0),
('2083100000000000004', '2083000000000000001', '品牌传播策略.md', 'markdown', 7680,
 's3://ragstudio/document/marketing-group-document/2083100000000000004.md',
 'PENDING', 'admin', NOW(), 0),

-- 研发部文档 (Markdown)
('2083100000000000005', '2083000000000000002', '后端开发规范.md', 'markdown', 10240,
 's3://ragstudio/document/rd-group-document/2083100000000000005.md',
 'PENDING', 'admin', NOW(), 0),
('2083100000000000006', '2083000000000000002', '系统架构设计.md', 'markdown', 12288,
 's3://ragstudio/document/rd-group-document/2083100000000000006.md',
 'PENDING', 'admin', NOW(), 0),
('2083100000000000007', '2083000000000000002', 'API接口文档.md', 'markdown', 9728,
 's3://ragstudio/document/rd-group-document/2083100000000000007.md',
 'PENDING', 'admin', NOW(), 0),

-- 战略发展部文档 (PDF + Word，含图表)
('2083100000000000008', '2083000000000000003', '2026年H1战略经营分析报告.pdf', 'pdf', 330752,
 's3://ragstudio/document/strategy-group-document/2083100000000000008.pdf',
 'PENDING', 'admin', NOW(), 0),
('2083100000000000009', '2083000000000000003', '2026年度战略规划白皮书.pdf', 'pdf', 159744,
 's3://ragstudio/document/strategy-group-document/2083100000000000009.pdf',
 'PENDING', 'admin', NOW(), 0),
('2083100000000000010', '2083000000000000003', '行业发展趋势与机会洞察报告.docx', 'docx', 175104,
 's3://ragstudio/document/strategy-group-document/2083100000000000010.docx',
 'PENDING', 'admin', NOW(), 0),
('2083100000000000011', '2083000000000000003', '战略投资评估报告.docx', 'docx', 301056,
 's3://ragstudio/document/strategy-group-document/2083100000000000011.docx',
 'PENDING', 'admin', NOW(), 0);

-- 3. 新增对话示例问题
INSERT INTO t_sample_question (kb_id, question, category, sort_order, created_by, create_time, deleted)
VALUES
-- 市场部
('2083000000000000001', '2026年的营销预算总规模是多少？', '数字查询', 1, 'admin', NOW(), 0),
('2083000000000000001', '竞品A相比我们有什么优势和劣势？', '竞品分析', 2, 'admin', NOW(), 0),
('2083000000000000001', '内容营销的发布频率要求是什么？', '操作规范', 3, 'admin', NOW(), 0),
('2083000000000000001', '公司品牌的核心定位是什么？', '品牌战略', 4, 'admin', NOW(), 0),

-- 研发部
('2083000000000000002', '后端开发中异常处理有哪些规范？', '编码规范', 1, 'admin', NOW(), 0),
('2083000000000000002', 'RAGStudio的系统架构有几个核心模块？', '架构设计', 2, 'admin', NOW(), 0),
('2083000000000000002', '聊天对话API的请求格式是什么？', 'API参考', 3, 'admin', NOW(), 0),
('2083000000000000002', '生产环境的部署架构是怎样的？', '运维部署', 4, 'admin', NOW(), 0),

-- 战略发展部
('2083000000000000003', '2026年H1的营收同比增长了多少？', '经营分析', 1, 'admin', NOW(), 0),
('2083000000000000003', '公司在AI知识管理市场的份额变化趋势如何？', '市场竞争', 2, 'admin', NOW(), 0),
('2083000000000000003', '三步走战略的各阶段目标是什么？', '战略规划', 3, 'admin', NOW(), 0),
('2083000000000000003', '2026年风险应对的主要措施有哪些？', '风险管理', 4, 'admin', NOW(), 0);
