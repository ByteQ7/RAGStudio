# RAGStudio 部署方案

## 项目概述

RAGStudio 是一个基于 Java 17 + Spring Boot 3.5 的企业级 Agentic RAG 平台。所有请求走 **ReACT Agent 循环**（Thought → Action → Observation），LLM 自主推理、调用工具、观察结果，直到给出最终答案。

### 核心功能

| 功能 | 说明 |
|------|------|
| **ReACT Agent 循环** | Thought → Action → Observation 循环替代传统线性 RAG 管线 |
| **多模型路由** | 数据库驱动动态配置，百炼/DeepSeek/SiliconFlow 故障秒级切换 |
| **混合检索** | pgvector 语义 + tsvector 关键词，RRF 融合排序 |
| **语义裁剪** | 用 Cross-encoder 模型对 Chunk 逐句打分，裁剪无关句子，节省 LLM Token |
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
┌──────────────────┐
│ 语义裁剪服务     │  ← Python 微服务 (Cross-encoder 0.6B)
│ skills/          │     占用 1-1.5GB 内存
│ semantic-highlight│     http://localhost:8001
└──────────────────┘
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

# 运行增量迁移（如果存在）
for f in resources/database/V2/migration/*.sql; do
  echo "执行迁移: $f"
  psql -U postgres -d ragstudio -f "$f"
done
```

### 3. 环境变量配置

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

### 4. 启动后端

```bash
cd bootstrap
mvn spring-boot:run
# → http://localhost:9090/api/ragstudio
```

### 5. 启动前端

```bash
cd frontend
npm install
npm run dev
# → http://localhost:5173
```

---

## 可选组件：语义裁剪服务

裁剪服务是一个独立的 Python 微服务，对检索到的 Chunk 逐句打分，只保留与问题相关的句子。

### 启动方式一：Docker 镜像（推荐）

```bash
# 进入裁剪服务目录
cd resources/docker/semantic-highlight

# 加载镜像（已构建好的 tar 文件）
docker load -i ragstudio-highlight.tar

# 启动
docker compose up -d

# 验证
curl http://localhost:8001/health
# 输出: {"status":"ok","model_loaded":true,"device":"cpu"}
```

### 启动方式二：自行构建

```bash
cd resources/docker/semantic-highlight

# 构建镜像（约需 5-10 分钟，首次需下载模型）
docker build -t ragstudio-highlight:latest .

# 启动
docker compose up -d
```

### 启动方式三：直接运行 Python（无需 Docker）

```bash
cd resources/docker/semantic-highlight

# 安装依赖
pip install -r requirements.txt
pip install torch --index-url https://download.pytorch.org/whl/cpu

# 启动
bash start.sh
# 或: QUANTIZE=int8 uvicorn main:app --host 0.0.0.0 --port 8001
```

### 验证裁剪功能

```bash
curl -X POST http://localhost:8001/highlight \
  -H "Content-Type: application/json" \
  -d '{"question":"年假政策","chunks":[{"id":"1","text":"公司年假制度规定员工每年享有带薪年假。事假需提前申请。"}],"threshold":0.2}'
```

### 关闭裁剪

如果不需要此功能，修改 `.env`：

```bash
SEMANTIC_HIGHLIGHT_ENABLED=false
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
| `SEMANTIC_HIGHLIGHT_ENABLED` | `true` | 语义裁剪开关 |
| `SEMANTIC_HIGHLIGHT_BASE_URL` | `http://localhost:8001` | 裁剪服务地址 |
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

### Q: 语义裁剪服务连接超时
确认裁剪服务已启动：`curl http://localhost:8001/health`。如不需要可关闭：`SEMANTIC_HIGHLIGHT_ENABLED=false`。

### Q: Agent 重复检索结果为空
提示词已优化——Agent 会在关键词不足时先用 `[USER_CHOICE]` 让用户补充信息，再检索。确保 `rag_search` 工具的 query 参数使用 3-5 个关键词和同义词。

### Q: 前端页面空白
检查 Vite 开发服务器是否启动在 5173 端口，后端是否在 9090 端口。
