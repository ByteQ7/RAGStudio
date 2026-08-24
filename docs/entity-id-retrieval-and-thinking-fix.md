# 修复设计文档：实体 ID 检索不命中 & 思考内容泄漏正文

> 关联案例：用户选择「开票知识库」后提问 `91330108MA1K2L3M4N？`，
> 系统返回的全是 HR 库 chunk，且大段推理分析文字直接出现在回答正文。

---

## 一、问题 1：实体 ID（统一社会信用代码）检索不命中

### 1.1 排查结论（已逐层实证）

| 层 | 验证方式 | 结论 |
|---|---|---|
| PG 扩展 | `SELECT extname FROM pg_extension` | ✅ `vector 0.8.4`、`pg_trgm 1.6` 均已安装；两张向量表 `idx_kv_*_content_trgm` GIN 索引齐全。**"PG 未开扩展"假设排除** |
| 数据层 | 直查 `t_knowledge_vector_1024` | ✅ 目标代码存在于 chunk `2090685384471232516`（collection=`invoice-group-document`，position=567） |
| SQL 层 | 手工执行应用同款 ILIKE+similarity SQL | ✅ 开票库命中该 chunk（score=0.076），HR 库命中为空 |
| 编排层 | 代码走读 | ❌ **故障点**：见 1.2 |

### 1.2 根因链（按触发顺序）

```
用户输入 "91330108MA1K2L3M4N？"
        │
        ▼
① EntityIdQueryDetector.isEntityIdQuery() = false   ← 【第一根因】
   整串匹配 ^[A-Za-z0-9]+$，尾部全角问号 "？" 破坏匹配
        │
        ├─→ 查询改写未跳过 → 改写模型可能篡改 ID
        │    （历史上发生过 91330108 → 913330108 幻觉，StreamChatPipeline 注释有记录）
        │
        └─→ 语义选库未跳过 → 随机串嵌入 vs KB 描述相似度趋近 0/随机
             → 开票库得分 < kb-selection-threshold(0.32) 被误杀 / 选错库
             → effectiveKbIds 里没有开票库 → 后续所有通道只查 HR 库
        │
        ▼
② RrfHybridChannel 内部 per-query 实体判定同样整串匹配失效
   → 向量噪声通道开启，进一步稀释关键词精确命中的权重
        │
        ▼
③ 即使关键词通道命中了目标 chunk（ILIKE '%91330108MA1K2L3M4N%'），
   RerankPostProcessor 的 rerank-min-score=0.3 底线过滤可能将其丢弃：
   cross-encoder 对随机 ID 串打分普遍偏低（无语义信号），无精确匹配保护 ← 【第二根因】
        │
        ▼
④ extractKeywords 上限 16 词 + 中文滑窗可能把长 ID token 挤出列表（次要）
```

LLM 自行扩展 query（如 `"91330108MA1K2L3M4N 开票信息"`）时，②的整串判定同样失效——这是同一根因的第二种表现形态。

### 1.3 修复方案

#### P0-1 `EntityIdQueryDetector` 重构：从"整串判定"升级为"强 ID token 提取"

```java
// 新增 API：
public static List<String> extractStrongIdTokens(String query)
// 提取查询中的强 ID token：连续字母数字段 [A-Za-z0-9]{8,} 且同时含数字与字母（税号/编码型）；
// 或纯数字 ≥10 位（单号型）。中文、标点、空格均为天然分隔符。

public static boolean containsStrongEntityId(String query)
// !extractStrongIdTokens(query).isEmpty()

// 保留 isEntityIdQuery()（纯 ID 单 token 场景），内部改为基于 strip 后判定，
// 兼容尾部问号/句号等标点。
```

判定示例：

| 输入 | containsStrongEntityId |
|---|---|
| `91330108MA1K2L3M4N？` | true（strip 后纯 ID） |
| `税号91330108MA1K2L3M4N是多少` | true（token 提取） |
| `2026年假期政策` | false（"2026" 仅 4 位且纯数字 <10 位） |
| `订单20260822001状态` | true（10 位纯数字 token） |

#### P0-2 `StreamChatPipeline`：改用 `containsStrongEntityId` 触发快速通道

含强 ID token 即：跳过查询改写（防篡改）+ 跳过语义选库（检索全部已选 KB）。
原意图注释已写明这两个"应该"，只是被①的判定漏洞架空了。

#### P0-3 `RrfHybridChannel`：per-query 判定同步替换为 `containsStrongEntityId`

覆盖 LLM 扩展 query 的场景："ID + 中文描述"混合查询时，ID 不参与向量侧，但保留关键词侧。

#### P0-4 精确匹配保护（新增）：exactMatch 打标 + rerank 保底

客观事实优先于语义打分：chunk 内容**精确包含**查询中的强 ID token 时，这是可证明的相关性，不允许被 reranker 主观分数丢弃。

- `RetrievedChunk` 增加 `boolean exactMatch` 字段（默认 false，不序列化进 metadata）
- `PgRetrieverService.retrieveByKeyword`：检索结果逐条检查是否包含任一强 ID token，命中则置 `exactMatch=true`
- `RerankPostProcessor`（文本/多模态两条路径）：动态 TopK + minScore 过滤完成后，
  将被丢弃的 exactMatch chunk **回插到结果头部**（数量计入 dynamic-top-k-overflow-cap 上限内）

#### P1-5 `extractKeywords`：含字母数字的长 token 排序提前

防止 16 词上限截断把 ID token 挤出（中文滑窗词最多可达 13 个）。

#### P1-6 pg_trgm 探测缓存加 TTL（60s）

现状为进程级永久缓存，扩展中途安装后需重启才生效；与 dimension/model 缓存策略对齐。

#### P1-7 可观测性

- `RrfHybridChannel`：日志输出每个 query 的实体 ID 判定结果与提取到的 token
- `PgRetrieverService`：日志输出 exactMatch 命中数

### 1.4 明确不做的事

- **不改** kb-selection-threshold 数值——随机串问题应靠"识别后跳过"解决，调阈值会误伤闲聊过滤
- **不做** 改写时 ID 掩码还原——复杂度高且 P0-2 已整体跳过改写，收益重复
- **不动** schema_pg.sql——扩展与索引均正常

---

## 二、问题 2：思考内容泄漏到正文 & 思考通道前后端断裂

### 2.1 现状架构梳理

思考内容现有 **三条并行通道**，职责互相交叉：

| 通道 | 生产方 | 前端消费方 | 现状 |
|---|---|---|---|
| SSE `message{type:"think"}` 增量 | StreamChatEventHandler.onThinking | chatStore.onMessage **直接丢弃**（L414 只放行 response） | ❌ 断裂：commit c3ccb48 删除了前端消费端，深度思考功能恢复后未接回 |
| SSE `agent_step` 步骤卡片 | AgentScopeReActExecutor.pushStep | AgentSteps 组件 | ✅ 正常，但 FINISH 步 thought = 全量 thinkingBuffer，与泄漏内容重复展示 |
| 正文 content 流 | onContent | MarkdownRenderer | ⚠️ 泄漏点：见 2.2 |

历史消息：后端已持久化 `thinkingContent`（t_message），API 已返回（sessionService.ts L14），前端 Message 类型无此字段、渲染忽略。

### 2.2 正文泄漏路径（本次案例的实际路径）

`AgentScopeReActExecutor` 的迭代文本分流逻辑：

1. Prompt（react-system-agentscope.st）只说"直接输出最终回答"，**未要求任何标记**
2. 缓冲达 64 字符 → `routeIterationText`：无 `Final Answer:` 标记；
   `REASONING_SIGNAL_PATTERN` 仅匹配 `Observation|Thought:|Action Input|调用\`|下一步：|【强制检索】`
   ——本案例的分析性长文（"检索结果中未发现…该编号格式…因此…"）一个都不含
3. 达到 256 字符（PLAIN_ANSWER_COMMIT_LEN）仍无信号 → 判定"普通长回答"
   → **整段分析流入正文**（incrementalContent=true 后增量直透）

另一潜在路径：部分模型（OpenAI 兼容网关上的 Qwen3/DeepSeek-R1-distill 等）
在 content 中内联输出 `<think>...</think>`，全栈无任何剥离逻辑，原样进正文并显示原始标签。

### 2.3 修复方案

#### Q0-1 输出契约：Prompt 强制 `最终回答：` 标记（治本）

解析器 `FINAL_ANSWER_MARKER_PATTERN` 已兼容 `最终回答：/最终答案：/Final Answer:`，
标记后的文本才会进正文、标记前的分析自动改道 think 通道——机制现成，只差 Prompt 约束。

在 react-system-agentscope.st 中新增规则：

```
- 最终回答必须以单独一行「最终回答：」开头，随后紧跟回答正文；
  该标记之前的所有分析、推导文字都会作为推理过程展示给用户，不会出现在正文中
```

同步更新两处示例，使 few-shot 与规则一致。解析器对无标记输出已有优雅降级（剥离协议行后原样透出），模型偶发遗忘不会导致内容丢失。

#### Q0-2 `<think>` 标签流式剥离器（防御）

新增 `rag/core/agent/ThinkTagStreamFilter`：有状态流式过滤器，正确处理标签跨 delta 切分
（如 `"<th"+"ink>"` 分两个增量到达）：

```
feed(delta, contentOut, thinkOut)  // 内部维护缓冲与 insideThink 状态
flush(contentOut, thinkOut)        // 流结束时清空悬挂缓冲
static String strip(String text)   // 非流式兜底（用于 extractFinalAnswerText）
```

接入点：`AgentScopeReActExecutor` TextBlockDeltaEvent 处理分支（RunState 持有过滤器实例），
剥出的 think 内容并入 thinkingBuffer + callback.onThinking；`extractFinalAnswerText` 同步兜底 strip。

#### Q0-3 前端接回 think 通道（补齐断裂）

| 文件 | 变更 |
|---|---|
| `types/index.ts` | Message 增加 `thinking?: string` |
| `stores/chatStore.ts` | onMessage 处理 `type==="think"`：复用 streamingTimer 节拍批量写入 streaming message.thinking；selectSession 历史映射 `thinking: item.thinkingContent ?? undefined` |
| `components/chat/ThinkingPanel.tsx`（新增） | 折叠面板：Brain 图标 + "思考过程" + 耗时徽标；streaming 中默认展开（脉冲动画），完成/历史态默认折叠可展开 |
| `components/chat/MessageItem.tsx` | message.thinking 存在时于 AgentSteps 上方渲染 ThinkingPanel |

#### Q1-4 FINISH 步骤 thought 截断

`finishStream` 中 finishStep.thought 取 drain(thinkingBuffer) 全量，步骤卡片重复展示整条思维链。
截断至 2000 字符（完整版已在 thinkingBuffer → ChatMessage.thinkingContent 持久化，ThinkingPanel 可看全量）。

#### Q1-5 REASONING_SIGNAL_PATTERN 保持不变

Q0-1 落地后该模式仅作兜底；扩充弱信号词（如"基于检索"）误伤正常回答的风险大于收益，不动。

---

## 三、实施清单

| # | 文件 | 变更类型 | 对应方案 |
|---|---|---|---|
| 1 | `rag/core/retrieve/EntityIdQueryDetector.java` | 重构 | P0-1 |
| 2 | `rag/service/pipeline/StreamChatPipeline.java` | 修改判定调用 | P0-2 |
| 3 | `rag/core/retrieve/channel/RrfHybridChannel.java` | 修改判定调用+日志 | P0-3/P1-7 |
| 4 | `framework/.../convention/RetrievedChunk.java` | 加字段 | P0-4 |
| 5 | `rag/core/retrieve/PgRetrieverService.java` | exactMatch 打标+关键词排序+trgm TTL+日志 | P0-4/P1-5/P1-6 |
| 6 | `rag/core/retrieve/postprocessor/RerankPostProcessor.java` | exactMatch 保底回插 | P0-4 |
| 7 | `rag/core/agent/ThinkTagStreamFilter.java` | 新增 | Q0-2 |
| 8 | `rag/core/agent/AgentScopeReActExecutor.java` | 过滤器接入+finishStep 截断 | Q0-2/Q1-4 |
| 9 | `resources/prompt/react-system-agentscope.st` | 输出契约 | Q0-1 |
| 10 | `frontend/src/types/index.ts` | 加字段 | Q0-3 |
| 11 | `frontend/src/stores/chatStore.ts` | think 消费+历史映射 | Q0-3 |
| 12 | `frontend/src/components/chat/ThinkingPanel.tsx` | 新增 | Q0-3 |
| 13 | `frontend/src/components/chat/MessageItem.tsx` | 渲染接入 | Q0-3 |

## 四、验证计划

1. **单测**：EntityIdQueryDetector 各形态输入矩阵；ThinkTagStreamFilter 跨块切分/多标签/无标签用例
2. **SQL 回归**：模拟应用同款关键词 SQL，确认开票库命中（本文档 1.1 已预验）
3. **编译**：`mvn compile -pl framework,bootstrap -am` + `cd frontend && npx tsc -b --noEmit`
4. **端到端手工验证**：
   - 选开票库提问 `91330108MA1K2L3M4N？` → 应命中快手科技开票信息
   - 提问含中文前后缀 `帮我查下91330108MA1K2L3M4N的开票抬头` → 同样命中
   - 观察正文不再出现分析性长文；思考内容出现在独立折叠面板；历史消息刷新后面板仍在
