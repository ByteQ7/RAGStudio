## RAGStudio 重构后代码审查报告

审查范围：全项目 Java 源码（infra-ai、bootstrap、framework、mcp-server 四个模块），重点覆盖 Spring AI 集成、AI 模型动态配置、数据摄入流水线、知识库管理、MCP 连接管理等核心功能。

---

### 一、已修复的 BUG（共 13 处）

#### 1. SpEL 注入远程代码执行漏洞 (Critical Security)

**文件**：`ingestion/engine/ConditionEvaluator.java`

`evalSpel()` 方法使用 `StandardEvaluationContext` 解析用户可定义的条件表达式，允许执行任意 Java 代码（如 `T(java.lang.Runtime).getRuntime().exec(...)`）。已替换为 `SimpleEvaluationContext.forReadOnlyDataBinding()`，仅允许只读属性绑定，彻底阻断 RCE 攻击路径。

#### 2. Ollama ChatModel 缺少默认模型名 (BUG)

**文件**：`infra-ai/.../SpringAiChatModelFactory.java`

创建 Ollama ChatModel 时未设置 `defaultOptions.model`，与 EmbeddingModel 的处理不一致。当 `Prompt` 未携带 `ChatOptions` 时，Ollama API 会因"model not specified"而报错。已补充 `.defaultOptions(OllamaChatOptions.builder().model(...).build())`。

#### 3. 流式取消不通知 Callback (BUG)

**文件**：`infra-ai/.../FluxToStreamCallbackBridge.java`

`cancel()` 仅设置 `terminated` 并 dispose Reactor 订阅，但从未调用 `callback.onComplete()` 或 `callback.onError()`。导致 `StreamSpanCallback` 等实现的 `onFinish()` 钩子永远不被触发，造成 trace span 泄漏和下游 SSE 监听器永久挂起。已在 cancel 时调用 `callback.onComplete()`。

#### 4. MCP 工具注册表线程不安全 (BUG)

**文件**：`rag/core/mcp/DefaultMcpToolRegistry.java`

`executorMap` 使用普通 `HashMap`，但 `register()`/`unregister()` 可在运行时被调用，同时 `listAllTools()` 等方法在聊天流水线线程中并发读取。HashMap 在并发读写下可能损坏或死循环。已替换为 `ConcurrentHashMap`。同时修复了 `init()` 中 `autoDiscoveredExecutors` 为空时缺少 `return` 导致的 NPE。

#### 5. PostgreSQL 连接池上设置 Session 级变量 (BUG)

**文件**：`rag/core/retrieve/PgRetrieverService.java`

`SET hnsw.ef_search = 200` 是 session 级设置，在 HikariCP 连接池复用时会影响后续所有查询。已改为 `SET LOCAL`，限定作用域为当前事务。

#### 6. MCP 连接管理器关闭时跳过元素 (BUG)

**文件**：`mcp/service/DynamicMcpConnectionManager.java`

`destroy()` 方法在遍历 `activeClients.keySet()` 的同时调用 `disconnectInternal()` 修改 Map，ConcurrentHashMap 的弱一致性迭代器会跳过部分元素，导致部分 MCP 连接在应用关闭时未被正确关闭。已改为先拷贝 key 集合再遍历。同时将 `healthCheck()` 加上 `synchronized`，修复与 `connect()`/`disconnect()` 之间的竞态条件。将 `ObjectMapper` 改为构造器注入以复用 Spring 全局配置。

#### 7. 数据摄入服务 @Transactional 包裹 LLM 调用 (BUG)

**文件**：`ingestion/service/impl/IngestionTaskServiceImpl.java`

`execute()` 和 `upload()` 方法整体被 `@Transactional` 包裹，但内部执行了 HTTP 抓取、LLM API 调用、向量写入等可能耗时数秒甚至分钟的操作。在并发负载下会耗尽 HikariCP 连接池，导致整个应用宕机。已移除方法级 `@Transactional`。同时修复了 `fileType.endsWith(".md")` 的逻辑错误（fileType 是 MIME 类型字符串，不可能以 ".md" 结尾）。

#### 8. StreamChatEventHandler 使用 StringBuffer (MEDIUM)

**文件**：`rag/service/handler/StreamChatEventHandler.java`

`answer` 和 `thinking` 声明为 `StringBuffer`，每个 `append()` 都带同步开销。但 handler 实例是每请求创建的，回调由单线程顺序调用，同步完全多余。已替换为 `StringBuilder`。

#### 9. TikaDocumentParser PDFParserConfig 是死代码 (BUG)

**文件**：`core/parser/TikaDocumentParser.java`

静态块中创建了 `PDFParserConfig` 并配置禁用图片提取，但从未将其应用到 `Tika` 实例上。已改用 `AutoDetectParser` + `ParseContext` 使配置真正生效。

#### 10. ModelHealthStore 状态转换遗漏 (MEDIUM)

**文件**：`infra-ai/.../ModelHealthStore.java`

CLOSED -> OPEN 转换时未清除 `halfOpenInFlight` 标志。虽然在当前流程中不太可能触发，但属于潜在的状态机缺陷。已在所有到 OPEN 状态的转换中显式清除。

#### 11. Collectors.toMap 重复 key 导致启动崩溃 (HIGH)

**文件**：`RoutingLLMService.java`、`RoutingEmbeddingService.java`、`RoutingRerankService.java`

构造器中使用 `Collectors.toMap(Client::provider, identity())`，如果两个 client bean 声明相同的 provider ID，`toMap` 会抛出 `IllegalStateException: Duplicate key` 导致 Spring 应用启动失败。已添加合并函数并在冲突时打印警告日志。

#### 12. 多通道检索 CompletableFuture.join() 无超时 (MEDIUM)

**文件**：`rag/core/retrieve/MultiChannelRetrievalEngine.java`

`CompletableFuture.join()` 无限阻塞，如果某个检索通道挂起会导致整个检索管线永久阻塞。已改为 `future.get(30, TimeUnit.SECONDS)` 并处理超时异常。

#### 13. MCP healthCheck 与 connect/disconnect 竞态 (HIGH)

**文件**：`mcp/service/DynamicMcpConnectionManager.java`

`connect()` 和 `disconnect()` 是 synchronized 方法，但 `healthCheck()` 不是。可能导致在 healthCheck 获取 client 引用后被 disconnect 关闭，对已关闭的 client 调用 `listTools()` 行为不可预期。已给 `healthCheck()` 添加 `synchronized`。

---

### 二、未修复的 BUG 和不足（需要人工评估）

#### 安全问题

| 编号 | 严重性 | 文件 | 描述 |
|------|--------|------|------|
| S-1 | HIGH | `LocalFileFetcher.java` | 无路径遍历防护，可读取服务器任意文件（如 `/etc/passwd`）。需要配置允许目录白名单，使用 `path.toRealPath()` 规范化后校验。 |
| S-2 | HIGH | `HttpUrlFetcher.java`、`FeishuFetcher.java` | SSRF 漏洞：未校验 URL 是否指向内部服务（`169.254.169.254`、`localhost`、`10.x` 等）。需要实现 DNS 解析后拒绝私有 IP 段。 |
| S-3 | HIGH | `S3Fetcher.java`、`LocalFileFetcher.java` | 无文件大小限制，`readAllBytes()` 可导致 OOM。需要添加可配置的最大文件大小并使用有界读取。 |

#### 数据一致性

| 编号 | 严重性 | 文件 | 描述 |
|------|--------|------|------|
| D-1 | BUG | `KnowledgeDocumentServiceImpl.java:247-259` | `persistChunksAndVectorsAtomically()` 在 DB 事务中包裹了向量存储操作，但向量存储不可回滚。如果向量索引失败后 DB 回滚，旧向量已被永久删除。建议将非事务性操作移到 `@TransactionalEventListener(phase = AFTER_COMMIT)` 中。 |
| D-2 | BUG | `KnowledgeDocumentServiceImpl.java:517-531` | `update()` 方法的调度验证在 DB 更新之前重新查询了数据库，读到的是旧值。应基于请求参数和当前记录在内存中构建"will-be"状态。 |
| D-3 | HIGH | `AiModelConfigServiceImpl.java:52,158` | `createProvider()` 和 `createModel()` 执行"先查后插"模式但缺少 `@Transactional` 和数据库唯一约束，并发请求可创建重复记录。 |
| D-4 | HIGH | `AiModelConfigServiceImpl.java:84-112` | `updateProvider()` 允许禁用 Provider 但不检查其下是否有启用的 Model，导致被禁用 Provider 下的 Model 加载后 providerName 为空，API 调用失败。 |
| D-5 | MEDIUM | `AiModelConfigServiceImpl.java:115-136` | `deleteProvider()` 未调用 `chatModelFactory.evict()` 清除缓存的 Spring AI 模型实例，可能残留已删除 Provider 的 API Key。 |

#### 性能问题

| 编号 | 严重性 | 文件 | 描述 |
|------|--------|------|------|
| P-1 | HIGH | `AiModelConfigServiceImpl.java:272-396` | `getModel()` 调用 `loadProviderNameMap()` 执行 `providerMapper.selectList(null)` 全表扫描。每次查询单个模型都会加载所有 Provider。 |
| P-2 | MEDIUM | `IngestionPipelineServiceImpl.java:100-104` | Pipeline 分页查询对每条记录单独调用 `fetchNodes()`，页大小 20 时产生 21 次 DB 查询。应改为批量查询。 |
| P-3 | MEDIUM | `KnowledgeChunkServiceImpl.java:200-202` | `batchCreate()` 逐条插入 Chunk 而非批量插入，对于产生数百个 Chunk 的文档性能很差。应使用 MyBatis-Plus 的 `saveBatch()`。 |
| P-4 | MEDIUM | `SampleQuestionServiceImpl.java:100` | `ORDER BY RANDOM()` 在 PostgreSQL 上需要全表扫描。对于大表应改用 `TABLESAMPLE` 或缓存。 |

#### 输入验证缺失

| 编号 | 严重性 | 文件 | 描述 |
|------|--------|------|------|
| V-1 | HIGH | `ChatRequest.java` | `question` 字段无 `@NotBlank`，null 值会传播到 LLM 调用链，浪费 API 额度并可能触发 NPE。 |
| V-2 | HIGH | `aimodel/controller/request/*.java` | 所有请求 DTO 缺少 JSR-303 注解（`@NotBlank`、`@Size`、`@Pattern` 等），控制器方法缺少 `@Valid`。`capability` 字段不校验有效值，`priority` 可为负数，`dimension` 无范围限制。 |
| V-3 | MEDIUM | `ingestion/controller/*.java` | 同上，Ingestion 模块的控制器 `@RequestBody` 参数未添加 `@Valid`。 |
| V-4 | LOW | `ingestion/controller/*.java` | 分页参数 `pageNo`/`pageSize` 无边界校验，可传负数或超大值。 |

#### 资源泄漏与内存问题

| 编号 | 严重性 | 文件 | 描述 |
|------|--------|------|------|
| R-1 | HIGH | `SpringAiChatModelFactory.java:82-87` | `evict()` 移除 ChatModel/EmbeddingModel 但从不关闭底层 HTTP 连接池（OkHttpClient / Reactor Netty）。频繁配置变更会累积未关闭的连接池，最终耗尽文件描述符。 |
| R-2 | MEDIUM | `PromptTemplateLoader.java:26-27` | 模板缓存 `ConcurrentHashMap` 只增不减，长期运行下模板文件更新后缓存不刷新。 |
| R-3 | MEDIUM | `NodeOutputExtractor.java:49-54` | 将整个 `rawBytes` Base64 编码后存入数据库，50MB 文件产生约 67MB 的 Base64 字符串。建议仅保留 `rawBytesLength`。 |
| R-4 | MEDIUM | `RocketMQProducerAdapter.java:99-115` | 事务消息发送失败时注册的 `localTransaction` 回调不会被清理，长期积累导致内存泄漏。 |

#### 其他不足

| 编号 | 严重性 | 文件 | 描述 |
|------|--------|------|------|
| O-1 | HIGH | `KnowledgeBaseServiceImpl.java:49` | `create()` 的 `@Transactional` 缺少 `rollbackFor = Exception.class`，checked exception 不会回滚。创建 S3 bucket 和 vector collection 无补偿清理逻辑。 |
| O-2 | HIGH | `KnowledgeBaseServiceImpl.java:160-179` | `delete()` 仅软删除 DB 记录，不清理 S3 bucket 和向量集合，长期运行导致资源泄漏。 |
| O-3 | MEDIUM | `JdbcConversationMemoryStore.java:86-94` | 加载对话历史时丢弃了 `thinkingContent`，多轮对话中 LLM 看不到之前的推理内容，降低深度思考场景质量。 |
| O-4 | MEDIUM | `DeduplicationPostProcessor.java:76-81` | 去重使用 `String.hashCode()`（32 位），不同文本的 hash 碰撞会导致误删。建议改用 SHA-256 或 MurmurHash。 |
| O-5 | MEDIUM | `SpringAiChatModelFactory.java:91-116` | 手工创建的 ChatModel 缺少 Spring AI 自动配置的 `RetryTemplate` 和 `ObservationRegistry`，导致无内置重试和可观测性。 |
| O-6 | MEDIUM | `HeuristicTokenCounterService.java:42-44` | 空白字符完全跳过不计，导致 token 估算偏低 15-25%。应按每 4-5 个空白字符计 1 token。 |
| O-7 | MEDIUM | `StructureAwareTextChunker.java:289-291` | overlap 文本被追加到下一个 chunk 但不计入 maxChars 预算，导致分块可能超出 embedding 模型的 token 限制。 |
| O-8 | MEDIUM | `KnowledgeChunkServiceImpl.java:373-374` | `batchToggleEnabled()` 在所有 chunk 已处于目标状态时抛异常而非静默返回，与单条 `enableChunk()` 行为不一致。 |
| O-9 | MEDIUM | `McpServerServiceImpl.java:238-252` | `toVO()` 直接暴露 `headers` 字段（可能包含 API Key / Token）给前端。应脱敏或仅显示 header 名称。 |
| O-10 | LOW | `MessageFeedbackServiceImpl.java:35` | `@Value("message-feedback_topic${unique-name:}")` 语法不正确，`message-feedback_topic` 部分缺少 `${...}` 包裹，导致无法通过配置文件自定义 topic。 |
| O-11 | LOW | `ChatQueueLimiter.java:130` | `String.valueOf(rejectedContext.messageId())` 在 messageId 为 null 时发送字符串 `"null"` 给前端。 |
| O-12 | LOW | `TextCleanupUtil.java:34` | 正则 `[ \\t]+\\n` 不匹配 Windows 换行符 `\\r\\n`，导致 Windows 文档的尾部空格未被清理。 |
| O-13 | LOW | `RoutingLLMService.java:50` | 首包超时 60 秒对思考模型（DeepSeek-R1 等）可能不够，复杂推理可能超过 60 秒。建议根据 `thinking` 标志动态调整超时。 |

---

### 三、建议优先处理顺序

1. **安全问题**（S-1 ~ S-3）：路径遍历、SSRF、无限制文件读取直接影响生产环境安全
2. **数据一致性**（D-1 ~ D-5）：事务与非事务操作混合、缺少唯一约束等会导致数据损坏
3. **资源泄漏**（R-1）：Spring AI 模型缓存驱逐时不关闭连接池，长期运行必崩
4. **输入验证**（V-1 ~ V-4）：缺少 JSR-303 验证是当前所有 API 的共性短板
5. **性能优化**（P-1 ~ P-4）：N+1 查询和全表扫描在数据量增长后会成为瓶颈
6. **其他不足**（O-1 ~ O-13）：按各条自身严重性安排修复计划
