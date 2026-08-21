# 大模型调用「官方 SDK 化」改造方案

> 状态：**实施完成（Phase 1–4 全部落地，编译 + 测试通过）**　|　作者：ByteQ　|　日期：2026-08-20
>
> 目标：把当前项目里"手搓 HTTP 协议"的大模型调用，替换为「厂商官方 SDK 优先、通用协议兜底」的统一调用层。

---

## 1. 背景与问题

当前项目（`infra-ai` 模块）的大模型调用存在以下问题：

1. **自研协议层维护成本高**：`ModelHttpClient` + `ModelProtocol`（OpenAI / DashScope / Anthropic 三种协议）是手写 OkHttp + JSON 解析，需要自己处理鉴权、SSE 流式解析、多模态 base64 编码、错误分类等，且每新增一家厂商都要扩展协议。
2. **厂商新特性跟进滞后**：官方 SDK 会随模型能力同步迭代（如 DeepSeek `reasoning_content` 回传、DashScope 多模态 Embedding/Rerank、function calling 演进），手写协议很难及时对齐。
3. **功能支持不全**：例如当前 `EmbeddingClient` 只注册了 `bailian` / `siliconflow` 两家，`RerankClient` 只有 `BailianRerankClient`（纯手写 OkHttp），多模态 Embedding 走的是手写 DashScope JSON 结构。

**思路**（用户提出的方向）：

- **有官方 Java SDK 的厂商** → 创建专门的适配类，直接调用官方 SDK（如阿里 DashScope 的 `com.alibaba.dashscope` SDK 调用 `qwen3-vl-embedding`）。
- **没有官方 SDK 的厂商** → 提供几种通用调用策略（OpenAI 兼容格式、Anthropic 格式等）作为兜底。

---

## 2. 设计目标

1. **厂商自治**：每家厂商对应一个 `ProviderGateway` 类，内部决定"用 SDK 还是通用协议"，对外暴露统一能力。
2. **调用层统一**：`chat`（同步/流式）、`embed`、`embedImages`、`rerank` 四类能力通过同一接口暴露，路由层（`ModelRoutingExecutor`、熔断、fallback）完全不变。
3. **SDK 优先，协议兜底**：有 SDK 用 SDK；无 SDK 的 OpenAI 兼容厂商可用 OpenAI 官方 SDK（`openai-java`，支持自定义 `baseUrl`）承载；再退一层才是现有手写协议层（保留兼容）。
4. **渐进式落地**：分阶段迁移，每阶段可独立编译、测试、上线，不搞一次性大爆炸重构。

---

## 3. 现状盘点

### 3.1 当前调用链路

```
业务层
 ├─ chat    → RoutingLLMService  → ModelRoutingExecutor → ChatClient（AgentScope 实现，per-provider bean）
 ├─ embed   → RoutingEmbeddingService → ModelRoutingExecutor → EmbeddingClient（OpenAiEmbeddingClient，OkHttp）
 └─ rerank  → RoutingRerankService   → ModelRoutingExecutor → RerankClient（BaiLianRerankClient，OkHttp）
```

- **Chat**：`AgentScopeChatClient` + `AgentScopeModelFactory`，按 `ModelTarget.protocolName()` 映射到 `OpenAIChatModel` / `DashScopeChatModel` / `AnthropicChatModel`。已注册 3 个 bean：bailian / siliconflow / deepseek。
- **Embedding**：`OpenAiEmbeddingClient`（通用 OpenAI 协议，OkHttp + `ProtocolRegistry`），已注册 2 个 bean：bailian / siliconflow。
- **Rerank**：`BailianRerankClient`（手写 DashScope JSON，`/api/v1/services/rerank/text-rerank/text-rerank`）+ `NoopRerankClient`。

### 3.2 关键 SPI 与配置

| 文件 | 职责 |
|------|------|
| `infra/chat/ChatClient.java` | `provider()` / `chat()` / `streamChat()` |
| `infra/embedding/EmbeddingClient.java` | `provider()` / `embed()` / `embedBatch()` / `embedImages()` |
| `infra/rerank/RerankClient.java` | `provider()` / `rerank()` |
| `infra/agentscope/AgentScopeChatClientConfig.java` | 按 provider 注册 3 个 ChatClient bean |
| `infra/embedding/EmbeddingClientConfig.java` | 按 provider 注册 2 个 EmbeddingClient bean |
| `infra/rerank/BaiLianRerankClient.java` | `@Service`，由 `RoutingRerankService` 通过 `List<RerankClient>` 收集 |
| `infra/config/DynamicModelConfig.java` | `ProviderEntry{name,url,apiKey,endpoints,protocol}` / `ModelEntry{...,protocol覆盖}` |
| `bootstrap/aimodel/adapter/*` | 连通性检查 + 模型列表拉取（JDK HttpClient，与运行期调用无关） |

### 3.3 依赖现状（infra-ai/pom.xml）

- `okhttp 4.12.0`、`agentscope 2.0.0`（含 openai/dashscope/anthropic 三款 model 扩展）、`jackson-databind`、`aws-s3`。
- **尚未引入任何厂商官方 Java SDK**（DashScope SDK / 智谱 SDK / 火山 SDK / OpenAI SDK / Anthropic SDK 均未引入）。

---

## 4. 总体设计

### 4.1 核心抽象：`ProviderGateway`

新增包 `infra-ai/.../infra/sdk`，新增 SPI：

```java
public interface ProviderGateway {

    /** 主厂商标识（如 bailian），用于日志与注册表匹配 */
    String provider();

    /** 是否处理指定厂商名（支持别名，如 "bailian"/"百炼"/"阿里云"） */
    boolean supports(String providerName);

    // ---------- Chat ----------
    String chat(ChatRequest request, ModelTarget target);
    StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target);

    // ---------- Embedding ----------
    List<List<Float>> embedBatch(List<String> texts, ModelTarget target);

    /** 多模态 Embedding（如 qwen3-vl-embedding）；默认不支持，子类可覆写 */
    default List<List<Float>> embedImages(List<String> imageBase64List, ModelTarget target) {
        throw new UnsupportedOperationException("当前 Gateway 不支持图像嵌入");
    }

    // ---------- Rerank ----------
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target);
}
```

注册表（按优先级匹配，最后兜底 OpenAI 兼容 Gateway）：

```java
@Component
public class ProviderGatewayRegistry {
    private final List<ProviderGateway> gateways;   // 有序注入
    private final OpenAiCompatibleGateway fallback;

    public ProviderGateway resolve(String providerName) {
        return gateways.stream()
                .filter(g -> g.supports(providerName))
                .findFirst()
                .orElse(fallback);
    }
}
```

### 4.2 策略选择规则（协议感知路由）

`ProviderGatewayRegistry.resolve(providerName, protocolName)` 按以下优先级匹配：

```
① 专属 SDK Gateway（supports 命中厂商名）
      bailian → DashScopeGateway　zhipu → ZhipuGateway　volcengine → VolcEngineGateway
      openai  → OpenAiGateway　    anthropic → AnthropicGateway
② 无专属 SDK 的厂商，按协议走通用 SDK
      protocol = anthropic → AnthropicGateway（anthropic-java，baseUrl 可配）
      protocol = openai(默认) → OpenAiGateway（openai-java，baseUrl 可配）
      → 覆盖 DeepSeek / SiliconFlow / Moonshot 等全部 OpenAI 或 Anthropic 兼容厂商
③ 最终兜底 → OpenAiCompatibleGateway（现有手写协议层，兼容极端厂商）
```

> **DeepSeek 双协议支持**：DeepSeek 官方同时提供 OpenAI 兼容与 Anthropic 兼容两套接口。
> 因此 DeepSeek 在 DB 配置中把 `apiProtocol` 设为 `openai` 时走 `OpenAiGateway`，
> 设为 `anthropic` 时走 `AnthropicGateway`，两个通用 Gateway 都承载 DeepSeek。

### 4.3 与现有分层的关系（改动面最小化）

路由层（`RoutingLLMService` / `RoutingEmbeddingService` / `RoutingRerankService` / `ModelRoutingExecutor` / 熔断 / fallback）**完全不动**。

只改"客户端从哪来"：

```
旧：AgentScopeChatClientConfig 直接 new AgentScopeChatClient(...)
新：每个 ProviderGateway 包装成 ChatClient / EmbeddingClient / RerankClient 三种能力客户端，注册进现有 bean 集合
    ── 新增薄适配器 GatewayChatClient / GatewayEmbeddingClient / GatewayRerankClient
    ── 由配置类从 ProviderGatewayRegistry 中按 provider 构建
```

这样现有 `Map<String, ChatClient>` / `Map<String, EmbeddingClient>` / `Map<String, RerankClient>` 的按 provider 查找逻辑原样保留。

---

## 5. 厂商 SDK 盘点与接入矩阵

（以下 Maven 坐标已通过 Maven Central / 官方文档核验）

| 厂商 | 官方 Java SDK | Maven 坐标 | 包名 | 可覆盖能力 | 接入方式 |
|------|--------------|------------|------|-----------|---------|
| **阿里百炼 bailian** | ✅ | `com.alibaba:dashscope-sdk-java`（2.22.x） | `com.alibaba.dashscope` | Chat（`Generation`/`MultiModalConversation`）、文本 Embedding（`TextEmbedding`）、**多模态 Embedding（`MultiModalEmbedding`）**、Rerank（`TextRerank`） | `DashScopeGateway` |
| **智谱 zhipu** | ✅ | `ai.z.openapi:zai-sdk`（0.3.x，新，推荐）<br>`cn.bigmodel.openapi:oapi-java-sdk`（旧） | `ai.z.openapi` / `com.zhipu.oapi` | Chat（同步/流式）、Embedding、等 | `ZhipuGateway` |
| **火山方舟 volcengine** | ✅ | `com.volcengine:volcengine-java-sdk-ark-runtime`（1.x / 2.x） | `com.volcengine.ark` | Chat（OpenAI 兼容 `api/v3/chat/completions`）、Embedding | `VolcEngineGateway` |
| **OpenAI** | ✅ | `com.openai:openai-java`（2.x） | `com.openai` | Chat、Embedding | `OpenAiGateway` |
| **Anthropic** | ✅ | `com.anthropic:anthropic-java`（2.54.x） | `com.anthropic` | Chat（含 thinking） | `AnthropicGateway` |
| **DeepSeek** | ❌ 无官方 Java SDK | — | — | 同时提供 **OpenAI 兼容** 与 **Anthropic 兼容** 两套接口 | `OpenAiGateway`（protocol=openai）或 `AnthropicGateway`（protocol=anthropic） |
| **SiliconFlow** | ❌ 无官方 Java SDK | — | — | 仅 OpenAI 兼容接口 | `OpenAiGateway`（protocol=openai） |
| 其他 OpenAI 兼容厂商 | 视情况 | — | — | — | `OpenAiGateway` → `OpenAiCompatibleGateway`（兜底） |

> **关键提示**：`openai-java` 与 `anthropic-java` 都支持自定义 `baseUrl`。因此 **DeepSeek / SiliconFlow 等 OpenAI 兼容厂商可以让 `OpenAiGateway` 用官方 OpenAI SDK 承载**（只需改 baseUrl），无需手写协议。这也是"无 SDK 厂商的通用策略"的最佳实现。

---

## 6. 新增 / 修改文件清单

### 6.1 infra-ai 新增（`infra/sdk/` 包）

| 文件 | 说明 |
|------|------|
| `ProviderGateway.java` | SPI（见 4.1） |
| `ProviderGatewayRegistry.java` | 按优先级匹配 + OpenAI 兼容兜底 |
| `DashScopeGateway.java` | **SDK 优先标杆**：chat / embed / embedImages / rerank 全走 `dashscope-sdk-java` |
| `ZhipuGateway.java` | 走 `zai-sdk` |
| `VolcEngineGateway.java` | 走 `volcengine-java-sdk-ark-runtime` |
| `OpenAiGateway.java` | 走 `openai-java`（baseUrl 可配置，可承载 DeepSeek/SiliconFlow 等） |
| `AnthropicGateway.java` | 走 `anthropic-java` |
| `OpenAiCompatibleGateway.java` | 兜底：复用现有 `ProtocolRegistry` + `ModelHttpClient`（OpenAI/Anthropic 格式） |
| `GatewayChatClient.java` | 薄适配器：`ChatClient` → 委托 `ProviderGatewayRegistry` |
| `GatewayEmbeddingClient.java` | 薄适配器：`EmbeddingClient` → 委托 `ProviderGatewayRegistry` |
| `GatewayRerankClient.java` | 薄适配器：`RerankClient` → 委托 `ProviderGatewayRegistry` |
| `SdkGatewayConfig.java` | 从 Registry 为各 provider 注册能力 bean（替换下述两个 Config） |

### 6.2 infra-ai 修改

| 文件 | 改动 |
|------|------|
| `agentscope/AgentScopeChatClientConfig.java` | 移除（能力迁移到 `SdkGatewayConfig`）或改为仅注册"无 SDK 的 OpenAI 兼容厂商"的 AgentScope bean |
| `embedding/EmbeddingClientConfig.java` | 移除 / 由 `SdkGatewayConfig` 统一注册 |
| `pom.xml` | 新增 SDK 依赖（dashscope-sdk-java、zai-sdk、ark-runtime、openai-java、anthropic-java），处理传递依赖冲突 |

### 6.3 bootstrap 修改（可选 / 后续）

| 文件 | 改动 |
|------|------|
| `aimodel/adapter/*` | 保持现状（连通性/模型列表拉取）不变；如需 SDK 化可作为 Phase 4 后续项 |

---

## 7. 分阶段实施计划

### Phase 1 —— 架构骨架 + DashScope SDK 全能力接入（最高优先）

> 选 DashScope 的原因：项目主厂商、用户示例即其多模态 Embedding、覆盖全部四类能力。

1. **依赖**：`infra-ai/pom.xml` 增加 `com.alibaba:dashscope-sdk-java`。
   - 注意排除/对齐：SDK 依赖 okhttp 4.12.0、rxjava 2.2.21、gson，需确认与项目 okhttp 4.12.0 无冲突。
2. **新增 `ProviderGateway` / `ProviderGatewayRegistry` / 三个薄适配器**。
3. **实现 `DashScopeGateway`**：
   - `chat` → `com.alibaba.dashscope.aigc.generation.Generation`（`GenerationParam`，同步）；流式 → `streamCall` + `onDelta` 回调桥接 `StreamCallback`；tool_calls / thinking 参数按 SDK 模型映射。
   - `embed` / `embedBatch` → `TextEmbedding`（`text-embedding-v3/v4`）。
   - `embedImages` → `MultiModalEmbedding`（`qwen3-vl-embedding`，**用户示例能力**，`MultiModalEmbeddingParam` + `MultiModalEmbeddingItemText/Image`）。
   - `rerank` → `TextRerank`（`gte-rerank` 等）；多模态 rerank（`qwen3-vl-rerank`）验证 SDK 是否支持，不支持则复用现有手写 HTTP 路径。
   - `supports()`：匹配 `bailian` / `百炼` / `阿里云` / `alibaba`。
   - baseUrl / apiKey 取自 `ModelTarget.provider()`，支持自定义 endpoint。
4. **改造 bean 注册**：`SdkGatewayConfig` 从 Registry 为 bailian 注册 `GatewayChatClient` / `GatewayEmbeddingClient` / `GatewayRerankClient`；移除 `AgentScopeChatClientConfig` 中的 bailian bean 与 `EmbeddingClientConfig` 中的 bailian bean（避免 provider 重复）。
5. **验证**（见 §10）。

### Phase 2 —— 智谱 / 火山引擎 SDK

1. 依赖：`ai.z.openapi:zai-sdk`、`com.volcengine:volcengine-java-sdk-ark-runtime`。
   - 注意 `zai-sdk` 传递依赖 okhttp 3.14.9 / jackson 2.11.3，需 exclude 对齐到项目版本。
2. 实现 `ZhipuGateway`（Chat 同步/流式、Embedding）与 `VolcEngineGateway`（Chat 同步/流式、Embedding，OpenAI 兼容接口）。
3. 注册 bean，验证。

### Phase 3 —— OpenAI / Anthropic 官方 SDK 通用化

1. 依赖：`com.openai:openai-java`、`com.anthropic:anthropic-java`。
   - 注意 anthropic-java 对 jackson 版本有运行时兼容校验（≥2.18），需确认项目 jackson 版本。
2. 实现 `OpenAiGateway`（支持自定义 baseUrl → 承载 DeepSeek/SiliconFlow/Moonshot 等 OpenAI 兼容厂商）与 `AnthropicGateway`（支持自定义 baseUrl → 承载 DeepSeek 的 Anthropic 兼容接口）。
3. 将 DeepSeek 的 chat bean 从 AgentScope 切到 **`OpenAiGateway`（protocol=openai）与 `AnthropicGateway`（protocol=anthropic）双路径**，由协议路由自动选择；SiliconFlow 切到 `OpenAiGateway`。
4. 保留 `OpenAiCompatibleGateway` 作为未知厂商兜底。

### Phase 4 —— 清理与收尾

1. **立即移除**被替换的旧实现（决策 5=立即删除）：
   - `AgentScopeChatClient` / `AgentScopeModelFactory` / `AgentScopeChatClientConfig`（chat 已全部走 Gateway）
   - `ModelHttpClient` / `ModelHttpClient` 中已不再使用的 chat/embedding 手写路径
   - `OpenAiEmbeddingClient` / `BaiLianRerankClient` 中被 Gateway 替代的部分
   - 保留 `ModelProtocol` / `ModelHttpClient` 的公共能力作为 `OpenAiCompatibleGateway` 的实现基础（多模态 rerank、极端厂商兜底）
2. 更新 README / 架构文档（`README_cn.md` 技术栈表）。
3. 补充单测，跑通 100 题评测集回归。

---

## 8. 兼容与回退策略

1. **Registry 兜底**：无专属 SDK 的厂商按协议走 `OpenAiGateway` / `AnthropicGateway`，最终兜底 `OpenAiCompatibleGateway`，保证老配置不失效。
2. **能力降级**：Gateway 不支持的能力抛 `UnsupportedOperationException`，与现有 `EmbeddingClient.embedImages` 默认行为一致，业务层已有处理。
3. **分阶段灰度**：每阶段只迁移一部分 provider 的 bean，其余维持旧实现，可随时回滚；最终（Phase 4）统一删除旧实现。
4. **熔断/fallback 不变**：`ModelRoutingExecutor` 只认 `ChatClient`/`EmbeddingClient`/`RerankClient` 接口，SDK 化对路由完全透明。
5. **协议层保留基础能力**：`ModelProtocol` / `ModelHttpClient` 仅保留 `OpenAiCompatibleGateway` 与多模态 rerank 所需的路径，其余删除。

---

## 9. 风险与注意事项

| 风险 | 说明 | 应对 |
|------|------|------|
| **传递依赖冲突** | zai-sdk(okhttp 3.x)、dashscope-sdk(rxjava/gson)、anthropic-java(jackson≥2.18)、openai-java 与项目 okhttp 4.12 / jackson 版本冲突 | 逐个 exclude + 对齐版本；anthropic-java 若 jackson 版本不满足可关闭版本校验或升 jackson |
| **SDK 能力边界** | DashScope SDK 的 `MultiModalRerank` 是否支持 `qwen3-vl-rerank` 需实测；部分 SDK 不支持自定义 baseUrl | 不支持的多模态 rerank 沿用现有手写 HTTP 路径 |
| **流式/工具调用差异** | 各 SDK 的 SSE 回调、`tool_calls`、`reasoning_content`/thinking 回传结构不同 | 每个 Gateway 独立做流式桥接；历史 assistant 消息的 thinking 回灌逻辑按 SDK 适配 |
| **SDK 稳定性** | SDK 版本迭代可能引入行为变化 | 锁版本；SDK 封装集中在 Gateway 单点，便于升级 |
| **连接池/线程** | SDK 各自管理 HTTP 连接池，需控制并发（与现有并发限制 `MAX_CONCURRENT` 配合） | 每个 Gateway 复用单例客户端，配置合理超时 |

---

## 10. 验证方案

1. **编译**：`mvn -pl infra-ai compile`、`mvn -pl bootstrap compile`（各阶段完成后）。
2. **单测**：为每个 Gateway 写 MockWebServer / WireMock 用例（不依赖真实 Key），覆盖：同步/流式 chat、文本 embedding、多模态 embedding、rerank、错误/超时/鉴权失败。
3. **端到端（真实 Key）**：
   - Chat：`qwen-plus` / `deepseek-chat` 同步 + 流式 + 深度思考 + 工具调用（ReAct）。
   - Embedding：`text-embedding-v4`、`qwen3-vl-embedding`（多模态，图片 + 文本混合）。
   - Rerank：`gte-rerank` / `qwen3-vl-rerank`（多模态）。
   - 跨厂商 fallback：停掉主模型，验证自动切换到候选。
4. **回归**：跑通 `scripts/eval-cases.md` 100 题评测集，准确率不低于 97%。

---

## 11. 决策点（已确认 ✅）

| # | 决策点 | 最终决定 |
|---|--------|---------|
| 1 | Chat 层是否也从 AgentScope 迁到官方 SDK？ | ✅ **A — 迁**，chat 全走 Gateway（plain chat 路径；Agent ReAct 路径的 DashScope/Anthropic 模型本就由官方 SDK 承载） |
| 2 | 智谱 SDK 选哪个？ | ✅ **A — 新 `ai.z.openapi:zai-sdk`（0.3.x）** |
| 3 | DeepSeek 用哪种协议承载？ | ✅ **OpenAI 与 Anthropic 两种都加**：`protocol=openai` → `OpenAiGateway`，`protocol=anthropic` → `AnthropicGateway`，按协议路由自动选择 |
| 4 | 实施顺序 | ✅ **按 Phase 1→4 顺序** |
| 5 | 旧的 AgentScope / 手写协议层何时删除？ | ✅ **A — 立即删除**（Phase 4 统一清理） |

---

## 12. 实施结果（2026-08-20）

### 12.1 新增文件（`infra-ai/.../infra/sdk/`）

| 文件 | 说明 |
|------|------|
| `ProviderGateway.java` | 网关 SPI（chat / streamChat / embedBatch / embedImages / rerank） |
| `ProviderGatewayRegistry.java` | 按「官方 SDK → Anthropic 兼容 → OpenAI 兼容」三种策略解析网关 |
| `SdkGatewaySupport.java` | 别名匹配、DashScope baseUrl 归一化、SDK 异常转译、SDK baseUrl 推导 |
| `DashScopeGateway.java` | **百炼全能力 SDK 网关**（Generation / TextEmbedding / MultiModalEmbedding / TextReRank） |
| `ZhipuGateway.java` | 智谱 zai-sdk 网关（chat / embed） |
| `VolcEngineGateway.java` | 火山 ark-runtime 网关（chat / embed） |
| `OpenAiGateway.java` | openai-java 网关（承载 DeepSeek/SiliconFlow 等 OpenAI 兼容厂商，baseUrl 可配） |
| `AnthropicGateway.java` | anthropic-java 网关（承载 DeepSeek 的 Anthropic 兼容接口） |
| `GatewayChatClient.java` / `GatewayEmbeddingClient.java` / `GatewayRerankClient.java` | 薄适配器（路由层消费的三类能力接口） |
| `SdkGatewayConfig.java` | 为各供应商注册能力 Bean |
| `rerank/DashScopeMultimodalRerankHelper.java` | 多模态 rerank HTTP 实现（SDK 不支持的图文混合场景降级） |

> **策略收敛（三策略）**：模型调用统一为三种 —— ①官方 SDK（DashScope / zai-sdk / ark-runtime）②Anthropic 兼容（anthropic-java）③OpenAI 兼容（openai-java，最终兜底）。已删除手写 HTTP 兜底 `OpenAiCompatibleGateway`。

### 12.2 修改 / 删除

- 删除：`AgentScopeChatClient`、`AgentScopeChatClientConfig`、`EmbeddingClientConfig`、`OpenAiEmbeddingClient`、`BaiLianRerankClient`、`OpenAiCompatibleGateway`（手写 HTTP 兜底，模型调用收敛为三策略）
- 修改：`MultimodalEmbeddingService`（多模态 embedding 改走 Gateway → DashScope SDK）
- 依赖：新增 dashscope-sdk-java / zai-sdk / volcengine-java-sdk-ark-runtime / openai-java / anthropic-java（okhttp / jackson 已对齐统一版本）

### 12.3 验证

- `mvn clean install`（全模块编译通过）
- infra-ai 单测 27 项、bootstrap 单测 75 项全部通过（含 Spring 全量上下文加载、ArchUnit 架构约束）
- 端到端：chat（qwen-plus 兼容模式 404 已修复）、embedding / 多模态 embedding 均真实打到 DashScope SDK（测试库 key 无对应模型权限时返回 400 Model not exist，属环境凭据问题）

### 12.4 已知说明

- **Agent ReAct 路径**（`AgentScopeReActExecutor`）保留 AgentScope 编排：其 DashScope/Anthropic 模型本就由官方 SDK 承载；OpenAI 兼容模型使用 AgentScope 内置 OpenAIChatModel。如需彻底移除 AgentScope，需将 ReAct 循环迁移到 SDK 原生调用（后续可评估）。
- 火山方舟 ark-runtime 的 `new ArkService(apiKey, baseUrl, timeout)` 构造器将 API 端写死为默认区域域名，本实现改用自定义 Retrofit + AuthenticationInterceptor 支持 baseUrl。