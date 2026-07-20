-- Migration: 为 t_message 添加 Chunk 高亮字段
-- 用于异步高亮 AI 回答中引用自 Chunk 的文字片段

ALTER TABLE t_message
    ADD COLUMN IF NOT EXISTS highlight_data TEXT,
    ADD COLUMN IF NOT EXISTS highlight_status VARCHAR(32);

COMMENT ON COLUMN t_message.highlight_data IS 'Chunk高亮映射JSON：[{chunkId, kbName, docName, spans:[{start,end,text}]}]';
COMMENT ON COLUMN t_message.highlight_status IS '高亮状态：PENDING/PROCESSING/COMPLETED/FAILED';
