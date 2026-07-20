# RAGStudio — 智能 Agent RAG 平台

<p align="center">
  <em>基于 ReACT Agent 循环的深度推理引擎 · 覆盖从文档入库到智能问答的完整链路</em>
</p>

<p align="center">
  <a href="README.md"><img src="https://img.shields.io/badge/🌐_English_Version-6366f1?style=for-the-badge&logo=readme&logoColor=white" alt="English" height="36"/></a>
</p>

---

<p align="center">
  <a href="https://openlist.qbyte.top/@s/GVWZlUAk?preview=video" target="_blank">
    <img src="https://img.shields.io/badge/▶_观看演示视频-7c3aed?style=for-the-badge&logo=youtubegaming&logoColor=white&labelColor=581c87" alt="观看演示视频" height="48"/>
  </a>
</p>

## 概述

**RAGStudio** 基于 **Java 17 + Spring Boot 3.5** 构建，所有请求统一走 **ReACT Agent 循环**（Thought → Action → Observation），LLM 自主推理、调用工具、观察结果，直到给出最终答案。

### 核心能力

| 能力 | 说明 |
|------|------|
| **ReACT Agent 循环** | Thought → Action → Observation 循环替代传统线性 RAG 管线 |
| **多模型路由** | 数据库驱动动态配置，百炼/DeepSeek/SiliconFlow 故障秒级切换 |
| **混合检索** | pgvector 语义 + tsvector 关键词，RRF 融合排序 |
| **MCP 协议** | 运行态发现和调用外部工具，Agent 自主决策 |
| **深度思考** | 0–100% 可调推理深度，分步链式思考过程可见 |
| **多模态对话** | 图片上传（文件/粘贴），S3 存储 + 预签名 HTTP 展示 |
| **SKILL 技能系统** | 写 YAML 定义工具，零代码接入 Agent 循环 |
| **全链路追踪** | 自研轻量级分布式追踪，记录管线每个阶段耗时 |
| **数据摄取管线** | 可视化编排的文档处理流水线：抓取 → 解析 → 分块 → 增强 → 索引 |
| **仪表盘监控** | 管理后台实时展示系统 KPI、请求趋势、模型调用量、性能指标 |
| **数据摄取管线** | 可视化编排的文档处理流水线：抓取 → 解析 → 分块 → 增强 → 索引 |
| **仪表盘监控** | 管理后台实时展示系统 KPI、请求趋势、模型调用量、性能指标 |

---

## 架构

```
用户提问
  │
  ▼
StreamChatPipeline
  ├─ 1. 记忆加载 — 对话历史 + 摘要
  ├─ 2. 工具注册 — MCP + rag_search + 自定义 SKILL
  ├─ 3. KB 相关性判断 — 轻量 LLM 过滤无关知识库
  └─ 4. Agent Loop — 迭代至 FINISH
        ├─ Thought → Action → Observation → 继续
        └─ Thought → FINISH → Final Answer（流式推送）
```

### 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Java 17, Spring Boot 3.5, MyBatis-Plus, RocketMQ, Sa-Token |
| AI 引擎 | ReACT Agent Loop + Spring AI (OpenAI 兼容) |
| 向量存储 | PostgreSQL + pgvector (HNSW) + tsvector (GIN) |
| 前端 | React 18, TypeScript, Vite, Tailwind CSS, shadcn/ui, Zustand |
| 基础设施 | Redis, Docker 沙箱, S3 对象存储 |

### 模块结构

```
ragstudio
├── bootstrap/     — 业务代码（控制器、服务、Agent 循环）
├── framework/     — 基础框架（缓存、数据库、安全、异常、MQ、分布式ID）
└── infra-ai/      — AI 基础设施（LLM 客户端、路由、推理、Embedding）
```

---

## 快速开始

**环境要求：** JDK 17+, Maven 3.8+, Node.js 18+, PostgreSQL 14+ (pgvector), Redis 6+, Docker

```bash
# 1. 启动基础设施（Docker）
# ── RocketMQ（根据 CPU 架构选择版本）──
docker compose -f resources/docker/rocketmq-stack-5.2.0.compose.yaml up -d       # ARM64
docker compose -f resources/docker/rocketmq-stack-amd-5.2.0.compose.yaml up -d   # AMD64
# ── PostgreSQL + pgvector ──
docker run -d --name pgvector -e POSTGRES_DB=ragstudio -e POSTGRES_PASSWORD=postgres -p 5432:5432 pgvector/pgvector:pg16
# ── Redis ──
docker run -d --name redis -p 6379:6379 redis:7-alpine
# ── MinIO (S3 兼容存储) ──
docker run -d --name minio -p 9000:9000 -p 9001:9001 -e MINIO_ROOT_USER=admin -e MINIO_ROOT_PASSWORD=password minio/minio server /data --console-address ":9001"

# 2. 初始化数据库
createdb -U postgres ragstudio
psql -U postgres -d ragstudio -f resources/database/V2/schema_pg.sql
psql -U postgres -d ragstudio -f resources/database/V2/init_data_pg.sql

# 3. 配置环境变量
cp .env-example .env   # 修改数据库 / Redis / RocketMQ / S3 配置
# .env 文件在项目根目录，bootstrap 模块通过 spring-dotenv 自动读取 ../.env

# 4. 启动后端
cd bootstrap && mvn spring-boot:run   # → http://localhost:9090/api/ragstudio

# 5. 启动前端
cd frontend && npm install && npm run dev   # → http://localhost:5173
```

> **注意：** 后端 context-path 为 `/api/ragstudio`，前端的 Vite 代理配置会将 `/api` 请求转发到 `localhost:9090`，开发环境下无需跨域配置。

---

## 核心功能

### Agent 循环

```
迭代 0:  Thought → 需要查日期
          Action → time_now({})
          Observation → 2026年6月21日

迭代 1:  Thought → 查节日
          Action → web_search({"query": "6月21日 节日"})
          Observation → 父亲节

迭代 2:  Thought → 信息充分
          Action → FINISH
          Final Answer → 今天是2026年6月21日，父亲节。
```

- **先规划再执行**：多步任务先输出 Plan 规划再逐步执行
- **知识库相关性判断**：进入循环前用 LLM 判断哪些知识库相关，只检索这些
- **缺参数处理**：可搜索参数（日期）自动获取；用户参数（城市）反问用户
- **格式校正**：LLM 未按格式输出时自动注入纠正提示重试

### 深度思考

聊天界面提供滑块控制推理深度（0–100%）。深度越高，LLM 在给出最终答案前输出更详细的链式推理过程（展示在流式输出中）。思考内容持久化在 `t_message.thinking_content`。

### 多模态对话

- 文件选择或 Ctrl+V 粘贴上传图片（单次最多 10 张）
- 图片上传到 S3，通过预签名 HTTP URL 在浏览器展示
- 支持普通问答和 Agent 两种模式

### 混合检索（RRF 融合）

两通道并行检索后融合：

| 通道 | 方法 | 索引 |
|------|------|------|
| 向量 | pgvector cosine similarity | HNSW |
| 关键词 | PostgreSQL tsvector `plainto_tsquery` + `ts_rank` | GIN |

RRF 公式：`score = Σ 1/(60 + rank)`，无需人工调权重。

### 知识库与文档

- 多格式上传：PDF / DOCX / HTML / Markdown（支持文件和 URL）
- 三种分块策略：重叠分块、递归分块、结构感知分块
- 定时同步（cron + ETag/Hash 变更检测）
- 分块查看、启用/禁用、手动编辑

### MCP 集成

- 运行时注册外部 MCP 服务器（SSE / Streamable HTTP）
- Agent 在循环中自主发现和调用工具
- 失败时可自动重试或切换工具

### SKILL 技能系统

写 YAML 文件放到 `skills/{name}/` 即可，无需写 Java 或搭 MCP：

```yaml
# skills/my-tool/skill.yaml
name: my-tool
description: "查询内部 API"
type: http
url: https://internal.example.com/api
```

- 类型：`http`（REST API）、`script`（脚本）、`command`（命令）
- `script`/`command` 在 Docker 沙箱隔离运行（只读文件系统、去权、无网络、30 秒超时）
- 启动时自动加载，无需额外配置

### 链路追踪

- 全链路 trace：每个阶段记录耗时、状态、异常
- 管理后台查看延迟/成功率趋势，节点级耗时详情
- 消息点赞/点踩反馈

---

## 配置参考

核心配置（`bootstrap/src/main/resources/application.yaml`）：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `rag.agent.max-iterations` | `10` | Agent 循环最大迭代次数 |
| `rag.agent.tool-timeout-ms` | `30000` | 单工具调用超时（毫秒） |
| `rag.skills.allowed-commands` | `""` | SKILL 命令白名单（空=禁用 command 类型） |
| `rag.skills.sandbox.enabled` | `true` | Docker 沙箱隔离执行 |
| `rag.search.default-top-k` | `10` | 检索返回 Top-K 条数 |
| `rag.search.channels.hybrid-rrf.k` | `60` | RRF 融合平滑常数 |
| `rag.search.channels.hybrid-rrf.top-k` | `5` | RRF 融合后最终返回数 |
| `rag.memory.history-keep-turns` | `4` | 保留最近对话轮数 |
| `rag.memory.compress-threshold` | `8` | 压缩触发阈值 |
| `rag.memory.summary-enabled` | `true` | 启用对话摘要 |
| `rag.memory.title-max-length` | `30` | 会话标题最大字符数 |
| `rag.trace.enabled` | `true` | 启用链路追踪 |
| `rag.rate-limit.global.max-concurrent` | `1` | 全局并发对话数限制 |
| `rag.rate-limit.global.max-wait-seconds` | `15` | 排队最大等待秒数 |
| `rag.semantic-highlight.enabled` | `false` | 语义裁剪开关 |
| `rag.query-rewrite.enabled` | `true` | 多轮查询改写开关 |
| `app.default-avatar-url` | `https://avatars.githubusercontent.com/u/583231?v=4` | 用户默认头像 |

---

<p align="center">
  <a href="LICENSE">MIT License</a> · Built by ByteQ
</p>