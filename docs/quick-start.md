# 快速开始指南

## 项目架构

RAGStudio 采用**双管线设计**：**Agent 模式为默认（mode=agent）**，RAG 模式作为 `mode=rag` 备选。

### Agent 模式流程

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

### RAG 模式流程（mode=rag）

```
用户提问
  │
  ▼
StreamChatPipeline.doExecuteRag()
  │
  ├─ 1. 记忆加载
  ├─ 2. 查询改写 + MCP 决策
  ├─ 3. 多通道检索 + Rerank
  ├─ 4. MCP 工具执行（条件）
  └─ 5. 流式回答
```

## 核心文件

### Agent 系统

```
bootstrap/src/main/java/com/byteq/ai/ragstudio/rag/core/agent/
├── AgentLoop.java                  # ReACT 循环引擎
├── AgentContext.java               # 循环上下文
├── AgentStep.java                  # 单步记录（thought/action/observation）
├── ReActResponseParser.java        # 三级降级解析器
├── ReActPromptBuilder.java         # System Prompt 构建器
├── Tool.java                       # 统一工具接口
├── ToolRegistry.java               # 工具注册中心（30 秒超时）
├── ToolResult.java                 # 工具执行结果
├── McpToolAdapter.java             # MCP 协议 → 通用 Tool 适配器
├── RagSearchTool.java              # 知识库检索工具
└── KbRelevanceChecker.java         # KB 相关性判断
```

### 检索系统

```
bootstrap/src/main/java/com/byteq/ai/ragstudio/rag/core/retrieve/channel/
├── SearchChannel.java              # 检索通道接口
├── SearchChannelType.java          # 通道类型枚举
├── VectorGlobalSearchChannel.java  # 向量全局检索
├── KnowledgeBaseSelectionChannel.java # 知识库选择检索
└── AbstractParallelRetriever.java  # 并行检索抽象类

bootstrap/src/main/java/com/byteq/ai/ragstudio/rag/core/retrieve/postprocessor/
├── SearchResultPostProcessor.java  # 后置处理器接口
├── DeduplicationPostProcessor.java # 去重处理器
└── RerankPostProcessor.java        # Rerank 重排序处理器
```

## API 示例

### 1. Agent 模式（默认）

```bash
curl -X POST http://localhost:9090/api/ragstudio/rag/v3/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: <token>" \
  -d '{"question": "HashMap的原理是什么？"}'
```

### 2. Agent 模式 + 知识库

```bash
curl -X POST http://localhost:9090/api/ragstudio/rag/v3/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: <token>" \
  -d '{
    "question": "公司年假怎么申请？",
    "knowledgeBaseIds": ["kb-001"]
  }'
```

### 3. RAG 模式

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

## 配置说明

### 应用配置（application.yaml）

```yaml
rag:
  # Agent 模式配置
  agent:
    max-iterations: 10        # Agent 循环最大迭代次数
    tool-timeout-ms: 30000    # 单工具调用超时

  # 检索配置
  search:
    default-top-k: 5
    channels:
      vector-global:
        top-k-multiplier: 3
      knowledge-base-selection:
        top-k-multiplier: 3

  # 记忆管理
  memory:
    history-keep-turns: 4
    summary-start-turns: 5
    summary-enabled: true
    summary-max-chars: 200
```

## 运行

```bash
# 1. 启动基础设施
docker compose up -d

# 2. 启动后端
cd bootstrap && mvn spring-boot:run

# 3. 启动前端
cd frontend && npm run dev
```

前端访问 http://localhost:5173，后端 API 在 http://localhost:9090/api/ragstudio。
