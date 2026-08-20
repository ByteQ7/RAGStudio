#!/bin/bash
# RAGStudio AI 供应商图标上传
# 从 resources/provider-icons/ 上传彩色图标到 S3（配置读取 .env 的 RUSTFS_*）
# 用法: ./scripts/upload-provider-icons.sh [--force]
cd "$(dirname "$0")/.." || exit 1
exec python3 scripts/upload-provider-icons.py "$@"