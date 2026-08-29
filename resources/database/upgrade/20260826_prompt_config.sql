-- ============================================================================
-- 已有线上库升级脚本：提示词统一管理（V4 增量）
-- 对应全新部署脚本 schema_all.sql「第五部分 B」
-- 说明：
--   1. 建 t_prompt_config 与 t_prompt_config_history 两表（IF NOT EXISTS 可重放）。
--   2. 提示词内容种子由应用启动时自动播种（从 classpath resources/prompt/*.st 读取），
--      本脚本不硬编码大段文本。
--   3. 执行方式：psql -f 20260826_prompt_config.sql 或后管数据库迁移工具。
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
