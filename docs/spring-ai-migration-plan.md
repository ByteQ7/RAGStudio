## RAGStudio 迁移 Spring AI 方案分析

### 一、现状总结

你的 `infra-ai` 模块是一个完全自研的 AI 基础设施层，基于 OkHttp + Gson 手工实现了完整的 OpenAI 兼容协议调用，架构清晰分层如下：

```
业务层 (bootstrap)
    RAGChatService → StreamChatPipeline → LLMService / EmbeddingService / RerankService

路由层 (infra-ai)
    RoutingLLMService / RoutingEmbeddingService / RoutingRerankService
    + ModelSelector（候选模型筛选）
    + ModelHealthStore（熔断器：CLOSED / OPEN / HALF_OPEN）
    + ModelRoutingExecutor（按优先级逐模型 fallback）
    + ProbeStreamBridge + LlmFirstPacketProbe（流式首包探测 + 模型切换）

客户端层 (infra-ai)
    ChatClient 实现：BaiLianChatClient / SiliconFlowChatClient / OllamaChatClient / DeepSeekChatClient
    EmbeddingClient 实现：SiliconFlowEmbeddingClient / OllamaEmbeddingClient / AIHubMixEmbeddingClient
    RerankClient 实现：BaiLianRerankClient / NoopRerankClient

底层 HTTP (infra-ai)
    AbstractOpenAIStyleChatClient（OkHttp 模板方法 + SSE 解析 + StreamSpanCallback 链路追踪）
    AbstractOpenAIStyleEmbeddingClient（OkHttp 模板方法）
    OpenAIStyleSseParser（逐行 SSE 解析）
    StreamAsyncExecutor（异步线程池 + 取消句柄）
```

**已配置的能力：** 5 个提供商（ollama / bailian / aihubmix / siliconflow / deepseek），6 个 chat 候选模型，3 个 embedding 候选模型，1 个 rerank 模型 + 1 个 noop fallback，以及 MCP 客户端/服务端集成。

---

### 二、Spring AI 能给你什么

Spring AI（当前最新 1.1.4，兼容 Spring Boot 3.5.x）提供了一套标准化的 AI 抽象：

**核心抽象：** `ChatModel` / `EmbeddingModel` / `RerankModel` 接口，与你的 `ChatClient` / `EmbeddingClient` / `RerankClient` 定位类似，但属于 Spring 生态标准。

**内置提供商 Starter：** `spring-ai-starter-model-openai`（覆盖 DeepSeek / SiliconFlow / AIHubMix 等所有 OpenAI 兼容 API）、`spring-ai-starter-model-ollama`、以及 `spring-ai-alibaba-starter`（阿里云百炼/DashScope 原生支持，版本 1.1.2.0 对应 Spring AI 1.1.2 + Spring Boot 3.5.x）。

**流式支持：** 基于 Project Reactor 的 `Flux<ChatResponse>`，取代你当前 OkHttp SSE 手工解析 + StreamCallback 回调模式。

**高级特性：** Prompt Template（`PromptTemplate` / `SystemPromptTemplate`）、Structured Output（`BeanOutputConverter`）、Advisor 链（类似 AOP 拦截模型调用）、内置 Conversation Memory、RAG 检索链（`DocumentRetriever` + `QuestionAnswerAdvisor`）。

**MCP 集成：** `spring-ai-starter-mcp-client` 和 `spring-ai-starter-mcp-server-webmvc`，可以替代你当前直接使用的 `io.modelcontextprotocol.sdk:mcp`。

**重试机制：** 内置 `RetryTemplate`，可替代部分手工错误处理逻辑。

---

### 三、迁移的核心矛盾

你现有架构中有几个 Spring AI **不直接提供**的能力，这些是迁移的核心矛盾：

1. **多模型路由 + 熔断器**：`ModelSelector` 按优先级筛选候选模型 → `ModelRoutingExecutor` 逐模型 fallback → `ModelHealthStore` 做 CLOSED/OPEN/HALF_OPEN 熔断。Spring AI 本身不提供这种多模型 failover 机制，它的设计是"一个 ChatModel Bean 对应一个提供商"。

2. **流式首包探测 + 自动切换**：`ProbeStreamBridge` + `LlmFirstPacketProbe` 实现了"等待首包最多 60 秒，超时则取消当前连接并切换下一个模型"的策略。Spring AI 的 `Flux<ChatResponse>` 没有内置这种逻辑。

3. **自定义链路追踪**：`@RagTraceNode` AOP + `RagStreamTraceSupport`（StreamSpan / StreamSpanCallback）深度集成到了流式回调的生命周期中。Spring AI 的 Advisor 机制可以做调用拦截，但无法直接对接你的 StreamSpan 体系。

4. **StreamCancellationHandle**：你的取消机制是从 OkHttp Call 级别实现的真取消。Spring AI 的 Flux 取消是通过 Reactor 的 `Disposable.dispose()` 实现的，语义相同但接口不同。

---

### 四、三个可选方案

#### 方案 A：适配器模式（推荐，低风险渐进式）

**核心思路：** 保留现有 `LLMService` / `EmbeddingService` / `RerankService` 接口和整个路由层不变，只替换最底层的 HTTP 实现 —— 用 Spring AI 的 `ChatModel` 替代 OkHttp 手工调用。

**具体做法：**

```
保留不变：
  - LLMService / ChatClient 接口体系
  - RoutingLLMService（路由 + fallback + 首包探测）
  - ModelSelector / ModelHealthStore / ModelRoutingExecutor
  - ProbeStreamBridge / LlmFirstPacketProbe
  - 所有 trace 体系
  - 所有业务层代码

替换的部分：
  - AbstractOpenAIStyleChatClient 的内部实现
    → 注入 Spring AI 的 ChatModel，调用 model.call() / model.stream()
    → 将 Flux<ChatResponse> 桥接回现有的 StreamCallback
  - OpenAIStyleSseParser / StreamAsyncExecutor → 可删除
  - OkHttpClient 的 syncHttpClient / streamingHttpClient → 可删除
  - AbstractOpenAIStyleEmbeddingClient 同理替换
```

**新增依赖（父 pom.xml）：**
```xml
<properties>
    <spring-ai.version>1.1.4</spring-ai.version>
    <spring-ai-alibaba.version>1.1.2.2</spring-ai-alibaba.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

**新增依赖（infra-ai/pom.xml）：**
```xml
<dependencies>
    <!-- OpenAI 兼容（覆盖 DeepSeek / SiliconFlow / AIHubMix） -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-openai</artifactId>
    </dependency>
    <!-- Ollama -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-ollama</artifactId>
    </dependency>
    <!-- 阿里云百炼 / DashScope -->
    <dependency>
        <groupId>com.alibaba.cloud.ai</groupId>
        <artifactId>spring-ai-alibaba-starter</artifactId>
        <version>${spring-ai-alibaba.version}</version>
    </dependency>
</dependencies>
```

**需要新增的 Spring 配置（application.yaml）：**
```yaml
spring:
  ai:
    openai:
      api-key: "placeholder"  # 由代码动态注入，不用配置文件
      base-url: "placeholder"
    ollama:
      base-url: ${OLLAMA_URL:http://localhost:11434}
```

**适配器代码示意（BaiLianChatClient 改造后）：**
```java
@Service
public class BaiLianChatClient implements ChatClient {

    @Autowired
    private DashScopeChatModel dashScopeChatModel; // Spring AI Alibaba 提供

    @Override
    public String provider() { return "bailian"; }

    @Override
    @RagTraceNode(name = "bailian-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        Prompt prompt = toPrompt(request, target);
        ChatResponse response = dashScopeChatModel.call(prompt);
        return response.getResult().getOutput().getText();
    }

    @Override
    @RagTraceNode(name = "bailian-stream-chat", type = "LLM_PROVIDER")
    public StreamCancellationHandle streamChat(ChatRequest request,
                                                StreamCallback callback,
                                                ModelTarget target) {
        Prompt prompt = toPrompt(request, target);
        Disposable disposable = dashScopeChatModel.stream(prompt)
            .subscribe(
                chunk -> {
                    String text = chunk.getResult().getOutput().getText();
                    if (text != null) callback.onContent(text);
                },
                callback::onError,
                callback::onComplete
            );
        return () -> disposable.dispose();
    }
}
```

**优点：** 改动范围最小（只动 infra-ai 的客户端实现层），路由/熔断/trace/业务层零改动，可以随时回退，且能渐进式迁移（比如先迁 Ollama，再迁百炼）。

**缺点：** 仍然是两层抽象（你的 ChatClient + Spring AI 的 ChatModel），增加了一层间接性。配置体系需要并存（你的 `ai.providers` 和 Spring AI 的 `spring.ai.*`）。

**预估工作量：** 2-3 天。

---

#### 方案 B：混合重构（中等风险，收益与成本平衡）

**核心思路：** 用 Spring AI 的 `ChatModel` / `EmbeddingModel` 替代你的 `ChatClient` / `EmbeddingClient`，同时保留路由层但改为操作 Spring AI 的接口。

**具体做法：**

```
替换：
  - ChatClient → org.springframework.ai.chat.model.ChatModel
  - EmbeddingClient → org.springframework.ai.embedding.EmbeddingModel
  - 所有具体 Client 实现 → Spring AI Starter 自动注册的 Bean
  - AbstractOpenAIStyleChatClient → 删除
  - 整个 HTTP 层（OkHttp / SSE Parser / StreamAsyncExecutor）→ 删除

保留但适配：
  - RoutingLLMService → 改为持有 Map<String, ChatModel>，按 provider 查找
  - ModelSelector → 输出改为包含 ChatModel 引用
  - ProbeStreamBridge → 改为桥接 Flux<ChatResponse> 到 StreamCallback
  - 链路追踪 → 在 Flux 链上用 doOnSubscribe/doOnTerminate 对接 StreamSpan

新增：
  - SpringAiModelFactory：根据数据库配置动态创建 ChatModel 实例
    （因为你需要运行时多模型，而 Spring AI 默认是一提供商一 Bean）
```

**关键变化 —— 动态 ChatModel 工厂：**

由于你的多模型架构不同于 Spring AI 的"一个提供商一个 Bean"默认模式，你需要一个工厂来根据配置动态创建 ChatModel：

```java
@Component
public class SpringAiModelFactory {

    public ChatModel createChatModel(ProviderConfig provider, ModelCandidate candidate) {
        return switch (provider.getId()) {
            case "bailian" -> DashScopeChatModel.builder()
                .apiKey(provider.getApiKey())
                .modelName(candidate.getModel())
                .build();
            case "ollama" -> OllamaChatModel.builder()
                .baseUrl(provider.getUrl())
                .modelName(candidate.getModel())
                .build();
            default -> OpenAiChatModel.builder()
                .apiKey(provider.getApiKey())
                .baseUrl(provider.getUrl() + provider.getEndpoint("chat"))
                .modelName(candidate.getModel())
                .build();
        };
    }
}
```

**Flux 桥接 StreamCallback 示意：**

```java
public StreamCancellationHandle streamChat(ChatModel model, Prompt prompt,
                                            StreamCallback callback, StreamSpan span) {
    span.begin();
    Disposable disposable = model.stream(prompt)
        .doOnSubscribe(s -> span.recordFirstPacket())
        .subscribe(
            chunk -> {
                String text = chunk.getResult().getOutput().getText();
                if (text != null) callback.onContent(text);
            },
            error -> {
                span.finish(error);
                callback.onError(error);
            },
            () -> {
                span.finish(null);
                callback.onComplete();
            }
        );
    return () -> {
        disposable.dispose();
        span.finish(null);
    };
}
```

**优点：** 彻底去掉了自研 HTTP 层，代码量减少约 40%（删除 AbstractOpenAIStyleChatClient、SSE Parser、StreamAsyncExecutor、OkHttp 配置等）。与 Spring AI 生态对齐，后续新增提供商只需加 Starter 依赖。

**缺点：** 路由层需要较大改动来适配 Spring AI 的接口。动态创建 ChatModel 绕过了 Spring AI 的 Auto-Configuration，部分配置需要手工管理。Flux 到 StreamCallback 的桥接需要仔细处理背压和线程安全。

**预估工作量：** 5-7 天。

---

#### 方案 C：全面拥抱 Spring AI（高风险高收益，大改动）

**核心思路：** 全面使用 Spring AI 的 Advisor 链、ChatClient（Spring AI 自己的高级 API）、Advisor-based tracing，并引入 Spring AI Alibaba 作为百炼的接入层。

**具体做法：**

```
删除：
  - 整个自研 ChatClient / EmbeddingClient / RerankClient 体系
  - 自研路由层（RoutingLLMService / ModelSelector / ModelHealthStore）
  - 自研流式管理（ProbeStreamBridge / StreamAsyncExecutor / StreamCancellationHandle）
  - 自研 HTTP 层（全部 OkHttp 相关）

使用 Spring AI 替代：
  - LLMService.chat() → Spring AI ChatClient.prompt().call()
  - LLMService.streamChat() → Spring AI ChatClient.prompt().stream().chatResponse()
  - 多模型路由 → 自定义 Advisor 实现 fallback 逻辑
  - 熔断器 → Spring Retry + 自定义 Advisor
  - Trace → Advisor 拦截 + Micrometer Observation
  - Prompt 构造 → Spring AI PromptTemplate + SystemPromptTemplate
  - Conversation Memory → Spring AI ChatMemory + MessageChatMemoryAdvisor
  - MCP → spring-ai-starter-mcp-client
  - 业务层 RAGChatService → 改为使用 Spring AI ChatClient
```

**Spring AI ChatClient 用法示意：**

```java
@Service
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {

    private final ChatClient.Builder chatClientBuilder;

    public void streamChat(String question, ..., SseEmitter emitter) {
        ChatClient client = chatClientBuilder.build();
        client.prompt()
            .system("你是一个智能助手...")
            .user(question)
            .advisors(
                new MessageChatMemoryAdvisor(chatMemory),
                new QuestionAnswerAdvisor(vectorStore),
                new ModelFallbackAdvisor(modelCandidates) // 自定义
            )
            .stream()
            .chatResponse()
            .subscribe(chunk -> {
                emitter.send(SseEmitter.event().data(chunk));
            });
    }
}
```

**优点：** 架构最简洁，与 Spring AI 生态完全对齐。可以复用 Spring AI 的大量内置功能（Prompt 模板、Output 解析、Chat Memory、RAG Advisor），减少自研代码量约 60%。后续升级只需跟 Spring AI 版本即可。

**缺点：** 改动量巨大，几乎重写整个 infra-ai 模块并涉及 bootstrap 业务层。多模型路由和熔断器需要自己写 Advisor 实现（Spring AI 不提供现成的）。现有 trace 体系（@RagTraceNode / RagStreamTraceSupport）需要迁移到 Micrometer 或 Advisor 拦截。MCP 集成方式也要从直接 SDK 调用迁移到 Spring AI MCP Starter。风险高，测试工作量大。

**预估工作量：** 10-15 天。

---

### 五、方案对比

| 维度 | 方案 A（适配器） | 方案 B（混合） | 方案 C（全面） |
|------|-----------------|---------------|---------------|
| 改动范围 | infra-ai 客户端层 | infra-ai 大部分 | infra-ai 全部 + bootstrap 部分 |
| 风险 | 低 | 中 | 高 |
| 代码减少 | ~10% | ~40% | ~60% |
| 路由/熔断保留 | 完全保留 | 保留但适配 | 用 Advisor 重写 |
| Trace 体系保留 | 完全保留 | Flux 桥接适配 | 迁移到 Micrometer |
| Spring AI 对齐度 | 仅底层 HTTP | 接口层对齐 | 完全对齐 |
| 回退难度 | 随时可回退 | 可回退但需保留旧代码 | 难以回退 |
| 预估工时 | 2-3 天 | 5-7 天 | 10-15 天 |
| 适合场景 | 先跑通验证 | 想实质简化代码 | 长期技术投资 |

---

### 六、建议

如果目标是降低维护成本同时引入 Spring AI 生态，**方案 B（混合重构）** 是最优平衡点。它去掉了最"脏"的一层（手工 HTTP/SSE 解析），与 Spring AI 的接口层对齐，同时保留了你最有价值的自研能力（多模型路由、熔断器、首包探测、trace 体系）。

如果时间紧、风险敏感，先用**方案 A** 快速验证 Spring AI 在项目中的可行性，后续再升级到方案 B。

如果这是一次长期技术投资、且愿意做完整回归测试，可以考虑**方案 C**，但要做好多模型路由和 trace 体系的自研适配工作。

---

### 七、不管选哪个方案都要注意的事项

1. **Spring AI Alibaba 版本锁定：** 1.1.2.2（对应 Spring AI 1.1.2 + Spring Boot 3.5.x），注意你的 Spring Boot 是 3.5.7，需要验证兼容性。
2. **多提供商配置冲突：** Spring AI 的 OpenAI Starter 和 Ollama Starter 同时引入时，注意 `spring.ai.openai` 和 `spring.ai.ollama` 的 auto-configuration 不要冲突。对于 DeepSeek / SiliconFlow / AIHubMix 这些 OpenAI 兼容的提供商，都需要用 OpenAI Starter 但传不同的 base-url。
3. **动态 API Key 注入：** 你当前是从数据库或配置文件动态读取 API Key 的，Spring AI 默认从 `application.yaml` 读取，需要在创建 ChatModel 实例时手工传入。
4. **Flux 与 StreamCallback 线程模型差异：** Spring AI 的 Flux 在 Reactor 的调度线程上运行，你当前的 StreamCallback 在 modelStreamExecutor 上运行，桥接时注意线程安全。
5. **MCP 迁移可独立进行：** 你当前的 MCP 模块用的是官方 SDK（`io.modelcontextprotocol.sdk:mcp`），这个和 Spring AI MCP Starter 是同一个底层 SDK，迁移优先级可以低于 AI 客户端层。
