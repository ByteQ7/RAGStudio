-- V4__remove_highlight_default_model_config.sql
-- 移除已废弃的 Chunk 高亮默认模型配置
-- 高亮功能已改为检索后语义裁剪（ContextCropper），不再需要独立的默认模型配置

DELETE FROM t_default_model_config WHERE config_key = 'highlight';
