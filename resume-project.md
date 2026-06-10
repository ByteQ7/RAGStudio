## 企业级 Agentic RAG 智能问答平台

**技术栈：** Java 17 / Spring Boot 3.5 / PostgreSQL + pgvector / Redis + Redisson / RocketMQ / MCP SDK / React 18 + TypeScript

**项目描述：** 面向企业场景的智能检索增强生成（RAG）平台，覆盖文档入库、知识检索、智能问答完整链路，支持多模型路由调度、MCP 工具调用、多通道检索、对话记忆与全链路追踪。

---

### 核心职责与技术亮点

**1. 流式 LLM 模型路由与熔断降级机制**

设计并实现了面向流式 LLM 调用的「首包探测 + 自动降级」方案（ProbeStreamBridge）。针对流式场景下"连接成功但无内容输出"的假活问题，在模型返回首个 token 前引入缓冲探测层：通过 ProbeStreamBridge 代理原始 StreamCallback，阻塞等待首包（60s 超时），成功则将缓冲一次性放行并切换为直通模式，失败则自动 cancel 连接并切换至下一候选模型。底层采用三态断路器（CLOSED / OPEN / HALF_OPEN）管理模型健康状态，线程安全通过 ConcurrentHashMap.compute() 原子操作 + volatile 字段保证，实现了多 Provider（百炼 / DeepSeek / SiliconFlow / Ollama）间的零感知故障切换。

**2. 多通道并行检索引擎**

基于策略模式构建了可扩展的多通道检索引擎（MultiChannelRetrievalEngine）。各检索通道（KnowledgeBaseSelectionChannel / VectorGlobalSearchChannel）通过 SearchChannel 接口自动注册，使用 CompletableFuture + 专用线程池并行执行，每个通道独立判断是否参与本轮检索。后置处理器采用责任链模式（SearchResultPostProcessor），按 order 排序依次执行去重、Rerank 精排等操作，异常自动跳过不阻断主链路。支持跨 Collection 并行检索，兼顾召回率与精准度。

**3. MCP 协议集成与 Agentic RAG 闭环**

将 Model Context Protocol（MCP）融入 RAG 对话流程，实现 LLM 自主决策调用外部业务工具的 Agentic 能力。在 StreamChatPipeline 中设计了 MCP 决策分支：LLM 通过流式输出 `__MCP_CALL__:toolId` 标记触发工具调用，LLMMcpParameterExtractor 根据工具 JSON Schema 自动提取参数，McpClientToolExecutor 通过 McpSyncClient 调用远端 MCP Server，工具返回结果注入上下文后触发二次流式生成。整个流程对上层业务代码透明，检索与工具调用无缝融合。

**4. 查询改写与多级兜底策略**

实现查询预处理链路：术语归一化（QueryTermMappingService，Redis 缓存 + DB 回填，支持按知识库过滤和最长匹配优先级）→ LLM 改写 + 多问句拆分（MultiQuestionRewriteService，输出 JSON 结构化结果）→ 规则拆分兜底（按标点分隔符）。LLM 输出通过 LLMResponseCleaner 清洗 markdown 代码块后解析，JSON 解析失败时降级为归一化文本，确保链路不因单点故障中断。

**5. 对话记忆与长上下文管理**

设计双通道并行加载的对话记忆方案：通过 CompletableFuture.allOf 并行加载对话摘要（LLM 自动压缩）和近期历史消息，摘要注入 System Prompt、历史消息作为上下文窗口。对话轮次超阈值时自动触发 LLM 摘要压缩，平衡上下文质量与 Token 开销。

**6. 分布式公平限流器**

基于 Redis 实现分布式公平排队限流器（FairDistributedRateLimiter），使用 Sorted Set 维护排队顺序，PermitExpirableSemaphore 管理并发配额，Lua 脚本保证队头 claim + 僵尸清理的原子性，RTopic Pub/Sub 实现跨实例即时唤醒。Ticket 状态机（PENDING → GRANTED / TIMED_OUT / CANCELLED）通过 CAS（AtomicReference.compareAndSet）保证终态互斥，Entry TTL + 存活标记机制防止 JVM 崩溃后的资源泄漏。

**7. 文档摄入 DAG 流水线**

设计可配置的文档摄入链式引擎（IngestionEngine），支持 Fetcher → Parser → Enhancer → Chunker → Indexer 六类处理节点的 DAG 编排。引擎自动检测起始节点、循环依赖和非法引用，支持条件分支执行（ConditionEvaluator），节点级日志记录执行耗时与状态。多来源获取策略覆盖 Local / S3 / HTTP / 飞书四种数据源。

**8. 全链路追踪与可观测性**

基于注解 + AOP + 装饰器回调组合实现零侵入的 RAG 链路追踪：`@RagTraceNode` / `@RagTraceRoot` 注解自动采集各阶段（改写 / 检索 / 重排 / 模型路由 / 生成）的耗时与输入输出，StreamSpanCallback 管理流式场景下的 span 生命周期并支持跨线程传播（TransmittableThreadLocal）。Trace 数据持久化到数据库，配合前端回放调试和管理后台 Dashboard（P95 延迟、成功率、慢查询率等指标），形成完整的可观测性闭环。

---

### 技术难点总结

| 难点 | 挑战 | 解决方案 |
|------|------|----------|
| 流式 LLM 假活检测 | 模型连接成功但无内容输出，传统超时无法区分 | ProbeStreamBridge 首包探测 + 缓冲放行机制 |
| 多模型并发降级 | 流式场景下的 cancel + 切换 + 缓冲一致性问题 | synchronized + volatile committed 双重保护 |
| 高并发公平限流 | 多实例部署下排队公平性与资源不泄漏 | Lua 原子操作 + CAS 状态机 + Entry TTL |
| RAG 检索精准度与召回率平衡 | 单通道难以兼顾 | 多通道并行 + 后置去重 + Rerank 精排 |
| 长对话上下文管理 | Token 限制与上下文质量的矛盾 | LLM 自动摘要压缩 + 双通道并行加载 |
| 跨线程链路追踪 | 异步/流式场景下 traceId 和 span 传播 | TransmittableThreadLocal + StreamSpan 生命周期管理 |
