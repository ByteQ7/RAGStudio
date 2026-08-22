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