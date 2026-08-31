-- ============================================================
-- SKILL 管理升级脚本（存量库执行；全新部署直接执行 schema_all.sql，无需本文件）
-- 内容与 schema_all.sql 中「SKILL 管理」段落一致，可重复执行（IF NOT EXISTS）
-- 见 docs/skill-management-design.md
-- ============================================================

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
