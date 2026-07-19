# RAGStudio — 项目亮点与简历文档

> 基于 Java 17 + Spring Boot 3.5 的企业级 Agentic RAG 平台  
> 本文件包含简历精简版和完整项目亮点说明

---

## 目录

- [简历](#简历)
- [亮点一：ReACT Agent 循环引擎](#亮点一react-agent-循环引擎)
- [亮点二：混合检索（向量 + 关键词 + RRF）](#亮点二混合检索向量--关键词--rrf)
- [亮点三：两阶段召回管线（粗召 → 精排）](#亮点三两阶段召回管线粗召--精排)
- [亮点四：用户自定义 SKILL 工具系统](#亮点四用户自定义-skill-工具系统)
- [亮点五：MCP 协议集成](#亮点五mcp-协议集成)
- [亮点六：引用溯源机制](#亮点六引用溯源机制)
- [亮点七：多模型路由与熔断降级](#亮点七多模型路由与熔断降级)
- [亮点八：深度思考路由机制](#亮点八深度思考路由机制)
- [亮点九：高级文档分块策略](#亮点九高级文档分块策略)
- [亮点十：KB 相关性智能判断](#亮点十kb-相关性智能判断)
- [亮点十一：对话记忆管理](#亮点十一对话记忆管理)
- [亮点十二：全链路追踪](#亮点十二全链路追踪)
- [亮点十三：前端引用渲染系统](#亮点十三前端引用渲染系统)
- [亮点十四：A2A 多 Agent 架构](#亮点十四a2a-多-agent-架构)
- [亮点十五：文档智能解析管线](#亮点十五文档智能解析管线tika-markdown--多模态图片提取)
- [AI 设计模式总结](#ai-设计模式总结)

---

## 简历

### RAGStudio — 企业级 Agentic RAG 平台

**项目时间：** 2024.xx - 至今  
**项目角色：** 核心开发 / 后端开发  
**技术栈：** Java 17 / Spring Boot 3.5 / Spring AI 1.1.2 / pgvector / tsvector / MCP SDK / Redis + Redisson / RocketMQ / MyBatis-Plus / Sa-Token / Docker

**项目描述：**  
基于 ReACT Agent 循环的 Agentic RAG 平台，所有请求统一走 Agent 管线。LLM 在循环中自主推理、调用工具、管理记忆，覆盖从文档入库到智能问答的全链路。

### 核心亮点

- **设计文档智能解析管线**：PDF/Word/ODT 双通道并行提取——Tika XHTML→Markdown 提取文字，嵌入图片由轻量多模态模型 (Qwen3.5-9B) 独立提取文字，两者 Markdown 追加合并，互不覆盖。Base64 data URI 内联传递图片，无需额外 S3 存储。
- **设计 A2A 多 Agent 架构**：基于 Agent Card / Task / Artifact 的 A2A 通信协议，Orchestrator 主 Agent 统一路由 + 子 Agent 专精执行。Task 驱动的工作流，Artifact 跟踪执行结果。已实现 Q&A 问答 Agent、Tool 工具执行 Agent、Title 标题生成 Agent，支持热插拔注册。
- **设计并实现 ReACT Agent 循环引擎**：Thought → Action → Observation 循环，LLM 自主多步推理与工具调用。Plan-then-Execute 多步规划，三级降级解析器兼容 LLM 输出偏差，30 秒工具超时 + 失败自动重试，首轮未输出 ReACT 格式时注入纠正提示重试。
- **设计混合检索系统**：pgvector 余弦相似度语义检索 + PostgreSQL tsvector 全文检索，通过 RRF（Reciprocal Rank Fusion）算法融合排序。两个检索通道并行执行，RRF 融合后经去重 → Rerank 排序输出。
- **设计两阶段召回管线**：粗召阶段各通道以 topK×2 过量召回（hnsw.ef_search=200 扩大索引候选），后处理链先去重再通过语义 Rerank 模型（BaiLian API）精排截断，兼顾 HNSW 快速搜索与 Rerank 精确匹配。
- **设计 SKILL 自定义工具系统**：用户写 YAML 文件定义 Agent 工具，支持 http/script/command 三种类型。Docker 容器沙箱隔离执行（`--read-only`、`--cap-drop=ALL`、`--user 1000:1000`），SecurityAuditor 命令黑名单审计，15 秒轮询热更新，内置 skill_reader 工具让 LLM 在循环中读取 SKILL 详情。
- **设计 MCP 协议集成**：通过 McpToolAdapter 桥接 MCP 协议的 CallToolResult 到通用 Tool 接口，Agent 在循环中自主决策调用外部工具。
- **设计引用溯源机制**：方案A——LLM 在回答中使用 `[^chunk_id]` 标记引用；方案B——前端 10 字文本匹配兜底。引用数据随消息持久化到 `t_message.citations`，刷新不丢失。
- **实现多模型智能路由与熔断降级**：数据库驱动的动态模型配置（Spring AI），优先级路由 + Circuit Breaker 状态机，单模型故障秒级自动 fallback。
- **设计深度思考路由机制**：声明式注册各模型推理能力类型（连续/离散/布尔/不支持），0–100 统一滑块经策略模式适配为各模型原生 API 参数。模型选择器自动过滤 `supportsThinking=true` 候选，与熔断降级联动。推理内容通过 SSE 推送，前端展示思考过程与 `深度思考 {level}%` 徽章。
- **实现高级文档分块策略**：三种策略——固定大小分块（512 字符 + 句子边界对齐）、结构感知分块（Markdown 标题/代码块/段落识别）、递归分块（多级分隔符从粗到细递归切分，参考 LangChain 算法）。
- **实现 LLM 驱动对话记忆管理**：滑动窗口压缩（第 8 轮起触发，压缩后保留 4 轮原文），推理链自动嵌入消息内容，LLM 生成标题。
- **实现 KB 相关性智能判断**：进入 Agent 循环前用轻量 LLM 判断问题与所选知识库的相关性，LLM 可指定具体的 `collection_names` 精准过滤。

---

## 亮点一：ReACT Agent 循环引擎

### 背景

传统的 RAG 管线是线性的：改写 → 检索 → 回答。LLM 只在固定的几个环节做出决策，无法根据工具返回的结果调整推理方向，也无法做多步推理。

### 设计思路

将管线替换为 Thought → Action → Observation 的 ReACT 循环。LLM 在循环中完全控制推理流程：想一下需要什么信息 → 调用工具获取 → 观察返回结果 → 再决定下一步，直到信息充分后输出最终回答。

### 实现细节

**循环架构（AgentLoop.java）：**
1. **构建初始消息**：System Prompt（含工具定义 + KB 上下文）→ 对话历史 → 用户问题 → 格式提醒
2. **迭代循环**（最多 10 次）：
   - 同步调用 LLM（temperature=0.0，保证格式稳定性）
   - 空响应自动重试一次
   - 首轮未输出 ReACT 格式时注入纠正提示重试
   - 解析为 `AgentStep{thought, action, plan, toolName, toolInput, observation}`
   - **Plan-then-Execute**：如果 LLM 输出了 Plan，注入到后续消息中作为执行计划
   - 根据 Action 分支：FINISH → 流式输出最终回答；TOOL_CALL → 执行工具 → 追加 Observation → 继续循环

**ToolRegistry（工具注册中心）：**
- 基于 `ConcurrentHashMap`，线程安全
- `execute(name, params)` 使用 `CompletableFuture.supplyAsync` + 30 秒超时
- `formatForSystemPrompt()` 生成 Markdown 格式的工具描述，包含参数类型、必填/可选标记、默认值和枚举值

**格式校正：**
- 初次迭代 LLM 未输出 `Action:` 时，注入纠正提示重试一次
- 三级降级解析器：完整 JSON → 部分匹配 → 正则提取

**性能考虑：**
- 每请求新建 AgentLoop 实例（ToolRegistry 是 per-request 的）
- 同步 LLM 调用保证决策的完整性（不使用 streamChat）
- 最终回答每 5 字符分块流式推送

---

## 亮点二：混合检索（向量 + 关键词 + RRF）

### 背景

纯向量检索对专有名词、产品型号、编码类词汇召回不足。例如搜索 "HTTP 404"，语义向量可能匹配到"网络错误"而非具体的状态码。

### 设计思路

同时运行两条检索通道——向量通道找"意思相近的"，关键词通道找"字面匹配的"——通过 RRF 融合排序，确保两者都有贡献。

### 实现细节

**向量通道（PgRetrieverService）：**
```sql
SELECT id, content, 1 - (embedding <=> ?::vector) AS score
FROM t_knowledge_vector
WHERE metadata->>'collection_name' = ?
ORDER BY embedding <=> ?::vector LIMIT ?
```
- 使用 pgvector 的 `<=>` 余弦距离运算符
- HNSW 索引 + `SET LOCAL hnsw.ef_search = 200` 保证精度
- 返回 cosine similarity（范围 0~2，1 表示完全相同方向）

**关键词通道（KeywordSearchChannel）：**
```sql
SELECT id, content, ts_rank(to_tsvector('simple', content), plainto_tsquery('simple', ?)) AS score
FROM t_knowledge_vector
WHERE metadata->>'collection_name' = ?
  AND to_tsvector('simple', content) @@ plainto_tsquery('simple', ?)
ORDER BY score DESC LIMIT ?
```
- PostgreSQL 原生 tsvector 全文检索
- `simple` 词典（不做词干化，兼容中英文）
- GIN 索引加速
- 多个 collection 并行检索

**RRF 融合（RrfMerger）：**
```
score = Σ 1 / (60 + rank_i(d))
```
- 将两个通道的结果按 rank 位置加权融合
- k=60 为标准 RRF 常数，无需调权重
- 融合后去重 → Rerank 排序

**RrfHybridChannel：**
- Priority=0（最高优先级），启用时自动替代单独的向量/关键词通道
- 两条通道通过 `CompletableFuture` 并行执行
- 异常降级：混合检索失败时回退到纯向量检索

---

## 亮点三：两阶段召回管线（粗召 → 精排）

### 背景

RAG 检索面临一个根本矛盾：召回太少会漏掉相关文档，召回太多又会让 LLM 的上下文充满噪声。简单的前 N 条向量相似度检索无法兼顾查全率和精度。

### 设计思路

两阶段策略——第一阶段用放大系数**过量粗召**，宁可多不可少；第二阶段用去重 + 语义 Rerank 模型**精排截断**，只保留最相关的 topK。向量层还通过 HNSW 的 ef_search 参数扩大图搜索候选集，进一步降低漏检风险。

### 实现细节

**阶段一：多通道并行粗召**

各通道不是只查 topK 条，而是用 `topKMultiplier` 放大召回量：

| 通道 | 召回倍数 | 效果 |
|------|:--------:|------|
| 向量检索（按知识库） | ×2 | topK=5 时实际召回 10 |
| 关键词检索（tsvector） | ×2 | topK=5 时实际召回 10 |
| RRF 混合通道 | 自身 topK=5 | 融合排序后保留 5 |

同时，pgvector 的 HNSW 索引搜索参数 `hnsw.ef_search = 200` 在索引层评估 200 个候选节点，即使最终只返回 10 条，搜索空间远大于简单 topK。

配置见 [`SearchChannelProperties.java`](bootstrap/src/main/java/com/byteq/ai/ragstudio/rag/core/retrieve/SearchChannelProperties.java:55-85)：
```java
knowledgeBaseSelection.topKMultiplier = 2
keyword.topKMultiplier = 2
hybridRrf.topK = 5
```

**阶段二：后处理精排**

粗召结果进入后置处理器链：

1. **去重** [`DeduplicationPostProcessor`](bootstrap/src/main/java/com/byteq/ai/ragstudio/rag/core/retrieve/postprocessor/DeduplicationPostProcessor.java:29-67) — 合并多通道结果，按 chunk ID 去重，保留最高评分
2. **语义重排序** [`RerankPostProcessor`](bootstrap/src/main/java/com/byteq/ai/ragstudio/rag/core/retrieve/postprocessor/RerankPostProcessor.java:17-46) — 调用 [`RoutingRerankService`](bootstrap/src/main/java/com/byteq/ai/ragstudio/infra/rerank/RoutingRerankService.java:47-72) 以用户原始问题为 query，对候选 chunks 逐一计算语义相关性，重新排序后截断 topK

Rerank 模型通过 Alibaba Cloud Bailian API 调用（`BaiLianRerankClient`），候选数 ≤ topN 时跳过 API 调用直接返回。

**完整流程：**

```
用户问题
    │
    ▼
并行粗召（×2 放大）
    ├── 向量检索（ef_search=200）
    ├── 关键词检索
    └── RRF 融合（如启用混合通道）
    │
    ▼
去重
    │
    ▼
语义 Re-rank（BaiLian Rerank API）
    │
    ▼
最终 topK → LLM 上下文
```

关键设计思想：**召回阶段损失容忍度大，排序阶段再用语义模型补精度**，兼顾了 HNSW 的快速搜索和 Rerank 的精确匹配。

---

## 亮点四：用户自定义 SKILL 工具系统

### 背景

MCP 工具需要外部独立进程和标准协议，对于内部 API 调用和脚本执行等轻量场景太重了。需要一个更轻量的方式让用户扩展 Agent 的工具集。

### 设计思路

用户写一个 YAML 文件放到 `skills/` 目录，Agent 就能多一个可调用的工具。不需要写 Java 代码，不需要搭 MCP 服务器。script/command 类型通过 Docker 沙箱隔离执行，保证安全。

### 实现细节

**目录结构：**
```
skills/{skill-name}/
├── skill.yaml         # 定义文件（名称、描述、类型、配置、参数）
├── SKILL.md           # 技能说明书（LLM 通过 skill_reader 读取）
├── scripts/           # 可执行脚本文件
└── references/        # 参考资料
```

**支持的三种工具类型：**

| 类型 | 说明 | 示例 |
|------|------|------|
| http | 调外部 API | IP 归属地查询、天气查询 |
| script | 执行 scripts/ 下的脚本 | 系统信息采集 |
| command | 执行命令（默认禁用） | 需配置白名单 |

**SkillLoader（自动加载与热更新）：**
- 启动时扫描 `skills/` 子目录 → 解析 `skill.yaml` → 读取 `SKILL.md` → 扫描 `scripts/` / `references/` → 写入 Redis 缓存
- 15 秒轮询热更新（对比文件 mtime），增删改文件自动生效，无需重启

**SkillReaderTool（内置阅读器）：**
- 注册为 Agent 的工具 `skill_reader`，LLM 可在循环中调用
- 支持五个操作：`read_doc`（读 SKILL.md）、`list_scripts`、`read_script`、`list_refs`、`read_ref`
- 返回内容 5000 字符截断防止撑爆上下文

**Docker 沙箱（SandboxExecutor）：**
```
docker run --rm --read-only --cap-drop=ALL --user 1000:1000
  --tmpfs /tmp:size=1G,noexec --memory=256m --cpus=0.5 --pids-limit=50
  --network=none <image> sh -c "<command>"
```
- `--read-only`：根文件系统只读
- `--cap-drop=ALL`：剔除所有 Linux 权限
- `--tmpfs /tmp:size=1G,noexec`：临时文件上限 1GB，禁止执行
- `--memory=256m --cpus=0.5 --pids-limit=50`：资源限制
- `--network=none`：默认无网络
- 30 秒超时自动 docker kill

**命令安全审计（SecurityAuditor）：**
- 15 条正则黑名单规则，包括：
  - `rm -rf /`、`mkfs`、`dd if=` 等破坏性操作
  - `sudo`、`chmod 777`、`chown root` 等提权操作
  - `nc -e`、`bash -i >&`、`base64 -d | sh` 等反弹 Shell
  - 内网 IP 地址探测

---

## 亮点五：MCP 协议集成

### 背景

Agent 需要调用外部工具获取实时数据（天气、新闻、订单信息等）。MCP（Model Context Protocol）提供了标准化的工具发现和调用机制。

### 实现细节

**McpToolAdapter：** 将 MCP 协议的 `CallToolResult` 桥接到通用的 `Tool` 接口，使 MCP 工具与内置工具在 Agent 的 ToolRegistry 中统一管理。Agent 不区分工具来源，通过 `toolRegistry.execute(name, params)` 统一调用。

**McpToolRegistry：** 管理 MCP 工具的注册和生命周期，启动时异步加载不阻塞应用。

**动态连接管理（DynamicMcpConnectionManager）：**
- 运行时新增/更新 MCP Server 配置立即生效
- 基于 per-server `ReentrantLock` 的细粒度并发控制
- 支持 SSE 和 Streamable HTTP 两种传输协议

---

## 亮点六：引用溯源机制

### 背景

RAG 系统的回答必须可追溯。用户需要知道当前回答引用了哪些知识库文档，验证回答的真实性。

### 设计思路

方案A优先——在 System Prompt 中告知 LLM chunk 的 ID，引导 LLM 在回答中用 `[^chunk_id]` 标记引用。方案B兜底——前端文本匹配。

### 实现细节

**方案A（LLM 主动标注）：**
- `DefaultContextFormatter` 在格式化 kbContext 时给每个 chunk 加上 `[^chunk_{id}]` 前缀
- LLM 在上下文中看到 chunk_abc 的完整内容，可以在回答中写 `[^chunk_abc]` 引用
- Agent 完成后，后端扫描答案匹配 `[^chunk_(\w+)]` 正则

**方案B（前端文本匹配兜底）：**
- 连续 10 字文本重叠匹配
- 在 `CitationList` 组件中作者前处理

**数据持久化：**
- 引用的 chunk 列表通过 SSE `event: citation` 推送到前端
- 同时通过 `StreamChatEventHandler.onCitation()` 暂存，在 `onComplete()` 中传递给 `ChatMessage.assistant(..., citationsJson)`
- 存储到 `t_message.citations` TEXT 字段，随消息一起持久化
- 刷新页面后重新加载消息时，前端解析 citations JSON 恢复引用展示

**交互：**
- 默认折叠，用户可逐个展开查看 chunk 原文

---

## 亮点七：多模型路由与熔断降级

### 背景

生产环境中单一路模型提供商存在单点故障风险。需要支持多个 LLM 提供商的动态切换和故障自动恢复。

### 实现细节

**动态模型配置：**
- 模型配置存储在数据库中，通过管理后台运行时修改，无需重启
- 支持多个提供商：百炼（阿里云）、SiliconFlow、DeepSeek 等
- 基于 Spring AI 的 OpenAI 兼容协议

**熔断降级（Circuit Breaker）：**
- 状态机：CLOSED（正常）→ OPEN（熔断）→ HALF_OPEN（半开探测）
- 故障阈值：连续 2 次调用失败进入 OPEN 状态
- 恢复探测：30 秒后自动转为 HALF_OPEN，尝试探测请求
- 流式场景与同步场景共享健康检查状态

**优先级路由：**
- 模型配置包含优先级，高优先级模型优先使用
- 高优先级模型故障时自动 fallback 到低优先级模型
- fallback 过程秒级完成

---

## 亮点八：深度思考路由机制

### 背景

不同 LLM 对"深度思考"的支持方式差异极大。Claude 通过 `budget_tokens` 连续控制推理预算，DeepSeek 只支持开/关布尔开关，GPT-4o 完全不支持。需要在 0–100 的统一滑块背后，为每个模型适配其原生参数。

### 设计思路

声明式注册各模型的推理能力类型（连续/离散/布尔/不支持），将 0–100 滑块值通过策略模式适配为各模型的原生 API 参数。前端发送统一 thinkingLevel(0–100)，后端自动路由到正确的参数格式。

### 实现细节

**四种适配策略（ReasoningAdapter）：**

| 类型 | 说明 | 模型示例 | 输出参数 |
|------|------|----------|----------|
| `CONTINUOUS` | 连续区间线性插值 | Claude Opus (1024–8192) | `{"thinking":{"budget_tokens":4096}}` |
| `DISCRETE` | 阈值查找，分档输出 | 各模型不同档位 | 档位对应的 API 参数 |
| `BOOLEAN` | 仅开/关 | DeepSeek R1/V4, Qwen3, GLM-4 | `{"thinking":{"type":"enabled"}}` |
| `NONE` | 不支持 | GPT-4o | 无参数 |

**模型注册（ModelReasoningRegistry）：**
- 启动时注册各模型的推理配置：类型 + 参数范围 + API 字段路径
- 运行时 `ReasoningRouter.route(modelId, thinkingLevel)` 查询注册表 → 适配器转换 → 返回参数字典

**模型选择联动（ModelSelector）：**
- 深度思考开启时，自动过滤出 `supportsThinking=true` 的候选模型
- 若无可用模型则日志警告 "深度思考模式没有可用候选模型"
- 与熔断降级联动：高优先级深度思考模型故障时 fallback 到低优先级

**前端滑块（DeepThinkingSlider）：**
- 0–100 滑块，分段描述：0=关闭 / 1–33=轻度 / 34–66=中等 / 67–100=深度
- 渐变填充轨道 + 粒子动画 + 刻度标签
- 值存储在 `chatStore.deepThinkingLevel`，随消息发送到后端

**思考内容提取：**
- `FluxToStreamCallbackBridge` 从 Spring AI `ChatResponse.metadata["reasoningContent"]` 提取推理内容
- 通过 SSE `event: thinking` 推送到前端，存储在 `message.thinkingContent`
- 前端 `AgentSteps` 组件展示 `深度思考 {level}%` 徽章

---

## 亮点九：高级文档分块策略

### 背景

分块策略直接影响 RAG 的检索质量。简单的固定滑动窗口在复杂文档上容易产生上下文割裂。需要针对不同文档类型提供不同的分块策略。

### 实现细节

**ChunkingStrategy 接口：**
```java
interface ChunkingStrategy {
    ChunkingMode getType();
    List<VectorChunk> chunk(String text, ChunkingOptions config);
}
```

**三种策略：**

**固定大小分块（FixedSizeTextChunker）：**
- 按固定字符数切分，默认 512 字符/块，128 字符 overlap
- 切分时自动对齐句子边界（中文句号、感叹号、问号，英文句点）
- 自动修复 URL 跨行断开问题（如 `https://example.\\ncom` → `https://example.com`）
- 中文软换行修复（如 `商\\n保通` → `商保通`）

**结构感知分块（StructureAwareTextChunker）：**
- 专为 Markdown 文档设计，逐行扫描识别：
  - HEADING：`^#{1,6}\s+` 标题行，作为独立块
  - CODE：`` ^``` `` 代码围栏，保持完整不切割
  - ATOMIC：`![]()` 图片行、`[]()` 链接行
  - PARA：其他连续非空行，空行为段落边界
- 三级预算打包：min=600 / target=1400 / max=1800 字符
- 只在块边界切分，标题和正文不会分到两块里
- 末尾小 chunk 自动向前合并

**递归分块（RecursiveTextChunker）：**
- 参考 LangChain 的 RecursiveCharacterTextSplitter
- 多级分隔符从粗到细递归切分：`\n\n` → `\n` → `。` → `！` → `？` → `. ` → `，`
- 块太大时用下一级分隔符递归切分
- 用尽所有分隔符后强制按 chunkSize 切分
- 支持 configurable overlap

---

## 亮点十：KB 相关性智能判断

### 背景

用户可能选择了多个知识库，但当前问题可能只与其中一部分相关。不加判断地检索所有知识库会引入噪声，降低回答质量。

### 实现细节

- 在进入 Agent 循环前，使用轻量 LLM 调用判断问题与所选知识库的相关性
- LLM 返回 `{ relevant, reasoning, relevantCollectionNames }` 结构
- 如果 LLM 指定了具体的 collection_names，知识库列表会被过滤到只检索这些 collection
- 判断依据：知识库名称 + 知识库描述（创建/编辑时可填写）
- 判断异常时默认"相关"（宁检不错）
- JSON 断尾修复：处理 LLM 输出被截断的情况

---

## 亮点十一：对话记忆管理

### 背景

长时间对话中，完整的消息历史很快会撑爆 LLM 的上下文窗口。需要一种压缩机制保留关键信息同时控制 token 消耗。

### 实现细节

**滑动窗口压缩（Sliding Window + Summary）：**
- 保留最近 4 轮完整对话历史
- 当用户消息轮数达到 8 轮时触发压缩，LLM 将早期对话压缩为摘要，保留 4 轮原文
- 压缩阈值可配置，默认按 `historyKeepTurns * 2` 自动计算
- 摘要保留主题信息，最大 200 字符

**Agent 推理链嵌入：**
- `StreamChatEventHandler.enrichWithAgentTrace()` 在 `onComplete()` 时将 agentStepsJson
  解析为可读的 Markdown blockquote 格式，追加到 assistant content 尾部
- 用户可在消息中直接看到 Agent 的完整推理过程（思考、工具调用、观察结果）
- 同时推理链 JSON 仍存储在 `t_message.agent_steps` 字段供前端回放

**标题生成：**
- 新对话首次提问后，LLM 自动生成对话标题
- 标题存储在 `t_conversation` 表，用于前端会话列表展示

**Agent 步骤持久化：**
- 推理步骤序列化为 JSON 存储在 `t_message.agent_steps` TEXT 字段
- 前端通过 `event: agent_step` 和 `event: agent_steps_complete` 接收并回放
- 刷新页面后从数据库加载恢复

---

## 亮点十二：全链路追踪

### 背景

RAG 管线涉及多个阶段，需要记录每个阶段的耗时和状态，便于排查问题和性能优化。

### 实现细节

- 基于 ThreadLocal 的轻量级追踪方案，不依赖 OpenTelemetry 等外部组件
- 每阶段记录：阶段名称、类型、耗时、状态，写入 `t_rag_trace_node` 表
- `ForwardingStreamCallback` 装饰器模式包装回调，记录首包耗时（TTFT）
- 异步线程通过 TransmittableThreadLocal 传递 traceId
- **后台管理删除功能**：支持手动删除 stuck RUNNING 的 trace 记录，先删节点表再删运行表，同步执行

---

## 亮点十三：前端引用渲染系统

### 背景

后端通过 SSE `event: citation` 推送引用数据，前端需要将 LLM 回答中的 `[^chunk_id]` 标记转换为可点击的 `[N]` 引用链接，同时隐藏 LLM 生成的 `[QUOTE:chunk_id] "原文"` 脚注。此外，用户在自己的消息中也可能手动输入 `[QUOTE:chunk_id]` 引用知识库。

### 设计思路

前端根据消息上下文分流处理——AI 回答有完整的 citations 数据结构，走完整引用链路；用户消息无 citations 数据，走简化的视觉 badge 方案。

### 实现细节

**处理流程（MarkdownRenderer.tsx）：**

```
MarkdownRenderer 收到 content + citations
    │
    ├── citations 存在且有数据（AI 回答）
    │   ├── [^chunk_id] → [N](#cite:xxx)    可点击引用数字
    │   ├── [QUOTE:...] → 全部 strip        无论有无引号
    │   └── CitationList 渲染在底部          点击 [N] 展开 chunk 原文
    │
    └── citations 不存在或无数据（用户消息）
        ├── [QUOTE:id] "原文"    → strip            （粘贴的 AI 残留）
        ├── [QUOTE:id] 原文      → **📎 `id`** 原文 （视觉徽章，不可点击）
        └── [QUOTE:id] 孤立      → strip
```

**关键设计决策：**
- **AI 回答统一 strip**：AI 回答中无论 `[QUOTE:...]` 是否有引号，全部 strip。因为引用信息已通过 `[N]` 链接 + CitationList 完整展示
- **用户消息视觉 badge**：无 citations 数据，无法支持展开，改为纯文本 `**📎 \`id\`**` 格式，避免误导性可点击样式
- **行内引用交互**：`[N]` 链接点击时 dispatch `expand-citation` 自定义事件，CitationList 监听并自动展开对应条目 + 滚动到可视区域
- **历史数据兼容**：`t_message.citations` 字段持久化引用数据，刷新页面后重新加载时 JSON.parse 恢复

---

## 亮点十四：A2A 多 Agent 架构

...

---

## 亮点十五：文档智能解析管线（Tika Markdown + 多模态图片提取）

### 背景

企业知识库中包含大量 PDF、Word、ODT 等格式的文档，这些文档既有文字又有图片、表格、图表等复杂结构。传统方案只用 Apache Tika 提取纯文本，会丢失表格结构和图片中的文字信息。

### 设计思路

双通道互补提取：
1. **文字通道**：Tika 解析 XHTML → 转 Markdown，保留表格、标题、列表等结构化信息
2. **图片通道**：从文档中提取嵌入图片（或 PDF 渲染为图片）→ 轻量多模态模型 (Qwen3.5-9B) → Markdown 输出

两通道结果合并后分块 + Embedding，最大程度保留文档的原始结构和完整信息。

### Pipeline 架构

```
PDF / Word / ODT / PPTX / 图片
        │
        ├─ Tika 解析 (ToXMLContentHandler)
        │     │
        │     ▼
        │  XHTML → Jsoup 递归转换
        │     │  <h1~h6>     → # ~ ###### 标题
        │     │  <table>     → | 列名 | 列名 |\n | --- | --- |\n | 内容 |
        │     │  <ul>/<ol>   → - / 1. 列表，支持嵌套
        │     │  <strong>    → **加粗**
        │     │  <code>/<pre>→ `代码` / ```代码块```
        │     │  <a>         → [链接](url)
        │     │  <blockquote>→ > 引用
        │     │
        │     ▼
        │  Markdown 文本
        │
        ├── 文档含嵌入图片？ ────┐
        │   (PDF/ODT/DOCX/PPTX)   │
        │          │               │
        │          ▼               │
        │   ┌─ PDF  → PDFBox 渲染  │
        │   │        每页为图片     │
        │   │         (150 DPI)    │
        │   │                      │
        │   ├─ ODT/DOCX/PPTX       │
        │   │   → ZIP 解压         │
        │   │   → 提取嵌入图片     │
        │   │   (Pictures/,        │
        │   │    word/media/,      │
        │   │    ppt/media/)       │
        │   │                      │
        │   └─ 图片文件            │
        │       → 直接使用          │
        │          │               │
        │          ▼               │
        │   Base64 data URI        │
        │          │               │
        │          ▼               │
        │   轻量多模态模型          │
        │   (Qwen3.5-9B)           │
        │          │               │
        │          ▼               │
        │   Markdown 格式输出      │
        │   (同结构化格式)          │
        │          │               │
        └──────────┘               │
                   │               │
                   ▼               │
          Tika 文字 + "\n\n"
          + 图片文字（追加） ────┘
                   │
                   ▼
             分块 + Embedding → 向量库
```

**关键设计：文字与图片分别提取后追加合并**

与常见的"Tika 提取不充分时用多模态兜底替换"方案不同，本系统是**始终并行提取**：

| 通道 | 提取内容 | 输出格式 |
|------|----------|----------|
| Tika | 文档中所有文字 | Markdown |
| 多模态模型 (Qwen3.5-9B) | 文档中所有嵌入图片 | Markdown |

两者通过 `text + "\n\n" + visionText` **追加合并**，互不覆盖。这样文档中的文字段落和图片中的信息都能完整保留，不会出现"文字很多但图片信息被忽略"或"有图片但文字内容被替换"的问题。

### 实现细节

**文字通道（TikaDocumentParser.extractAsMarkdown()）：**

关键实现在 `TikaDocumentParser.java`：
- 使用 `ToXMLContentHandler` 替代 `BodyContentHandler`，获取 XHTML
- `convertXhtmlToMarkdown()` 用 Jsoup 解析 XHTML 为 DOM 树
- `convertElement()` 递归处理各节点，支持 6 级标题、嵌套列表、Markdown 表格、代码块、引用块
- `convertTable()` 通过 `<tr>` / `<th>` / `<td>` 构建 `| --- | --- |` 格式的 Markdown 表格
- `convertInline()` 处理内联样式：粗体、斜体、行内代码、链接、图片、上下标
- 失败时降级为 `extractText()` 纯文本提取

**图片通道（DocumentVisionExtractor）：**

| 文档类型 | 图片提取方式 | 原因 |
|----------|-------------|------|
| PDF | PDFBox 渲染 150 DPI | PDF 不是 ZIP 包，无嵌入文件 |
| ODT/DOCX/PPTX | ZIP 解压提取 `Pictures/`、`word/media/` 等目录 | 原生嵌入图片 |
| 图片文件 | 直接读取字节 | 本身就是图片 |

提取的图片统一转 Base64 data URI（`data:image/jpeg;base64,...`），通过 `ChatMessage.imageUrls` 传递给多模态模型（默认 `qwen3.5-9B`），prompt 要求以 Markdown 格式输出文字内容。

**Base64 传递优化：**
- 不将提取的图片上传到 S3（避免存储浪费和权限管理）
- 直接通过 `data:` URI 内联传递给 LLM API
- S3 中只存储原始文档文件

**触发策略（runChunkProcess）：**
```
// 始终从 Tika 获取文字
text = extractAsMarkdown(stream)

// 文档类型含嵌入图片 → 独立提取图片文字并追加
if (mayContainEmbeddedImages(fileType)) {
    visionText = extractTextWithVision(fileUrl, fileType, fileName)
    if (visionText 不为空) {
        text = text + "\n\n" + visionText
    }
}

// 纯图片文件无 Tika 文字
if (mimeType.startsWith("image/") && text 为空) {
    text = extractTextWithVision(...)
}
```

**格式一致性：**
- 无论来自文字通道还是图片通道，最终输出都是 Markdown
- 分块时统一按 Markdown 文本处理
- 检索时保留 Markdown 格式，LLM 上下文中的表格/标题结构清晰

---

## 模式总结

### 已使用的设计模式

### 背景

单 Agent 将所有能力（知识库检索、工具调用、用户交互）混在一起，prompt 越来越臃肿。不同任务对 prompt、工具集、模型的要求不同，混合在一起互相干扰。需要一种机制让不同能力的 Agent 各司其职，协同工作。

### 设计思路

基于 A2A（Agent-to-Agent）通信协议设计多 Agent 架构：
- **Agent Card**：每个 Agent 发布身份卡，描述名称、能力、输入输出协议
- **Task**：主 Agent 创建 Task 投递给目标子 Agent，Task 携带参数和状态
- **Artifact**：Agent 执行完成后产出 Artifact，携带执行结果
- **Orchestrator**：统一路由、任务编排、结果汇总

### 实现细节

**核心模型（4个 record/class）：**

| 模型 | 字段 | 用途 |
|------|------|------|
| `AgentCard` | name, description, capabilities | Agent 身份发布与发现 |
| `Task` | id, targetAgent, input, status | 跨 Agent 工作单元 |
| `Artifact` | id, taskId, agentName, type, content | 执行结果封装 |
| `AgentMailbox` | agentName, inbox queue | Task 投递队列 |

**OrchestratorAgent 注册制：**
```java
OrchestratorAgent orchestrator = new OrchestratorAgent();
orchestrator.register(qaSubAgent);    // Q&A 问答
orchestrator.register(toolSubAgent);  // 工具执行
orchestrator.register(titleSubAgent); // 标题生成
orchestrator.run(question, ctx, callback);
```

**已实现的子 Agent：**

| Agent | 名称 | 能力 | 核心逻辑 |
|-------|------|------|---------|
| **Q&A Agent** | `qa` | kb_retrieval, mcp_tools, skill_tools | 包装 AgentLoop 运行 ReACT 循环 |
| **Tool Agent** | `tool` | mcp_tools, skill_tools, time_tool | 直接执行 MCP/SKILL 工具 |
| **Title Agent** | `title` | title_generation | 调用标题生成 prompt |

**Agent 间通信流程：**

```
用户问题
    │
    ▼
OrchestratorAgent.run()
    │
    ├── 1. 路由 → Q&A Agent
    │      ├── Task{target:"qa", input:{question}}
    │      ├── Q&A Agent.run() → AgentLoop → SSE 流式输出
    │      └── 返回 Artifact[{type:"answer"}, {type:"steps"}, {type:"citations"}]
    │
    ├── 2. 有 answer → 路由 → Title Agent
    │      ├── Task{target:"title", input:{question, answer}}
    │      ├── Title Agent.run() → LLM 调用
    │      └── 返回 Artifact[{type:"title"}]
    │
    └── 收集所有 Artifact（框架层可扩展持久化跟踪）
```

**可扩展性：**
- 新增 Agent 只需实现 `SubAgent` 接口 + `orchestrator.register()`
- Agent 之间通过 Task/Artifact 解耦，不共享内部状态
- 路由策略可替换（当前单 Agent 直通，可扩展 LLM 路由）

---

## AI 设计模式总结

| 模式 | 应用场景 | 实现位置 |
|------|---------|---------|
| **ReACT 循环** | LLM 自主推理 + 工具调用 | AgentLoop.java |
| **Plan-then-Execute** | 多步任务规划与执行 | AgentLoop.java（Plan 注入） |
| **Strategy 模式** | 可切换的检索通道 | SearchChannel 接口 + 4 种实现 |
| **模板方法** | 并行检索模板（粗召通道） | AbstractParallelRetriever |
| **策略模式** | 可切换的分块策略 | ChunkingStrategy 接口 + 3 种实现 |
| **装饰器** | 回调透传 + 追踪注入 | ForwardingStreamCallback |
| **适配器** | MCP 工具适配通用 Tool | McpToolAdapter |
| **Circuit Breaker** | 多 LLM 熔断降级 | ModelRoutingExecutor |
| **RRF 融合** | 多通道结果融合排序 | RrfMerger |
| **Docker 沙箱** | 安全执行用户工具 | SandboxExecutor + SecurityAuditor |
| **目录监听** | SKILL 热更新 | SkillLoader（15s 轮询） |
| **观察者** | SSE 事件推送 | StreamChatEventHandler |
| **A2A 通信** | 多 Agent 协同（Card/Task/Artifact） | OrchestratorAgent + SubAgent |
| **注册制** | Agent 热插拔 | OrchestratorAgent.register() |
| **策略模式** | 可切换的路由策略 | OrchestratorAgent.resolveAgent() |

---

*文档版本：V1.0*  
*生成日期：2026-06*
