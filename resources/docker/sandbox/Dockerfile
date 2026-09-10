# RAGStudio SKILL 沙箱镜像
# 基于 Alpine，预装脚本执行常用工具（三类 SKILL 统一沙箱执行后，http 类型也依赖 curl）
# 构建：docker build -t sandbox:latest -f resources/docker/sandbox/Dockerfile .
FROM alpine:3.18

RUN apk add --no-cache \
    bash \
    curl \
    wget \
    python3 \
    py3-requests \
    coreutils \
    jq \
    git \
    unzip \
    zip \
    ca-certificates \
    tzdata

CMD ["sh"]
