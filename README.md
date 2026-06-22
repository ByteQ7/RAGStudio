# RAGStudio — Enterprise Agentic RAG Platform

<p align="center">
  <em>企业级智能检索增强生成（RAG）平台 · 基于 ReACT Agent 循环的深度推理引擎</em><br/>
  <em>Enterprise-grade Retrieval-Augmented Generation platform powered by ReACT Agent loop</em>
</p>

---

## 📋 目录 | Table of Contents

- [项目简介 | Introduction](#项目简介--introduction)
- [架构总览 | Architecture Overview](#架构总览--architecture-overview)
- [核心功能 | Core Features](#核心功能--core-features)
- [技术栈 | Technology Stack](#技术栈--technology-stack)
- [项目结构 | Project Structure](#项目结构--project-structure)
- [分层架构 | Layered Architecture](#分层架构--layered-architecture)
- [快速开始 | Quick Start](#快速开始--quick-start)
  - [环境要求 | Prerequisites](#环境要求--prerequisites)
  - [启动基础设施 | Start Infrastructure](#启动基础设施--start-infrastructure)
  - [初始化数据库 | Initialize Database](#初始化数据库--initialize-database)
  - [配置环境变量 | Configure Environment](#配置环境变量--configure-environment)
  - [启动后端 | Start Backend](#启动后端--start-backend)
  - [启动前端 | Start Frontend](#启动前端--start-frontend)
- [API 示例 | API Examples](#api-示例--api-examples)
- [配置说明 | Configuration Guide](#配置说明--configuration-guide)
- [截图展示 | Screenshots](#截图展示--screenshots)
- [项目文档 | Documentation](#项目文档--documentation)

---

## 项目简介 | Introduction

**RAGStudio** 是一个基于 **Java 17 + Spring Boot 3.5** 构建的企业级 AI 问答平台，默认采用 **ReACT Agent 循环**（Thought → Action → Observation），支持多步推理、链式工具调用和知识库自主检索。

**RAGStudio** is an enterprise AI Q&A platform built on **Java 17 + Spring Boot 3.5**. It adopts the **ReACT Agent loop** (Thought → Action → Observation) by default, supporting multi-step reasoning, chained tool calls, and autonomous knowledge base retrieval.

### 核心能力 | Core Capabilities

- **ReACT Agent 循环**：用 Thought → Action → Observation 循环替代传统线性 RAG 管线，LLM 在循环中自主推理、调用工具、观察结果
- **多文档知识库**：支持 PDF/DOCX/HTML/Markdown 等多种格式文档的解析、向量化存储和语义检索
- **多模型路由**：数据库驱动的动态模型配置，支持百炼、SiliconFlow、DeepSeek 等多个 LLM 提供商，故障时秒级自动切换
- **MCP 协议集成**：支持运行时动态发现和调用外部工具，Agent 在循环中自主决策调用
- **全链路追踪**：自研轻量级分布式追踪系统，记录 RAG 管线的每个阶段

---

## 架构总览 | Architecture Overview

![Architecture](docs/assets/architecture.svg)

### 双管线设计 | Dual-Pipeline Design

RAGStudio 采用**双管线设计**：**Agent 模式为默认（`mode=agent`）**，RAG 模式作为 `mode=rag` 备选。

#### Agent 模式流程 | Agent Mode Flow

```
用户提问 User Question
  │
  ▼
ChatQueueLimiter (分布式限流 Distributed Rate Limiting)
  │
  ▼
StreamChatPipeline.doExecuteAgent()
  │
  ├─ 1. 记忆加载 Memory Loading ─── 并行加载对话历史 + 摘要 Parallel loading of history + summary
  │
  ├─ 2. 工具注册 Tool Registration ─── MCP 工具 + rag_search 工具注册到 ToolRegistry
  │
  ├─ 3. KB 相关性判断 KB Relevance Check ─── 轻量 LLM 判断问题是否与所选知识库相关
  │
  └─ 4. Agent Loop ─── 迭代至 FINISH Iterate until FINISH
        ├─ Thought → Action(TOOL_CALL) → Observation → 继续 Continue
        └─ Thought → Action(FINISH) → Final Answer (逐字流式推送 Streaming)
```

#### RAG 模式流程 | RAG Mode Flow

```
用户提问 User Question
  │
  ▼
StreamChatPipeline.doExecuteRag()
  │
  ├─ 1. 记忆加载 Memory Loading
  ├─ 2. 查询改写 + MCP 决策 Query Rewriting + MCP Decision
  ├─ 3. 多通道检索 + Rerank Multi-Channel Retrieval + Rerank
  ├─ 4. MCP 工具执行 MCP Tool Execution (条件 Conditional)
  └─ 5. 流式回答 Streaming Response
```

---

## 核心功能 | Core Features

### 🤖 ReACT Agent 循环

Agent 模式为默认交互方式，LLM 在循环中自主推理和决策：

```
迭代 0:  Thought → 需要查日期 Need to check date
          Action → time_now({})
          Observation → 2026年6月21日

迭代 1:  Thought → 知道了日期，查节日 Date known, check festival
          Action → web_search({query: "6月21日 节日"})
          Observation → 父亲节 Father's Day

迭代 2:  Thought → 信息充分 Information sufficient
          Action → FINISH
          Final Answer → 今天是2026年6月21日，父亲节。
```

- **Plan-then-Execute**：Agent 第一步输出 Plan 规划多步方案，后续按计划执行
- **知识库精准检索**：LLM 判断问题与哪些知识库相关（`relevant_collection_names`），只检索相关的
- **内置时间工具**：`time_now` 支持任意 IANA 时区（含夏令时自动处理），不依赖外部服务
- **MCP 工具调用**：MCP 工具通过适配器接入 ToolRegistry，Agent 在循环中自主决策调用
- **Agent 步骤持久化**：推理步骤序列化为 JSON 存储在 `t_message.agent_steps`，前端可回放
- **缺参数先问**：可搜索参数（日期）→ 搜索获取；用户参数（城市）→ 反问用户
- **格式校正**：LLM 未按要求输出 ReACT 格式时，自动注入纠正提示后重试一次

### 🧠 知识库关联性判断

- 进入 Agent 循环前，用轻量 LLM 调用判断问题与所选知识库是否相关
- **按库精准过滤**：LLM 返回 `relevant_collection_names` 字段，指定具体哪些知识库相关，只检索这些
- **判断依据**：知识库名称 + 知识库描述（创建/编辑时可填写）
- **JSON 断尾修复**：处理 LLM 输出被截断的情况，确保解析健壮

### 📚 知识库管理

- 创建/编辑/删除知识库，支持配置 embedding 模型
- **知识库描述**：创建和编辑时可填写描述，用于 AI 关联判断
- 文档上传（文件/URL），支持 PDF/DOCX/HTML/Markdown 格式
- 自动分块 + embedding 向量化入库
- 定时刷新同步（cron 表达式 + ETag/Hash 变更检测）
- 分块查看、启用/禁用、手动编辑

### ⚙️ 多模型路由与熔断降级

- 数据库驱动的动态模型配置，运行时无重启切换
- 优先级路由 + Circuit Breaker 状态机（CLOSED → OPEN → HALF_OPEN）
- 单模型故障秒级自动 fallback，流式场景与同步场景共享健康检查

### 🧩 MCP 集成

- MCP 服务器注册与管理，启动时异步加载不阻塞应用
- Agent 在循环中自主调用，支持多次调用和链式调用
- 工具调用失败时 Agent 可自主重试或换用其他工具

### 🖥️ 管理后台

- **仪表盘**：用户量、对话量、消息量等核心 KPI 概览，延迟/成功率趋势
- **知识库管理**：知识库列表、文档管理、分块详情、处理日志
- **摄入管道**：管道定义与执行任务管理
- **RAG 链路追踪**：全链路 trace 展示，节点级耗时查看
- **系统设置**：模型参数、记忆配置、限流策略
- **MCP 服务**：外部 MCP 服务器注册管理
- **用户管理**：账号管理、角色分配

### 🔐 用户认证与权限

- Sa-Token 登录/注销（用户名+密码）
- 管理员 / 普通用户双角色权限

---

## 技术栈 | Technology Stack

| 层级 Layer | 技术 Technology |
|------------|----------------|
| **后端 Backend** | Java 17, Spring Boot 3.5.7, MyBatis-Plus, RocketMQ, Sa-Token |
| **AI 引擎 AI Engine** | ReACT Agent Loop (Thought → Action → Observation) |
| **LLM 集成 LLM Integration** | Spring AI (OpenAI 兼容协议), 多模型路由 + 熔断降级 |
| **向量存储 Vector Store** | PostgreSQL + pgvector (HNSW 索引) |
| **前端 Frontend** | React 18, TypeScript, Vite, Tailwind CSS, shadcn/ui, Zustand |
| **缓存 Cache** | Redis + Redisson (分布式锁 Distributed Lock) |
| **文档解析 Document Parsing** | Apache Tika 3.2 (PDF/DOCX/HTML/Markdown) |
| **协议 Protocol** | MCP (Model Context Protocol) 1.1.2 |
| **对象存储 Object Storage** | S3 兼容存储 (RustFS / MinIO) |

---

## 项目结构 | Project Structure

### 多模块 Maven 架构 | Multi-Module Maven Architecture

```
ragstudio（父模块 Parent POM）
├── bootstrap          # 应用启动模块，包含所有业务代码
├── framework          # 基础框架层：缓存、数据库、安全、异常处理、MQ、分布式ID
└── infra-ai           # AI 基础设施层：LLM 客户端、Embedding、Rerank、模型路由
```

### 模块详解 | Module Details

#### `bootstrap` — 应用启动模块 Application Bootstrap

包含完整的业务代码，按功能域（package-by-feature）组织：

```
bootstrap/src/main/java/com/byteq/ai/ragstudio/
├── admin/              # 管理后台仪表盘 Dashboard
├── aimodel/            # AI 模型配置管理 AI Model Configuration
├── core/               # 文档解析与分块 Document Parsing & Chunking
├── ingestion/          # 摄入管道引擎 Ingestion Pipeline Engine
├── knowledge/          # 知识库管理 Knowledge Base Management
├── mcp/                # MCP 服务管理 MCP Server Management
├── rag/                # RAG 核心引擎 RAG Core Engine
│   ├── controller/     # REST 控制器 REST Controllers
│   ├── service/        # 业务服务层 Business Services
│   │   ├── pipeline/   # 流式聊天管线 Streaming Chat Pipeline
│   │   ├── handler/    # 流式事件处理 Streaming Event Handlers
│   │   ├── ratelimit/  # 分布式限流 Distributed Rate Limiting
│   │   └── impl/       # 服务实现 Service Implementations
│   ├── core/           # 核心引擎 Core Engine
│   │   ├── agent/      # ReACT Agent 循环 Agent Loop
│   │   ├── retrieve/   # 多通道检索 Multi-Channel Retrieval
│   │   ├── memory/     # 会话记忆管理 Conversation Memory
│   │   ├── prompt/     # 提示词模板 Prompt Templates
│   │   ├── rewrite/    # 查询改写 Query Rewriting
│   │   ├── vector/     # 向量存储抽象 Vector Store Abstractions
│   │   └── mcp/        # MCP 工具注册与执行 MCP Tool Registry
│   └── dao/            # 数据访问层 Data Access Layer
│       ├── entity/     # 数据库实体 Database Entities
│       └── mapper/     # MyBatis Mapper
├── user/               # 用户认证与权限 User Auth & Permissions
└── RAGStudioApplication.java  # 启动入口 Main Entry
```

#### `framework` — 基础框架层 Foundation Framework

```
framework/src/main/java/com/byteq/ai/ragstudio/framework/
├── cache/               # Redis 序列化 Redis Serialization
├── config/              # 自动配置 Auto Configuration (DB, MQ, Web)
├── context/             # 用户上下文 User Context
├── convention/          # 通用数据契约 Common Data Contracts
├── database/            # MyBatis-Plus 元数据处理器 Meta Object Handler
├── distributedid/       # 分布式 ID 生成器 Snowflake ID Generator
├── errorcode/           # 错误码定义 Error Code Definitions
├── exception/           # 统一异常体系 Unified Exception Hierarchy
├── idempotent/          # 幂等提交保证 Idempotent Submit
├── mq/                  # RocketMQ 消息队列封装 RocketMQ Wrapper
├── security/            # 密码哈希 Password Hashing
├── trace/               # 轻量级分布式追踪 Distributed Tracing
└── web/                 # 全局异常处理等 Web Utilities
```

#### `infra-ai` — AI 基础设施层 AI Infrastructure

```
infra-ai/src/main/java/com/byteq/ai/ragstudio/infra/
├── chat/                # LLM 聊天客户端 Chat Clients
│   └── client/          # 各厂商实现：百炼、DeepSeek、SiliconFlow
├── config/              # 动态模型配置 Dynamic Model Configuration
├── embedding/           # Embedding 客户端 Embedding Clients
├── enums/               # 模型提供商枚举 Model Provider Enums
├── http/                # HTTP 客户端封装 HTTP Client Utilities
├── model/               # 模型路由与健康检查 Model Routing & Health
├── rerank/              # Rerank 排序服务 Rerank Services
├── springai/            # Spring AI 适配器 Spring AI Adapter
├── token/               # Token 计数 Token Counting
└── util/                # LLM 响应清理 LLM Response Cleaner
```

---

## 分层架构 | Layered Architecture

RAGStudio 在每个模块内部采用标准的 **四层架构**（Standard Four-Layer Architecture）：

```
┌─────────────────────────────────────────────────────────┐
│                    Controller 层                          │
│            REST API 入口 / 请求校验 / 响应封装              │
│         REST API Entry / Request Validation / Response    │
├─────────────────────────────────────────────────────────┤
│                    Service 层                             │
│            业务逻辑编排 / 事务管理 / 领域服务               │
│         Business Logic Orchestration / Transaction Mgmt   │
├─────────────────────────────────────────────────────────┤
│                    DAO 层                                 │
│            Mapper (MyBatis-Plus) + Entity (数据映射)       │
│              Data Access / ORM Mapping                    │
├─────────────────────────────────────────────────────────┤
│                  Database (PostgreSQL)                     │
│                       + Redis                             │
└─────────────────────────────────────────────────────────┘
```

### 分层职责 | Layer Responsibilities

| 层级 Layer | 职责 Responsibility | 典型注解 Annotations |
|-----------|-------------------|---------------------|
| **Controller** | HTTP 请求接收、参数校验、VO 封装返回 | `@RestController`, `@RequestMapping`, `@Valid` |
| **Service** | 业务逻辑编排、事务管理、跨领域协调 | `@Service`, `@Transactional`, `@Async` |
| **Service.Impl** | 服务接口实现 | `@Service`, 实现各自 Service 接口 |
| **DAO.Mapper** | MyBatis 数据映射、SQL 定义 | `@Mapper`, `BaseMapper<T>` |
| **DAO.Entity** | 数据库表映射实体 | `@TableName`, `@TableId`, `@TableField` |
| **VO / DTO** | 视图层对象 / 数据传输对象 | `@Data`, 用于接口出入参 |

### 典型代码组织 | Typical Code Organization

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

### 核心 Agent 系统文件 | Core Agent System Files

```
bootstrap/src/main/java/com/byteq/ai/ragstudio/rag/core/agent/
├── AgentLoop.java                   # ReACT 循环引擎 ReACT Loop Engine
├── AgentContext.java                # 循环上下文 Loop Context
├── AgentStep.java                   # 单步记录 Step Recording
├── ReActResponseParser.java         # 三级降级解析器 3-Level Fallback Parser
├── ReActPromptBuilder.java          # System Prompt 构建器 Prompt Builder
├── Tool.java                        # 统一工具接口 Unified Tool Interface
├── ToolRegistry.java                # 工具注册中心 Tool Registry (30s timeout)
├── ToolResult.java                  # 工具执行结果 Tool Execution Result
├── McpToolAdapter.java              # MCP 协议 → 通用 Tool 适配器
├── RagSearchTool.java               # 知识库检索工具 KB Search Tool
├── TimeTool.java                    # 时间工具 Time Tool
└── KbRelevanceChecker.java          # KB 相关性判断 KB Relevance Checker
```

### 检索系统文件 | Retrieval System Files

```
bootstrap/src/main/java/com/byteq/ai/ragstudio/rag/core/retrieve/
├── channel/
│   ├── SearchChannel.java                  # 检索通道接口
│   ├── SearchChannelType.java              # 通道类型枚举
│   ├── VectorGlobalSearchChannel.java      # 向量全局检索
│   ├── KnowledgeBaseSelectionChannel.java  # 知识库选择检索
│   └── AbstractParallelRetriever.java      # 并行检索抽象类
├── postprocessor/
│   ├── SearchResultPostProcessor.java      # 后置处理器接口
│   ├── DeduplicationPostProcessor.java     # 去重处理器
│   └── RerankPostProcessor.java            # Rerank 重排序处理器
├── MultiChannelRetrievalEngine.java        # 多通道检索引擎
├── RetrievalEngine.java                    # 检索引擎接口
├── RetrieverService.java                   # 检索服务接口
├── PgRetrieverService.java                 # pgvector 检索实现
└── RetrieveRequest.java                    # 检索请求对象
```

---

## 快速开始 | Quick Start

### 环境要求 | Prerequisites

| 依赖 Dependency | 版本要求 Version | 用途 Purpose |
|----------------|-----------------|-------------|
| **JDK** | 17+ | 后端运行 Backend Runtime |
| **Maven** | 3.8+ | 项目构建 Build Tool |
| **Node.js** | 18+ | 前端构建 Frontend Build |
| **npm** | 9+ | 前端包管理 Frontend Package Manager |
| **PostgreSQL** | 14+ (需 pgvector 扩展) | 业务数据 + 向量存储 |
| **Redis** | 6+ | 缓存 + 分布式锁 Cache & Distributed Lock |
| **RocketMQ** | 5.2.0 | 异步消息队列 Async Message Queue |
| **Docker** / **Podman** | 最新 Latest | 容器化基础设施运行 Container Runtime |

### 启动基础设施 | Start Infrastructure

#### 方法一：使用 Docker Compose（推荐）

RocketMQ 基础设施：

```bash
# 启动 RocketMQ（消息队列）
docker compose -f resources/docker/rocketmq-stack-5.2.0.compose.yaml up -d
```

> **注意**：PostgreSQL 和 Redis 需要自行启动或添加到 Docker Compose，因为项目中未包含它们的 Compose 文件。你可以使用以下命令快速启动：
>
> ```bash
> # PostgreSQL（含 pgvector）
> docker run -d --name pgvector \
>   -e POSTGRES_DB=ragstudio \
>   -e POSTGRES_USER=postgres \
>   -e POSTGRES_PASSWORD=postgres \
>   -p 5432:5432 \
>   pgvector/pgvector:pg16
>
> # Redis
> docker run -d --name redis \
>   -p 6379:6379 \
>   redis:7-alpine
> ```

#### 方法二：手动安装

- **PostgreSQL 14+**：需安装 [pgvector](https://github.com/pgvector/pgvector) 扩展
- **Redis 6+**：默认端口 6379
- **RocketMQ 5.2.0**：参考 [官方文档](https://rocketmq.apache.org/docs/quick-start/)

### 初始化数据库 | Initialize Database

```bash
# 创建数据库
createdb -U postgres ragstudio

# 执行建表脚本
psql -U postgres -d ragstudio -f resources/database/V2/schema_pg.sql

# 导入初始数据
psql -U postgres -d ragstudio -f resources/database/V2/init_data_pg.sql
```

### 配置环境变量 | Configure Environment

```bash
# 从示例复制并编辑
cp .env-example .env

# 根据你的环境修改配置
# 编辑 .env 文件，填入实际的数据库、Redis、RocketMQ 等连接信息
```

`.env` 文件包含以下配置项：

| 环境变量 Variable | 说明 Description | 默认值 Default |
|------------------|-----------------|---------------|
| `DB_USERNAME` | 数据库用户名 DB Username | `postgres` |
| `DB_PASSWORD` | 数据库密码 DB Password | `postgres` |
| `DB_URL` | 数据库连接地址 JDBC URL | `jdbc:postgresql://localhost:5432/ragstudio` |
| `REDIS_HOST` | Redis 主机 Redis Host | `localhost` |
| `REDIS_PORT` | Redis 端口 Redis Port | `6379` |
| `REDIS_PASSWORD` | Redis 密码 Redis Password | _(空 Empty)_ |
| `ROCKETMQ_NAMESERVER` | RocketMQ 命名服务地址 | `localhost:9876` |
| `RUSTFS_URL` | S3 对象存储地址 S3 Endpoint | `http://localhost:9000` |
| `RUSTFS_ACCESS_KEY` | S3 访问密钥 Access Key | `minioadmin` |
| `RUSTFS_SECRET_KEY` | S3 秘密密钥 Secret Key | `minioadmin` |

### 启动后端 | Start Backend

```bash
# 编译并启动
cd bootstrap && mvn spring-boot:run

# 或者先打包再运行
mvn clean package -DskipTests
cd bootstrap/target
java -jar bootstrap-0.0.1-SNAPSHOT.jar
```

后端启动后，API 地址：**http://localhost:9090/api/ragstudio**

### 启动前端 | Start Frontend

```bash
# 进入前端目录
cd frontend

# 安装依赖（仅首次）
npm install

# 启动开发服务器
npm run dev
```

前端启动后，访问地址：**http://localhost:5173**

---

## API 示例 | API Examples

### 1. Agent 模式（默认） | Agent Mode (Default)

```bash
curl -X POST http://localhost:9090/api/ragstudio/rag/v3/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: <token>" \
  -d '{"question": "HashMap的原理是什么？"}'
```

### 2. Agent 模式 + 知识库 | Agent Mode with Knowledge Base

```bash
curl -X POST http://localhost:9090/api/ragstudio/rag/v3/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: <token>" \
  -d '{
    "question": "公司年假怎么申请？",
    "knowledgeBaseIds": ["kb-001"]
  }'
```

### 3. RAG 模式 | RAG Mode

```bash
curl -X POST http://localhost:9090/api/ragstudio/rag/v3/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: <token>" \
  -d '{
    "question": "公司年假怎么申请？",
    "knowledgeBaseIds": ["kb-001"],
    "mode": "rag"
  }'
```

---

## 配置说明 | Configuration Guide

### 应用配置 | Application Configuration

`bootstrap/src/main/resources/application.yaml` 中的核心配置段：

| 配置项 Configuration | 描述 Description | 默认值 Default |
|--------------------|-----------------|---------------|
| `rag.agent.max-iterations` | Agent 循环最大迭代次数 | `10` |
| `rag.agent.tool-timeout-ms` | 单工具调用超时 | `30000` |
| `rag.search.default-top-k` | 检索返回 Top-K 条数 | `5` |
| `rag.memory.history-keep-turns` | 保留最近 N 轮对话历史 | `4` |
| `rag.memory.summary-start-turns` | 从第 N 轮开始启用摘要 | `5` |
| `rag.memory.summary-enabled` | 是否启用对话摘要 | `true` |
| `rag.trace.enabled` | 是否启用全链路追踪 | `true` |
| `rag.rate-limit.global.max-concurrent` | 全局最大并发数 | `1` |

### 数据库初始化脚本 | Database Init Scripts

```bash
resources/database/
├── V2/
│   ├── schema_pg.sql          # 建表脚本 Table Schema
│   └── init_data_pg.sql       # 初始数据 Initial Data
├── upgrade_v1.0_to_v1.1.sql   # 版本升级脚本 Upgrade Script
└── upgrade_v1.1_to_v1.2.sql   # 版本升级脚本 Upgrade Script
```

---

## 截图展示 | Screenshots

### 对话与 Agent | Chat & Agent

| 对话界面 Chat UI | Agent 推理展示 Agent Reasoning |
|:---:|:---:|
| ![对话](docs/assets/img.png) | ![推理示例](docs/assets/img_1.png) |

| Agent 推理步骤 Agent Step Details |
|:---:|
| ![步骤折叠](docs/assets/img_2.png) |

### 知识库 | Knowledge Base

| 知识库管理 KB Management | 知识库详情 KB Details |
|:---:|:---:|
| ![知识库列表](docs/assets/img_3.png) | ![知识库详情](docs/assets/img_4.png) |

### 链路追踪 | Trace

| 链路追踪总览 Trace Overview |
|:---:|
| ![链路追踪](docs/assets/img_5.png) |

| 追踪详情 Trace Details |
|:---:|
| ![追踪详情](docs/assets/img_6.png) |

### MCP

| MCP 服务管理 MCP Server Management |
|:---:|
| ![MCP](docs/assets/img_7.png) |

### 模型管理 | Model Management

| 模型配置 Model Configuration |
|:---:|
| ![模型管理](docs/assets/img_8.png) |

---

## 项目文档 | Documentation

- [快速开始指南 Quick Start Guide](docs/quick-start.md)
- [多通道检索架构 Multi-Channel Retrieval](docs/multi-channel-retrieval.md)
- [PDF 摄入示例 PDF Ingestion Example](docs/examples/pdf-ingestion-example.md)
- [Docker 轻量部署 Lightweight Docker Deployment](resources/docker/lightweight/README.md)

---

<p align="center">
  Built with ❤️ by ByteQ<br/>
  <a href="LICENSE">MIT License</a>
</p>
