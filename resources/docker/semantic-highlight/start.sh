#!/bin/bash
# 启动语义高亮微服务 (zilliz/semantic-highlight-bilingual-v1)
# 使用: bash start.sh
# 
# 环境变量:
#   CUDA_VISIBLE_DEVICES=  GPU 选择
#   QUANTIZE=int8         量化模式 (fp16/int8, 默认 int8)
#   HIGHLIGHT_WORKERS     chunk 级并发线程数 (默认 min(8, cpu核心数))
#   HIGHLIGHT_BATCH_SIZE  单个 chunk 内句子批量大小 (默认 2)

cd "$(dirname "$0")"

# 默认 INT8 量化（比 FP32 省 60%+ 内存）
QUANTIZE="${QUANTIZE:-int8}"

echo "正在启动语义高亮服务，量化模式=${QUANTIZE} ..."

# 强制离线模式（模型已在构建时下载，运行时无需联网）
env HF_HUB_OFFLINE=1 \
    QUANTIZE="${QUANTIZE}" \
  uvicorn main:app --host 0.0.0.0 --port 8001
