# RAG 检索审计日志（RAG Search Audit）设计文档

> 状态：待评审 · 关联：多通道检索（`docs/multi-channel-retrieval.md`）
> 目标：排查"检索/打分有问题"时，能还原每一次 RAG 检索的**文档归属、RRF 分数、重排后顺序**。

## 1. 背景与目标

当前检索链路（粗召 RRF 融合 → 重排 → 动态 TopK）存在多处分数覆盖与截断：

- `RrfMerger` 把 RRF 分数写入 `RetrievedChunk.score`（`RrfMerger.java:76`），随后重排网关会用 cross-encoder 分数**覆盖** `score`；
- `RerankPostProcessor` 内部做候选截断（`limitRerankCandidates`）、动态 TopK、精确匹配回插、图片追加，最终顺序经过多步调整；
- `docName` 只在 `RetrievalEngine.doRetrieve()` 末尾批量反填（`RetrievalEngine.java:167-249`）。

因此"某个 Chunk 来自哪个文档、RRF 打了几分、重排后排第几"这几个信息在最终产物里难以还原，排查问题时只能靠打断点/临时日志。

**本功能目标**：在检索出口统一输出一份结构化审计记录，包含每个返回 Chunk 的：

1. 所属**文档名**（`docName`）+ 知识库名（`kbName`）；
2. **RRF 融合分数**（融合后、被重排覆盖前的原始分）及 per-KB 排名；
3. **重排后的顺序**（最终送入 LLM 的顺序，1-based）。

**约束**：可开关、默认关闭、关闭时零开销、不落库、不改 `RetrievedChunk` DTO。
**已确认决策**：① 落独立文件（仿 `AiLogService`，按日归档）；② `record-rrf-candidates` 默认开；③ 记录 chunk 正文；④ 每次检索一行（`searchId` 粒度）。

## 2. 总体设计

```
RetrievalEngine.doRetrieve()
  ├─ SearchAuditRecorder.begin()        # 仅 enabled 时创建 SearchAudit（含 searchId）
  │        │   SearchAudit 通过新增的 overload 参数传入 MultiChannelRetrievalEngine，
  │        │   再挂到 SearchContext.searchAudit 字段，随 context 流转
  │        ▼
  │   MultiChannelRetrievalEngine.retrieveKnowledgeChannels(..., audit)
  │        ├─ RrfHybridChannel.search()        # 粗召：RRF 融合后逐条 addRrfCandidate(chunkId, collection, score, rank)
  │        └─ RerankPostProcessor.process()    # 重排：markRerank(modelId)；降级时 markRerankFallback()
  │        ▼
  │   （docName/kbName 富化在此处之后完成）
  │        ▼
  └─ audit.finish(query, kbNames, topK, chunks)  # 输出一行 JSON
```

- **`SearchAudit` 随 `SearchContext` 传递而非 ThreadLocal**：通道 `search()` 跑在 `ragRetrievalExecutor` 线程、后置处理器与 `finish()` 跑在调用线程，ThreadLocal 会在线程切换时丢失；随 context 显式传递可跨线程安全（且 `CompletableFuture.get()` 的 happens-before 保证可见性，缓冲再叠加并发队列双保险）。
- **采集点在融合/重排阶段，输出点在 `doRetrieve` 末端**：因为 `docName` 只有末端才反填完整，三份信息（文档名 / RRF 分 / 重排顺序）必须合并到末端一次性输出。
- **开关关闭时短路**：`begin()` 直接返回 `null`，所有采集点 `if (audit != null)` 判空，不分配缓冲、不拼字符串，零开销。

## 3. 配置项

新增 `SearchAuditLogProperties`（`bootstrap/.../rag/config/`），前缀 `rag.search.audit-log`：

```yaml
rag:
  search:
    audit-log:
      enabled: ${RAG_SEARCH_AUDIT_LOG:false}      # 总开关，默认关
      include-chunk-text: true                      # 记录 chunk 正文（已确认开启）
      record-rrf-candidates: true                   # 记录 RRF 全量候选（已确认开启）
      log-dir: ${RAG_SEARCH_AUDIT_LOG_DIR:./logs/rag-search}  # 独立文件目录
```

输出写入**独立文件**（仿 `AiLogService`）：`<log-dir>/YYYYMMDD.log`，每次检索追加一行 JSON。`logs/` 已在 `.gitignore` 中，不会入库。

## 4. 三类数据的采集点

| 数据 | 采集点 | 时机 | 说明 |
|---|---|---|---|
| RRF 分数 + per-KB 排名 | `RrfHybridChannel.search()`，`RrfMerger.merge()` 返回后（`RrfHybridChannel.java:192`） | 粗召融合后、被簇截断前 | 逐条 `addRrfCandidate(chunkId, collection, score, rank)`；rank 为 per-KB 融合输出内 1-based 排名 |
| 文档名 / 知识库名 | `RetrievalEngine.doRetrieve()` 末尾（`RetrievalEngine.java:251` 前） | docName 富化完成后 | 直接从最终 `chunks` 取 `docName`/`kbName` |
| 重排后顺序 | `doRetrieve` 末端 | 后置处理器链全部完成后 | 取最终 `chunks` 的 1-based 下标；重排未生效/降级时即原始顺序，由 `rerankModel`/`rerankFallback` 字段说明 |
| （附加）重排模型 / 是否降级 | `RerankPostProcessor.process()` / `executeRerank()` catch 块 | 重排执行时 | `markRerank(effectiveModelId)`；失败降级时 `markRerankFallback()`，便于识别"排序没变是被降级了" |

## 5. 输出格式（日志样例）

每次 `doRetrieve`（含 Agent 多次检索中的每一次）输出**一行** JSON，`searchId` 标识当次检索：

```json
{
  "searchId": "6f0d1a2e-...",
  "time": "2026-08-24T12:00:00.123",
  "query": "2024年增值税申报逾期怎么办",
  "kbNames": ["税务知识库"],
  "topK": 5,
  "rerankModel": "bge-reranker-v2-m3",
  "rerankFallback": false,
  "finalCount": 5,
  "chunks": [
    {
      "order": 1,
      "chunkId": "1844...",
      "kbName": "税务知识库",
      "docName": "增值税申报指南.pdf",
      "rrfScore": 0.0163934,
      "rrfRank": 1,
      "rrfCollection": "tax_kb_2024",
      "rrfSurvived": true,
      "finalScore": 0.9231
    }
  ]
}
```

字段说明：

- `order`：重排后顺序（= 最终送入 LLM 的 1-based 下标）；
- `rrfScore` / `rrfRank`：粗召阶段 RRF 分数与 per-KB 排名（`rrfScore` 保留的是**重排覆盖前**的值）；
- `rrfSurvived`：该候选是否挺过簇截断 / 去重进入最终结果；
- `finalScore`：重排后的最终分数（即当前 `chunk.score`）；
- `rerankFallback: true` 表示重排失败降级为原始排序。

当 `record-rrf-candidates: true` 时追加 `candidates` 数组，记录**全部** RRF 融合候选（含被截断丢弃的，只有 chunkId / collection / rrfScore / rrfRank / rrfSurvived，无 docName），便于回答"这个文档为什么没进结果"：

```json
{
  "candidates": [
    { "chunkId": "9b...", "rrfScore": 0.0151, "rrfRank": 6, "rrfCollection": "tax_kb_2024", "rrfSurvived": false }
  ]
}
```

## 6. 实现清单（改动点）

**新增（bootstrap 模块，`rag/core/retrieve/audit/` 包）：**

1. `SearchAuditLogProperties` —— 配置类（§3）。
2. `SearchAuditRecorder`（`@Component`）—— 单例：持有配置 + 文件写入逻辑（仿 `AiLogService`，按日文件追加 JSON）；
   `SearchAudit begin()`（disabled 返回 null）、`write(audit, ...)` 用 Jackson 拼 JSON 并写入 `<log-dir>/YYYYMMDD.log`。
3. `SearchAudit` —— 每次检索一个实例：
   - `ConcurrentLinkedQueue<RrfCandidate> candidates`（recordRrfCandidates=false 时也可只留最终结果，见边界）；
   - `volatile String rerankModel`、`volatile boolean rerankFallback`；
   - 方法：`addRrfCandidate(chunkId, collection, score, rank)`、`markRerank(model)`、`markRerankFallback()`、`finish(query, kbNames, topK, chunks)`。

**改动：**

4. `SearchContext` —— 新增可选字段 `SearchAudit searchAudit`（builder + getter，默认 null）。
5. `MultiChannelRetrievalEngine` —— `retrieveKnowledgeChannels` 增加带 `SearchAudit` 的 overload（旧签名委托传 null，不影响既有调用与单测）；`buildSearchContext` 透传。
6. `RrfHybridChannel.search()` —— `RrfMerger.merge()` 返回后逐条 `addRrfCandidate(...)`。
7. `RerankPostProcessor.process()` / `executeRerank()` —— `markRerank(...)` / `markRerankFallback()`。
8. `RetrievalEngine.doRetrieve()` —— 开头 `SearchAudit audit = recorder.begin()`；把 audit 传进 `retrieveKnowledgeChannels`；用可重赋值的局部 `chunks` + `finally` 保证所有出口（含空结果早退）都调用 `audit.finish(...)`。

## 7. 边界与降级

- **无 RRF**（hybrid 关闭、走单一向量/关键词通道）：`candidates` 为空，`rrfScore`/`rrfRank` 为 null，`finalScore` 为通道原始分，仍输出一行便于排查。
- **零召回**：仍输出一行（`chunks` 空），便于确认"是没召回还是被截断/过滤"。
- **重排降级 / 未配置**：`rerankFallback` 或 `rerankModel=null` 标识，顺序即原始顺序。
- **Agent 多次检索**：每次 `doRetrieve` 独立 `searchId`、独立一行，便于按对话时间线对照。
- **多知识库**：`kbNames` 列表 + 每条 `rrfCollection` 区分。
- **文件写入失败**：仅 `log.warn`，不影响检索主流程（审计日志为旁路，永不阻塞/降级检索）。

## 8. 测试

- 单元测试 `SearchAuditTest`：begin→addRrfCandidate→markRerank→finish 输出为合法 JSON 且关键字段齐全；`enabled=false` 时 `begin()` 返回 null；
- 单元/集成：`RrfHybridChannel` 注入 audit 后 `candidates` 数量 = RRF 融合输出条数；`RetrievalEngine` 端到端验证 `finish` 记录含 docName；
- 编译与格式：`./mvnw -q compile -pl bootstrap -am -o`、`./mvnw -q spotless:check -pl bootstrap -o`（前端无改动）。

## 9. 明确不做（边界）

- 不落库（如需可复用 `t_rag_trace_node.extra_data` 或新建表，另行设计）；
- 不改 `RetrievedChunk` DTO（审计数据走独立缓冲，避免污染检索产物）；
- 不记录 MCP 上下文 / Prompt 拼装内容；
- `candidates` 不反填 docName（避免开启时多一次批量查询；如确需，作为可选增强）。

## 10. 待确认问题（已确认）

| # | 问题 | 结论 |
|---|---|---|
| 1 | 输出载体 | **独立文件**（`logs/rag-search/YYYYMMDD.log`） |
| 2 | `record-rrf-candidates` | **开**（默认 `true`） |
| 3 | 是否记录 chunk 正文 | **记录**（`include-chunk-text` 默认 `true`） |
| 4 | `searchId` 粒度 | **符合**（每次检索一行） |
