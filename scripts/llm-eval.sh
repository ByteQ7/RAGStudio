#!/bin/bash
# RAGStudio LLM 评测（推荐使用）
# 流程: 读取 scripts/eval-cases.md → RAG 对话 → 另一个 LLM 打分 → 报告
# 用法: ./scripts/llm-eval.sh [--limit N] [--reuse 日志目录] [--concurrent N]
cd "$(dirname "$0")/.." || exit 1
exec python3 scripts/llm-eval.py "$@"
