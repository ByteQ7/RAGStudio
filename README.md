# RAGStudio — 智能 Agent RAG 平台

<p align="center">
  <em>基于 ReACT Agent 循环的深度推理引擎 · 覆盖从文档入库到智能问答的完整链路</em>
</p>

<p align="center">
  <a href="README_en.md">
    <img src="https://img.shields.io/badge/English_Version-blue?style=for-the-badge&logoColor=white" alt="English Version"/>
  </a>
</p>

---

## 目录

- [项目简介](#项目简介)
- [架构总览](#架构总览)
- [核心功能](#核心功能)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [分层架构](#分层架构)
- [快速开始](#快速开始)
- [API 示例](#api-示例)
- [配置说明](#配置说明)
- [截图展示](#截图展示)
- [项目文档](#项目文档)

---

## 项目简介

**RAGStudio** 是一个基于 **Java 17 + Spring Boot 3.5** 构建的 AI 问答平台，采用 **ReACT Agent 循环**（Thought → Action → Observation），所有请求统一走 Agent 管线，LLM 在循环中自主推理、调用工具（包括知识库检索）、观察结果，直到给出最终答案。

### 核心能力

- **ReACT Agent 循环** — 用 Thought → Action → Observation 循环替代传统线性 RAG 管线，LLM 在循环中自主推理、调用工具、观察结果
- **多文档知识库** — 支持 PDF/DOCX/HTML/Markdown 等多种格式文档的解析、向量化存储和语义检索
- **多模型路由** — 数据库驱动的动态模型配置，支持百炼、SiliconFlow、DeepSeek 等多个 LLM 提供商，故障时秒级自动切换
- **MCP 协议集成** — 支持运行时动态发现和调用外部工具，Agent 在循环中自主决策调用
- **全链路追踪** — 自研轻量级分布式追踪系统，记录 RAG 管线的每个阶段

---

## 架构总览

![架构图](docs/assets/architecture.svg)

### Agent 管线流程

所有请求统一走 Agent 循环。Agent 在循环中自主推理、调用工具（包括检索工具）、观察结果，直到给出最终答案。

```
用户提问
  │
  ▼
ChatQueueLimiter (分布式限流)
  │
  ▼
StreamChatPipeline.doExecuteAgent()
  │
  ├─ 1. 记忆加载 ─── 并行加载对话历史 + 摘要
  │
  ├─ 2. 工具注册 ─── MCP 工具 + rag_search 工具注册到 ToolRegistry
  │
  ├─ 3. KB 相关性判断 ─── 轻量 LLM 判断问题是否与所选知识库相关
  │
  └─ 4. Agent Loop ─── 迭代至 FINISH
        ├─ Thought → Action(TOOL_CALL) → Observation → 继续
        └─ Thought → Action(FINISH) → Final Answer（逐字流式推送）
```



---

## 核心功能

### ReACT Agent 循环

Agent 模式为默认交互方式，LLM 在循环中自主推理和决策：

```
迭代 0:  Thought → 需要查日期
          Action → time_now({})
          Observation → 2026年6月21日

迭代 1:  Thought → 知道了日期，查节日
          Action → web_search({query: "6月21日 节日"})
          Observation → 父亲节

迭代 2:  Thought → 信息充分
          Action → FINISH
          Final Answer → 今天是2026年6月21日，父亲节。
```

- **Plan-then-Execute** — Agent 第一步输出 Plan 规划多步方案，后续按计划执行
- **知识库精准检索** — LLM 判断问题与哪些知识库相关（`relevant_collection_names`），只检索相关的
- **内置时间工具** — `time_now` 支持任意 IANA 时区（含夏令时自动处理），不依赖外部服务
- **MCP 工具调用** — MCP 工具通过适配器接入 ToolRegistry，Agent 在循环中自主决策调用
- **Agent 步骤持久化** — 推理步骤序列化为 JSON 存储在 `t_message.agent_steps`，前端可回放
- **缺参数先问** — 可搜索参数（日期）→ 搜索获取；用户参数（城市）→ 反问用户
- **格式校正** — LLM 未按要求输出 ReACT 格式时，自动注入纠正提示后重试一次

### 知识库关联性判断

进入 Agent 循环前，用轻量 LLM 调用判断问题与所选知识库是否相关：

- **按库精准过滤** — LLM 返回 `relevant_collection_names` 字段，指定具体哪些知识库相关，只检索这些
- **判断依据** — 知识库名称 + 知识库描述（创建/编辑时可填写）
- **JSON 断尾修复** — 处理 LLM 输出被截断的情况，确保解析健壮

### 知识库管理

- 创建/编辑/删除知识库，支持配置 embedding 模型
- **知识库描述** — 创建和编辑时可填写描述，用于 AI 关联判断
- 文档上传（文件/URL），支持 PDF/DOCX/HTML/Markdown 格式
- 自动分块 + embedding 向量化入库
- 定时刷新同步（cron 表达式 + ETag/Hash 变更检测）
- 分块查看、启用/禁用、手动编辑

### 混合检索

默认同时进行**向量检索 + 关键词检索**，通过 RRF（Reciprocal Rank Fusion）算法融合排序：

- **向量通道**：pgvector cosine similarity（HNSW 索引），处理语义匹配
- **关键词通道**：PostgreSQL tsvector 全文检索（GIN 索引），`plainto_tsquery` + `ts_rank`，确保专有名词、编码、型号等精确匹配不被语义检索忽略
- **RRF 融合**：`score = Σ 1/(60 + rank)`，两通道并行检索后融合，去重 → Rerank → 输出

两条通道互补——向量找"意思相近的"，关键词找"字面匹配的"。RRF 确保两者都有贡献，不依赖人工调权重。

### 引用溯源

Agent 回答中涉及知识库内容时，自动在答案下方展示参考文献。回答完成后，后端扫描答案中的 `[^chunk_{id}]` 标记，匹配对应的 Chunk 原文，通过 SSE 事件 `citation` 推送到前端。

- **方案A**：LLM 在回答中主动使用 `[^chunk_id]` 标记引用（System Prompt 中的 kbContext 已带 `[^chunk_id]` 前缀）
- **方案B**：LLM 未带标记时，前端自动做连续 10 字文本匹配兜底
- **持久化**：引用的 Chunk 随消息一起存入 `t_message.citations`，刷新页面后仍可查看
- **交互**：默认折叠，可逐个展开查看 Chunk 原文

### 文档分块策略

目前内置两种分块策略，在知识库配置中可选：

**固定大小分块（`fixed_size`）**
- 按固定字符数切分，默认 512 字符/块，支持配置 overlap（默认 128 字符）
- 切块时自动对齐句子边界（中英文句号、感叹号、问号），不会从句子中间断开
- 自动处理 URL 换行断开的问题

**结构感知分块（`structure_aware`）**
- 专为 Markdown 文档设计，识别标题、代码块、段落等结构
- 按最小/目标/最大三级预算打包块（默认 600/1400/1800 字符）
- 块内尽量保持语义完整，不会把标题和正文拆到两块里
- 支持 block-level overlap、末尾小块的自动合并

### 多模型路由与熔断降级

- 数据库驱动的动态模型配置，运行时无重启切换
- 优先级路由 + Circuit Breaker 状态机（CLOSED → OPEN → HALF_OPEN）
- 单模型故障秒级自动 fallback，流式场景与同步场景共享健康检查

### MCP 集成

- MCP 服务器注册与管理，启动时异步加载不阻塞应用
- Agent 在循环中自主调用，支持多次调用和链式调用
- 工具调用失败时 Agent 可自主重试或换用其他工具

### SKILL 技能系统

用户写一个 YAML 文件放到 `skills/` 目录，Agent 就能多一个可调用的工具，不需要写 Java 代码或搭 MCP 服务器。

- **目录结构**：`skills/{name}/skill.yaml` + `SKILL.md`（说明书）+ `scripts/`（可执行脚本）+ `references/`（参考资料）
- **自动加载**：启动时扫描目录 → 写入 Redis 缓存，15 秒轮询热更新，增删改文件自动生效，无需重启
- **工具类型**：`http`（调外部 API）、`script`（执行脚本）、`command`（执行命令）
- **内置阅读器**：`skill_reader` 工具让 LLM 在 Agent 循环中读取 SKILL.md、浏览脚本和参考资料
- **管理接口**：`GET /admin/skills` 查看列表，`POST /admin/skills/reload` 手动刷新

#### 安全沙箱

`script` 和 `command` 类型的 SKILL 不在宿主机上直接执行，而是在 Docker 容器中隔离运行：

- `--read-only`：根文件系统只读，无法写文件
- `--cap-drop=ALL`：剔除所有 Linux 权限
- `--user 1000:1000`：非 root 运行
- `--tmpfs /tmp:size=1G,noexec`：临时文件上限 1GB，禁止执行
- `--memory=256m --cpus=0.5 --pids-limit=50`：资源上限
- `--network=none`：默认无网络
- 命令先经过 `SecurityAuditor` 黑名单审计（拦截 `rm -rf /`、`sudo`、反弹 Shell、内网探测等）
- 超时 30 秒未完成自动 `docker kill`

### 管理后台

- **仪表盘** — 用户量、对话量、消息量等核心 KPI 概览，延迟/成功率趋势
- **知识库管理** — 知识库列表、文档管理、分块详情、处理日志
- **摄入管道** — 管道定义与执行任务管理
- **RAG 链路追踪** — 全链路 trace 展示，节点级耗时查看
- **系统设置** — 模型参数、记忆配置、限流策略
- **MCP 服务** — 外部 MCP 服务器注册管理
- **用户管理** — 账号管理、角色分配

### 用户认证与权限

- Sa-Token 登录/注销（用户名+密码）
- 管理员 / 普通用户双角色权限

---

## 技术栈

| 层级 | 技术 |
|------|------|
| **后端** | Java 17, Spring Boot 3.5.7, MyBatis-Plus, RocketMQ, Sa-Token |
| **AI 引擎** | ReACT Agent Loop (Thought → Action → Observation) |
| **LLM 集成** | Spring AI (OpenAI 兼容协议), 多模型路由 + 熔断降级 |
| **向量存储** | PostgreSQL + pgvector (HNSW 索引) + tsvector 全文检索 (GIN 索引) |
| **前端** | React 18, TypeScript, Vite, Tailwind CSS, shadcn/ui, Zustand |
| **缓存** | Redis + Redisson (分布式锁) |
| **文档解析** | Apache Tika 3.2 (PDF/DOCX/HTML/Markdown) |
| **协议** | MCP (Model Context Protocol) 1.1.2 |
| **对象存储** | S3 兼容存储 (RustFS / MinIO) |

---

## 项目结构

### 多模块 Maven 架构

```
ragstudio（父模块）
├── bootstrap          # 应用启动模块，包含所有业务代码
├── framework          # 基础框架层：缓存、数据库、安全、异常处理、MQ、分布式ID
└── infra-ai           # AI 基础设施层：LLM 客户端、Embedding、Rerank、模型路由
```

### 模块详解

#### `bootstrap` — 应用启动模块

包含完整的业务代码，按功能域（package-by-feature）组织：

```
bootstrap/src/main/java/com/byteq/ai/ragstudio/
├── admin/              # 管理后台仪表盘
├── aimodel/            # AI 模型配置管理
├── core/               # 文档解析与分块
├── ingestion/          # 摄入管道引擎
├── knowledge/          # 知识库管理
├── mcp/                # MCP 服务管理
├── rag/                # RAG 核心引擎
│   ├── controller/     # REST 控制器
│   ├── service/        # 业务服务层
│   │   ├── pipeline/   # 流式聊天管线
│   │   ├── handler/    # 流式事件处理
│   │   ├── ratelimit/  # 分布式限流
│   │   └── impl/       # 服务实现
│   ├── core/           # 核心引擎
│   │   ├── agent/      # ReACT Agent 循环
│   │   ├── retrieve/   # 多通道检索
│   │   ├── memory/     # 会话记忆管理
│   │   ├── prompt/     # 提示词模板
│   │   ├── rewrite/    # 查询改写
│   │   ├── vector/     # 向量存储抽象
│   │   └── mcp/        # MCP 工具注册与执行
│   └── dao/            # 数据访问层
│       ├── entity/     # 数据库实体
│       └── mapper/     # MyBatis Mapper
├── user/               # 用户认证与权限
└── RAGStudioApplication.java  # 启动入口
```

#### `framework` — 基础框架层

公共基础能力，被 bootstrap 模块依赖：

```
framework/src/main/java/com/byteq/ai/ragstudio/framework/
├── cache/               # Redis 序列化
├── config/              # 自动配置 (DB, MQ, Web)
├── context/             # 用户上下文
├── convention/          # 通用数据契约
├── database/            # MyBatis-Plus 元数据处理器
├── distributedid/       # 分布式 ID 生成器
├── errorcode/           # 错误码定义
├── exception/           # 统一异常体系
├── idempotent/          # 幂等提交保证
├── mq/                  # RocketMQ 消息队列封装
├── security/            # 密码哈希
├── trace/               # 轻量级分布式追踪
└── web/                 # 全局异常处理等
```

#### `infra-ai` — AI 基础设施层

AI 相关客户端与路由逻辑，被 bootstrap 模块依赖：

```
infra-ai/src/main/java/com/byteq/ai/ragstudio/infra/
├── chat/                # LLM 聊天客户端
│   └── client/          # 各厂商实现：百炼、DeepSeek、SiliconFlow
├── config/              # 动态模型配置
├── embedding/           # Embedding 客户端
├── enums/               # 模型提供商枚举
├── http/                # HTTP 客户端封装
├── model/               # 模型路由与健康检查
├── rerank/              # Rerank 排序服务
├── springai/            # Spring AI 适配器
├── token/               # Token 计数
└── util/                # LLM 响应清理
```

---

## 分层架构

RAGStudio 在每个模块内部采用标准的**四层架构**：

```
┌─────────────────────────────────────────────────────────┐
│                    Controller 层                          │
│              REST API 入口 / 请求校验 / 响应封装            │
├─────────────────────────────────────────────────────────┤
│                    Service 层                             │
│              业务逻辑编排 / 事务管理 / 领域服务              │
├─────────────────────────────────────────────────────────┤
│                    DAO 层                                 │
│              Mapper (MyBatis-Plus) + Entity               │
├─────────────────────────────────────────────────────────┤
│                  Database (PostgreSQL)                     │
│                       + Redis                             │
└─────────────────────────────────────────────────────────┘
```

### 分层职责

| 层级 | 职责 | 典型注解 |
|------|------|---------|
| **Controller** | HTTP 请求接收、参数校验、VO 封装返回 | `@RestController`, `@RequestMapping`, `@Valid` |
| **Service** | 业务逻辑编排、事务管理、跨领域协调 | `@Service`, `@Transactional`, `@Async` |
| **Service.Impl** | 服务接口实现 | `@Service` |
| **DAO.Mapper** | MyBatis 数据映射、SQL 定义 | `@Mapper`, `BaseMapper<T>` |
| **DAO.Entity** | 数据库表映射实体 | `@TableName`, `@TableId`, `@TableField` |
| **VO / DTO** | 视图层对象 / 数据传输对象 | `@Data` |

### 典型代码组织

以知识库模块为例：

```
knowledge/
├── controller/                 # REST 接口层
│   ├── KnowledgeBaseController.java
│   ├── request/                # 请求参数对象
│   └── vo/                     # 响应视图对象
├── service/                    # 业务服务接口
│   ├── KnowledgeBaseService.java
│   └── impl/
│       └── KnowledgeBaseServiceImpl.java
├── dao/                        # 数据访问层
│   ├── entity/                 # 数据库实体
│   │   └── KnowledgeBaseDO.java
│   └── mapper/                 # MyBatis Mapper
│       └── KnowledgeBaseMapper.java
├── enums/                      # 枚举定义
├── mq/                         # 消息队列事件
└── schedule/                   # 定时任务
```

### 核心 Agent 系统文件

```
bootstrap/src/main/java/com/byteq/ai/ragstudio/rag/core/agent/
├── AgentLoop.java                   # ReACT 循环引擎
├── AgentContext.java                # 循环上下文
├── AgentStep.java                   # 单步记录
├── ReActResponseParser.java         # 三级降级解析器
├── ReActPromptBuilder.java          # System Prompt 构建器
├── Tool.java                        # 统一工具接口
├── ToolRegistry.java                # 工具注册中心（30 秒超时）
├── ToolResult.java                  # 工具执行结果
├── McpToolAdapter.java              # MCP → 通用工具适配器
├── RagSearchTool.java               # 知识库检索工具
├── TimeTool.java                    # 时间工具
└── KbRelevanceChecker.java          # KB 相关性判断
```

### 检索系统文件

```
bootstrap/src/main/java/com/byteq/ai/ragstudio/rag/core/retrieve/
├── channel/
│   ├── SearchChannel.java                  # 检索通道接口
│   ├── SearchChannelType.java              # 通道类型枚举
│   ├── VectorGlobalSearchChannel.java      # 向量全局检索（默认关闭）
│   ├── KnowledgeBaseSelectionChannel.java  # 知识库选择检索（向量通道）
│   ├── KeywordSearchChannel.java           # 关键词检索（tsvector 通道）← 新增
│   ├── RrfHybridChannel.java               # RRF 混合融合通道 ← 新增
│   └── AbstractParallelRetriever.java      # 并行检索抽象类
├── postprocessor/
│   ├── SearchResultPostProcessor.java      # 后置处理器接口
│   ├── DeduplicationPostProcessor.java     # 去重处理器
│   └── RerankPostProcessor.java            # Rerank 重排序
├── MultiChannelRetrievalEngine.java        # 多通道检索引擎
├── RetrievalEngine.java                    # 检索引擎接口
├── RetrieverService.java                   # 检索服务接口
├── PgRetrieverService.java                 # pgvector / tsvector 检索实现
├── RrfMerger.java                          # RRF 融合算法 ← 新增
└── RetrieveRequest.java                    # 检索请求对象
```

---

## 快速开始

### 环境要求

| 依赖 | 版本要求 | 用途 |
|------|---------|------|
| **JDK** | 17+ | 后端运行 |
| **Maven** | 3.8+ | 项目构建 |
| **Node.js** | 18+ | 前端构建 |
| **npm** | 9+ | 前端包管理 |
| **PostgreSQL** | 14+（需 pgvector 扩展） | 业务数据 + 向量存储 |
| **Redis** | 6+ | 缓存 + 分布式锁 |
| **RocketMQ** | 5.2.0 | 异步消息队列 |
| **Docker** | 最新 | 容器化基础设施运行 |

### 启动基础设施

#### 方法一：使用 Docker（推荐）

RocketMQ（消息队列）：

```bash
docker compose -f resources/docker/rocketmq-stack-5.2.0.compose.yaml up -d
```

PostgreSQL（含 pgvector）和 Redis：

```bash
# PostgreSQL with pgvector
docker run -d --name pgvector \
  -e POSTGRES_DB=ragstudio \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  pgvector/pgvector:pg16

# Redis
docker run -d --name redis \
  -p 6379:6379 \
  redis:7-alpine
```

#### 方法二：手动安装

- **PostgreSQL 14+**：需安装 [pgvector](https://github.com/pgvector/pgvector) 扩展
- **Redis 6+**：默认端口 6379
- **RocketMQ 5.2.0**：参考 [官方文档](https://rocketmq.apache.org/docs/quick-start/)

### 初始化数据库

```bash
# 创建数据库
createdb -U postgres ragstudio

# 执行建表脚本
psql -U postgres -d ragstudio -f resources/database/V2/schema_pg.sql

# 导入初始数据
psql -U postgres -d ragstudio -f resources/database/V2/init_data_pg.sql
```

### 配置环境变量

```bash
# 从示例复制并编辑
cp .env-example .env

# 根据你的环境修改配置，填入实际的数据库、Redis、RocketMQ 等连接信息
```

`.env` 文件包含以下配置项：

| 环境变量 | 说明 | 默认值 |
|---------|------|--------|
| `DB_USERNAME` | 数据库用户名 | `postgres` |
| `DB_PASSWORD` | 数据库密码 | `postgres` |
| `DB_URL` | 数据库连接地址 | `jdbc:postgresql://localhost:5432/ragstudio` |
| `REDIS_HOST` | Redis 主机 | `localhost` |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `REDIS_PASSWORD` | Redis 密码 | _(空)_ |
| `ROCKETMQ_NAMESERVER` | RocketMQ 命名服务地址 | `localhost:9876` |
| `RUSTFS_URL` | S3 对象存储地址 | `http://localhost:9000` |
| `RUSTFS_ACCESS_KEY` | S3 访问密钥 | `minioadmin` |
| `RUSTFS_SECRET_KEY` | S3 秘密密钥 | `minioadmin` |

### 启动后端

```bash
# 方式一：Maven 直接启动
cd bootstrap && mvn spring-boot:run

# 方式二：打包后启动
mvn clean package -DskipTests
cd bootstrap/target
java -jar bootstrap-0.0.1-SNAPSHOT.jar
```

后端启动后，API 地址：**http://localhost:9090/api/ragstudio**

### 启动前端

```bash
cd frontend
npm install   # 仅首次需要
npm run dev
```

前端启动后，访问地址：**http://localhost:5173**

---

## API 示例

### Agent 模式

```bash
curl -X POST http://localhost:9090/api/ragstudio/rag/v3/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: <token>" \
  -d '{"question": "HashMap的原理是什么？"}'
```

### Agent + 知识库

```bash
curl -X POST http://localhost:9090/api/ragstudio/rag/v3/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: <token>" \
  -d '{
    "question": "公司年假怎么申请？",
    "knowledgeBaseIds": ["kb-001"]
  }'
```



---

## 配置说明

### 应用配置

`bootstrap/src/main/resources/application.yaml` 中的核心配置段：

| 配置项 | 描述 | 默认值 |
|--------|------|--------|
| `rag.agent.max-iterations` | Agent 循环最大迭代次数 | `10` |
| `rag.agent.tool-timeout-ms` | 单工具调用超时 | `30000` |
| `rag.search.default-top-k` | 检索返回 Top-K 条数 | `5` |
| `rag.memory.history-keep-turns` | 保留最近 N 轮对话历史 | `4` |
| `rag.memory.summary-start-turns` | 从第 N 轮开始启用摘要 | `5` |
| `rag.memory.summary-enabled` | 是否启用对话摘要 | `true` |
| `rag.trace.enabled` | 是否启用全链路追踪 | `true` |
| `rag.rate-limit.global.max-concurrent` | 全局最大并发数 | `1` |

### 数据库初始化脚本

```bash
resources/database/
├── V2/
│   ├── schema_pg.sql          # 建表脚本
│   └── init_data_pg.sql       # 初始数据
├── upgrade_v1.0_to_v1.1.sql   # 版本升级 (v1.0 → v1.1)
└── upgrade_v1.1_to_v1.2.sql   # 版本升级 (v1.1 → v1.2)
```

---

## 截图展示

### 对话页面

| 对话界面 |
|:---:|
| ![问答页面](docs/assets/问答页面.png) |

### 知识库

| 知识库管理 | 文档管理 | 检索展示（语义检索） |
|:---:|:---:|:---:|
| ![知识库管理1](docs/assets/后管-知识库管理1.png) | ![知识库管理2](docs/assets/后管-知识库管理2.png) | ![语义检索](docs/assets/对话界面-知识库检索展示-语义检索.png) |

| 知识库详情 | 检索展示（关键词检索） |
|:---:|:---:|
| ![知识库管理3](docs/assets/后管-知识库管理3.png) | ![关键词检索](docs/assets/对话界面-知识库检索展示-关键词检索.png) |

### 链路追踪

| 链路追踪总览 | 追踪详情 |
|:---:|:---:|
| ![链路追踪1](docs/assets/后管-链路追踪1.png) | ![链路追踪2](docs/assets/后管-链路追踪2.png) |

### 数据通道

| 流水线管理 | 流水线任务 |
|:---:|:---:|
| ![数据通道1](docs/assets/后管-数据通道-流水线管理1.png) | ![数据通道2](docs/assets/后管-数据通道-流水线管理2.png) |

| 流水线任务详情 |
|:---:|
| ![流水线任务](docs/assets/后管-数据通道-流水线任务.png) |

### 模型管理

| 模型列表 |
|:---:|
| ![模型列表](docs/assets/后管-模型列表展示.png) |

### MCP

| MCP 服务管理 | MCP 工具调用 |
|:---:|:---:|
| ![MCP列表](docs/assets/后管-MCP列表展示.png) | ![MCP调用1](docs/assets/对话界面-MCP调用展示-天气查询1.png) |

| MCP 调用详情 |
|:---:|
| ![MCP调用2](docs/assets/对话界面-MCP调用展示-天气查询2.png) |

### SKILL 技能

| SKILL 列表 |
|:---:|
| ![SKILL列表](docs/assets/后管-SKILL列表展示.png) |

### 仪表盘

| 仪表盘总览 |
|:---:|
| ![仪表盘](docs/assets/后管-Dashboard.png) |

---

## 项目文档

- [快速开始指南](docs/quick-start.md) — 更详细的启动说明
- [多通道检索架构](docs/multi-channel-retrieval.md) — 检索系统设计文档
- [PDF 摄入示例](docs/examples/pdf-ingestion-example.md) — PDF 文档处理示例
- [Docker 轻量部署](resources/docker/lightweight/README.md) — 低资源环境部署

---

<p align="center">
  Built by ByteQ<br/>
  <a href="LICENSE">MIT License</a>
</p>
