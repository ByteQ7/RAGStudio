#!/bin/bash
# 启动语义高亮微服务 (zilliz/semantic-highlight-bilingual-v1)
# 使用: bash start.sh
# 
# 环境变量:
#   CUDA_VISIBLE_DEVICES=  GPU 选择
#   QUANTIZE=int8         量化模式 (fp16/int8, 默认 int8)

cd "$(dirname "$0")"

# 默认 INT8 量化（比 FP32 省 60%+ 内存）
QUANTIZE="${QUANTIZE:-int8}"

echo "正在启动语义高亮服务，量化模式=${QUANTIZE} ..."

# 清除代理环境变量（避免 HuggingFace 下载被拦截）
env -u https_proxy -u http_proxy -u HTTP_PROXY -u HTTPS_PROXY \
    -u all_proxy -u ALL_PROXY \
    QUANTIZE="${QUANTIZE}" \
  uvicorn main:app --host 0.0.0.0 --port 8001
