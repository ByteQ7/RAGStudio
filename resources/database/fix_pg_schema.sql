-- ============================================
-- PostgreSQL Schema Fix Script
-- 修复 schema_pg.sql 中的列类型及过时表问题
-- ============================================

-- Fix 1: t_rag_trace_node.trace_id VARCHAR(20) -> VARCHAR(64)
-- 与 t_rag_trace_run.trace_id 类型保持一致
ALTER TABLE t_rag_trace_node ALTER COLUMN trace_id TYPE VARCHAR(64);

-- Fix 2: t_sample_question.question VARCHAR(255) -> VARCHAR(1024)
-- 示例问题内容可能超过255字符
ALTER TABLE t_sample_question ALTER COLUMN question TYPE VARCHAR(1024);

-- Fix 3: t_intent_node 表已废弃（意图树功能已移除）
-- schema_pg.sql 中仍包含该表的 CREATE TABLE 定义，属于过时代码
-- 如无需保留数据和表结构，可执行以下语句清理：
-- DROP TABLE IF EXISTS t_intent_node CASCADE;
