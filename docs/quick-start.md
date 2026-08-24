# 快速开始指南

## 项目架构

RAGStudio 当前采用**单一管线**：所有请求统一走 **AgentScope ReActAgent 循环**（Agent 模式）。
> 注：早期规划的 `mode=rag` 独立模式尚未实现，`ChatRequest` 无 `mode` 字段，后续版本再行支持。

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
  ├─ 2. 工具注册 ─── MCP 工具 + rag_search + SKILL 工具注册（AgentScope Toolkit）
  │
  ├─ 3. KB 语义选择 ─── 嵌入模型按问题筛选相关知识库（多模态选库）
  │
  └─ 4. Agent Loop ─── 迭代至 AgentEnd / 最大迭代次数
        ├─ Thought → Action(TOOL_CALL) → Observation → 继续
        └─ Thought → Final Answer（逐字流式推送）
```

## 核心文件

### Agent 系统

```
bootstrap/src/main/java/com/byteq/ai/ragstudio/rag/core/agent/
├── AgentScopeReActExecutor.java     # AgentScope ReActAgent 执行器（事件流透传 SSE）
├── AgentContext.java                # 循环上下文（迭代次数/总超时/子问题）
├── AgentStep.java                   # 单步记录（thought/action/observation）
├── ProjectToolAdapter.java          # 项目 Tool → AgentScope Toolkit 适配器
├── Tool.java                        # 统一工具接口
├── ToolResult.java                  # 工具执行结果
├── McpToolAdapter.java              # MCP 协议 → 通用 Tool 适配器
├── RagSearchTool.java               # 知识库检索工具
├── KbEmbeddingSelector.java         # 知识库语义选择（多模态 Embedding）
└── SkillTool.java                   # SKILL 技能工具（http/script/command）
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

### 3. 仅 Agent 模式

> 当前仅支持 Agent 模式；`mode` 字段不存在，按此格式请求即可（与 Agent 模式一致）。

```bash
curl -X POST http://localhost:9090/api/ragstudio/rag/v3/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: <token>" \
  -d '{
    "question": "公司年假怎么申请？",
    "knowledgeBaseIds": ["kb-001"]
  }'
```

## 配置说明

### 应用配置（application.yaml）

```yaml
rag:
  # Agent 模式配置
  agent:
    max-iterations: 10        # Agent 循环最大迭代次数
    timeout-ms: 120000        # Agent 总执行超时（毫秒），超时强制中断

  # 检索配置
  search:
    default-top-k: 5
    channels:
      vector-global:
        enabled: false        # 向量全局检索当前已禁用
        top-k-multiplier: 3
      knowledge-base-selection:
        top-k-multiplier: 3
      hybrid-rrf:
        enabled: true
        k: 60                 # RRF 平滑常数（仅 per-KB 融合；最终数量由 Rerank + 动态 TopK 控制）

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
