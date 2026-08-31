-- ============================================================================
-- RAGStudio 全量数据库初始化脚本（Schema + 种子数据，单文件）
-- ============================================================================
-- 仅用于「全新部署」：createdb 后执行本文件一次即可完成全部初始化。
-- ⚠️ 已有数据的数据库禁止执行本文件（CREATE TABLE 无 IF NOT EXISTS 会报错）。
-- 历史升级脚本（V2/V3 目录）已合并入库，旧库升级所需增量语句见 Git 历史。
-- 合并来源：V2/schema_pg.sql + V2/init_data_pg.sql + V3/graph_pg.sql
--          + V3/mineru_parse_engine.sql + V3/model_connection_protocol.sql
--          + V3/graph_runtime_config.sql
-- ============================================================================

-- ============================================================================
-- 第一部分：Schema（V2 基础 + 已并入的 MinerU 表）
-- ============================================================================
-- ============================================
-- PostgreSQL Schema for RAGStudio V2
-- 根据 Java 实体类精确生成
-- ============================================

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ============================================
-- User & Conversation
-- ============================================

-- Entity: UserDO (@TableName="t_user", @TableId=ASSIGN_ID, @TableLogic)
CREATE TABLE t_user (
    id          VARCHAR(64)  NOT NULL PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL,
    password    VARCHAR(128) NOT NULL,
    avatar      VARCHAR(1024),
    role        VARCHAR(32)  NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT    DEFAULT 0,
    CONSTRAINT uk_user_username UNIQUE (username)
);
COMMENT ON TABLE t_user IS '系统用户表';
COMMENT ON COLUMN t_user.id IS '主键ID';
COMMENT ON COLUMN t_user.username IS '用户名，唯一';
COMMENT ON COLUMN t_user.password IS '密码';
COMMENT ON COLUMN t_user.avatar IS '用户头像';
COMMENT ON COLUMN t_user.role IS '角色：admin/user';
COMMENT ON COLUMN t_user.create_time IS '创建时间';
COMMENT ON COLUMN t_user.update_time IS '更新时间';
COMMENT ON COLUMN t_user.deleted IS '是否删除 0：正常 1：删除';

-- Entity: ConversationDO (@TableName="t_conversation", @TableId=ASSIGN_ID, @TableLogic)
CREATE TABLE t_conversation (
    id              VARCHAR(64) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    user_id         VARCHAR(64) NOT NULL,
    title           VARCHAR(128) NOT NULL,
    last_time       TIMESTAMP,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT    DEFAULT 0,
    CONSTRAINT uk_conversation_user UNIQUE (conversation_id, user_id)
);
CREATE INDEX idx_user_time ON t_conversation (user_id, last_time);
COMMENT ON TABLE t_conversation IS '会话列表';
COMMENT ON COLUMN t_conversation.id IS '主键ID';
COMMENT ON COLUMN t_conversation.conversation_id IS '会话ID';
COMMENT ON COLUMN t_conversation.user_id IS '用户ID';
COMMENT ON COLUMN t_conversation.title IS '会话名称';
COMMENT ON COLUMN t_conversation.last_time IS '最近消息时间';
COMMENT ON COLUMN t_conversation.create_time IS '创建时间';
COMMENT ON COLUMN t_conversation.update_time IS '更新时间';
COMMENT ON COLUMN t_conversation.deleted IS '是否删除 0：正常 1：删除';

-- Entity: ConversationSummaryDO (@TableName="t_conversation_summary", @TableId=ASSIGN_ID, @TableLogic)
CREATE TABLE t_conversation_summary (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    user_id         VARCHAR(64) NOT NULL,
    content         TEXT        NOT NULL,
    last_message_id VARCHAR(64) NOT NULL,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT    DEFAULT 0
);
CREATE INDEX idx_conv_user ON t_conversation_summary (conversation_id, user_id);
COMMENT ON TABLE t_conversation_summary IS '会话摘要表';
COMMENT ON COLUMN t_conversation_summary.id IS '主键ID';
COMMENT ON COLUMN t_conversation_summary.conversation_id IS '会话ID';
COMMENT ON COLUMN t_conversation_summary.user_id IS '用户ID';
COMMENT ON COLUMN t_conversation_summary.content IS '会话摘要内容';
COMMENT ON COLUMN t_conversation_summary.last_message_id IS '摘要最后消息ID';
COMMENT ON COLUMN t_conversation_summary.create_time IS '创建时间';
COMMENT ON COLUMN t_conversation_summary.update_time IS '更新时间';
COMMENT ON COLUMN t_conversation_summary.deleted IS '是否删除 0：正常 1：删除';

-- Entity: ConversationMessageDO (@TableName="t_message", @TableId=ASSIGN_ID, @TableLogic)
CREATE TABLE t_message (
    id                VARCHAR(64) NOT NULL PRIMARY KEY,
    conversation_id   VARCHAR(64) NOT NULL,
    user_id           VARCHAR(64) NOT NULL,
    role              VARCHAR(32) NOT NULL,
    content           TEXT        NOT NULL,
    thinking_content  TEXT,
    thinking_duration INTEGER,
    thinking_level    INTEGER NOT NULL DEFAULT 0,
    image_urls        TEXT,
    agent_steps       TEXT,
    citations         TEXT,
    create_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted           SMALLINT    DEFAULT 0
);
CREATE INDEX idx_conversation_user_time ON t_message (conversation_id, user_id, create_time);
CREATE INDEX idx_conversation_summary ON t_message (conversation_id, user_id, create_time);
COMMENT ON TABLE t_message IS '会话消息记录表';
COMMENT ON COLUMN t_message.id IS '主键ID';
COMMENT ON COLUMN t_message.conversation_id IS '会话ID';
COMMENT ON COLUMN t_message.user_id IS '用户ID';
COMMENT ON COLUMN t_message.role IS '角色：user/assistant';
COMMENT ON COLUMN t_message.content IS '消息内容';
COMMENT ON COLUMN t_message.thinking_content IS '深度思考内容';
COMMENT ON COLUMN t_message.thinking_duration IS '深度思考耗时（秒）';
COMMENT ON COLUMN t_message.create_time IS '创建时间';
COMMENT ON COLUMN t_message.update_time IS '更新时间';
COMMENT ON COLUMN t_message.deleted IS '是否删除 0：正常 1：删除';


-- Entity: MessageFeedbackDO (@TableName="t_message_feedback", @TableId=ASSIGN_ID, @TableLogic)
CREATE TABLE t_message_feedback (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    message_id      VARCHAR(64)  NOT NULL,
    conversation_id VARCHAR(64)  NOT NULL,
    user_id         VARCHAR(64)  NOT NULL,
    vote            SMALLINT     NOT NULL,
    reason          VARCHAR(255),
    comment         VARCHAR(1024),
    create_time     TIMESTAMP    NOT NULL,
    update_time     TIMESTAMP    NOT NULL,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_msg_user UNIQUE (message_id, user_id)
);
CREATE INDEX idx_conversation_id ON t_message_feedback (conversation_id);
CREATE INDEX idx_user_id ON t_message_feedback (user_id);
COMMENT ON TABLE t_message_feedback IS '会话消息反馈表';
COMMENT ON COLUMN t_message_feedback.id IS '主键ID';
COMMENT ON COLUMN t_message_feedback.message_id IS '消息ID';
COMMENT ON COLUMN t_message_feedback.conversation_id IS '会话ID';
COMMENT ON COLUMN t_message_feedback.user_id IS '用户ID';
COMMENT ON COLUMN t_message_feedback.vote IS '投票 1：赞 -1：踩';
COMMENT ON COLUMN t_message_feedback.reason IS '反馈原因';
COMMENT ON COLUMN t_message_feedback.comment IS '反馈评论';
COMMENT ON COLUMN t_message_feedback.create_time IS '创建时间';
COMMENT ON COLUMN t_message_feedback.update_time IS '更新时间';
COMMENT ON COLUMN t_message_feedback.deleted IS '是否删除 0：正常 1：删除';

-- Entity: SampleQuestionDO (@TableName="t_sample_question", @TableId=ASSIGN_ID, @TableLogic)
CREATE TABLE t_sample_question (
    id          VARCHAR(64)  NOT NULL PRIMARY KEY,
    title       VARCHAR(64),
    description VARCHAR(255),
    question    VARCHAR(1024) NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT    DEFAULT 0
);
CREATE INDEX idx_sample_question_deleted ON t_sample_question (deleted);
COMMENT ON TABLE t_sample_question IS '示例问题表';
COMMENT ON COLUMN t_sample_question.id IS 'ID';
COMMENT ON COLUMN t_sample_question.title IS '展示标题';
COMMENT ON COLUMN t_sample_question.description IS '描述或提示';
COMMENT ON COLUMN t_sample_question.question IS '示例问题内容';
COMMENT ON COLUMN t_sample_question.create_time IS '创建时间';
COMMENT ON COLUMN t_sample_question.update_time IS '更新时间';
COMMENT ON COLUMN t_sample_question.deleted IS '是否删除 0：正常 1：删除';

-- ============================================
-- Knowledge Base
-- ============================================

-- Entity: KnowledgeBaseDO (@TableName="t_knowledge_base", @TableId=ASSIGN_ID)
CREATE TABLE t_knowledge_base (
    id                 VARCHAR(64) NOT NULL PRIMARY KEY,
    name               VARCHAR(128) NOT NULL,
    description        TEXT,
    embedding_provider VARCHAR(64),
    embedding_model    VARCHAR(128) NOT NULL,
    dimension          INTEGER     NOT NULL DEFAULT 1536,
    collection_name    VARCHAR(128) NOT NULL,
    supports_image_embedding SMALLINT NOT NULL DEFAULT 0,
    parse_engine         VARCHAR(32) NOT NULL DEFAULT 'AUTO',
    created_by         VARCHAR(64)  NOT NULL,
    updated_by         VARCHAR(64),
    create_time        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_collection_name UNIQUE (collection_name)
);
CREATE INDEX idx_kb_name ON t_knowledge_base (name);
COMMENT ON TABLE t_knowledge_base IS '知识库表';
COMMENT ON COLUMN t_knowledge_base.id IS '主键 ID';
COMMENT ON COLUMN t_knowledge_base.name IS '知识库名称';
COMMENT ON COLUMN t_knowledge_base.embedding_provider IS '嵌入模型供应商，如 siliconflow';
COMMENT ON COLUMN t_knowledge_base.embedding_model IS '嵌入模型标识';
COMMENT ON COLUMN t_knowledge_base.dimension IS '向量维度';
COMMENT ON COLUMN t_knowledge_base.collection_name IS 'Collection名称';
COMMENT ON COLUMN t_knowledge_base.supports_image_embedding IS '是否支持图像嵌入: 1-是，0-否。由嵌入模型的多模态能力自动判断';
COMMENT ON COLUMN t_knowledge_base.created_by IS '创建人';
COMMENT ON COLUMN t_knowledge_base.updated_by IS '修改人';
COMMENT ON COLUMN t_knowledge_base.create_time IS '创建时间';
COMMENT ON COLUMN t_knowledge_base.update_time IS '更新时间';

-- Entity: KnowledgeDocumentDO (@TableName="t_knowledge_document", @TableId=ASSIGN_ID, @TableLogic)
-- chunk_config uses JsonbTypeHandler
CREATE TABLE t_knowledge_document (
    id               VARCHAR(64)   NOT NULL PRIMARY KEY,
    kb_id            VARCHAR(64)   NOT NULL,
    doc_name         VARCHAR(256)  NOT NULL,
    source_type      VARCHAR(32),
    source_location  VARCHAR(1024),
    schedule_enabled SMALLINT,
    schedule_cron    VARCHAR(128),
    enabled          SMALLINT      NOT NULL DEFAULT 1,
    chunk_count      INTEGER       DEFAULT 0,
    file_url         VARCHAR(1024) NOT NULL,
    file_type        VARCHAR(32)   NOT NULL,
    file_size        BIGINT,
    process_mode     VARCHAR(32)   DEFAULT 'chunk',
    parse_engine     VARCHAR(32),
    chunk_strategy   VARCHAR(32),
    chunk_config     JSONB,
    pipeline_id      VARCHAR(64),
    status           VARCHAR(32)   NOT NULL DEFAULT 'pending',
    created_by       VARCHAR(64)   NOT NULL,
    updated_by       VARCHAR(64),
    create_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_kb_id ON t_knowledge_document (kb_id);
COMMENT ON TABLE t_knowledge_document IS '知识库文档表';
COMMENT ON COLUMN t_knowledge_document.id IS 'ID';
COMMENT ON COLUMN t_knowledge_document.kb_id IS '知识库ID';
COMMENT ON COLUMN t_knowledge_document.doc_name IS '文档名称';
COMMENT ON COLUMN t_knowledge_document.source_type IS '来源类型：file/url';
COMMENT ON COLUMN t_knowledge_document.source_location IS '来源地址';
COMMENT ON COLUMN t_knowledge_document.schedule_enabled IS '是否启用定时刷新';
COMMENT ON COLUMN t_knowledge_document.schedule_cron IS '定时表达式';
COMMENT ON COLUMN t_knowledge_document.enabled IS '是否启用 1：启用 0：禁用';
COMMENT ON COLUMN t_knowledge_document.chunk_count IS '分块数量';
COMMENT ON COLUMN t_knowledge_document.file_url IS '文件存储路径';
COMMENT ON COLUMN t_knowledge_document.file_type IS '文件类型';
COMMENT ON COLUMN t_knowledge_document.file_size IS '文件大小（字节）';
COMMENT ON COLUMN t_knowledge_document.process_mode IS '处理模式：chunk/pipeline';
COMMENT ON COLUMN t_knowledge_document.chunk_strategy IS '分块策略';
COMMENT ON COLUMN t_knowledge_document.chunk_config IS '分块配置JSON';
COMMENT ON COLUMN t_knowledge_document.pipeline_id IS 'Pipeline ID';
COMMENT ON COLUMN t_knowledge_document.status IS '状态：pending/running/success/failed';
COMMENT ON COLUMN t_knowledge_document.created_by IS '创建人';
COMMENT ON COLUMN t_knowledge_document.updated_by IS '修改人';
COMMENT ON COLUMN t_knowledge_document.create_time IS '创建时间';
COMMENT ON COLUMN t_knowledge_document.update_time IS '更新时间';

-- Entity: KnowledgeChunkDO (@TableName="t_knowledge_chunk", @TableId=ASSIGN_ID)
CREATE TABLE t_knowledge_chunk (
    id           VARCHAR(64) NOT NULL PRIMARY KEY,
    kb_id        VARCHAR(64) NOT NULL,
    doc_id       VARCHAR(64) NOT NULL,
    chunk_index  INTEGER     NOT NULL,
    content      TEXT        NOT NULL,
    content_hash VARCHAR(64),
    char_count   INTEGER,
    token_count  INTEGER,
    content_type VARCHAR(32) DEFAULT 'TEXT',
    image_url    VARCHAR(1024),
    enabled      SMALLINT    NOT NULL DEFAULT 1,
    created_by   VARCHAR(64) NOT NULL,
    updated_by   VARCHAR(64),
    create_time  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_doc_id ON t_knowledge_chunk (doc_id);
COMMENT ON TABLE t_knowledge_chunk IS '知识库文档分块表';
COMMENT ON COLUMN t_knowledge_chunk.id IS 'ID';
COMMENT ON COLUMN t_knowledge_chunk.kb_id IS '知识库ID';
COMMENT ON COLUMN t_knowledge_chunk.doc_id IS '文档ID';
COMMENT ON COLUMN t_knowledge_chunk.chunk_index IS '分块序号';
COMMENT ON COLUMN t_knowledge_chunk.content IS '分块内容';
COMMENT ON COLUMN t_knowledge_chunk.content_hash IS '内容哈希';
COMMENT ON COLUMN t_knowledge_chunk.char_count IS '字符数';
COMMENT ON COLUMN t_knowledge_chunk.token_count IS 'Token数';
COMMENT ON COLUMN t_knowledge_chunk.enabled IS '是否启用';
COMMENT ON COLUMN t_knowledge_chunk.created_by IS '创建人';
COMMENT ON COLUMN t_knowledge_chunk.updated_by IS '修改人';
COMMENT ON COLUMN t_knowledge_chunk.create_time IS '创建时间';
COMMENT ON COLUMN t_knowledge_chunk.update_time IS '更新时间';

-- Entity: KnowledgeDocumentChunkLogDO (@TableName="t_knowledge_document_chunk_log", @TableId=ASSIGN_ID)
-- NOTE: no @TableLogic → no deleted column
CREATE TABLE t_knowledge_document_chunk_log (
    id               VARCHAR(64) NOT NULL PRIMARY KEY,
    doc_id           VARCHAR(64) NOT NULL,
    status           VARCHAR(20) NOT NULL,
    process_mode     VARCHAR(20),
    chunk_strategy   VARCHAR(50),
    pipeline_id      VARCHAR(64),
    extract_duration BIGINT,
    chunk_duration   BIGINT,
    embed_duration   BIGINT,
    persist_duration BIGINT,
    total_duration   BIGINT,
    chunk_count      INTEGER,
    error_message    TEXT,
    start_time       TIMESTAMP,
    end_time         TIMESTAMP,
    create_time      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_doc_id_log ON t_knowledge_document_chunk_log (doc_id);
COMMENT ON TABLE t_knowledge_document_chunk_log IS '知识库文档分块日志表';
COMMENT ON COLUMN t_knowledge_document_chunk_log.id IS 'ID';
COMMENT ON COLUMN t_knowledge_document_chunk_log.doc_id IS '文档ID';
COMMENT ON COLUMN t_knowledge_document_chunk_log.status IS '状态';
COMMENT ON COLUMN t_knowledge_document_chunk_log.process_mode IS '处理模式';
COMMENT ON COLUMN t_knowledge_document_chunk_log.chunk_strategy IS '分块策略';
COMMENT ON COLUMN t_knowledge_document_chunk_log.pipeline_id IS 'Pipeline ID';
COMMENT ON COLUMN t_knowledge_document_chunk_log.extract_duration IS '提取耗时（毫秒）';
COMMENT ON COLUMN t_knowledge_document_chunk_log.chunk_duration IS '分块耗时（毫秒）';
COMMENT ON COLUMN t_knowledge_document_chunk_log.embed_duration IS '向量化耗时（毫秒）';
COMMENT ON COLUMN t_knowledge_document_chunk_log.persist_duration IS 'DB持久化耗时（毫秒）';
COMMENT ON COLUMN t_knowledge_document_chunk_log.total_duration IS '总耗时（毫秒）';
COMMENT ON COLUMN t_knowledge_document_chunk_log.chunk_count IS '分块数量';
COMMENT ON COLUMN t_knowledge_document_chunk_log.error_message IS '错误信息';
COMMENT ON COLUMN t_knowledge_document_chunk_log.start_time IS '开始时间';
COMMENT ON COLUMN t_knowledge_document_chunk_log.end_time IS '结束时间';
COMMENT ON COLUMN t_knowledge_document_chunk_log.create_time IS '创建时间';
COMMENT ON COLUMN t_knowledge_document_chunk_log.update_time IS '更新时间';

-- Entity: KnowledgeDocumentScheduleDO (@TableName="t_knowledge_document_schedule", @TableId=ASSIGN_ID)
-- NOTE: no @TableLogic → no deleted column
CREATE TABLE t_knowledge_document_schedule (
    id                VARCHAR(64) NOT NULL PRIMARY KEY,
    doc_id            VARCHAR(64) NOT NULL,
    kb_id             VARCHAR(64) NOT NULL,
    cron_expr         VARCHAR(128),
    enabled           SMALLINT DEFAULT 0,
    next_run_time     TIMESTAMP,
    last_run_time     TIMESTAMP,
    last_success_time TIMESTAMP,
    last_status       VARCHAR(32),
    last_error        VARCHAR(512),
    last_etag         VARCHAR(256),
    last_modified     VARCHAR(256),
    last_content_hash VARCHAR(128),
    lock_owner        VARCHAR(128),
    lock_until        TIMESTAMP,
    create_time       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_doc_id UNIQUE (doc_id)
);
CREATE INDEX idx_next_run ON t_knowledge_document_schedule (next_run_time);
CREATE INDEX idx_lock_until ON t_knowledge_document_schedule (lock_until);
COMMENT ON TABLE t_knowledge_document_schedule IS '知识库文档定时刷新任务表';
COMMENT ON COLUMN t_knowledge_document_schedule.id IS 'ID';
COMMENT ON COLUMN t_knowledge_document_schedule.doc_id IS '文档ID';
COMMENT ON COLUMN t_knowledge_document_schedule.kb_id IS '知识库ID';
COMMENT ON COLUMN t_knowledge_document_schedule.cron_expr IS 'Cron表达式';
COMMENT ON COLUMN t_knowledge_document_schedule.enabled IS '是否启用';
COMMENT ON COLUMN t_knowledge_document_schedule.next_run_time IS '下次执行时间';
COMMENT ON COLUMN t_knowledge_document_schedule.last_run_time IS '上次执行时间';
COMMENT ON COLUMN t_knowledge_document_schedule.last_success_time IS '上次成功时间';
COMMENT ON COLUMN t_knowledge_document_schedule.last_status IS '上次状态';
COMMENT ON COLUMN t_knowledge_document_schedule.last_error IS '上次错误';
COMMENT ON COLUMN t_knowledge_document_schedule.last_etag IS '上次ETag';
COMMENT ON COLUMN t_knowledge_document_schedule.last_modified IS '上次修改时间';
COMMENT ON COLUMN t_knowledge_document_schedule.last_content_hash IS '上次内容哈希';
COMMENT ON COLUMN t_knowledge_document_schedule.lock_owner IS '锁持有者';
COMMENT ON COLUMN t_knowledge_document_schedule.lock_until IS '锁过期时间';
COMMENT ON COLUMN t_knowledge_document_schedule.create_time IS '创建时间';
COMMENT ON COLUMN t_knowledge_document_schedule.update_time IS '更新时间';

-- Entity: KnowledgeDocumentScheduleExecDO (@TableName="t_knowledge_document_schedule_exec", @TableId=ASSIGN_ID)
-- NOTE: no @TableLogic → no deleted column
CREATE TABLE t_knowledge_document_schedule_exec (
    id            VARCHAR(64) NOT NULL PRIMARY KEY,
    schedule_id   VARCHAR(64) NOT NULL,
    doc_id        VARCHAR(64) NOT NULL,
    kb_id         VARCHAR(64) NOT NULL,
    status        VARCHAR(32) NOT NULL,
    message       VARCHAR(512),
    start_time    TIMESTAMP,
    end_time      TIMESTAMP,
    file_name     VARCHAR(512),
    file_size     BIGINT,
    content_hash  VARCHAR(128),
    etag          VARCHAR(256),
    last_modified VARCHAR(256),
    create_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_schedule_time ON t_knowledge_document_schedule_exec (schedule_id, start_time);
CREATE INDEX idx_doc_id_exec ON t_knowledge_document_schedule_exec (doc_id);
COMMENT ON TABLE t_knowledge_document_schedule_exec IS '知识库文档定时刷新执行记录';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.id IS 'ID';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.schedule_id IS '调度ID';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.doc_id IS '文档ID';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.kb_id IS '知识库ID';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.status IS '状态';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.message IS '消息';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.start_time IS '开始时间';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.end_time IS '结束时间';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.file_name IS '文件名';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.file_size IS '文件大小';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.content_hash IS '内容哈希';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.etag IS 'ETag';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.last_modified IS '最后修改时间';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.create_time IS '创建时间';
COMMENT ON COLUMN t_knowledge_document_schedule_exec.update_time IS '更新时间';

-- ============================================
-- Query Term Mapping
-- ============================================

-- Entity: QueryTermMappingDO (@TableName="t_query_term_mapping", @TableId=ASSIGN_ID)
-- NOTE: no @TableLogic, no @TableField(fill) → no deleted column, no auto-fill
CREATE TABLE t_query_term_mapping (
    id                  VARCHAR(64)  NOT NULL PRIMARY KEY,
    domain              VARCHAR(64),
    source_term         VARCHAR(128) NOT NULL,
    target_term         VARCHAR(128) NOT NULL,
    match_type          SMALLINT     NOT NULL DEFAULT 1,
    priority            INTEGER      NOT NULL DEFAULT 100,
    enabled             SMALLINT     NOT NULL DEFAULT 1,
    remark              VARCHAR(255),
    knowledge_base_ids  TEXT,
    create_by           VARCHAR(64),
    update_by           VARCHAR(64),
    create_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_domain ON t_query_term_mapping (domain);
CREATE INDEX idx_source ON t_query_term_mapping (source_term);
COMMENT ON TABLE t_query_term_mapping IS '关键词归一化映射表';
COMMENT ON COLUMN t_query_term_mapping.id IS 'ID';
COMMENT ON COLUMN t_query_term_mapping.domain IS '领域';
COMMENT ON COLUMN t_query_term_mapping.source_term IS '源词';
COMMENT ON COLUMN t_query_term_mapping.target_term IS '目标词';
COMMENT ON COLUMN t_query_term_mapping.match_type IS '匹配类型 1：精确 2：模糊';
COMMENT ON COLUMN t_query_term_mapping.priority IS '优先级';
COMMENT ON COLUMN t_query_term_mapping.enabled IS '是否启用';
COMMENT ON COLUMN t_query_term_mapping.remark IS '备注';
COMMENT ON COLUMN t_query_term_mapping.knowledge_base_ids IS '关联知识库ID列表（JSON数组）';
COMMENT ON COLUMN t_query_term_mapping.create_by IS '创建人';
COMMENT ON COLUMN t_query_term_mapping.update_by IS '修改人';
COMMENT ON COLUMN t_query_term_mapping.create_time IS '创建时间';
COMMENT ON COLUMN t_query_term_mapping.update_time IS '修改时间';

-- ============================================
-- RAG Trace
-- ============================================

-- Entity: RagTraceRunDO (@TableName="t_rag_trace_run", @TableId=ASSIGN_ID, @TableLogic)
CREATE TABLE t_rag_trace_run (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    trace_id        VARCHAR(64)  NOT NULL,
    trace_name      VARCHAR(128),
    entry_method    VARCHAR(256),
    conversation_id VARCHAR(64),
    task_id         VARCHAR(64),
    user_id         VARCHAR(64),
    status          VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',
    error_message   VARCHAR(1000),
    start_time      TIMESTAMP(3),
    end_time        TIMESTAMP(3),
    duration_ms     BIGINT,
    extra_data      TEXT,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT    DEFAULT 0,
    CONSTRAINT uk_run_id UNIQUE (trace_id)
);
CREATE INDEX idx_task_id ON t_rag_trace_run (task_id);
CREATE INDEX idx_user_id_trace ON t_rag_trace_run (user_id);
COMMENT ON TABLE t_rag_trace_run IS 'Trace 运行记录表';
COMMENT ON COLUMN t_rag_trace_run.id IS 'ID';
COMMENT ON COLUMN t_rag_trace_run.trace_id IS '全局链路ID';
COMMENT ON COLUMN t_rag_trace_run.trace_name IS '链路名称';
COMMENT ON COLUMN t_rag_trace_run.entry_method IS '入口方法';
COMMENT ON COLUMN t_rag_trace_run.conversation_id IS '会话ID';
COMMENT ON COLUMN t_rag_trace_run.task_id IS '任务ID';
COMMENT ON COLUMN t_rag_trace_run.user_id IS '用户ID';
COMMENT ON COLUMN t_rag_trace_run.status IS 'RUNNING/SUCCESS/ERROR';
COMMENT ON COLUMN t_rag_trace_run.error_message IS '错误信息';
COMMENT ON COLUMN t_rag_trace_run.start_time IS '开始时间';
COMMENT ON COLUMN t_rag_trace_run.end_time IS '结束时间';
COMMENT ON COLUMN t_rag_trace_run.duration_ms IS '耗时毫秒';
COMMENT ON COLUMN t_rag_trace_run.extra_data IS '扩展字段(JSON)';
COMMENT ON COLUMN t_rag_trace_run.create_time IS '创建时间';
COMMENT ON COLUMN t_rag_trace_run.update_time IS '更新时间';
COMMENT ON COLUMN t_rag_trace_run.deleted IS '是否删除';

-- Entity: RagTraceNodeDO (@TableName="t_rag_trace_node", @TableId=ASSIGN_ID, @TableLogic)
CREATE TABLE t_rag_trace_node (
    id             VARCHAR(64)  NOT NULL PRIMARY KEY,
    trace_id       VARCHAR(64)  NOT NULL,
    node_id        VARCHAR(64)  NOT NULL,
    parent_node_id VARCHAR(64),
    depth          INTEGER      DEFAULT 0,
    node_type      VARCHAR(64),
    node_name      VARCHAR(128),
    class_name     VARCHAR(256),
    method_name    VARCHAR(128),
    status         VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',
    error_message  VARCHAR(1000),
    start_time     TIMESTAMP(3),
    end_time       TIMESTAMP(3),
    duration_ms    BIGINT,
    extra_data     TEXT,
    create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted        SMALLINT    DEFAULT 0,
    CONSTRAINT uk_run_node UNIQUE (trace_id, node_id)
);
COMMENT ON TABLE t_rag_trace_node IS 'Trace 节点记录表';
COMMENT ON COLUMN t_rag_trace_node.id IS 'ID';
COMMENT ON COLUMN t_rag_trace_node.trace_id IS '所属链路ID';
COMMENT ON COLUMN t_rag_trace_node.node_id IS '节点ID';
COMMENT ON COLUMN t_rag_trace_node.parent_node_id IS '父节点ID';
COMMENT ON COLUMN t_rag_trace_node.depth IS '节点深度';
COMMENT ON COLUMN t_rag_trace_node.node_type IS '节点类型';
COMMENT ON COLUMN t_rag_trace_node.node_name IS '节点名称';
COMMENT ON COLUMN t_rag_trace_node.class_name IS '类名';
COMMENT ON COLUMN t_rag_trace_node.method_name IS '方法名';
COMMENT ON COLUMN t_rag_trace_node.status IS 'RUNNING/SUCCESS/ERROR';
COMMENT ON COLUMN t_rag_trace_node.error_message IS '错误信息';
COMMENT ON COLUMN t_rag_trace_node.start_time IS '开始时间';
COMMENT ON COLUMN t_rag_trace_node.end_time IS '结束时间';
COMMENT ON COLUMN t_rag_trace_node.duration_ms IS '耗时毫秒';
COMMENT ON COLUMN t_rag_trace_node.extra_data IS '扩展字段(JSON)';
COMMENT ON COLUMN t_rag_trace_node.create_time IS '创建时间';
COMMENT ON COLUMN t_rag_trace_node.update_time IS '更新时间';
COMMENT ON COLUMN t_rag_trace_node.deleted IS '是否删除';

-- ============================================
-- Ingestion Pipeline
-- ============================================

-- Entity: IngestionPipelineDO (@TableName="t_ingestion_pipeline", @TableId=ASSIGN_ID, @TableLogic)
CREATE TABLE t_ingestion_pipeline (
    id          VARCHAR(64)   NOT NULL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    created_by  VARCHAR(64) DEFAULT '',
    updated_by  VARCHAR(64) DEFAULT '',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT    NOT NULL DEFAULT 0,
    CONSTRAINT uk_ingestion_pipeline_name UNIQUE (name, deleted)
);
COMMENT ON TABLE t_ingestion_pipeline IS '摄取流水线表';
COMMENT ON COLUMN t_ingestion_pipeline.id IS 'ID';
COMMENT ON COLUMN t_ingestion_pipeline.name IS '流水线名称';
COMMENT ON COLUMN t_ingestion_pipeline.description IS '流水线描述';
COMMENT ON COLUMN t_ingestion_pipeline.created_by IS '创建人';
COMMENT ON COLUMN t_ingestion_pipeline.updated_by IS '更新人';
COMMENT ON COLUMN t_ingestion_pipeline.create_time IS '创建时间';
COMMENT ON COLUMN t_ingestion_pipeline.update_time IS '更新时间';
COMMENT ON COLUMN t_ingestion_pipeline.deleted IS '是否删除 0：正常 1：删除';

-- Entity: IngestionPipelineNodeDO (@TableName="t_ingestion_pipeline_node", @TableId=ASSIGN_ID, @TableLogic)
-- settings_json, condition_json use JsonbTypeHandler
CREATE TABLE t_ingestion_pipeline_node (
    id             VARCHAR(64) NOT NULL PRIMARY KEY,
    pipeline_id    VARCHAR(64) NOT NULL,
    node_id        VARCHAR(64) NOT NULL,
    node_type      VARCHAR(30) NOT NULL,
    next_node_id   VARCHAR(64),
    settings_json  JSONB,
    condition_json JSONB,
    created_by     VARCHAR(64) DEFAULT '',
    updated_by     VARCHAR(64) DEFAULT '',
    create_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        SMALLINT    NOT NULL DEFAULT 0,
    CONSTRAINT uk_ingestion_pipeline_node UNIQUE (pipeline_id, node_id, deleted)
);
CREATE INDEX idx_ingestion_pipeline_node_pipeline ON t_ingestion_pipeline_node (pipeline_id);
COMMENT ON TABLE t_ingestion_pipeline_node IS '摄取流水线节点表';
COMMENT ON COLUMN t_ingestion_pipeline_node.id IS 'ID';
COMMENT ON COLUMN t_ingestion_pipeline_node.pipeline_id IS '流水线ID';
COMMENT ON COLUMN t_ingestion_pipeline_node.node_id IS '节点标识(同一流水线内唯一)';
COMMENT ON COLUMN t_ingestion_pipeline_node.node_type IS '节点类型';
COMMENT ON COLUMN t_ingestion_pipeline_node.next_node_id IS '下一个节点ID';
COMMENT ON COLUMN t_ingestion_pipeline_node.settings_json IS '节点配置JSON';
COMMENT ON COLUMN t_ingestion_pipeline_node.condition_json IS '条件JSON';
COMMENT ON COLUMN t_ingestion_pipeline_node.created_by IS '创建人';
COMMENT ON COLUMN t_ingestion_pipeline_node.updated_by IS '更新人';
COMMENT ON COLUMN t_ingestion_pipeline_node.create_time IS '创建时间';
COMMENT ON COLUMN t_ingestion_pipeline_node.update_time IS '更新时间';
COMMENT ON COLUMN t_ingestion_pipeline_node.deleted IS '是否删除 0：正常 1：删除';

-- Entity: IngestionTaskDO (@TableName="t_ingestion_task", @TableId=ASSIGN_ID, @TableLogic)
-- logs_json, metadata_json use JsonbTypeHandler
CREATE TABLE t_ingestion_task (
    id               VARCHAR(64) NOT NULL PRIMARY KEY,
    pipeline_id      VARCHAR(64) NOT NULL,
    source_type      VARCHAR(20) NOT NULL,
    source_location  TEXT,
    source_file_name VARCHAR(255),
    status           VARCHAR(20) NOT NULL,
    chunk_count      INTEGER     DEFAULT 0,
    error_message    TEXT,
    logs_json        JSONB,
    metadata_json    JSONB,
    started_at       TIMESTAMP,
    completed_at     TIMESTAMP,
    created_by       VARCHAR(64) DEFAULT '',
    updated_by       VARCHAR(64) DEFAULT '',
    create_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          SMALLINT    NOT NULL DEFAULT 0
);
CREATE INDEX idx_ingestion_task_pipeline ON t_ingestion_task (pipeline_id);
CREATE INDEX idx_ingestion_task_status ON t_ingestion_task (status);
COMMENT ON TABLE t_ingestion_task IS '摄取任务表';
COMMENT ON COLUMN t_ingestion_task.id IS 'ID';
COMMENT ON COLUMN t_ingestion_task.pipeline_id IS '流水线ID';
COMMENT ON COLUMN t_ingestion_task.source_type IS '来源类型';
COMMENT ON COLUMN t_ingestion_task.source_location IS '来源地址或URL';
COMMENT ON COLUMN t_ingestion_task.source_file_name IS '原始文件名';
COMMENT ON COLUMN t_ingestion_task.status IS '任务状态';
COMMENT ON COLUMN t_ingestion_task.chunk_count IS '分块数量';
COMMENT ON COLUMN t_ingestion_task.error_message IS '错误信息';
COMMENT ON COLUMN t_ingestion_task.logs_json IS '节点日志JSON';
COMMENT ON COLUMN t_ingestion_task.metadata_json IS '扩展元数据JSON';
COMMENT ON COLUMN t_ingestion_task.started_at IS '开始时间';
COMMENT ON COLUMN t_ingestion_task.completed_at IS '完成时间';
COMMENT ON COLUMN t_ingestion_task.created_by IS '创建人';
COMMENT ON COLUMN t_ingestion_task.updated_by IS '更新人';
COMMENT ON COLUMN t_ingestion_task.create_time IS '创建时间';
COMMENT ON COLUMN t_ingestion_task.update_time IS '更新时间';
COMMENT ON COLUMN t_ingestion_task.deleted IS '是否删除 0：正常 1：删除';

-- Entity: IngestionTaskNodeDO (@TableName="t_ingestion_task_node", @TableId=ASSIGN_ID, @TableLogic)
CREATE TABLE t_ingestion_task_node (
    id            VARCHAR(64) NOT NULL PRIMARY KEY,
    task_id       VARCHAR(64) NOT NULL,
    pipeline_id   VARCHAR(64) NOT NULL,
    node_id       VARCHAR(64) NOT NULL,
    node_type     VARCHAR(30) NOT NULL,
    node_order    INTEGER     NOT NULL DEFAULT 0,
    status        VARCHAR(20) NOT NULL,
    duration_ms   BIGINT      NOT NULL DEFAULT 0,
    message       TEXT,
    error_message TEXT,
    output_json   TEXT,
    create_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       SMALLINT    NOT NULL DEFAULT 0
);
CREATE INDEX idx_ingestion_task_node_task ON t_ingestion_task_node (task_id);
CREATE INDEX idx_ingestion_task_node_pipeline ON t_ingestion_task_node (pipeline_id);
CREATE INDEX idx_ingestion_task_node_status ON t_ingestion_task_node (status);
COMMENT ON TABLE t_ingestion_task_node IS '摄取任务节点表';
COMMENT ON COLUMN t_ingestion_task_node.id IS 'ID';
COMMENT ON COLUMN t_ingestion_task_node.task_id IS '任务ID';
COMMENT ON COLUMN t_ingestion_task_node.pipeline_id IS '流水线ID';
COMMENT ON COLUMN t_ingestion_task_node.node_id IS '节点标识';
COMMENT ON COLUMN t_ingestion_task_node.node_type IS '节点类型';
COMMENT ON COLUMN t_ingestion_task_node.node_order IS '节点顺序';
COMMENT ON COLUMN t_ingestion_task_node.status IS '节点状态';
COMMENT ON COLUMN t_ingestion_task_node.duration_ms IS '执行耗时(毫秒)';
COMMENT ON COLUMN t_ingestion_task_node.message IS '节点消息';
COMMENT ON COLUMN t_ingestion_task_node.error_message IS '错误信息';
COMMENT ON COLUMN t_ingestion_task_node.output_json IS '节点输出JSON(全量)';
COMMENT ON COLUMN t_ingestion_task_node.create_time IS '创建时间';
COMMENT ON COLUMN t_ingestion_task_node.update_time IS '更新时间';
COMMENT ON COLUMN t_ingestion_task_node.deleted IS '是否删除 0：正常 1：删除';

-- ============================================
-- Vector Storage (pgvector) — 按维度分表
-- 不同维度的向量存储在不同的表中，避免 pgvector 固定 vector(N) 的限制。
-- 知识库创建时根据所选 embedding 模型的维度确定使用哪张表。
-- ============================================

-- 向量按维度分表，入库维度均 ≤ 2000，统一使用 HNSW 索引。
-- 预创建常用维度表，其他维度由 PgVectorStoreAdmin 在运行时动态创建。
CREATE TABLE t_knowledge_vector_1024 (
    id           VARCHAR(64) PRIMARY KEY,
    content      TEXT,
    metadata     JSONB,
    embedding    vector(1024),
    content_type VARCHAR(32) DEFAULT 'TEXT'
);
CREATE INDEX idx_kv_1024_metadata ON t_knowledge_vector_1024 USING gin(metadata);
CREATE INDEX idx_kv_1024_embedding ON t_knowledge_vector_1024 USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_kv_1024_content_trgm ON t_knowledge_vector_1024 USING gin (content gin_trgm_ops);
COMMENT ON TABLE t_knowledge_vector_1024 IS '知识库向量存储表（1024维）';

CREATE TABLE t_knowledge_vector_1536 (
    id           VARCHAR(64) PRIMARY KEY,
    content      TEXT,
    metadata     JSONB,
    embedding    vector(1536),
    content_type VARCHAR(32) DEFAULT 'TEXT'
);
CREATE INDEX idx_kv_1536_metadata ON t_knowledge_vector_1536 USING gin(metadata);
CREATE INDEX idx_kv_1536_embedding ON t_knowledge_vector_1536 USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_kv_1536_content_trgm ON t_knowledge_vector_1536 USING gin (content gin_trgm_ops);
COMMENT ON TABLE t_knowledge_vector_1536 IS '知识库向量存储表（1536维）';

-- 替换说明：pg_trgm GIN 索引替代了旧的 tsvector 索引
-- tsvector 不拆分中文词（无空格），对中文关键词检索基本失效
-- pg_trgm 按 3 字符片段索引，配合 ILIKE 实现中文/混合文本子串匹配
-- 建索引前需要先执行：CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ============================================
-- MCP Server 动态管理
-- ============================================

-- Entity: McpServerDO (@TableName="t_mcp_server", @TableId=ASSIGN_ID, @TableLogic)
CREATE TABLE t_mcp_server (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    url             VARCHAR(512) NOT NULL,
    description     VARCHAR(512),
    enabled         SMALLINT     NOT NULL DEFAULT 1,
    transport_type  VARCHAR(32)  NOT NULL DEFAULT 'streamable_http',
    headers         JSONB,
    last_status     VARCHAR(32),
    last_error      VARCHAR(512),
    last_check_time TIMESTAMP,
    created_by      VARCHAR(64),
    updated_by      VARCHAR(64),
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_mcp_server_name UNIQUE (name, deleted)
);
COMMENT ON TABLE t_mcp_server IS 'MCP Server 动态配置表';
COMMENT ON COLUMN t_mcp_server.id IS '主键 ID';
COMMENT ON COLUMN t_mcp_server.name IS '服务名称（唯一）';
COMMENT ON COLUMN t_mcp_server.url IS '服务地址';
COMMENT ON COLUMN t_mcp_server.description IS '服务描述';
COMMENT ON COLUMN t_mcp_server.enabled IS '是否启用 1：启用 0：禁用';
COMMENT ON COLUMN t_mcp_server.transport_type IS '传输类型：streamable_http/sse';
COMMENT ON COLUMN t_mcp_server.headers IS '自定义请求头（JSON）';
COMMENT ON COLUMN t_mcp_server.last_status IS '最近连接状态：connected/disconnected/error';
COMMENT ON COLUMN t_mcp_server.last_error IS '最近错误信息';
COMMENT ON COLUMN t_mcp_server.last_check_time IS '最近检查时间';
COMMENT ON COLUMN t_mcp_server.created_by IS '创建人';
COMMENT ON COLUMN t_mcp_server.updated_by IS '修改人';
COMMENT ON COLUMN t_mcp_server.create_time IS '创建时间';
COMMENT ON COLUMN t_mcp_server.update_time IS '更新时间';
COMMENT ON COLUMN t_mcp_server.deleted IS '是否删除 0：正常 1：删除';

-- ============================================
-- AI Model Configuration
-- ============================================

-- Entity: AiProviderDO (@TableName="t_ai_provider", @TableId=ASSIGN_ID, @TableLogic)
-- endpoints 使用 JsonbTypeHandler
CREATE TABLE t_ai_provider (
    id           VARCHAR(64)  NOT NULL PRIMARY KEY,
    name         VARCHAR(50)  NOT NULL,
    display_name VARCHAR(100),
    base_url     VARCHAR(500) NOT NULL,
    api_key      VARCHAR(500),
    endpoints    JSONB,
    enabled      SMALLINT     NOT NULL DEFAULT 1,
    icon_url     VARCHAR(500),
    api_protocol VARCHAR(32)  DEFAULT 'openai',
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted      SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_ai_provider_name UNIQUE (name, deleted)
);
COMMENT ON TABLE t_ai_provider IS 'AI模型供应商表';
COMMENT ON COLUMN t_ai_provider.id IS '主键ID';
COMMENT ON COLUMN t_ai_provider.name IS '供应商标识';
COMMENT ON COLUMN t_ai_provider.display_name IS '显示名称';
COMMENT ON COLUMN t_ai_provider.base_url IS 'API基础地址';
COMMENT ON COLUMN t_ai_provider.api_key IS 'API密钥';
COMMENT ON COLUMN t_ai_provider.endpoints IS '端点映射JSON';
COMMENT ON COLUMN t_ai_provider.enabled IS '是否启用 1：启用 0：禁用';
COMMENT ON COLUMN t_ai_provider.icon_url IS '供应商图标URL';
COMMENT ON COLUMN t_ai_provider.api_protocol IS 'API 协议类型: openai / dashscope / anthropic';
COMMENT ON COLUMN t_ai_provider.create_time IS '创建时间';
COMMENT ON COLUMN t_ai_provider.update_time IS '更新时间';
COMMENT ON COLUMN t_ai_provider.deleted IS '是否删除 0：正常 1：删除';

-- Entity: AiModelDO (@TableName="t_ai_model", @TableId=ASSIGN_ID, @TableLogic)
CREATE TABLE t_ai_model (
    id                  VARCHAR(64)  NOT NULL PRIMARY KEY,
    provider_id         VARCHAR(64)  NOT NULL,
    model_id            VARCHAR(100) NOT NULL,
    model_name          VARCHAR(200) NOT NULL,
    capability          VARCHAR(20)  NOT NULL,
    is_default          SMALLINT     NOT NULL DEFAULT 0,
    priority            INTEGER      NOT NULL DEFAULT 100,
    enabled             SMALLINT     NOT NULL DEFAULT 1,
    supports_thinking   SMALLINT     NOT NULL DEFAULT 0,
    supports_multimodal SMALLINT     NOT NULL DEFAULT 0,
    supports_json_output SMALLINT    NOT NULL DEFAULT 0,
    supports_json_schema SMALLINT    NOT NULL DEFAULT 0,
    dimension           TEXT,
    custom_url        VARCHAR(500),
    api_protocol      VARCHAR(32),
    create_time       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_ai_model_model_id UNIQUE (model_id, deleted)
);
CREATE INDEX idx_ai_model_provider ON t_ai_model (provider_id);
CREATE INDEX idx_ai_model_capability ON t_ai_model (capability);
COMMENT ON TABLE t_ai_model IS 'AI模型配置表';
COMMENT ON COLUMN t_ai_model.id IS '主键ID';
COMMENT ON COLUMN t_ai_model.provider_id IS '供应商ID';
COMMENT ON COLUMN t_ai_model.model_id IS '模型唯一标识';
COMMENT ON COLUMN t_ai_model.model_name IS '供应商侧实际模型名';
COMMENT ON COLUMN t_ai_model.capability IS '能力类型：CHAT/EMBEDDING/RERANK';
COMMENT ON COLUMN t_ai_model.is_default IS '是否为该capability的默认模型 1：是 0：否';
COMMENT ON COLUMN t_ai_model.priority IS '优先级，数字越小优先级越高';
COMMENT ON COLUMN t_ai_model.enabled IS '是否启用 1：启用 0：禁用';
COMMENT ON COLUMN t_ai_model.supports_thinking IS '是否支持深度思考 1：是 0：否';
COMMENT ON COLUMN t_ai_model.supports_multimodal IS '是否支持多模态(图片识别) 1：是 0：否';
COMMENT ON COLUMN t_ai_model.supports_json_output IS '是否支持JSON Output(response_format=json_object) 1：是 0：否';
COMMENT ON COLUMN t_ai_model.supports_json_schema IS '是否支持JSON Schema结构化输出(response_format=json_schema) 1：是 0：否';
COMMENT ON COLUMN t_ai_model.dimension IS '向量维度（仅embedding模型）';
COMMENT ON COLUMN t_ai_model.custom_url IS '自定义URL（覆盖供应商地址）';
COMMENT ON COLUMN t_ai_model.api_protocol IS 'API 协议类型覆盖: openai / dashscope / anthropic，NULL=继承供应商';
COMMENT ON COLUMN t_ai_model.create_time IS '创建时间';
COMMENT ON COLUMN t_ai_model.update_time IS '更新时间';
COMMENT ON COLUMN t_ai_model.deleted IS '是否删除 0：正常 1：删除';

-- ============================================
-- Default Model Config (场景默认模型配置)
-- ============================================

CREATE TABLE t_default_model_config (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    config_key      VARCHAR(64)  NOT NULL UNIQUE,
    model_id        VARCHAR(128) NOT NULL,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE t_default_model_config IS '场景默认模型配置表';
COMMENT ON COLUMN t_default_model_config.id IS '主键ID';
COMMENT ON COLUMN t_default_model_config.config_key IS '配置键: chat/summary/title/multimodal/doc_image/rerank';
COMMENT ON COLUMN t_default_model_config.model_id IS '关联 t_ai_model.modelId';
COMMENT ON COLUMN t_default_model_config.create_time IS '创建时间';
COMMENT ON COLUMN t_default_model_config.update_time IS '更新时间';

-- 说明：t_ai_model 使用软删除 (deleted=1)，唯一约束为 (model_id, deleted) 组合，
-- 单列 model_id 无唯一约束，因此无法建立标准外键。
-- model_id 的有效性由 DefaultModelConfigServiceImpl.updateConfig() 业务层保障。

-- ============================================
-- Alert Config (模型调用告警配置)
-- ============================================

CREATE TABLE t_alert_config (
    id                  VARCHAR(64)  NOT NULL PRIMARY KEY,
    enabled             SMALLINT     NOT NULL DEFAULT 0,
    smtp_host           VARCHAR(255),
    smtp_port           INTEGER      NOT NULL DEFAULT 465,
    smtp_username       VARCHAR(255),
    smtp_password       VARCHAR(255),
    from_address        VARCHAR(255),
    to_address          VARCHAR(255),
    time_window_hours   INTEGER      NOT NULL DEFAULT 5,
    failure_threshold   INTEGER      NOT NULL DEFAULT 5,
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE t_alert_config IS '模型调用告警配置表';
COMMENT ON COLUMN t_alert_config.id IS '主键ID';
COMMENT ON COLUMN t_alert_config.enabled IS '是否启用告警 1：启用 0：禁用';
COMMENT ON COLUMN t_alert_config.smtp_host IS 'SMTP服务器地址';
COMMENT ON COLUMN t_alert_config.smtp_port IS 'SMTP服务器端口';
COMMENT ON COLUMN t_alert_config.smtp_username IS 'SMTP用户名';
COMMENT ON COLUMN t_alert_config.smtp_password IS 'SMTP密码';
COMMENT ON COLUMN t_alert_config.from_address IS '发件人邮箱';
COMMENT ON COLUMN t_alert_config.to_address IS '收件人邮箱';
COMMENT ON COLUMN t_alert_config.time_window_hours IS '熔断统计时间窗口（小时），1-24';
COMMENT ON COLUMN t_alert_config.failure_threshold IS '窗口内熔断次数阈值，1-10';
COMMENT ON COLUMN t_alert_config.create_time IS '创建时间';
COMMENT ON COLUMN t_alert_config.update_time IS '更新时间';
COMMENT ON COLUMN t_alert_config.deleted IS '是否删除 0：正常 1：删除';

-- ---------------------------------------------------------------------------
-- MinerU 解析服务端点配置表
-- ---------------------------------------------------------------------------
CREATE TABLE t_mineru_config (
    id              VARCHAR(64)   NOT NULL PRIMARY KEY,
    local_enabled   BOOLEAN       NOT NULL DEFAULT FALSE,
    local_base_url  VARCHAR(512),
    local_backend   VARCHAR(32)   NOT NULL DEFAULT 'pipeline',
    local_lang      VARCHAR(32)   DEFAULT 'ch',
    local_extra     VARCHAR(1024),
    remote_enabled  BOOLEAN       NOT NULL DEFAULT FALSE,
    remote_base_url VARCHAR(512),
    remote_api_key  VARCHAR(512),
    remote_backend  VARCHAR(32)   NOT NULL DEFAULT 'pipeline',
    remote_lang     VARCHAR(32)   DEFAULT 'ch',
    remote_extra    VARCHAR(1024),
    updated_by      VARCHAR(64),
    update_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE  t_mineru_config IS 'MinerU 文档解析服务端点配置（本地/远程）';
COMMENT ON COLUMN t_mineru_config.id IS '主键（固定单行，如 single）';
COMMENT ON COLUMN t_mineru_config.local_enabled IS '是否启用本地 MinerU';
COMMENT ON COLUMN t_mineru_config.local_base_url IS '本地 MinerU base URL';
COMMENT ON COLUMN t_mineru_config.remote_enabled IS '是否启用远程 MinerU';
COMMENT ON COLUMN t_mineru_config.remote_base_url IS '远程 MinerU base URL';
COMMENT ON COLUMN t_mineru_config.remote_api_key IS '远程 MinerU API Key（可选）';

-- ============================================================================
-- 第二部分：V3 增量（MinerU 解析引擎；列已在 Schema 定义，IF NOT EXISTS 自动跳过）
-- ============================================================================
-- ============================================================================
-- MinerU PDF 智能解析接入 - 增量迁移脚本
-- 1. t_knowledge_base 增加 parse_engine 列（知识库级解析引擎）
-- 2. t_knowledge_document 增加 parse_engine 列（文档级解析引擎覆盖）
-- 3. 新增 t_mineru_config 表（本地/远程 MinerU 服务端点配置）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 知识库级解析引擎（默认 AUTO：优先 MinerU，失败回退多模态 LLM）
-- ---------------------------------------------------------------------------
ALTER TABLE t_knowledge_base
    ADD COLUMN IF NOT EXISTS parse_engine VARCHAR(32) NOT NULL DEFAULT 'AUTO';

COMMENT ON COLUMN t_knowledge_base.parse_engine IS '解析引擎：AUTO/LOCAL_MINERU/REMOTE_MINERU/MULTIMODAL_LLM';

-- ---------------------------------------------------------------------------
-- 2. 文档级解析引擎覆盖（NULL 时沿用知识库级配置）
-- ---------------------------------------------------------------------------
ALTER TABLE t_knowledge_document
    ADD COLUMN IF NOT EXISTS parse_engine VARCHAR(32);

COMMENT ON COLUMN t_knowledge_document.parse_engine IS '文档级解析引擎覆盖（NULL 沿用知识库级）';

-- ---------------------------------------------------------------------------
-- 3. MinerU 服务端点配置表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_mineru_config (
    id              VARCHAR(64)   PRIMARY KEY,
    -- 本地 MinerU
    local_enabled   BOOLEAN       NOT NULL DEFAULT FALSE,
    local_base_url  VARCHAR(512),
    local_backend   VARCHAR(32)   NOT NULL DEFAULT 'pipeline',
    local_lang      VARCHAR(32)   DEFAULT 'ch',
    local_extra     VARCHAR(1024),
    -- 远程 MinerU
    remote_enabled  BOOLEAN       NOT NULL DEFAULT FALSE,
    remote_base_url VARCHAR(512),
    remote_api_key  VARCHAR(512),
    remote_backend  VARCHAR(32)   NOT NULL DEFAULT 'pipeline',
    remote_lang     VARCHAR(32)   DEFAULT 'ch',
    remote_extra    VARCHAR(1024),
    updated_by      VARCHAR(64),
    update_time     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE  t_mineru_config IS 'MinerU 文档解析服务端点配置（本地/远程）';
COMMENT ON COLUMN t_mineru_config.id              IS '主键（固定单行，如 single）';
COMMENT ON COLUMN t_mineru_config.local_enabled   IS '是否启用本地 MinerU';
COMMENT ON COLUMN t_mineru_config.local_base_url  IS '本地 MinerU base URL，如 http://127.0.0.1:8000';
COMMENT ON COLUMN t_mineru_config.local_backend   IS '本地引擎：pipeline/vlm/hybrid';
COMMENT ON COLUMN t_mineru_config.local_lang      IS '语言分组，如 ch/en';
COMMENT ON COLUMN t_mineru_config.remote_enabled  IS '是否启用远程 MinerU';
COMMENT ON COLUMN t_mineru_config.remote_base_url IS '远程 MinerU base URL';
COMMENT ON COLUMN t_mineru_config.remote_api_key  IS '远程 MinerU API Key（可选）';
COMMENT ON COLUMN t_mineru_config.remote_backend  IS '远程引擎：pipeline/vlm/hybrid';
COMMENT ON COLUMN t_mineru_config.remote_lang     IS '远程语言分组';

-- 初始化单行记录（默认全部关闭，依赖 yml 默认值兜底）
INSERT INTO t_mineru_config (id)
VALUES ('single')
ON CONFLICT (id) DO NOTHING;
-- ============================================================================
-- 第三部分：V3 增量（模型连接协议语义调整，注释覆盖）
-- ============================================================================
-- ============================================================
-- V3 增量：模型连接协议语义调整
-- 1) 去除「继承供应商」协议选项：模型连接协议三选一
--    （openai / dashscope / anthropic），模型 api_protocol 必填
-- 2) 供应商 base_url 仅 OpenAI/Anthropic 兼容协议必填，
--    官方 SDK（dashscope 及智谱/火山专属 SDK）内置默认地址可留空
-- ============================================================

COMMENT ON COLUMN t_ai_model.api_protocol IS '连接协议: openai / dashscope / anthropic';
COMMENT ON COLUMN t_ai_provider.api_protocol IS '连接协议: openai / dashscope / anthropic';
COMMENT ON COLUMN t_ai_provider.base_url IS 'API 基础地址（官方 SDK 可空，SDK 内置默认地址）';
-- ============================================================================
-- 第四部分：V3 增量（Graph RAG 实体-关系图谱）
-- 设计文档：docs/graph-rag-design.md
-- ============================================================================
-- ============================================
-- Graph RAG Schema for RAGStudio V3
-- 实体-关系图谱：入库侧 LLM 抽取，检索侧图遍历
-- 设计文档：docs/graph-rag-design.md
-- ============================================

-- ============================================
-- 图谱实体表
-- 每个知识库独立维护 (kb_id, canonical_name) 唯一，同名实体自然归并为同一节点
-- ============================================
CREATE TABLE t_graph_entity (
    id              VARCHAR(64) NOT NULL PRIMARY KEY,
    kb_id           VARCHAR(64) NOT NULL,
    canonical_name  VARCHAR(256) NOT NULL,
    display_name    VARCHAR(256) NOT NULL,
    entity_type     VARCHAR(64) NOT NULL DEFAULT 'ENTITY',
    description     TEXT,
    aliases         JSONB NOT NULL DEFAULT '[]',
    extra           JSONB,
    created_by      VARCHAR(64),
    update_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_graph_entity_kb_name UNIQUE (kb_id, canonical_name)
);
CREATE INDEX idx_graph_entity_kb ON t_graph_entity (kb_id);
CREATE INDEX idx_graph_entity_type ON t_graph_entity (kb_id, entity_type);
COMMENT ON TABLE t_graph_entity IS '知识图谱实体表';
COMMENT ON COLUMN t_graph_entity.id IS '主键 ID';
COMMENT ON COLUMN t_graph_entity.kb_id IS '知识库 ID';
COMMENT ON COLUMN t_graph_entity.canonical_name IS '规范化名称（合并/去重键）';
COMMENT ON COLUMN t_graph_entity.display_name IS '展示名（首次出现的原文）';
COMMENT ON COLUMN t_graph_entity.entity_type IS '实体类型：PERSON/ORG/DEPT/ROLE/PRODUCT/PROCESS/SYSTEM/DOC/OTHER';
COMMENT ON COLUMN t_graph_entity.description IS '实体一句话描述';
COMMENT ON COLUMN t_graph_entity.aliases IS '别名数组 JSON';
COMMENT ON COLUMN t_graph_entity.extra IS '扩展属性 JSON';

-- ============================================
-- 图谱关系表
-- 关系携带证据 chunk（source_chunk_id），图谱检索结果可回链 chunk 体系
-- ============================================
CREATE TABLE t_graph_relation (
    id               VARCHAR(64) NOT NULL PRIMARY KEY,
    kb_id            VARCHAR(64) NOT NULL,
    source_entity_id VARCHAR(64) NOT NULL,
    target_entity_id VARCHAR(64) NOT NULL,
    predicate        VARCHAR(128) NOT NULL,
    direction        SMALLINT NOT NULL DEFAULT 1,
    weight           FLOAT NOT NULL DEFAULT 1.0,
    evidence         TEXT,
    source_chunk_id  VARCHAR(64),
    doc_id           VARCHAR(64),
    extra            JSONB,
    create_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_graph_relation UNIQUE (kb_id, source_entity_id, target_entity_id, predicate)
);
CREATE INDEX idx_graph_rel_src ON t_graph_relation (source_entity_id);
CREATE INDEX idx_graph_rel_tgt ON t_graph_relation (target_entity_id);
CREATE INDEX idx_graph_rel_kb ON t_graph_relation (kb_id);
CREATE INDEX idx_graph_rel_doc ON t_graph_relation (kb_id, doc_id);
CREATE INDEX idx_graph_rel_chunk ON t_graph_relation (source_chunk_id);
COMMENT ON TABLE t_graph_relation IS '知识图谱关系表';
COMMENT ON COLUMN t_graph_relation.id IS '主键 ID';
COMMENT ON COLUMN t_graph_relation.kb_id IS '知识库 ID';
COMMENT ON COLUMN t_graph_relation.source_entity_id IS '源实体 ID';
COMMENT ON COLUMN t_graph_relation.target_entity_id IS '目标实体 ID';
COMMENT ON COLUMN t_graph_relation.predicate IS '关系谓词（如 汇报给/负责/属于/审批）';
COMMENT ON COLUMN t_graph_relation.direction IS '方向 1=有向 source→target 0=无向';
COMMENT ON COLUMN t_graph_relation.weight IS '聚合权重（重复证据累加）';
COMMENT ON COLUMN t_graph_relation.evidence IS '证据原文（截断 200 字符）';
COMMENT ON COLUMN t_graph_relation.source_chunk_id IS '证据 chunk ID（回链 chunk 体系）';
COMMENT ON COLUMN t_graph_relation.doc_id IS '来源文档 ID（级联清理）';

-- ============================================
-- 抽取结果缓存表
-- content_hash 与 t_knowledge_chunk.content_hash 同源：
-- 内容未变直接复用缓存，重跑/重导零 LLM 成本（幂等）
-- ============================================
CREATE TABLE t_graph_extraction (
    id                 VARCHAR(64) NOT NULL PRIMARY KEY,
    kb_id              VARCHAR(64) NOT NULL,
    doc_id             VARCHAR(64) NOT NULL,
    chunk_id           VARCHAR(64) NOT NULL,
    chunk_content_hash VARCHAR(64) NOT NULL,
    entity_json        JSONB,
    relation_json      JSONB,
    status             VARCHAR(16) NOT NULL DEFAULT 'DONE',
    model_id           VARCHAR(128),
    duration_ms        INTEGER,
    error_message      TEXT,
    create_time        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_graph_extraction_chunk UNIQUE (chunk_id)
);
CREATE INDEX idx_graph_extraction_doc ON t_graph_extraction (doc_id);
COMMENT ON TABLE t_graph_extraction IS '图谱抽取结果缓存表';
COMMENT ON COLUMN t_graph_extraction.chunk_content_hash IS 'chunk 内容哈希（幂等复用键）';
COMMENT ON COLUMN t_graph_extraction.entity_json IS '抽取实体 JSON [{name,type,description}]';
COMMENT ON COLUMN t_graph_extraction.relation_json IS '抽取关系 JSON [{source,target,predicate,evidence}]';
COMMENT ON COLUMN t_graph_extraction.status IS 'DONE/FAILED/SKIPPED';
COMMENT ON COLUMN t_graph_extraction.model_id IS '生成所用模型（换模型需失效重抽）';

-- ============================================
-- 构建任务日志表
-- ============================================
CREATE TABLE t_graph_build_log (
    id               VARCHAR(64) NOT NULL PRIMARY KEY,
    kb_id            VARCHAR(64) NOT NULL,
    trigger_type     VARCHAR(16) NOT NULL,
    doc_id           VARCHAR(64),
    status           VARCHAR(16) NOT NULL,
    entity_added     INTEGER DEFAULT 0,
    entity_merged    INTEGER DEFAULT 0,
    relation_added   INTEGER DEFAULT 0,
    relation_removed INTEGER DEFAULT 0,
    llm_calls        INTEGER DEFAULT 0,
    duration_ms      BIGINT,
    error_message    TEXT,
    create_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_graph_build_log_kb ON t_graph_build_log (kb_id);
CREATE INDEX idx_graph_build_log_doc ON t_graph_build_log (doc_id);
COMMENT ON TABLE t_graph_build_log IS '图谱构建任务日志表';
COMMENT ON COLUMN t_graph_build_log.trigger_type IS '触发类型 DOC(单文档)/KB(全库重建)/CHUNK(单块)';
COMMENT ON COLUMN t_graph_build_log.status IS 'RUNNING/SUCCESS/FAILED';
COMMENT ON COLUMN t_graph_build_log.llm_calls IS 'LLM 调用次数（成本统计）';

-- ============================================
-- 社区表（可选：全局检索 Phase 2 预留）
-- ============================================
CREATE TABLE t_graph_community (
    id           VARCHAR(64) NOT NULL PRIMARY KEY,
    kb_id        VARCHAR(64) NOT NULL,
    community_id VARCHAR(64) NOT NULL,
    level        INTEGER NOT NULL DEFAULT 1,
    summary      TEXT,
    entity_count INTEGER NOT NULL DEFAULT 0,
    entity_ids   JSONB,
    build_id     VARCHAR(64),
    create_time  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_graph_community_kb ON t_graph_community (kb_id, community_id);
COMMENT ON TABLE t_graph_community IS '知识图谱社区表（全局检索摘要缓存）';
-- ============================================================================
-- 第五部分：V3 增量（Graph RAG 运行期配置表，后管「知识图谱」页动态控制）
-- ============================================================================
-- ============================================================================
-- Graph RAG 运行期配置表（后管页面动态控制）
-- 1. t_graph_config：图谱总开关 / 检索通道开关（单行，总开关仅由本表控制，不读 yaml/.env）
-- 2. 抽取 LLM 复用 t_default_model_config 的 graph_extract 场景键（无需新表）
-- ============================================================================

CREATE TABLE IF NOT EXISTS t_graph_config (
    id                VARCHAR(64)   PRIMARY KEY,
    enabled           BOOLEAN       NOT NULL DEFAULT FALSE,
    retrieval_enabled BOOLEAN       NOT NULL DEFAULT TRUE,
    updated_by        VARCHAR(64),
    update_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE  t_graph_config IS 'Graph RAG 运行期配置（后管页面控制，单行）';
COMMENT ON COLUMN t_graph_config.id                IS '主键（固定单行，如 single）';
COMMENT ON COLUMN t_graph_config.enabled           IS '图谱总开关：false 时构建与检索全部停用';
COMMENT ON COLUMN t_graph_config.retrieval_enabled IS '图谱检索通道开关（依赖图谱已构建）';
COMMENT ON COLUMN t_graph_config.updated_by        IS '最后修改人';
COMMENT ON COLUMN t_graph_config.update_time       IS '最后修改时间';

-- 初始化单行记录（默认关闭，总开关仅由后管「知识图谱」页控制）
INSERT INTO t_graph_config (id)
VALUES ('single')
ON CONFLICT (id) DO NOTHING;
-- ============================================================================
-- 第五部分 B：V4 增量（提示词统一管理，后管「提示词管理」页动态编辑 + 热重载）
-- ============================================================================
-- 说明：
--   1. t_prompt_config：提示词主表。key 为语义化标识（如 react_system / query_rewrite），
--      content 保存提示词正文（含 section 的模板保存完整文件）。
--   2. t_prompt_config_history：变更历史（每次更新前将旧内容落历史，支持回滚）。
--   3. 内容种子由应用启动时自动播种（从 classpath resources/prompt/*.st 读取），
--      此处不硬编码大段文本，避免与 classpath 默认值双份维护。
--   4. 读取策略：DB 快照优先（enabled=true），缺失/禁用时回退 classpath 默认值。
-- ============================================================================

CREATE TABLE IF NOT EXISTS t_prompt_config (
    id          VARCHAR(64)   PRIMARY KEY,
    category    VARCHAR(32)   NOT NULL DEFAULT 'chat',
    name        VARCHAR(128)  NOT NULL,
    description VARCHAR(512),
    content     TEXT          NOT NULL,
    variables   VARCHAR(255),
    version     INT           NOT NULL DEFAULT 1,
    enabled     BOOLEAN       NOT NULL DEFAULT TRUE,
    updated_by  VARCHAR(64),
    update_time TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE  t_prompt_config IS '提示词统一管理（后管动态编辑，DB 优先、classpath 兜底）';
COMMENT ON COLUMN t_prompt_config.id          IS '提示词语义化 key（如 react_system / query_rewrite）';
COMMENT ON COLUMN t_prompt_config.category    IS '分类：chat 对话 / query 查询理解 / memory 记忆 / graph 图谱 / ingestion 文档处理 / tool 工具';
COMMENT ON COLUMN t_prompt_config.name        IS '显示名称';
COMMENT ON COLUMN t_prompt_config.description IS '用途说明（后管展示）';
COMMENT ON COLUMN t_prompt_config.content     IS '提示词正文（含 section 的模板保存完整文件内容）';
COMMENT ON COLUMN t_prompt_config.variables   IS '支持的占位符说明，如 {tool_definitions},{kb_context}';
COMMENT ON COLUMN t_prompt_config.version     IS '版本号（每次编辑 +1）';
COMMENT ON COLUMN t_prompt_config.enabled     IS '启用开关：false 时回退 classpath 默认值';
COMMENT ON COLUMN t_prompt_config.updated_by  IS '最后修改人';
COMMENT ON COLUMN t_prompt_config.update_time IS '最后修改时间';

CREATE TABLE IF NOT EXISTS t_prompt_config_history (
    id          BIGSERIAL     PRIMARY KEY,
    prompt_id   VARCHAR(64)   NOT NULL,
    version     INT           NOT NULL,
    content     TEXT          NOT NULL,
    updated_by  VARCHAR(64),
    update_time TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE  t_prompt_config_history IS '提示词变更历史（支持查看 diff 与回滚）';
COMMENT ON COLUMN t_prompt_config_history.id          IS '历史主键（自增）';
COMMENT ON COLUMN t_prompt_config_history.prompt_id   IS '提示词 key（关联 t_prompt_config.id）';
COMMENT ON COLUMN t_prompt_config_history.version     IS '该历史记录对应的版本号';
COMMENT ON COLUMN t_prompt_config_history.content     IS '该版本的提示词内容';
COMMENT ON COLUMN t_prompt_config_history.updated_by  IS '修改人';
COMMENT ON COLUMN t_prompt_config_history.update_time IS '修改时间';

CREATE INDEX IF NOT EXISTS idx_prompt_history_prompt
    ON t_prompt_config_history (prompt_id, version);
-- ============================================================================
-- 第六部分：种子数据（全新部署初始化，全部 ON CONFLICT 幂等）
-- ============================================================================
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
-- AI Provider（22 家）
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
('1821609200917151744', 'anthropic', 'Anthropic (Claude)', 'https://api.anthropic.com', NULL, '{"chat": "/v1/messages", "models": "/v1/models"}'::jsonb, 0, 's3://ragstudio/provider-icons/anthropic.svg', 0, 'anthropic', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821609200921346048', 'xiaomi', '小米 (MiMo)', 'https://api.xiaomimimo.com', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models"}'::jsonb, 0, 's3://ragstudio/provider-icons/xiaomi.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821609200925540352', 'spark', '讯飞星火', 'https://spark-api-open.xf-yun.com', NULL, '{"chat": "/v1/chat/completions"}'::jsonb, 0, 's3://ragstudio/provider-icons/spark.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821609200929734656', 'ai360', '360智脑', 'https://api.360.cn', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models"}'::jsonb, 0, 's3://ragstudio/provider-icons/ai360.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1821609200933928960', 'xai', 'xAI (Grok)', 'https://api.x.ai', NULL, '{"chat": "/v1/chat/completions", "models": "/v1/models"}'::jsonb, 0, 's3://ragstudio/provider-icons/xai.svg', 0, 'openai', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- AI Model（54 条）
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
('1831609201722392641', '1821609200917151744', 'claude-opus-4-1', 'claude-opus-4-1', 'CHAT', 0, 2, 0, 1, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201722392642', '1821609200921346048', 'mimo-v2.5', 'mimo-v2.5', 'CHAT', 0, 1, 0, 1, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201722392643', '1821609200921346048', 'mimo-v2.5-pro', 'mimo-v2.5-pro', 'CHAT', 0, 2, 0, 1, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201722392644', '1821609200925540352', '4.0Ultra', '4.0Ultra', 'CHAT', 0, 1, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201722392645', '1821609200925540352', 'generalv3.5', 'generalv3.5 (Max)', 'CHAT', 0, 2, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201722392646', '1821609200929734656', '360gpt-pro', '360gpt-pro', 'CHAT', 0, 1, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201722392647', '1821609200933928960', 'grok-4', 'grok-4', 'CHAT', 0, 1, 0, 1, 1, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('1831609201722392648', '1821609200933928960', 'grok-3', 'grok-3', 'CHAT', 0, 2, 0, 0, 0, NULL, NULL, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
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

-- ============================================
-- SKILL 管理（DB 事实源 + 版本化，见 docs/skill-management-design.md）
-- t_skill          主表：当前行 = 当前生效版本（current_version 指向 t_skill_version）
-- t_skill_version  版本表：每次保存/回滚/导入产生一行，全量保留（含当前版本）
-- t_skill_file     版本文件表：路径 + sha256（内容在 t_skill_blob）
-- t_skill_blob     内容寻址存储：跨版本去重的文件内容
-- ============================================

CREATE TABLE IF NOT EXISTS t_skill (
    id              BIGSERIAL     PRIMARY KEY,
    name            VARCHAR(64)   NOT NULL UNIQUE,
    description     VARCHAR(1024),
    skill_type      VARCHAR(16),
    current_version INT           NOT NULL DEFAULT 1,
    enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    change_log      VARCHAR(512),
    synced_version  INT,
    updated_by      VARCHAR(64),
    update_time     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_skill_version (
    id           BIGSERIAL   PRIMARY KEY,
    skill_id     BIGINT      NOT NULL,
    version      INT         NOT NULL,
    change_log   VARCHAR(512),
    file_count   INT         NOT NULL DEFAULT 0,
    total_size   BIGINT      NOT NULL DEFAULT 0,
    manifest     TEXT,
    tree_hash    VARCHAR(64),
    created_by   VARCHAR(64),
    create_time  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (skill_id, version)
);
CREATE INDEX IF NOT EXISTS idx_skill_version_skill ON t_skill_version (skill_id, version DESC);

CREATE TABLE IF NOT EXISTS t_skill_file (
    id         BIGSERIAL    PRIMARY KEY,
    version_id BIGINT       NOT NULL,
    skill_id   BIGINT       NOT NULL,
    file_path  VARCHAR(512) NOT NULL,
    is_binary  BOOLEAN      NOT NULL DEFAULT FALSE,
    size       BIGINT       NOT NULL,
    blob_hash  VARCHAR(64)  NOT NULL,
    UNIQUE (version_id, file_path)
);
CREATE INDEX IF NOT EXISTS idx_skill_file_version ON t_skill_file (version_id);

CREATE TABLE IF NOT EXISTS t_skill_blob (
    sha256      VARCHAR(64) PRIMARY KEY,
    size        BIGINT      NOT NULL,
    is_binary   BOOLEAN     NOT NULL DEFAULT FALSE,
    content     BYTEA       NOT NULL,
    create_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
