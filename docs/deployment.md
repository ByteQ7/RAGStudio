# RAGStudio 部署方案

## 项目概述

RAGStudio 是一个基于 Java 17 + Spring Boot 3.5 的企业级 Agentic RAG 平台。所有请求走 **ReACT Agent 循环**（Thought → Action → Observation），LLM 自主推理、调用工具、观察结果，直到给出最终答案。

### 核心功能

| 功能 | 说明 |
|------|------|
| **ReACT Agent 循环** | Thought → Action → Observation 循环替代传统线性 RAG 管线 |
| **多模型路由** | 数据库驱动动态配置，百炼/DeepSeek/SiliconFlow 故障秒级切换 |
| **混合检索** | pgvector 语义 + tsvector 关键词，RRF 融合排序 |
| **语义裁剪** | 进程内 bge-small-zh-v1.5 模型对 Chunk 逐句打分，裁剪无关句子，节省 LLM Token |
| **MCP 协议** | 运行态发现和调用外部工具，Agent 自主决策 |
| **深度思考** | 0–100% 可调推理深度，分步链式思考过程可见 |
| **多模态对话** | 图片上传（文件/粘贴），S3 存储 + 预签名 HTTP 展示 |
| **SKILL 技能系统** | 写 YAML 定义工具，启动时自动加载，零代码接入 Agent 循环 |
| **数据摄取管线** | 可视化编排的文档处理流水线：抓取 → 解析 → 分块 → 增强 → 索引 |
| **全链路追踪** | 轻量级分布式追踪，记录管线每个阶段耗时，支持管理后台查看 |
| **仪表盘监控** | 管理后台实时展示系统 KPI、请求趋势、模型调用量、性能指标 |

---

## 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                       前端 (React + Vite)                    │
│                  http://localhost:5173                       │
└──────────────────────┬──────────────────────────────────────┘
                       │ /api/ragstudio/* (代理转发到 9090)
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                    后端 (Spring Boot)                        │
│              http://localhost:9090/api/ragstudio             │
│                                                             │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────────┐  │
│  │ Agent    │ │ 混合检索 │ │ SKILL    │ │ 数据摄取管线   │  │
│  │ 循环     │ │ pgvector │ │ YAML 工具│ │ 文档处理流水线  │  │
│  └──────────┘ │ + tsvector│ └──────────┘ └───────────────┘  │
│               └──────────┘                                  │
└──────┬──────────────┬──────────────┬────────────────────────┘
       │              │              │
       ▼              ▼              ▼
┌──────────┐ ┌──────────────┐ ┌──────────────────┐
│PostgreSQL│ │    Redis     │ │    RocketMQ      │
│+pgvector │ │  缓存/限流   │ │  异步任务队列    │
└──────────┘ └──────────────┘ └──────────────────┘
       │
       ▼
┌──────────────────┐
│  S3 对象存储     │
│ (MinIO / RustFS) │
└──────────────────┘
```

### 可选组件

```
┌──────────────────────────────┐
│ 语义裁剪模型（进程内）       │  bge-small-zh-v1.5 量化版
│ resources/models/           │  约 90MB，CPU 单句 ~10ms
│ bge-small-zh-v1.5           │  由后端进程内加载（无独立服务）
└──────────────────────────────┘
```

---

## 环境要求

| 组件 | 版本要求 | 说明 |
|------|---------|------|
| JDK | 17+ | 必须 |
| Maven | 3.8+ | 后端构建 |
| Node.js | 18+ | 前端构建 |
| PostgreSQL | 14+ | 需安装 pgvector 扩展 |
| Redis | 6+ | 缓存和限流 |
| Docker | 可选 | SKILL 沙箱隔离执行需要 |

---

## 部署步骤

### 1. 基础设施启动

```bash
# ── RocketMQ（根据 CPU 架构选择）──
docker compose -f resources/docker/rocketmq-stack-5.2.0.compose.yaml up -d       # ARM64
docker compose -f resources/docker/rocketmq-stack-amd-5.2.0.compose.yaml up -d   # AMD64

# ── PostgreSQL + pgvector ──
docker run -d --name pgvector \
  -e POSTGRES_DB=ragstudio \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  pgvector/pgvector:pg16

# ── Redis ──
docker run -d --name redis -p 6379:6379 redis:7-alpine

# ── MinIO (S3 兼容存储) ──
docker run -d --name minio \
  -p 9000:9000 -p 9001:9001 \
  -e MINIO_ROOT_USER=admin \
  -e MINIO_ROOT_PASSWORD=password \
  minio/minio server /data --console-address ":9001"
```

### 2. 初始化数据库

```bash
# 创建数据库
createdb -U postgres ragstudio

# 导入建表语句
psql -U postgres -d ragstudio -f resources/database/V2/schema_pg.sql

# 导入初始数据
psql -U postgres -d ragstudio -f resources/database/V2/init_data_pg.sql
```

### 3. 上传 AI 供应商图标（S3）

初始化数据中 17 家供应商的 `icon_url` 指向 `s3://ragstudio/provider-icons/<name>.svg`，
首次部署需将图标上传到 S3（配置读取 `.env` 的 `RUSTFS_URL / RUSTFS_ACCESS_KEY / RUSTFS_SECRET_KEY`，
需先完成第 4 步环境变量配置后再执行）：

```bash
./scripts/upload-provider-icons.sh          # 幂等上传，已存在则跳过
./scripts/upload-provider-icons.sh --force  # 强制覆盖
```

### 4. 环境变量配置

```bash
cp .env-example .env
```

编辑 `.env`，按实际情况修改以下配置：

```bash
# 必填项
DB_USERNAME=你的数据库用户名
DB_PASSWORD=你的数据库密码
DB_URL=jdbc:postgresql://localhost:5432/ragstudio

REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=你的Redis密码（无密码则留空）

RUSTFS_URL=http://localhost:9000
RUSTFS_ACCESS_KEY=你的S3访问密钥
RUSTFS_SECRET_KEY=你的S3密钥

# 可选项
SERVER_PORT=9090
SEMANTIC_HIGHLIGHT_ENABLED=true           # 如有部署裁剪服务则开启
SEMANTIC_HIGHLIGHT_BASE_URL=http://localhost:8001
SANDBOX_ENABLED=true                      # 如有Docker则开启
```

完整配置项见 `.env-example`。

### 5. 启动后端

```bash
cd bootstrap
mvn spring-boot:run
# → http://localhost:9090/api/ragstudio
```

### 6. 启动前端

```bash
cd frontend
npm install
npm run dev
# → http://localhost:5173
```

---

## 可选组件：语义裁剪

语义裁剪已迁移为进程内方案：在检索后、重排序前，用 bge-small-zh-v1.5（量化版，约 90MB）对每个 Chunk 逐句打分，只保留与问题相关的句子，并完整保留代码块。

### 准备模型文件（首次部署）

模型文件需先下载到本地目录（默认 `resources/models/bge-small-zh-v1.5`），包含 `config.json`、`vocab.txt` 与 `onnx/model_quantized.onnx`（或 `onnx/model.onnx`）。

```bash
# 方式一：脚本下载（推荐）
bash resources/models/bge-small-zh-v1.5/download.sh

# 方式二：手动下载（无脚本环境）
mkdir -p resources/models/bge-small-zh-v1.5/onnx
curl -L -o resources/models/bge-small-zh-v1.5/config.json \
  https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/main/config.json
curl -L -o resources/models/bge-small-zh-v1.5/vocab.txt \
  https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/main/vocab.txt
curl -L -o resources/models/bge-small-zh-v1.5/onnx/model_quantized.onnx \
  https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/main/onnx/model_quantized.onnx
```

### 启用语义裁剪

在 `.env` 中设置（默认关闭）：

```bash
RAG_CROP_ENABLED=true
RAG_CROP_MODEL_PATH=./resources/models/bge-small-zh-v1.5
RAG_CROP_THRESHOLD=0.35
```

> 注意：开启后应用启动时会校验本地模型目录与模型文件，缺失或加载失败则启动报错（fail-fast）。

### 关闭语义裁剪

如不需要此功能，修改 `.env`：

```bash
RAG_CROP_ENABLED=false
```

---

## 配置参考

核心配置通过 `.env` 管理，所有配置都有默认值，不填也能运行。

### `.env` 配置项

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DB_USERNAME` | — | PostgreSQL 用户名 |
| `DB_PASSWORD` | — | PostgreSQL 密码 |
| `DB_URL` | — | PostgreSQL JDBC URL |
| `REDIS_HOST` | `localhost` | Redis 地址 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_PASSWORD` | — | Redis 密码 |
| `ROCKETMQ_NAMESERVER` | `localhost:9876` | RocketMQ 地址 |
| `RUSTFS_URL` | — | S3 对象存储地址 |
| `RUSTFS_ACCESS_KEY` | — | S3 访问密钥 |
| `RUSTFS_SECRET_KEY` | — | S3 密钥 |
| `SERVER_PORT` | `9090` | 后端服务端口 |
| `MAX_FILE_SIZE` | `50MB` | 上传文件大小限制 |
| `MAX_REQUEST_SIZE` | `100MB` | 上传请求大小限制 |
| `RAG_CROP_ENABLED` | `false` | 语义裁剪开关（默认关闭） |
| `RAG_CROP_MODEL_PATH` | `./resources/models/bge-small-zh-v1.5` | 裁剪模型目录 |
| `SEMANTIC_HIGHLIGHT_READ_TIMEOUT` | `120s` | 裁剪服务超时 |
| `SANDBOX_ENABLED` | `true` | Docker 沙箱开关 |
| `SANDBOX_IMAGE` | `sandbox:latest` | 沙箱镜像 |
| `TOKEN_TIMEOUT` | `2592000` | 登录令牌过期时间(秒) |
| `MAX_CONCURRENT` | `1` | 全局并发对话数 |
| `TRACE_ENABLED` | `true` | 链路追踪开关 |
| `DEFAULT_TOP_K` | `10` | 默认检索数量 |
| `DEMO_MODE` | `false` | 演示模式 |
| `DEFAULT_AVATAR_URL` | GitHub 头像 | 用户默认头像 URL |

---

## 生产环境建议

### 配置调优

```bash
# .env 生产推荐值
SAVE_AI_LOG=false           # 关闭 AI 对话日志
TRACE_ENABLED=true          # 保持追踪（定位问题用）
MAX_CONCURRENT=5            # 根据服务器核数调整
TOKEN_TIMEOUT=28800         # 8 小时
DEMO_MODE=false
```

### 安全

- 所有密码/密钥放在 `.env`，不要提交到 Git
- `SANDBOX_ENABLED=true` 时确保 Docker 环境安全
- 生产环境务必关闭 `SAVE_AI_LOG`

### 监控

- 链路追踪数据在管理后台 `http://localhost:5173/admin/traces` 查看
- RocketMQ Dashboard 在 `http://localhost:8082`
- MinIO Console 在 `http://localhost:9001`

---

## 常见问题

### Q: 启动报数据库连接失败
检查 `.env` 中 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 是否正确，确认 PostgreSQL 已启动。
### Q: 语义裁剪未生效

确认 `.env` 中 `RAG_CROP_ENABLED=true`，并检查 `RAG_CROP_MODEL_PATH` 目录包含 `config.json`、`vocab.txt`、`onnx/model_quantized.onnx`（或 `onnx/model.onnx`）。如不需要可关闭：`RAG_CROP_ENABLED=false`。

### Q: Agent 重复检索结果为空
提示词已优化——Agent 会在关键词不足时先用 `[USER_CHOICE]` 让用户补充信息，再检索。确保 `rag_search` 工具的 query 参数使用 3-5 个关键词和同义词。

### Q: 前端页面空白
检查 Vite 开发服务器是否启动在 5173 端口，后端是否在 9090 端口。
