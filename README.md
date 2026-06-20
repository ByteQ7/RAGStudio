# RAGStudio - Enterprise Agentic RAG Platform

一个企业级智能检索增强生成（RAG）平台，覆盖从文档入库到智能问答的完整链路。**默认采用 ReACT Agent 循环**，支持多步推理、链式工具调用、知识库自主检索。

---

## 核心特性

### 🤖 ReACT Agent 智能问答（默认模式）

用 Thought → Action → Observation 循环替代传统线性 RAG 管线：

- **多步推理**：LLM 在循环中自主推理、调用工具、观察结果，直到给出最终答案
- **Plan-then-Execute**：Agent 第一步可输出 Plan 规划多步方案，然后逐条执行
- **知识库自主检索**：Agent 在循环中可主动调用 `rag_search` 工具检索知识库
- **MCP 工具调用**：MCP 工具通过适配器接入 Agent 的 ToolRegistry，Agent 自主决策调用
- **Agent 步骤持久化**：推理步骤序列化为 JSON 存储在 `t_message.agent_steps`，前端可回放展示
- **缺参数先问**：可搜索参数（日期）→ 搜索获取；用户参数（城市）→ 反问用户

### 🧠 知识库相关性判断

在进入 Agent 循环前，用轻量 LLM 调用判断问题与所选知识库是否相关：
- **相关** → 注册 `rag_search` 工具，Agent 自主检索
- **不相关** → 跳过检索，避免无效向量查询

### 🔍 多路检索（RAG 模式备选）

多渠道并行检索（向量知识库 + 全局搜索），经去重和重排后输出，兼顾精准度与召回率。通过 `mode=rag` 参数启用。

### 📥 文档摄入管道

可编排的 DAG 管道，支持多类型数据源摄入：
- **数据源**：HTTP URL、飞书文档、S3 兼容对象存储、本地文件
- **处理节点**：文档解析（Apache Tika 支持 PDF/DOCX/HTML/Markdown）、文本分块、LLM 增强、语义丰富、向量化索引

### 📚 知识库管理

- 知识库的创建、更新、删除，支持配置 embedding 模型
- 文档上传与处理（自动分块 + embedding）
- 定时刷新同步，支持 ETag/hash 变更检测 + 分布式锁
- 分块查看、启用/禁用，token 用量统计

### ⚙️ 多模型路由与熔断降级

- 数据库驱动的动态模型配置，运行时无重启切换
- 优先级路由 + Circuit Breaker 熔断器（CLOSED/OPEN/HALF_OPEN）
- 单模型故障秒级自动 fallback，流式场景共享同一套健康检查

### 🧩 MCP 集成

- MCP 服务器注册与管理
- 动态连接生命周期管理 + 健康状态检测
- Agent 模式下在循环中自主调用 MCP 工具

### 🖥️ 管理后台

- **仪表盘**：用户量、对话量、消息量等核心 KPI 概览，延迟/成功率趋势分析
- **知识库**：知识库管理、文档列表、分块详情
- **摄入管道**：管道定义与执行任务管理
- **RAG 链路追踪**：全链路 trace 展示，节点级耗时查看
- **系统设置**：模型参数、记忆配置、限流策略等
- **样本问题**：管理对话页面的推荐问题列表
- **查询映射**：同义词/术语归一化管理
- **MCP 服务**：外部 MCP 服务器注册
- **用户管理**：账号管理、角色分配

### 🔐 用户认证与权限

- Sa-Token 登录/注销（用户名+密码）
- 管理员 / 普通用户双角色权限

## 技术栈

| 层级 | 技术 |
|------|------|
| **后端** | Java 17, Spring Boot 3.5.7, MyBatis-Plus, RocketMQ, Sa-Token |
| **AI** | Spring AI, ReACT Agent Loop (Thought → Action → Observation) |
| **向量** | PostgreSQL (pgvector) |
| **前端** | React 18, TypeScript, Vite, Tailwind CSS, shadcn/ui, Zustand |
| **基础设施** | Docker Compose (RocketMQ), S3 兼容对象存储 |

## 快速开始

请参考 `docs/` 目录下的文档进行项目搭建和运行。

```bash
# 启动基础设施（PostgreSQL + Redis + RocketMQ）
docker compose up -d

# 启动后端
cd bootstrap && mvn spring-boot:run

# 启动前端
cd frontend && npm run dev
```

## API 示例

### Agent 模式问答（默认）

```bash
curl -X POST http://localhost:9090/api/ragstudio/rag/v3/chat \
  -H "Content-Type: application/json" \
  -d '{
    "question": "今天是什么日子？"
  }'
```

### RAG 模式问答（显式指定）

```bash
curl -X POST http://localhost:9090/api/ragstudio/rag/v3/chat \
  -H "Content-Type: application/json" \
  -d '{
    "question": "公司年假怎么申请？",
    "knowledgeBaseIds": ["kb-001"],
    "mode": "rag"
  }'
```
