-- ============================================================================
-- 已有线上库升级脚本：模型结构化输出能力标记（增量）
-- 对应全新部署脚本 schema_all.sql 中 t_ai_model 表的
-- supports_json_output / supports_json_schema 两列。
--
-- 背景：
--   业务侧（图谱抽取、查询实体抽取、查询改写、MCP 参数提取、元数据抽取等）
--   开始声明 JSON Schema 结构化输出。实际下发格式由模型能力决定，降级链为：
--   json_schema（约束解码强保证）→ json_object（仅保证合法 JSON）→ 不下发（纯提示词）。
--
-- 说明：
--   1. 两列默认 0（不发送 response_format），保持存量模型行为完全不变；
--      在「模型管理」中按供应商文档为模型开启对应能力。
--   2. ADD COLUMN IF NOT EXISTS 可重放。
--   3. 执行方式：psql -f 20260829_model_structured_output.sql
-- ============================================================================

ALTER TABLE t_ai_model
    ADD COLUMN IF NOT EXISTS supports_json_output SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS supports_json_schema SMALLINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN t_ai_model.supports_json_output IS '是否支持JSON Output(response_format=json_object) 1：是 0：否';
COMMENT ON COLUMN t_ai_model.supports_json_schema IS '是否支持JSON Schema结构化输出(response_format=json_schema) 1：是 0：否';
