# RAGStudio 项目 —— 面试亮点学习与复习指南

> 本文档覆盖简历中 RAGStudio 项目 6 个核心技术亮点的完整知识体系，帮助你系统学习、深入理解、从容应对面试追问。

---

## 亮点一：流式 LLM 模型路由与熔断降级

### 1.1 核心知识点

**RAG / LLM 流式调用基础**

- SSE（Server-Sent Events）协议：HTTP 长连接、text/event-stream MIME 类型、事件格式（event/data/id/retry 字段）、与 WebSocket 的区别
- LLM 流式生成原理：Token 逐字生成、TTFT（Time To First Token）指标含义、为什么流式场景下"连接成功≠能正常输出"
- 多 Provider 差异：不同模型 API 的兼容性（OpenAI 兼容格式 vs 私有协议），为什么需要统一抽象层

**断路器模式（Circuit Breaker）**

- 三态模型：CLOSED（正常放行）→ OPEN（拒绝请求）→ HALF_OPEN（探测恢复）
- 状态转移条件：连续失败次数达到阈值触发 OPEN；冷却期过后进入 HALF_OPEN；探测成功恢复 CLOSED，失败重新 OPEN
- 与熔断器在微服务中的应用对比（Hystrix / Resilience4j 的异同）

**并发编程与线程安全**

- `volatile` 关键字：可见性保证、禁止指令重排、不保证原子性
- `ConcurrentHashMap.compute()`：原子复合操作（读-改-写），与 `synchronized` 块的区别
- `CompletableFuture`：异步编排、`supplyAsync` / `allOf` / `thenApply`、线程池选择

**装饰器模式（Decorator Pattern）**

- ProbeStreamBridge 如何代理 StreamCallback：在首包到达前缓冲所有事件，首包到达后切换为直通模式
- `synchronized(lock)` + `volatile committed` 的双重保护：committed 为 true 后跳过锁以提高性能

### 1.2 学习路径

1. **理解 SSE 协议**：用浏览器 DevTools 观察 SSE 请求，手动实现一个简单的 SSE 客户端和服务器
2. **学习断路器模式**：阅读 Martin Fowler 的 CircuitBreaker 文章，手绘三态转移图
3. **Java 并发复习**：重点理解 volatile 的语义、ConcurrentHashMap 的 compute/merge 原子方法
4. **源码精读**：按顺序阅读 `ModelHealthStore` → `ModelRoutingExecutor` → `ProbeStreamBridge` → `RoutingLLMService`
5. **模拟故障演练**：在脑中推演"模型 A 超时 → 模型 B 接管"的完整流程，画出时序图

### 1.3 关键源文件

| 文件 | 作用 |
|------|------|
| `infra-ai/.../model/ModelHealthStore.java` | 三态断路器实现 |
| `infra-ai/.../model/ModelRoutingExecutor.java` | 同步调用 Fallback 执行器 |
| `infra-ai/.../chat/RoutingLLMService.java` | 流式调用路由 + 首包探测 |
| `infra-ai/.../chat/ProbeStreamBridge.java` | 首包探测桥接器 |
| `infra-ai/.../chat/AbstractOpenAIStyleChatClient.java` | 模板方法基类 |

### 1.4 高频面试题

**Q1：什么是断路器模式？你的项目中怎么用的？**

回答框架：断路器是微服务中防止级联故障的保护机制。我项目中用来管理多个 LLM Provider 的健康状态。CLOSED 状态正常调用；当连续失败 N 次后进入 OPEN 状态，直接拒绝请求避免雪崩；冷却期过后进入 HALF_OPEN，放一个探测请求验证恢复情况。线程安全通过 ConcurrentHashMap.compute() 原子操作保证。

**Q2：流式调用场景下，怎么判断模型是否"假活"？**

回答框架：传统 HTTP 超时只检测连接建立阶段，但流式 LLM 存在"TCP 连接成功、SSE 流已建立、但不输出任何 token"的情况。我设计了 ProbeStreamBridge，它在代理层缓冲所有回调事件，阻塞等待首个 onContent 或 onThinking 事件。60 秒内无首包则判定为假活，cancel 连接并切换下一个模型。首包到达后将缓冲一次性 flush 并切换为直通模式。

**Q3：volatile 和 synchronized 在你的代码里分别解决什么问题？**

回答框架：volatile committed 字段保证"切换为直通模式"这个状态变更对所有线程立即可见。synchronized(lock) 保护 committed 切换前的缓冲操作，确保缓冲写入和 committed 切换的原子性。committed 变为 true 后，后续事件直接走无锁快速路径，避免在高吞吐场景下每次都加锁。

---

## 亮点二：MCP 协议驱动的 Agentic RAG

### 2.1 核心知识点

**MCP（Model Context Protocol）**

- MCP 是什么：Anthropic 提出的标准化协议，让 LLM 能以统一方式调用外部工具和数据源
- 核心概念：MCP Server（工具提供方）、MCP Client（调用方）、Tool Specification（工具定义，含 JSON Schema 参数描述）
- 传输方式：SSE（Streamable HTTP）、stdio 两种模式的适用场景
- 与 OpenAI Function Calling 的区别：MCP 是开放标准，解耦更彻底

**Agentic RAG 设计思路**

- 传统 RAG vs Agentic RAG：传统 RAG 只做检索+生成；Agentic RAG 让 LLM 自主决策是否需要额外信息
- 决策时机：在流式生成过程中实时判断，而非先决策再生成
- 参数提取：LLM 根据工具的 JSON Schema 从用户问题中提取结构化参数

**Pipeline 编排**

- StreamChatPipeline 的阶段设计：记忆加载 → 查询改写 → 知识检索 → MCP 决策分支 → 流式生成
- 二次生成：工具返回结果注入上下文后，LLM 基于增强上下文重新生成

**策略模式（Strategy Pattern）**

- McpToolRegistry 注册表：工具 ID 到 McpToolExecutor 的映射
- LLMMcpParameterExtractor：利用 LLM 理解 JSON Schema 实现动态参数提取

### 2.2 学习路径

1. **MCP 协议入门**：阅读 MCP 官方文档 (modelcontextprotocol.io)，理解 Server/Client/Tool 三角关系
2. **动手搭建 MCP Server**：用 Java MCP SDK 实现一个简单的工具（如天气查询），理解 Tool Specification 定义
3. **理解 Agentic 范式**：对比 LangChain Agent 和 MCP 的设计哲学差异
4. **源码精读**：按顺序阅读 `McpClientAutoConfiguration` → `DefaultMcpToolRegistry` → `LLMMcpParameterExtractor` → `McpClientToolExecutor` → `StreamChatPipeline` 中的 MCP 分支
5. **画流程图**：完整绘制"用户提问 → LLM 决策 → 工具调用 → 二次生成"的时序图

### 2.3 关键源文件

| 文件 | 作用 |
|------|------|
| `bootstrap/.../rag/core/mcp/McpClientToolExecutor.java` | MCP 工具执行器 |
| `bootstrap/.../rag/core/mcp/LLMMcpParameterExtractor.java` | LLM 自动参数提取 |
| `bootstrap/.../rag/service/pipeline/StreamChatPipeline.java` | 对话 Pipeline（含 MCP 决策分支） |
| `mcp-server/.../executor/*.java` | MCP Server 端工具实现 |
| `mcp-server/.../config/McpServerConfig.java` | MCP Server 配置 |

### 2.4 高频面试题

**Q1：什么是 MCP？为什么要用 MCP 而不是直接调 API？**

回答框架：MCP 是 Anthropic 提出的开放标准协议，核心目标是将 LLM 与外部工具/数据源的交互标准化。相比直接调 API，MCP 的优势在于：1）工具定义与调用完全解耦，新增工具只需实现 Tool Specification；2）LLM 不需要知道底层 API 细节，只需理解工具的语义描述和 JSON Schema；3）工具可以被任意 MCP Client 复用。我项目中将 MCP Server 作为独立服务部署，主应用通过 McpSyncClient 调用。

**Q2：你的 Agentic RAG 是怎么让 LLM 决定要不要调用工具的？**

回答框架：在 StreamChatPipeline 中，如果存在已注册的 MCP 工具，我会将工具列表注入 MCP 决策 Prompt，让 LLM 在流式生成过程中自主判断。如果 LLM 认为需要调用工具，它会在输出中写入特定标记协议（`__MCP_CALL__:toolId`），我的代码会拦截这个标记、提取参数、执行工具，然后将工具结果注入上下文进行二次生成。对用户来说整个过程就是一次连续的流式对话。

**Q3：如果 LLM 提取的参数不对怎么办？有什么容错机制？**

回答框架：参数提取通过 LLMMcpParameterExtractor 用 LLM 理解工具的 JSON Schema 来完成。容错方面：1）JSON Schema 本身有 required/optional 约束，可以在 Prompt 中明确告知 LLM；2）MCP Server 端对入参有校验逻辑，参数不合法时返回错误信息，LLM 可以基于错误信息进行修正或告知用户；3）工具调用失败时不阻断主链路，Pipeline 会基于已有检索结果生成回答。

---

## 亮点三：多通道并行检索引擎

### 3.1 核心知识点

**向量检索基础**

- 向量嵌入（Embedding）：文本 → 高维向量的过程，余弦相似度/欧氏距离/内积三种度量方式
- pgvector：PostgreSQL 的向量扩展，ivfflat 和 hnsw 两种索引的原理与适用场景
- Rerank（重排序）：粗排（向量相似度）→ 精排（交叉编码器打分）的两阶段检索范式

**设计模式**

- 策略模式：`SearchChannel` 接口定义 `isEnabled()` + `search()` 方法，不同通道独立实现
- 责任链模式：`SearchResultPostProcessor` 按 order 排序依次执行（Deduplication → Rerank）
- Spring 自动注册：通过 `@Component` + `List<SearchChannel>` 注入实现通道的自动发现

**并发编程**

- `CompletableFuture.supplyAsync(task, executor)`：指定线程池异步执行
- 多通道并行 + Collection 间并行的两层并行模型
- 超时控制：`future.get(timeout, TimeUnit)` 防止单通道阻塞整体

**去重算法**

- 基于 chunk ID 的精确去重
- 基于文本相似度的模糊去重（可选）

### 3.2 学习路径

1. **向量数据库入门**：安装 pgvector，手动创建 collection、插入向量、执行 KNN 查询
2. **理解 RAG 检索范式**：阅读关于 Dense Retrieval + Rerank 的论文或博客
3. **策略模式复习**：理解开闭原则——新增通道只需实现接口，无需修改引擎代码
4. **源码精读**：`MultiChannelRetrievalEngine` → `KnowledgeBaseSelectionChannel` / `VectorGlobalSearchChannel` → `DeduplicationPostProcessor` / `RerankPostProcessor`
5. **性能分析**：思考通道并行 vs 串行的延迟差异，画出对比图

### 3.3 关键源文件

| 文件 | 作用 |
|------|------|
| `bootstrap/.../rag/core/retrieve/MultiChannelRetrievalEngine.java` | 多通道检索引擎 |
| `bootstrap/.../rag/core/retrieve/channel/*.java` | 各检索通道实现 |
| `bootstrap/.../rag/core/retrieve/postprocess/*.java` | 后置处理器链 |
| `bootstrap/.../rag/core/vector/*.java` | 向量存储管理 |

### 3.4 高频面试题

**Q1：为什么需要多通道检索？一个通道不够吗？**

回答框架：单通道难以兼顾精准度和召回率。比如用户选择了特定知识库时，"知识库定向通道"提供精准结果；但如果用户没有选择或知识库命中不足，"全局兜底通道"保证不会返回空结果。两个通道并行执行，不增加延迟，通过后置去重避免重复。这本质上是精准检索和泛化检索的平衡。

**Q2：Rerank 在你的系统里是怎么工作的？为什么需要两阶段？**

回答框架：第一阶段是粗排——用向量相似度快速从海量文档中召回 Top-K 候选（速度快但精度有限）；第二阶段是精排——用交叉编码器（Rerank 模型）对候选结果重新打分排序（精度高但慢，所以只对少量候选执行）。这种两阶段范式在推荐系统和搜索引擎中也很常见，平衡了性能和效果。

**Q3：如果某个检索通道超时或报错怎么办？**

回答框架：每个通道的执行是独立的 CompletableFuture，单个通道的异常不会阻塞其他通道。后置处理器链也设计了异常跳过机制——如果 Rerank 服务不可用，会跳过精排直接使用粗排结果。整体设计保证了"降级但不中断"。

---

## 亮点四：查询改写与多级兜底

### 4.1 核心知识点

**查询改写（Query Rewriting）**

- 为什么需要改写：用户的原始问题可能含糊、多义、包含缩写术语，直接用于检索效果差
- 术语归一化：将领域缩写/别名映射为标准术语（如"五险一金" → "社会保险和住房公积金"）
- 多问句拆分：将复合问题拆成多个独立子问题，分别检索后合并结果

**Redis 缓存策略**

- 缓存 + DB 回填模式：Redis 缓存术语映射表，Cache Miss 时从 DB 加载并回填
- 按知识库 ID 过滤：不同知识库有不同的术语体系，按 KB ID 交集筛选适用规则
- 优先级排序 + 最长匹配：多条规则冲突时，优先级高的先生效；同一优先级时最长匹配优先

**LLM 结构化输出**

- 让 LLM 返回 JSON 的 Prompt 工程技巧
- LLMResponseCleaner：清洗 markdown 代码块包裹（```json ... ```）
- 解析失败的降级策略

**兜底设计（Fallback Chain）**

- 三级兜底：LLM 改写（最智能）→ 归一化文本（中等）→ 原始文本（保底）
- 每一级失败都不阻断，自动降级到下一级

### 4.2 学习路径

1. **理解 Query Rewriting 的学术背景**：搜索 "Query Rewriting for RAG" 相关论文/博客
2. **Redis 缓存模式复习**：Cache-Aside Pattern、缓存穿透/击穿/雪崩的区别与防护
3. **JSON 解析容错**：了解 Jackson/FastJSON 的容错模式（DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES 等）
4. **源码精读**：`QueryTermMappingService` → `MultiQuestionRewriteService` → `LLMResponseCleaner`
5. **动手实验**：构造几个"脏"问题（含缩写、多问题混合），观察改写效果

### 4.3 关键源文件

| 文件 | 作用 |
|------|------|
| `bootstrap/.../rag/core/rewrite/QueryTermMappingService.java` | 术语归一化 |
| `bootstrap/.../rag/core/rewrite/MultiQuestionRewriteService.java` | LLM 改写 + 拆分 |
| `infra-ai/.../util/LLMResponseCleaner.java` | LLM 输出清洗 |

### 4.4 高频面试题

**Q1：术语归一化是怎么做的？为什么不直接用 LLM？**

回答框架：术语归一化用预定义的映射表（存 Redis + DB），是确定性规则，速度快、结果可控。LLM 改写是概率性的，有延迟和不确定性。两者互补：先做术语归一化（把"OA"替换为"办公自动化系统"），再交给 LLM 做语义层面的改写和拆分。这样既保证了领域术语的准确映射，又能处理更复杂的语义理解。

**Q2：如果 LLM 返回的 JSON 格式不对怎么办？**

回答框架：我设计了三级兜底。第一级是 LLMResponseCleaner 清洗——去掉 markdown 代码块标记、多余空白等。第二级是 JSON 解析失败时使用术语归一化后的文本作为改写结果。第三级是规则拆分——按标点符号（问号、分号）机械拆分。保证不管 LLM 输出什么格式，链路都不会中断。

---

## 亮点五：分布式公平限流器

### 5.1 核心知识点

**分布式限流基础**

- 限流算法：固定窗口、滑动窗口、令牌桶、漏桶的原理与适用场景
- 分布式限流的挑战：多实例部署下如何保证全局一致性
- 公平排队 vs 快速失败：为什么 RAG 场景需要公平排队（请求耗时长、用户需等待）

**Redis 数据结构高级应用**

- Sorted Set（ZSet）：score 用于排序（时间戳），member 存储 ticket ID
- Lua 脚本：保证多步操作的原子性（队头 claim + 僵尸清理）
- RPermitExpirableSemaphore：带 TTL 的信号量，permit 超时自动回收
- RTopic：Pub/Sub 模式实现跨实例通知

**状态机设计**

- Ticket 生命周期：PENDING → GRANTED（获取到 permit）/ TIMED_OUT（等待超时）/ CANCELLED（用户取消）
- CAS（Compare And Swap）：`AtomicReference.compareAndSet(expected, new)` 保证终态互斥
- 为什么需要 CAS：防止超时回调和用户取消同时触发导致状态不一致

**资源泄漏防护**

- Entry TTL：存活标记在 `maxWaitMillis + 5s` 后自动过期
- 僵尸清理：Lua 脚本中检测已过期的 entry 并清理
- JVM 崩溃场景：entry 自动过期后成为僵尸，被后续请求的 Lua 脚本清理

### 5.2 学习路径

1. **限流算法对比**：画表对比四种限流算法的优缺点，理解为什么 RAG 场景需要公平排队
2. **Redis 数据结构深入**：重点学习 ZSet 的 ZADD/ZRANGE/ZREM 操作和 Lua 脚本编写
3. **Redisson 分布式组件**：阅读 RPermitExpirableSemaphore 和 RTopic 的官方文档
4. **CAS 原理**：理解 AtomicReference 的底层实现（Unsafe.compareAndSwap）
5. **源码精读**：`FairDistributedRateLimiter` → `queue_claim_atomic.lua` → `ChatQueueLimiter` → `PollNotifier`
6. **故障推演**：在纸上推演"JVM 崩溃 → entry 过期 → 僵尸清理"的完整过程

### 5.3 关键源文件

| 文件 | 作用 |
|------|------|
| `bootstrap/.../rag/service/ratelimit/FairDistributedRateLimiter.java` | 核心限流器 |
| `bootstrap/.../rag/service/ratelimit/ChatQueueLimiter.java` | 对话限流门面 |
| `bootstrap/.../rag/service/ratelimit/PollNotifier.java` | 合并通知器 |
| `resources/.../lua/queue_claim_atomic.lua` | Lua 原子操作脚本 |

### 5.4 高频面试题

**Q1：为什么不用令牌桶或漏桶，而要自己实现公平排队？**

回答框架：令牌桶和漏桶适合"突发允许"或"匀速处理"场景，但不保证先来先服务。RAG 对话请求耗时较长（几十秒），用户需要明确知道排队位置。我的限流器用 Redis ZSet 按时间戳排序，保证严格 FIFO；PermitExpirableSemaphore 管理并发配额；Lua 脚本保证 claim 操作的原子性。这样在多实例部署下也能做到公平排队。

**Q2：permit 泄漏怎么防？**

回答框架：两层防护。第一层是 PermitExpirableSemaphore 的 lease 机制——每个 permit 有租约时间，超时自动回收。第二层是 Entry TTL——每个排队 entry 有存活标记，过期后成为僵尸，被后续请求的 Lua 脚本检测到并清理。即使 JVM 崩溃导致 permit 未被主动释放，lease 过期后 permit 也会自动回到池中。

**Q3：CAS 状态机怎么保证回调只触发一次？**

回答框架：Ticket 有 AtomicReference 状态字段，所有状态变更都通过 compareAndSet 执行。比如超时回调要把状态从 PENDING 改为 TIMED_OUT，如果此时用户取消操作已经把状态改成了 CANCELLED，CAS 会失败，超时回调就不会执行。这保证了无论多少个事件源同时触发，Ticket 只会进入一个终态。

---

## 亮点六：零侵入全链路追踪

### 6.1 核心知识点

**可观测性（Observability）**

- 三大支柱：Logging（日志）、Metrics（指标）、Tracing（追踪）
- RAG 场景的特殊性：链路长（改写→检索→重排→路由→生成），异步/流式环节多，传统 APM 工具覆盖不全

**AOP（面向切面编程）**

- Spring AOP 实现原理：JDK 动态代理 vs CGLIB 字节码增强
- 自定义注解 + 切面：`@RagTraceNode` 标记方法，切面自动采集耗时和上下文
- AOP 的局限性：默认基于 ThreadLocal，异步/流式场景下线程切换会丢失上下文

**跨线程上下文传播**

- ThreadLocal 的问题：线程池复用线程时 ThreadLocal 不会被自动传递
- TransmittableThreadLocal（TTL）：阿里开源的解决方案，在提交任务时捕获、在子线程执行时回放
- StreamSpan 设计：为流式场景设计的 span 生命周期管理——begin（创建）→ detach（脱离当前线程）→ finish（在新线程完成时关闭）

**装饰器回调模式**

- ForwardingStreamCallback：包装原始 callback，在 onContent/onComplete 等事件前后插入 trace 逻辑
- StreamSpanCallback：管理 span 的 begin/detach/finish 生命周期
- 多层装饰器组合：原始 callback → ForwardingStreamCallback → StreamSpanCallback → ProbeStreamBridge

### 6.2 学习路径

1. **分布式追踪基础**：学习 OpenTelemetry 的 Trace/Span 概念，理解 traceId + spanId 的传播方式
2. **Spring AOP 深入**：理解切面的执行顺序、环绕通知（@Around）的使用方式
3. **TTL 原理**：阅读 TransmittableThreadLocal 的源码或官方文档，理解 capture/replay 机制
4. **装饰器模式练习**：手绘多层装饰器的包装关系图，理解事件流转
5. **源码精读**：`RagTraceAspect` → `RagTraceContext` → `StreamSpanCallback` → `ForwardingStreamCallback` → `StreamChatTraceRunner`
6. **Trace 数据分析**：在前端 Trace 详情页观察一个完整请求的节点树，理解各阶段耗时

### 6.3 关键源文件

| 文件 | 作用 |
|------|------|
| `framework/.../trace/RagTraceContext.java` | Trace 上下文（ThreadLocal） |
| `framework/.../trace/RagTraceRoot.java` | 根注解 |
| `framework/.../trace/RagTraceNode.java` | 节点注解 |
| `framework/.../trace/RagStreamTraceSupport.java` | 流式追踪支持 |
| `bootstrap/.../rag/aop/RagTraceAspect.java` | AOP 切面 |
| `bootstrap/.../rag/trace/StreamChatTraceRunner.java` | 流式对话追踪 Runner |

### 6.4 高频面试题

**Q1：为什么叫"零侵入"？你是怎么做到的？**

回答框架：业务代码不需要写任何 trace 相关逻辑。通过自定义注解（`@RagTraceNode`）标记需要追踪的方法，AOP 切面自动在方法执行前后采集耗时、输入输出并记录到 trace 上下文。对于流式场景，通过装饰器模式包装 StreamCallback，在 onContent/onComplete 等事件回调中自动插入 trace 逻辑。开发者只需加一个注解或使用装饰器包装，业务逻辑完全不受影响。

**Q2：异步场景下 AOP 的 ThreadLocal 会失效，你怎么解决的？**

回答框架：Spring AOP 基于 ThreadLocal 存储 trace 上下文，当方法内有异步操作（如 CompletableFuture.supplyAsync）时，子线程拿不到父线程的 ThreadLocal。我用了两种方案：1）对于普通异步任务，使用 TransmittableThreadLocal 替代 ThreadLocal，在任务提交时自动捕获上下文、在子线程执行时自动回放；2）对于流式场景，设计了 StreamSpanCallback，在创建 span 时绑定到当前线程，在流式事件到达新线程时执行 detach + reattach，保证 span 生命周期的正确性。

**Q3：你的 Trace 系统能追踪哪些信息？怎么用？**

回答框架：追踪粒度是 Run（一次完整对话请求）→ Node（各阶段节点）。每个 Node 记录 nodeType（改写/检索/重排/路由/生成）、耗时、输入输出、状态（成功/失败）。还记录了 TTFT（首 token 到达时间）这个关键指标。数据持久化到数据库，前端管理后台有 Trace 列表和详情页，可以按 traceId/conversationId 检索，查看完整的节点树和各阶段耗时，用于性能诊断和问题排查。

---

## 通用复习清单

### Java 并发编程（必复习）

- `volatile`：可见性、禁止重排、不保证原子性
- `synchronized`：锁升级（偏向锁→轻量级锁→重量级锁）
- `ConcurrentHashMap`：JDK 8+ 的分段锁→CAS+synchronized 实现
- `CompletableFuture`：supplyAsync/thenApply/allOf/exceptionally
- `AtomicReference`：CAS 操作的底层原理（Unsafe）
- 线程池核心参数：corePoolSize/maxPoolSize/queue/rejectionPolicy

### Redis（必复习）

- 五大数据类型及其底层数据结构
- ZSet 的 skip list + hash 实现
- Lua 脚本的原子性保证
- 分布式锁（Redisson 实现 vs Redisson RedLock）
- Pub/Sub 模式的优缺点
- 缓存一致性方案（Cache-Aside、Write-Through、Write-Behind）

### 设计模式（必复习）

- 策略模式：定义 + 项目中的应用（检索通道、文档获取、ChatClient）
- 责任链模式：定义 + 项目中的应用（后置处理器链）
- 装饰器模式：定义 + 项目中的应用（StreamCallback 多层包装）
- 模板方法模式：定义 + 项目中的应用（AbstractOpenAIStyleChatClient）
- 断路器模式：定义 + 项目中的应用（ModelHealthStore）
- 工厂模式：定义 + 项目中的应用（ChunkingStrategyFactory）

### Spring Boot / Spring（必复习）

- AOP 实现原理（JDK Proxy vs CGLIB）
- Bean 生命周期
- `@Configuration` + `@Bean` 的配置方式
- Spring Boot AutoConfiguration 原理（spring.factories / @AutoConfiguration）
- SSE 流式响应（SseEmitter）

### 消息队列 RocketMQ（了解级）

- 消息模型：Producer → Broker → Consumer
- 事务消息机制
- 延时消息
- 与 RabbitMQ 的区别

### AI/LLM 相关（岗位重点）

- RAG 完整流程：文档分块 → Embedding → 向量存储 → 检索 → Rerank → Prompt 构建 → LLM 生成
- Embedding 模型选型：维度、度量方式、批处理
- Prompt Engineering：System Prompt 设计、Few-shot、CoT
- 向量数据库：pgvector / Milvus / Pinecone 的对比
- MCP 协议：架构设计、与 Function Calling 的区别
- TTFT / TPS 等 LLM 性能指标

---

## 面试准备建议

1. **STAR 法则讲项目**：每个亮点按 Situation（场景）→ Task（任务）→ Action（行动）→ Result（结果）组织
2. **画图是关键**：面试前准备好每个亮点的架构图、时序图、状态转移图，白板讲解时画图比口述清晰 10 倍
3. **准备数据**：如果可能，准备一些量化数据（如"模型切换在 2 秒内完成"、"检索延迟 P95 < 500ms"等）
4. **追问准备**：每个亮点至少准备 2 层追问的答案——"为什么这样做"和"还有什么替代方案"
5. **源码自信**：确保能流畅地说出关键类名、方法名和设计意图，这比泛泛而谈有说服力得多
