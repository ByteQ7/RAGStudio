## 企业级 Agentic RAG 智能问答平台

**技术栈**：Java 17 / Spring Boot 3.5 / PostgreSQL + pgvector / Redis & Redisson / RocketMQ / MCP SDK / Apache Tika / AWS S3 / React

---

### 项目亮点

**1. 多通道并行检索引擎**
设计可插拔的多通道检索架构（SearchChannel + PostProcessor 链），支持向量全局检索等多策略并行执行，检索结果经去重 → Rerank 模型精排的后置处理器链输出最终 TopK，兼顾召回率与准确率。通道与处理器均通过接口 + Spring Bean 自动注册实现热插拔扩展。

**2. 高可用模型路由与熔断降级**
自研模型调度引擎，支持多 Provider（百炼/SiliconFlow/Ollama）混合部署。实现三态断路器（CLOSED → OPEN → HALF_OPEN），基于连续失败阈值自动熔断，冷却期后半开探测恢复。流式场景首创"首包探测（TTFT Probe）"机制：阻塞等待首个 token 到达，超时或无内容时自动取消连接并 Fallback 到下一模型，避免"连接成功但不输出"的假活问题。

**3. 流式 MCP Agent 决策与工具调用**
将 MCP 工具调用融入 RAG 流式对话：LLM 在流式输出过程中自主判断是否需要调用业务工具（如天气/工单/销售查询），需要时通过 LLM 自动提取工具参数并调用远端 MCP Server，工具结果注入上下文后二次流式生成最终回答，实现知识检索与实时数据工具的无缝衔接。

**4. 可编排文档摄入流水线（Ingestion Pipeline）**
设计 DAG 链式执行引擎（Fetcher → Parser → Chunker → Enricher → Enhancer → Indexer），支持条件跳过、环检测、防死循环保护、节点级日志。文档获取支持本地文件、S3、HTTP URL、飞书等多来源，解析层集成 Apache Tika 实现多格式兼容。

**5. 分布式公平限流器**
基于 Redisson 实现公平排队限流（Fair Queue + Expirable Semaphore），通过 Lua 脚本保证"队头 claim + 僵尸清理"的原子性，Ticket 状态机（PENDING → GRANTED/TIMED_OUT/CANCELLED）以 CAS 保证终态互斥，跨实例通过 RTopic Pub/Sub 即时唤醒等待者，permit 过期自动回收避免泄漏。

**6. 全链路可观测 Trace 系统**
基于自定义注解 @RagTraceNode + AOP 切面，自动采集 RAG 全链路各阶段（改写/检索/重排/LLM 路由/生成）的耗时与输入输出，持久化到数据库供前端回放调试，支持流式场景下的 Trace 上下文传播。

---

### 技术难点

| 难点 | 解决方案 |
|---|---|
| 流式 LLM 多模型 Failover | 首包探测桥接器 ProbeStreamBridge 缓冲 token，探测成功后一次性放行，失败时取消当前连接并切换下一模型 |
| 断路器并发安全 | ConcurrentHashMap.compute() 原子操作 + volatile 字段保证多线程状态一致性 |
| 分布式限流公平性 | Redis Sorted Set 按序排队 + Lua 原子 claim + Entry TTL 标记防僵尸 + RTopic 跨实例通知 |
| 多通道检索结果融合 | 通道并行 CompletableFuture + 后置处理器责任链，处理器按 order 排序、异常跳过不阻断 |
| MCP 流式决策与参数提取 | LLM 流式输出中自主判断是否调用工具，结束后解析 MCP_CALL 前缀触发工具调用，LLM 理解 JSON Schema 自动提取参数 |
| 对话记忆 Token 优化 | 并行加载摘要 + 近期历史，LLM 自动压缩长对话为摘要，装饰后注入 System Prompt |
| 流水线环检测 | DFS 路径追踪检测循环依赖 + 执行计数上限兜底防死循环 |

---

### 简历精炼版（可直接使用）

> **企业级 Agentic RAG 智能问答平台** &emsp; Java / Spring Boot / pgvector / Redis / RocketMQ
>
> - 设计多通道并行检索引擎，支持多策略并行检索，配合去重 + Rerank 后置处理器链输出精排结果，通道与处理器通过接口热插拔扩展
> - 自研模型路由调度引擎，支持多 Provider 混合部署，实现三态断路器熔断降级，流式场景首创首包探测（TTFT Probe）机制，超时自动 Fallback 保障高可用
> - 实现流式 MCP Agent 决策，LLM 在流式输出中自主判断是否调用业务工具，自动提取参数调用远端 MCP Server 后二次流式生成最终回答，知识检索与实时工具无缝衔接
> - 设计可编排文档摄入流水线（DAG 链式引擎），支持多来源获取、Tika 多格式解析、LLM 增强/富化、向量索引，内置环检测与条件执行
> - 基于 Redis Lua + Sorted Set 实现分布式公平排队限流器，CAS 状态机保证终态互斥，RTopic 跨实例即时唤醒
> - 基于 AOP + 自定义注解构建全链路 Trace 系统，采集 RAG 各阶段耗时与上下文，支持流式场景 Trace 传播与前端回放
