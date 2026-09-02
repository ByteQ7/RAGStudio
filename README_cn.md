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

**RAGStudio** 基于 **Java 17 + Spring Boot 3.5** 构建，所有请求统一走 **AgentScope ReActAgent 循环**（原生工具调用），LLM 自主推理、调用工具、观察结果，直到给出最终答案。

### 核心能力

| 能力 | 说明 |
|------|------|
| **AgentScope ReActAgent** | 基于 AgentScope 编排框架的原生工具调用 ReACT 循环，事件流实时透传 SSE，工具结果以观察角色注入 |
| **官方 SDK 模型调用层** | 厂商官方 SDK 优先（DashScope SDK / 智谱 zai-sdk / 火山 ark / OpenAI / Anthropic），无 SDK 厂商走 OpenAI/Anthropic 兼容策略，支持同步/流式/深度思考参数 |
| **22 家供应商预置** | 种子配置内置 22 家供应商（百炼、DeepSeek、SiliconFlow、智谱、Moonshot、xAI、小米 MiMo、讯飞星火、360智脑等）54 个默认模型 |
| **结构化输出降级链** | LLM 结构化输出按模型能力自动降级：JSON Schema → JSON Output → 纯提示词 |
| **统一工具发现** | `tool_reader` 遍历 MCP + SKILL 注册表，LLM 运行态自主发现和调用任意工具 |
| **多模型路由** | 数据库驱动动态配置，供应商故障自动切换 |
| **混合检索** | pgvector 语义 + pg_trgm 关键词，RRF 融合排序 |
| **Graph RAG** | LLM 逐 chunk 抽取实体关系 + 图谱局部检索通道接入 RRF 融合，管理后台知识图谱可视化 |
| **MinerU 解析** | 本地 / 远程 MinerU 增强 PDF 解析，失败回退 Tika + 多模态 LLM |
| **深度思考** | 0–100% 可调推理深度，分步链式思考过程可见 |
| **多模态知识库** | 图片/PDF/Office 文档多模态分块与嵌入，IMAGE chunk 独立向量检索，检索结果图片直通多模态 LLM |
| **会话分组** | 会话分组管理，分组专属指令自动注入对话管线 |
| **检索质量优化** | 嵌入语义选库防误杀 + 分数簇感知动态 TopK + 多模态 Rerank（图片 base64 直传），100 题评测集问答准确率 97% |
| **SKILL 技能系统** | `SKILL.md` + 可选 `skill.yaml`，数据库版本化管理（历史/diff/导入/回滚），零代码接入 Agent 循环 |
| **全链路追踪** | 自研轻量级分布式追踪，记录管线每个阶段耗时 |
| **数据摄取管线** | 可视化编排的文档处理流水线：抓取 → 解析 → 分块 → 增强 → 索引 |
| **仪表盘监控** | 管理后台实时展示系统 KPI、请求趋势、模型调用量、性能指标 |

---

## 架构

```
用户提问
  │
  ▼
StreamChatPipeline
  ├─ 1. 记忆加载 — 对话历史 + 摘要 + 分组专属指令
  ├─ 2. 强实体 ID 检测 — 订单号/单据号类问题跳过改写与选库
  ├─ 3. 查询改写 — 多轮改写 + 问题拆分
  ├─ 4. 知识库语义选择 — 嵌入相似度过滤无关知识库
  └─ 5. Agent Loop — 迭代至 FINISH
        ├─ 工具：rag_search / MCP / SKILL（检索在循环内执行）
        ├─ Thought → Action → Observation → 继续
        └─ Thought → FINISH → Final Answer（流式推送，[^chunk_N] 引用）
```

### 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Java 17, Spring Boot 3.5, MyBatis-Plus, RocketMQ, Sa-Token |
| AI 引擎 | AgentScope ReActAgent 编排 + 厂商官方 SDK 网关（OpenAI / DashScope / Anthropic / 火山 ark / 智谱，OpenAI/Anthropic 兼容策略兜底） |
| 向量存储 | PostgreSQL + pgvector (HNSW) + pg_trgm (GIN) |
| 前端 | React 18, TypeScript, Vite, Tailwind CSS, shadcn/ui, Zustand, AntV G6（图谱可视化）, Mermaid |
| 基础设施 | Redis, Docker 沙箱, S3 对象存储（MinIO / RustFS） |

### 模块结构

```
ragstudio
├── bootstrap/     — 业务代码（控制器、服务、Agent 循环、检索、图谱）
├── framework/     — 基础框架（缓存、数据库、安全、异常、MQ、分布式ID）
└── infra-ai/      — AI 基础设施（LLM 客户端与 SDK 网关、路由、推理、Embedding）
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
# ── MinIO (S3 兼容存储；RustFS 或任意 S3 兼容服务均可) ──
docker run -d --name minio -p 9000:9000 -p 9001:9001 -e MINIO_ROOT_USER=admin -e MINIO_ROOT_PASSWORD=password minio/minio server /data --console-address ":9001"

# 2. 初始化数据库
createdb -U postgres ragstudio
psql -U postgres -d ragstudio -f resources/database/schema_all.sql   # 全量初始化（Schema + 种子数据，仅全新部署）

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
迭代 0:  LLM → 调用 time_now
         系统 → Observation: 2026年6月21日

迭代 1:  LLM → 调用 web-search("6月21日节日")
         系统 → Observation: 父亲节

迭代 2:  LLM → 直接回答：今天是2026年6月21日，父亲节。
         → 流式推送至前端
```

- **AgentScope 原生工具调用**：ReActAgent 驱动循环，工具定义自动转为各厂商 function calling 格式，工具结果以独立观察角色注入（不与用户发言混淆）
- **工具全量注册**：`rag_search`（混合检索）+ `tool_reader`（MCP/SKILL 发现）+ SKILL + MCP 全部注册到 Toolkit
- **知识库语义选择**：嵌入相似度选库，带并列带保护与阈值门控——闲聊等明显无关问题不触发任何检索
- **查询改写**：多轮改写 + 问题拆分；简单问题规则直通省一次 LLM 往返；强实体 ID（订单号、单据号）跳过改写/选库走精确检索
- **结构化输出降级链**：JSON Schema → JSON Output → 纯提示词，按模型能力自动选择；能力标记错误时自动降级重试
- **引用溯源**：回答携带 `[^chunk_N]` 编号引用，chunk 定位到具体知识库文档

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
| 关键词 | PostgreSQL pg_trgm `ILIKE` 子串匹配 | GIN (`gin_trgm_ops`) |

RRF 公式：`score = Σ 1/(60 + rank)`，无需人工调权重。

检索后处理链（per-KB 粗召融合 → Rerank → 动态 TopK）进一步精排：文本/图片统一由多模态 Rerank 模型（如 qwen3-vl-rerank）语义打分（图片以 base64 data URI 直传，不依赖公网地址），分数簇感知动态 TopK 按分数分布决定送入 LLM 的条数。100 题内部评测集问答准确率 **97%**。

### Graph RAG

- **图谱抽取**：LLM 逐 chunk 抽取实体与关系（结构化输出约束，按 chunk 增量抽取 + 缓存，校验失败自动修复重试）
- **图谱检索**：查询实体匹配图谱，局部子图按跳数扩展，以上下文三元组注入 RRF 融合——检索侧 `rag.graph.retrieval.*`，总开关在后管「知识图谱」页动态控制（默认关闭）
- **图谱可视化**：管理后台交互式知识图谱视图（AntV G6），构建日志与统计

### 知识库与文档

- 多格式上传：PDF / DOCX / HTML / Markdown / Excel（支持文件和 URL）
- **MinerU 解析**：本地或远程（mineru.net 免费 API）MinerU 版面感知 PDF 解析，失败回退 Tika；PDF 内表格/图片由多模态 LLM 提取
- 三种分块策略：重叠分块、递归分块、结构感知分块
- 定时同步（cron + ETag/Hash 变更检测）
- 分块查看、启用/禁用、手动编辑

### 会话分组

- 创建/重命名/删除分组，会话批量移入/移出分组
- 分组专属指令自动注入该组会话的对话管线
- 从分组页发起的新会话自动归组

### MCP 集成

- 运行时注册外部 MCP 服务器（SSE / Streamable HTTP）
- Agent 通过 `tool_reader search` 在循环中自主发现和调用工具
- 失败时可自动重试或切换工具

### SKILL 技能系统

在 `skills/{name}/` 目录下写 `SKILL.md`（元数据标准来源）+ 可选 `skill.yaml` 即可，无需写 Java 或搭 MCP：

````markdown
# skills/my-skill/SKILL.md
---
name: my-skill
description: "查询内部 API。当用户询问 xxx 时使用。"
---

## 使用步骤
...
````

- `name`/`description` 写在 SKILL.md frontmatter（兼容 Agent Skills 开放标准，可跨端复用）
- 类型：`http`（REST API）、`script`（脚本）、`command`（命令）；无执行配置则为纯知识型技能（通过 `tool_reader` 激活）
- **数据库版本化管理**：技能存于数据库（`t_skill` / `t_skill_version` / `t_skill_file` / `t_skill_blob`），支持版本历史、文件级 diff、回滚、zip 导入导出与 GitHub 导入；`skills/` 目录作为文件工作区，启动时以数据库为准对账（存量目录自动收编）
- `script`/`command` 在 Docker 沙箱隔离运行（只读文件系统、去权、无网络、30 秒超时）
- 管理后台「技能」页：版本管理、diff 查看、加载失败诊断

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
| `rag.agent.timeout-ms` | `120000` | Agent 总执行超时（毫秒） |
| `rag.skills.dir` | `${ragstudio.data-dir}/skills` | SKILL 工作区目录 |
| `rag.skills.max-versions` | `0` | 技能版本保留数（0 = 不限制） |
| `rag.skills.allowed-commands` | `""` | SKILL 命令白名单（空=禁用 command 类型） |
| `rag.skills.sandbox.enabled` | `true` | Docker 沙箱隔离执行 script/command |
| `rag.skills.script-timeout-ms` | `30000` | 脚本执行超时（毫秒） |
| `rag.query-rewrite.enabled` | `true` | 多轮查询改写开关（简单问题规则直通） |
| `rag.search.default-top-k` | `10` | 检索返回 Top-K 条数 |
| `rag.search.max-final-chunks` | `5` | 重排后基准条数（动态 TopK 目标值） |
| `rag.search.channels.hybrid-rrf.k` | `60` | RRF 融合平滑常数 |
| `rag.search.crop.enabled` | `false` | 语义裁剪开关（进程内 bge-small-zh-v1.5，在 `.env` 中设 `RAG_CROP_ENABLED=true` 开启） |
| `rag.memory.history-keep-turns` | `6` | 保留最近对话轮数 |
| `rag.memory.compress-threshold` | `12` | 压缩触发阈值 |
| `rag.memory.summary-enabled` | `true` | 启用对话摘要 |
| `rag.memory.title-max-length` | `30` | 会话标题最大字符数 |
| `rag.rate-limit.global.max-concurrent` | `3` | 全局并发对话数限制 |
| `rag.rate-limit.global.max-wait-seconds` | `15` | 排队最大等待秒数 |
| `rag.model-routing.selection.failure-threshold` | `2` | 连续失败次数达阈值后模型临时摘除 |
| `rag.graph.retrieval.enabled` | `true` | 图谱检索通道（总开关在后管，默认关闭） |
| `rag.trace.enabled` | `true` | 启用链路追踪 |
| `mineru.enabled` | `false` | MinerU 文档解析（false = 仅 Tika/多模态兜底） |
| `app.default-avatar-url` | `https://avatars.githubusercontent.com/u/583231?v=4` | 用户默认头像 |

---

<p align="center">
  <a href="LICENSE">MIT License</a> · Built by ByteQ
</p>
