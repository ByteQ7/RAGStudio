# RAGStudio - Enterprise Agentic RAG Platform

一个企业级智能检索增强生成（RAG）平台，覆盖从文档入库到智能问答的完整链路。**默认采用 ReACT Agent 循环**，支持多步推理、链式工具调用、知识库自主检索。

---

## 目录

- [项目简介](#项目简介)
- [架构总览](#架构总览)
- [核心功能](#核心功能)
- [技术栈](#技术栈)
- [截图展示](#截图展示)
- [快速开始](#快速开始)

---

## 项目简介

RAGStudio 是一个基于 Java 17 + Spring Boot 3.5 构建的企业级 AI 问答平台，核心能力包括：

- **ReACT Agent 循环**：用 Thought → Action → Observation 循环替代传统线性 RAG 管线，LLM 在循环中自主推理、调用工具、观察结果
- **多文档知识库**：支持 PDF/DOCX/HTML/Markdown 等多种格式文档的解析、向量化存储和语义检索
- **多模型路由**：数据库驱动的动态模型配置，支持百炼、SiliconFlow、DeepSeek 等多个 LLM 提供商，故障时秒级自动切换
- **MCP 协议集成**：支持运行时动态发现和调用外部工具，Agent 在循环中自主决策调用
- **全链路追踪**：自研轻量级分布式追踪系统，记录 RAG 管线的每个阶段

---

## 架构总览

```
┌─────────────────────────────────────────────────────┐
│ 展示层                                               │
│  React + TypeScript + Zustand + Tailwind CSS         │
│  ┌─────────────┐ ┌──────────────┐ ┌───────────────┐ │
│  │ 对话页面     │ │ 知识库管理    │ │ 管理后台       │ │
│  │ AgentSteps   │ │ KB 选择器    │ │ 仪表盘/设置    │ │
│  └─────────────┘ └──────────────┘ └───────────────┘ │
├─────────────────────────────────────────────────────┤
│ 应用层                                               │
│  StreamChatPipeline                                  │
│  ┌──────┐ ┌──────────┐ ┌──────────┐ ┌────────────┐ │
│  │ 记忆  │ │ 相关性   │ │ 工具注册 │ │ AgentLoop  │ │
│  │ 加载  │ │ 判断     │ │          │ │            │ │
│  └──────┘ └──────────┘ └──────────┘ └────────────┘ │
├─────────────────────────────────────────────────────┤
│ Agent 循环                                           │
│  LLM 同步调用 → 解析 ReACT → TOOL_CALL/FINISH       │
│  TOOL_CALL → 执行 → Observation → 下一轮            │
│  FINISH → 流式输出 Final Answer                      │
├─────────────────────────────────────────────────────┤
│ 工具层                                               │
│  ┌────────────┐ ┌─────────┐ ┌────────────────────┐  │
│  │ MCP 工具   │ │ TimeTool│ │ RagSearchTool      │  │
│  │ (外部服务) │ │ (内置)  │ │ (知识库检索)       │  │
│  └────────────┘ └─────────┘ └────────────────────┘  │
├─────────────────────────────────────────────────────┤
│ 基础设施                                             │
│  PostgreSQL + pgvector / Redis / RocketMQ / S3       │
└─────────────────────────────────────────────────────┘
```

---

## 核心功能

### 🤖 ReACT Agent 循环

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

- **Plan-then-Execute**：Agent 第一步输出 Plan 规划多步方案，后续按计划执行
- **知识库精准检索**：LLM 判断问题与哪些知识库相关（`relevant_collection_names`），只检索相关的
- **内置时间工具**：`time_now` 支持任意 IANA 时区（含夏令时自动处理），不依赖外部服务
- **MCP 工具调用**：MCP 工具通过适配器接入 ToolRegistry，Agent 在循环中自主决策调用
- **Agent 步骤持久化**：推理步骤序列化为 JSON 存储在 `t_message.agent_steps`，前端可回放
- **缺参数先问**：可搜索参数（日期）→ 搜索获取；用户参数（城市）→ 反问用户
- **格式校正**：LLM 未按要求输出 ReACT 格式时，自动注入纠正提示后重试一次

### 🧠 知识库关联性判断

进入 Agent 循环前，用轻量 LLM 调用判断问题与所选知识库是否相关：

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

## 截图展示

### 对话与 Agent

| 对话界面 | Agent 推理展示 |
|:---:|:---:|
| ![对话](docs/assets/img.png) | ![推理示例](docs/assets/img_1.png) |

| Agent 推理步骤 |
|:---:|
| ![步骤折叠](docs/assets/img_2.png) |

### 知识库

| 知识库管理 | 知识库详情 |
|:---:|:---:|
| ![知识库列表](docs/assets/img_3.png) | ![知识库详情](docs/assets/img_4.png) |

### 链路追踪

| 链路追踪总览 |
|:---:|
| ![链路追踪](docs/assets/img_5.png) |

| 追踪详情 |
|:---:|
| ![追踪详情](docs/assets/img_6.png) |

### MCP

| MCP 服务管理 |
|:---:|
| ![MCP](docs/assets/img_7.png) |

### 模型管理

| 模型配置 |
|:---:|
| ![模型管理](docs/assets/img_8.png) |

---

## 技术栈

| 层级 | 技术 |
|------|------|
| **后端** | Java 17, Spring Boot 3.5.7, MyBatis-Plus, RocketMQ, Sa-Token |
| **AI 引擎** | ReACT Agent Loop (Thought → Action → Observation) |
| **LLM 集成** | Spring AI (OpenAI 兼容协议), 多模型路由 + 熔断降级 |
| **向量存储** | PostgreSQL + pgvector (HNSW 索引) |
| **前端** | React 18, TypeScript, Vite, Tailwind CSS, shadcn/ui, Zustand |
| **缓存** | Redis + Redisson (分布式锁) |
| **文档解析** | Apache Tika 3.2 (PDF/DOCX/HTML/Markdown) |
| **协议** | MCP (Model Context Protocol) 1.1.2 |
| **存储** | S3 兼容对象存储 (RustFS/MinIO) |

---

## 快速开始

```bash
# 1. 启动基础设施（PostgreSQL + Redis + RocketMQ）
docker compose up -d

# 2. 启动后端
cd bootstrap && mvn spring-boot:run

# 3. 启动前端
cd frontend && npm run dev
```

前端访问 http://localhost:5173，后端 API 地址 http://localhost:9090/api/ragstudio。

### API 示例

**Agent 模式（默认）：**

```bash
curl -X POST http://localhost:9090/api/ragstudio/rag/v3/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "今天是什么日子？"}'
```

**带知识库的 Agent 问答：**

```bash
curl -X POST http://localhost:9090/api/ragstudio/rag/v3/chat \
  -H "Content-Type: application/json" \
  -d '{
    "question": "公司年假怎么申请？",
    "knowledgeBaseIds": ["kb-001"]
  }'
```

---

## 项目文档

- [快速开始指南](docs/quick-start.md)
- [多通道检索架构](docs/multi-channel-retrieval.md)
