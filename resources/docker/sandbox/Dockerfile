# RAGStudio SKILL 沙箱镜像
# 基于 Alpine，预装脚本执行常用工具
# 构建：docker build -t ragstudio-sandbox:latest -f resources/docker/sandbox/Dockerfile .
FROM alpine:3.18

RUN apk add --no-cache \
    bash \
    curl \
    python3 \
    coreutils \
    jq

CMD ["sh"]
