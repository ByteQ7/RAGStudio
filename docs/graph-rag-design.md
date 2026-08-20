# Graph RAG 设计方案（实体关系感知检索）

> 状态：**设计稿（未实施）** · 适用范围：RAGStudio 全栈（bootstrap / infra-ai / frontend / SQL）
> 目标：在现有"向量 + 关键词 + RRF 融合"检索之上，叠加**实体—关系图谱**检索能力，使系统能回答
> "X 和 Y 是什么关系"、"谁的上级是谁"、"X 涉及哪些环节" 等**关系型/路径型**问题，并提升多跳事实类问题的召回。

---

## 1. 背景与问题

### 1.1 现有检索的不足

当前链路（`docs/multi-channel-retrieval.md`）：

```
查询改写 → KB 语义选库 → RrfHybridChannel（向量 pgvector + 关键词 pg_trgm，per-KB RRF）
        → 去重 → 语义裁剪 → Rerank → 上下文格式化 [^chunk_N] → Agent 生成
```

三个结构性缺陷：

| 缺陷 | 举例 | 根因 |
|------|------|------|
| **关系断裂**：实体间关系分散在不同 chunk，向量检索按"语义相似"召回，无法跨 chunk 串联关系 | "财务部总监向谁汇报？"（A 在文档1，B 在文档2） | chunk 之间无结构关联 |
| **多跳失败**：A→B→C 的三跳事实，单次检索只能命中其中一跳 | "报销流程最后要经过哪个岗位审批？" | 无图遍历能力 |
| **聚合/全局问题失效**：问"整个公司有多少种假期类型"这类需要跨文档聚合的问题，向量检索只会召回最相似的少数几块 | "公司所有休假类型汇总" | 无主题级（社区级）索引 |

### 1.2 方案目标

1. **入库侧**：新增图抽取能力（LLM 抽取实体与关系，结构化落库），随文档增量维护、幂等可重跑；
2. **检索侧**：新增图谱检索通道，支持 **局部检索**（实体锚定 + K 跳子图展开）与 **全局检索**（社区摘要，可选）；
3. **体验侧**：图谱结果与原链路无缝融合（RRF / 去重 / Rerank / 引用 [^chunk_N] 全部复用），前端提供图谱可视化与实体管理；
4. **工程侧**：零新增基础设施（仅 PostgreSQL），配置开关可灰度，故障自动降级，成本可控。

---

## 2. 方案选型

### 2.1 存储选型对比

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| **PostgreSQL 原生表 + 递归 CTE** | 零新增基础设施；与现有 pgvector / MyBatis-Plus / 事务 / 权限体系完全一致；数据可 JOIN、可审计 | 百万级边以上性能下降（本项目为内部知识库，规模在万级边，远未触及） | ✅ **采用** |
| Neo4j | 图遍历最强、生态成熟 | 新增独立基础设施（部署/运维/备份/鉴权），与现有 docker 编排割裂 | 备选（未来规模再评估） |
| Apache AGE | PG 扩展，Cypher 语法 | PG16 兼容性不稳、社区活跃度一般、无法与现有 ORM/事务无缝整合 | 不采用 |
| LightRAG / Microsoft GraphRAG 整体引入 | 开箱即用 | 是 Python 独立服务（同语义高亮），LLM/存储自成一套，与现有 Agent/追踪/知识库体系割裂 | **借鉴其算法思路，原生实现** |

**结论**：采用 **PostgreSQL 原生图存储**（实体表 + 关系表 + 递归 CTE 遍历），借鉴 Microsoft GraphRAG 的
"局部检索（local search）"与"社区摘要（global search）"、LightRAG 的"实体—文本关联"思路，全部原生实现。

### 2.2 架构决策要点

- **图谱按知识库（kb_id）隔离**：与 `collection_name` 对齐，天然支持多租户与选库；
- **图节点与 Chunk 双向关联**：关系携带 `source_chunk_id` 证据，图检索结果可以**映射回原 chunk**，
  从而复用整套下游（去重/Rerank/裁剪/引用 [^chunk_N]），这是本方案与"独立返回三元组"类方案的关键差异；
- **抽取结果按 chunk 缓存**（`content_hash`），文档重导/重建图时零 LLM 成本；
- **新管线节点 + 新检索通道**：完全沿用现有扩展机制（`IngestionNode` / `SearchChannel` 接口），不改动既有代码主流程。

---

## 3. 总体架构

```
┌─────────────────────────── 入库侧（构建） ───────────────────────────┐
│  Fetcher → Parser → Chunker → Enricher → Indexer                      │
│                                   ↓                                    │
│                    【新增】GraphExtractorNode（管线节点）                │
│                        ├─ LLM 抽取实体/关系（按 chunk，JSON Schema）      │
│                        ├─ 实体规范化 + KB 内去重（别名表）                │
│                        ├─ 落库 t_graph_entity / t_graph_relation        │
│                        └─ 抽取结果按 content_hash 缓存，幂等重跑          │
└──────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────── 检索侧（查询） ───────────────────────────┐
│  RrfHybridChannel（per-KB 并行）                                        │
│    ├─ 向量检索（已有）      ─┐                                          │
│    ├─ 关键词检索（已有）     ─┼→ RRF 融合 → 簇感知截断 → 全局 TopK       │
│    └─ 【新增】GraphSearch    ─┘    （图谱通道返回“命中关系的源 chunk”）    │
│         ├─ 查询实体识别（LLM 抽取 / trgm 兜底 / 实体向量兜底）            │
│         ├─ K 跳子图展开（递归 CTE，双向，度数/深度上限）                  │
│         ├─ 子图评分 + 三元组渲染                                        │
│         └─ 映射回源 chunk（带图谱证据 metadata）                         │
│                                                                        │
│  【可选】GlobalGraphChannel：社区摘要 → 全局性问题                        │
└──────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
        去重 → 语义裁剪 → Rerank → 格式化 [^chunk_N]（全部复用）
```

**关键点**：图谱通道的输出是 `List<RetrievedChunk>`（子图中命中的关系所指向的源 chunk，附图谱证据），
与向量/关键词通道的产物类型一致，RRF 融合、去重、Rerank、裁剪、引用编号全部天然兼容。

---

## 4. 数据模型（SQL DDL）

新增 6 张表（追加到 `resources/database/V2/schema_pg.sql`，或用 `resources/database/V3/` 增量文件）。

### 4.1 实体表 `t_graph_entity`

```sql
CREATE TABLE t_graph_entity (
    id              VARCHAR(64) NOT NULL PRIMARY KEY,        -- Snowflake
    kb_id           VARCHAR(64) NOT NULL,                    -- 知识库隔离
    canonical_name  VARCHAR(256) NOT NULL,                   -- 规范化名称（合并/去重键）
    display_name    VARCHAR(256) NOT NULL,                   -- 展示名（首次出现原文）
    entity_type     VARCHAR(64) NOT NULL DEFAULT 'ENTITY',   -- PERSON/ORG/DEPT/PRODUCT/ROLE/PROCESS/DOC/OTHER
    description     TEXT,                                    -- LLM 抽取的一句话描述
    aliases         JSONB NOT NULL DEFAULT '[]',             -- 别名数组（含归一化别名）
    extra           JSONB,                                   -- 属性（扩展预留）
    created_by      VARCHAR(64),
    update_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_graph_entity_kb_name UNIQUE (kb_id, canonical_name)
);
CREATE INDEX idx_graph_entity_kb ON t_graph_entity (kb_id);
CREATE INDEX idx_graph_entity_type ON t_graph_entity (kb_id, entity_type);
```

> 说明：`(kb_id, canonical_name)` 唯一约束即"实体消歧"的落点——同名的不同写法在抽取/合并阶段被归一到同一个
> `canonical_name`，自然成为同一节点。**不做** 基于 embedding 的自动合并（避免错误合并），见 §8。

### 4.2 关系表 `t_graph_relation`

```sql
CREATE TABLE t_graph_relation (
    id               VARCHAR(64) NOT NULL PRIMARY KEY,
    kb_id            VARCHAR(64) NOT NULL,
    source_entity_id VARCHAR(64) NOT NULL,
    target_entity_id VARCHAR(64) NOT NULL,
    predicate        VARCHAR(128) NOT NULL,                 -- 关系谓词（如 汇报给/负责/属于/审批）
    direction        SMALLINT NOT NULL DEFAULT 1,           -- 1=有向（source→target）0=无向（对称关系）
    weight           FLOAT NOT NULL DEFAULT 1.0,            -- 聚合权重（重复证据累加）
    evidence         TEXT,                                  -- 证据原文（截断到 200 字符）
    source_chunk_id  VARCHAR(64),                           -- 证据 chunk（回链 chunk 检索体系）
    doc_id           VARCHAR(64),                           -- 来源文档（级联清理用）
    extra            JSONB,
    create_time      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_graph_relation UNIQUE (kb_id, source_entity_id, target_entity_id, predicate)
);
CREATE INDEX idx_graph_rel_src  ON t_graph_relation (source_entity_id);
CREATE INDEX idx_graph_rel_tgt  ON t_graph_relation (target_entity_id);
CREATE INDEX idx_graph_rel_kb   ON t_graph_relation (kb_id);
CREATE INDEX idx_graph_rel_doc  ON t_graph_relation (kb_id, doc_id);       -- 文档删除级联
CREATE INDEX idx_graph_rel_chunk ON t_graph_relation (source_chunk_id);    -- chunk 删除级联
```

> `(kb_id, source, target, predicate)` 唯一约束保证同谓词同向不重复；重复出现时 `weight` 累加（证据增强）。
> 反向遍历需求由查询侧 UNION 反边实现，不冗余存储反向行。

### 4.3 抽取结果缓存表 `t_graph_extraction`（幂等/免重算）

```sql
CREATE TABLE t_graph_extraction (
    id             VARCHAR(64) NOT NULL PRIMARY KEY,
    kb_id          VARCHAR(64) NOT NULL,
    doc_id         VARCHAR(64) NOT NULL,
    chunk_id       VARCHAR(64) NOT NULL,
    chunk_content_hash VARCHAR(64) NOT NULL,               -- 与 t_knowledge_chunk.content_hash 对应
    entity_json    JSONB,                                  -- [{name, type, description}]
    relation_json  JSONB,                                  -- [{source, target, predicate, evidence}]
    status         VARCHAR(16) NOT NULL DEFAULT 'DONE',    -- DONE/FAILED/SKIPPED
    model_id       VARCHAR(128),                           -- 生成所用模型（换模型需失效重抽）
    duration_ms    INTEGER,
    error_message  TEXT,
    create_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_graph_extraction_chunk UNIQUE (chunk_id)
);
```

> **幂等核心**：重新入库同一 chunk（`content_hash` 未变）→ 直接复用缓存，零 LLM 调用；
> chunk 内容变更 → 只重抽该 chunk 关联的子图（增量更新）。`model_id` 变更时全部失效重抽（模型升级场景）。

### 4.4 实体向量表（可选，兜底通道）`t_graph_entity_vector_{dim}`

复用"按维度分表"的既有模式，由 `PgVectorStoreAdmin` 风格的管理器动态创建：

```sql
CREATE TABLE t_graph_entity_vector_1536 (
    id             VARCHAR(64) PRIMARY KEY,        -- = t_graph_entity.id
    kb_id          VARCHAR(64) NOT NULL,
    canonical_name VARCHAR(256) NOT NULL,
    display_name   VARCHAR(256) NOT NULL,
    entity_type    VARCHAR(64),
    description    TEXT,
    embedding      vector(1536),
    metadata       JSONB                           -- 别名、doc 来源等
);
-- HNSW(embedding vector_cosine_ops) + GIN(metadata)，规则同 t_knowledge_vector_{dim}
```

> 可选启用：实体数量大、LLM 实体识别不可用时的兜底匹配通道。默认 **不启用**（省 embedding 成本）。

### 4.5 社区表（可选，全局检索用）`t_graph_community`

```sql
CREATE TABLE t_graph_community (
    id           VARCHAR(64) NOT NULL PRIMARY KEY,
    kb_id        VARCHAR(64) NOT NULL,
    community_id VARCHAR(64) NOT NULL,
    level        INTEGER NOT NULL DEFAULT 1,              -- 社区层级（LCC 分层）
    summary      TEXT,                                    -- LLM 生成的社区摘要（map-reduce）
    entity_count INTEGER NOT NULL DEFAULT 0,
    entity_ids   JSONB,                                   -- 成员实体 id 列表
    build_id     VARCHAR(64),                             -- 所属构建版本
    create_time  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_graph_community_kb ON t_graph_community (kb_id, community_id);
```

### 4.6 构建任务日志 `t_graph_build_log`

```sql
CREATE TABLE t_graph_build_log (
    id             VARCHAR(64) NOT NULL PRIMARY KEY,
    kb_id          VARCHAR(64) NOT NULL,
    trigger_type   VARCHAR(16) NOT NULL,       -- DOC(单文档增量)/KB(全库重建)/SCHEDULE
    doc_id         VARCHAR(64),
    status         VARCHAR(16) NOT NULL,       -- RUNNING/SUCCESS/FAILED
    entity_added   INTEGER DEFAULT 0,
    entity_merged  INTEGER DEFAULT 0,
    relation_added INTEGER DEFAULT 0,
    relation_removed INTEGER DEFAULT 0,
    llm_calls      INTEGER DEFAULT 0,          -- 成本统计
    duration_ms    BIGINT,
    error_message  TEXT,
    create_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

---

## 5. 入库侧设计：GraphExtractorNode

### 5.1 定位与接入

新增管线节点 `GraphExtractorNode`（实现 `IngestionNode`，`getNodeType() = "graph_extractor"`，
`@Component` 自动注册，注册表在 `IngestionEngine` 构造器收集），默认位置：**ChunkerNode 之后、IndexerNode 之前**。
既有 CHUNK 模式（`KnowledgeDocumentServiceImpl.persistChunksAndVectors`）不走管线，需在 `persistChunksAndVectors`
成功后同步调用 `GraphExtractionService.extractForDocument(docId, chunks)`（见 §5.6）。

### 5.2 抽取流程

```
GraphExtractorNode.execute(context)
  ├─ 1. 前置检查：kb 图开关开启？chunk 非空？否则 NodeResult.skip
  ├─ 2. 模型解析：GraphExtractorSettings.modelId → LLMService.chat（复用模型路由/熔断/fallback）
  ├─ 3. 逐 chunk 并行（线程池，限并发 4，超时 30s/chunk）：
  │       a. t_graph_extraction 命中且 content_hash 一致 → 直接复用缓存（跳过 LLM）
  │       b. IMAGE chunk / 纯图片块 → 记 SKIPPED（不抽）
  │       c. 调用抽取 Prompt（§5.3）→ 解析 JSON → JSON Schema 校验 → 失败重试 1 次 → 仍失败记 FAILED
  ├─ 4. 实体规范化（§5.4）：清洗 → KB 内合并到 canonical_name
  ├─ 5. 事务内落库：
  │       a. upsert t_graph_entity（新实体插入，旧实体补别名）
  │       b. upsert t_graph_relation（同谓词同向 weight+1，证据截断 200 字符）
  │       c. upsert t_graph_extraction（缓存）
  ├─ 6. 写 t_graph_build_log（entity_added/merged、relation_added、llm_calls、duration）
  └─ 7. 异常兜底：单 chunk 失败不阻塞整文档；图写入失败记录告警但不回滚文档状态（图可重建）
```

### 5.3 抽取 Prompt（`ingestion/prompt/GraphExtractionPromptManager.java`）

参考 Microsoft GraphRAG 的实体抽取提示词设计，约束：

```
系统提示：
- 你是知识图谱抽取器。从文本中抽取「命名实体」与「实体间的关系」。
- 实体类型限定枚举：PERSON / ORG / DEPT / ROLE / PRODUCT / PROCESS / SYSTEM / DOC / OTHER
- 关系谓词使用动词短语（如：汇报给、负责、属于、审批、包含、位于），禁止使用无意义的 is/有/是。
- 每个实体：name（原文）、type、description（≤ 40 字）。
- 每条关系：source / target（必须引用上一步输出中的实体 name）、predicate、evidence（原文中支持该关系的句子，原样摘录，≤ 200 字）。
- 只抽取明确陈述的事实，禁止推断与猜测。
- 输出严格 JSON（无 markdown 代码块）：
  {"entities":[{"name":"","type":"","description":""}],
   "relations":[{"source":"","target":"","predicate":"","evidence":""}]}
- 每个 chunk 实体不超过 {max_entities_per_chunk} 个、关系不超过 {max_relations_per_chunk} 条。

用户消息：<chunk 内容>
```

参数：temperature=0.1（确定性优先），topP=0.3。输出经 `JsonResponseParser`（复用 EnricherNode 的解析器）解析，
再经 `GraphSchemaValidator` 校验（缺失字段/非数组/自环/空名 → 修复重试一次，重试仍失败则废弃该 chunk 抽取）。

### 5.4 实体规范化与 KB 内去重（`GraphEntityNormalizer`）

| 步骤 | 规则 |
|------|------|
| 清洗 | 去首尾空白、全角→半角、统一引号、去除枚举前后缀（"（部门）"等） |
| 别名生成 | 保留原文名 + 清洗名 + 简称（"人力资源部"→"人力资源"→"HR"）；英文大小写折叠 |
| 归一 | `canonical_name = 清洗后全名`；别名并入 `aliases` JSONB |
| 冲突处理 | `(kb_id, canonical_name)` 冲突 → 复用已有实体，仅把新别名/描述并入；实体 type 冲突以首次为准，description 追加 |

> **不做自动 embedding 合并**：同名异写靠规范化 + 别名；近似名（"信息部"/"信息化部"）默认**保留为两个节点**，
> 由管理端人工合并（§9），避免错误合并污染图谱。可选开启"疑似重复实体检测"（实体向量余弦 > 0.92 标红，不自动合）。

### 5.5 图谱构建模式（管理端触发）

- **单文档增量**：文档入库/重导/内容变更时触发（走 §5.6）；
- **知识库全量重建**：管理端手动触发 → 清空该 kb 图 → 按文档顺序逐文档抽取（复用 extraction 缓存）→ 可选社区计算；
- **定时重建**（可选）：`@Scheduled` + 现有调度表模式，不做默认开启。

### 5.6 与现有文档生命周期集成（`KnowledgeDocumentServiceImpl`）

| 生命周期事件 | 现有行为 | 新增行为 |
|--------------|----------|----------|
| 文档入库/重新分块（`persistChunksAndVectors`） | DB 事务写 chunk → 事务外写向量 | 成功后 `graphExtractionService.extractForDocument(docId, chunks)` |
| 文档删除（`delete`） | 删 chunk → 删向量 → 删 S3 | 同事务追加：删该 doc 的 extraction 缓存 + 关系（`idx_graph_rel_doc`）+ 清理无入边/出边的孤立实体 |
| chunk 内容编辑（`KnowledgeChunkServiceImpl.update`） | 更新行 + upsert 向量 | 追加：删除该 chunk 派生关系 → 用新内容重抽该 chunk 图（content_hash 变化） |
| chunk 启/禁用 | 增删向量 | 禁用：删该 chunk 派生关系并清理孤立实体；启用：重抽该 chunk |
| 批量启停（≤500） | 事务包裹 | 同上，批量后统一清理孤立实体 |

> 孤立实体清理采用"引用计数"：实体没有 relation 且不在任何 extraction 的 entity_json 中 → 删除（含实体向量行）。

---

## 6. 检索侧设计：GraphRetrievalChannel（局部检索）

### 6.1 通道注册

```java
@Component
public class GraphRetrievalChannel implements SearchChannel {
    // getName()="GraphLocal", getType()=SearchChannelType.GRAPH（新增枚举）
    // getPriority()=10（混合通道内部排序用）
}
```

在 `RrfHybridChannel.search` 的 per-KB 内部，为每个 query 并行追加一路：
`graphFuture = CompletableFuture.supplyAsync(() -> safeSearch(graphChannel, singleCtx), executor)`，
与向量、关键词的 future 一起 `allOf` 后 RRF 融合。**改动点仅 3~5 行**，融合/截断/超时逻辑全部复用。

`isEnabled(context)` 条件：
- `rag.graph.retrieval.enabled=true` 且 `rag.graph.enabled=true`；
- 目标 KB 已构建图谱（`t_graph_relation` 有该 kb 数据）；
- LLM 实体识别可用（熔断/健康检查，见 §11 降级）；
- 问题为**事实/关系型**（规则预判，§6.3 可选加速）。

### 6.2 检索流程（本地检索 / Local Search）

```
GraphRetrievalChannel.search(context)  [@RagTraceNode("图谱检索", "GRAPH_RETRIEVE")]
  ├─ 1. 查询实体识别（§6.3）→ 命中实体列表 [{entityId, score}]
  ├─ 2. 无命中 → 返回空结果（不参与 RRF，不影响原链路）
  ├─ 3. K 跳子图展开（§6.4）→ 节点集 + 边集（带深度/分数）
  ├─ 4. 子图评分（§6.5）→ 每个"关系三元组"打分
  ├─ 5. 三元组 → 源 chunk 映射：按 source_chunk_id 分组聚合
  │      （证据缺失的纯结构三元组 → 挂到 description 最相关的实体源 chunk；都没有则丢弃）
  ├─ 6. 构建输出 List<RetrievedChunk>：
  │      chunk.score = 该 chunk 关联三元组最高分（图谱分数，供 RRF 排序）
  │      chunk.metadata["graph_evidence"] = [{source, predicate, target, evidence, depth}]
  │      chunk.metadata["graph_hit"] = true
  └─ 7. 返回 SearchChannelResult(channelType=GRAPH, chunks)
```

### 6.3 查询实体识别（三路兜底）

| 优先级 | 方式 | 说明 | 成本 |
|--------|------|------|------|
| 1 | **LLM 抽取**（`GraphQueryEntityExtractor`） | 复用 §5.3 抽取提示词（仅 entities 输出），从查询中抽取 1~5 个实体 | 1 次 LLM 调用 |
| 2 | **trgm 匹配** | 无 LLM 或 LLM 空结果：对 `canonical_name`/`display_name`/`aliases` 做 `ILIKE`/`similarity` 匹配（复用 `PgRetrieverService.extractKeywords` 的滑窗思路） | 0 |
| 3 | **实体向量**（可选启用） | 查询 embed → 实体向量表余弦 topK，阈值 0.60 | 1 次 embedding |

> **查询类型预判**（可选加速，`GraphQueryTypeDetector`）：关系型问题（含"谁/哪个/之间/关系/向谁汇报"等强特征）
> 才做 LLM 实体抽取；纯描述性问题直接跳过 LLM 走 trgm，降低 QPS 峰值 LLM 成本。

### 6.4 K 跳子图展开（递归 CTE）

```sql
-- 有向查询：以命中实体为种子，双向展开 depth 层
WITH RECURSIVE reach AS (
    SELECT e.id AS entity_id, 0 AS depth, e.canonical_name
    FROM t_graph_entity e WHERE e.kb_id = ? AND e.id = ANY(?)
    UNION
    SELECT r.target_entity_id, x.depth + 1, e2.canonical_name
    FROM reach x
    JOIN t_graph_relation r ON r.source_entity_id = x.entity_id
    JOIN t_graph_entity  e2 ON e2.id = r.target_entity_id
    WHERE x.depth < ?            -- max_depth（默认 2）
    LIMIT ?                      -- 每层度数上限（默认 30）
)
SELECT ... FROM reach;
```

约束与边界：
- **双向**：正边 + 反边（`UNION` 两个方向，谓词方向由 `direction` 标注）；
- **度数上限**：每层 `LIMIT`（默认 30），防 hub 节点爆炸（"公司"类实体数千条边）；
- **深度上限**：`max_depth` 默认 2（3 跳以上收益衰减且 token 成本陡增）；
- **节点总数上限**：展开后节点数 > 200 → 按边权重截断，仅保留高分子图；
- **谓词过滤**（可选）：查询含关系词时按词法映射谓词子集（"汇报" → predicate LIKE '%汇报%'）；
- **自环/重复**：`UNION` 天然去重，路径去重由 (entity_id, depth) 保证。

### 6.5 子图评分

```
三元组分数 = 种子实体匹配分(0~1) × 深度衰减(1/(1+depth)) × 边权重(1 + 0.1×(weight-1)) × 谓词命中boost(1.2 若查询含谓词)
chunk 分数 = max(其关联三元组分数)
```

分数仅用于通道内排序 → RRF 融合时 rank 主导，绝对数值不跨通道比较，无标定负担。

### 6.6 三元组渲染（给 LLM 的结构化上下文）

图谱命中的 chunk 在 `DefaultContextFormatter` 输出时，附加"图谱证据"小节（保持 `[^chunk_N]` 编号连续）：

```
【图谱关系证据】
- [^chunk_3] 人力资源部 →负责→ 员工培训（"人力资源部负责新员工入职培训"）
- [^chunk_5] 财务部 →审批→ 报销单（"报销单需财务部总监审批"）
- [^chunk_5] 财务部总监 →汇报给→ 总经理
```

实现：`GraphContextFormatter` 在 `formatKbContext` 后追加该小节（仅当存在 `graph_hit` chunk），
三元组去重（同 (s,p,t) 只出一次）、条数上限 `max-context-triples`（默认 40）、按分数降序。
这样 LLM 看到的是**跨 chunk 拼接出的关系链**，回答引用 [^chunk_N] 走现有机制，无需任何 Agent 改动。

---

## 7. 全局检索设计（可选，Phase 2）

解决"聚合/全局性问题"（§1.1 缺陷 3），借鉴 Microsoft GraphRAG：

### 7.1 社区构建（后台任务）

1. 对 kb 图跑 **Louvain/Leiden 社区发现**（轻量实现：基于边的模块度贪心算法，节点数 ≤ 5 万规模毫秒级；或用现有 LLM 聚类替代——先采用贪心模块度，纯 Java 实现，无新依赖）；
2. 每社区生成 `t_graph_community` 记录（level 1）；
3. **map-reduce 摘要**：map 阶段——每社区把成员实体描述 + 关系三元组喂 LLM 生成社区摘要；reduce 阶段——level 1 摘要合并生成 level 2（可选多级）；
4. 全部走 `LLMService` + 现有模型路由，结果缓存（社区不变不重算）。

### 7.2 全局检索

1. 查询 → LLM 抽取**主题/关键词**（或直接 embedding）；
2. 社区筛选：关键词重叠 / 社区摘要 embedding 相似度 topK（默认 3 个社区）；
3. 命中社区摘要 → 按 chunk 引用编号映射回源 chunk（社区记录成员实体 → 实体来源 chunk）；
4. 与局部检索结果合并进同一通道输出（按分数降序）。

> 全局检索成本较高（每社区一次 LLM 摘要调用），默认关闭（`rag.graph.community.enabled=false`），
> 文档级抽取完成且管理端显式触发时可用。

---

## 8. 实体管理（人工纠错闭环）

| 操作 | 行为 |
|------|------|
| **合并实体** | 选择"保留实体 + 被合并实体列表" → 事务内：关系重指向（`UPDATE source/target`）→ 删被合并实体 → 别名并入保留实体 → 清实体向量行 → 失效相关 extraction 缓存 |
| **拆分实体** | 逆操作（从合并历史反推，简单实现：仅支持"别名→新实体"拆分，即把实体 A 的别名 x 提升为新实体，并迁移指向 x 的关系） |
| **改别名/名称** | 更新 canonical_name 需先查重（冲突则提示合并） |
| **删实体** | 连带删关系 + 孤立清理 |
| **疑似重复列表** | 实体向量余弦 > 0.92 的候选对列表（`/admin/entities/duplicates`），标红展示，人工决定 |

所有管理操作记录 `t_graph_build_log` 或审计日志，支持撤销（操作前快照）。

---

## 9. 前端设计

### 9.1 知识库"图谱"页（新增 Tab，`KnowledgeDocumentsPage` 同级）

- **图谱总览**：实体数/关系数/抽取覆盖率（已抽 chunk / 总 chunk）/最后构建时间；
- **构建操作**：全量重建按钮（二次确认）、单文档增量重建、构建日志列表（`t_graph_build_log`）；
- **图可视化**：基于现有依赖 **mermaid**（`package.json` 已有 `mermaid@^11`），以 `flowchart LR` 渲染子图；
  - 渲染上限 200 节点（超出按分数截断并提示）；
  - 节点按 entity_type 着色，边标签为 predicate；
  - 点击实体 → 详情抽屉（描述/别名/关系列表/来源 chunk）；
  - 支持按实体名/类型搜索过滤后渲染；
- **实体管理**：列表（搜索/类型过滤/分页）、合并、拆分、别名编辑、疑似重复列表。

### 9.2 聊天页引用面板增强

- `citation` SSE 事件结构追加可选字段：`graphEvidence`（三元组列表）+ `graphHit: true`；
- 前端引用卡片显示"图谱命中"角标，展开显示三元组证据；
- 消息流中图谱证据小节按现有 markdown 渲染即可（无需新组件）。

### 9.3 管理 API（`GraphAdminController`，`@SaCheckRole("admin")`）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/graph/kb/{kbId}/overview` | 统计概览 |
| POST | `/admin/graph/kb/{kbId}/rebuild` | 全量重建（异步任务，返回 taskId） |
| POST | `/admin/graph/kb/{kbId}/community/build` | 社区构建（Phase 2） |
| GET | `/admin/graph/kb/{kbId}/entities` | 实体分页（搜索/类型过滤） |
| POST | `/admin/graph/entities/merge` | 合并实体 |
| POST | `/admin/graph/entities/split` | 拆分实体 |
| GET | `/admin/graph/kb/{kbId}/graph` | 子图数据（含 `?focusEntityId=` 定点展开，供 mermaid 渲染） |
| GET | `/admin/graph/kb/{kbId}/build-logs` | 构建日志 |
| GET | `/admin/graph/kb/{kbId}/duplicates` | 疑似重复实体（可选） |

---

## 10. 配置项（`application.yaml`，前缀 `rag.graph`）

```yaml
rag:
  graph:
    enabled: false                      # 总开关（默认关闭，灰度开启）
    extract:
      model-key: graph_extract          # t_default_model_config.config_key（新增键），缺省回退 chat
      max-entities-per-chunk: 30
      max-relations-per-chunk: 50
      temperature: 0.1
      timeout-ms: 30000
      parallel-limit: 4                 # 抽取并发度（LLM 调用）
    retrieval:
      enabled: true                     # 依赖 extract 图已建
      max-depth: 2                      # K 跳
      max-neighbors-per-hop: 30
      max-nodes: 200                    # 展开节点上限
      max-context-triples: 40           # 渲染给 LLM 的三元组上限
      query-entity-limit: 5
      enable-type-precheck: true        # 关系型问题预判（节省 LLM）
    entity:
      embedding-enabled: false          # 实体向量兜底通道
      embedding-dim: 1536
      merge-cosine-threshold: 0.92      # 疑似重复检测阈值（不自动合并）
    community:
      enabled: false                    # 全局检索（Phase 2）
      max-communities: 3
```

同时扩展 `t_default_model_config` 支持新 config_key：`graph_extract`（抽取/查询实体识别共用，可拆分 `graph_query`）。

---

## 11. 边界情况与降级策略

### 11.1 抽取侧

| 边界 | 处理 |
|------|------|
| LLM 返回非法 JSON | Schema 校验 → 修复提示重试 1 次 → 仍失败该 chunk 记 FAILED，不阻塞文档 |
| chunk 无实体 | 记 SKIPPED（缓存空结果，重跑不重复调用 LLM） |
| IMAGE chunk / 纯图片 | 跳过抽取 |
| chunk 内容超长 | 抽取前按 6000 字符截断（图谱抽取只需语义信息） |
| 实体/关系超上限 | 按出现顺序截断 + 日志记录 |
| LLM 熔断/不可用 | 抽取节点 skip + 告警；文档状态不受影响（图可后续重建） |
| 抽取自环 / 空谓词 | 校验层丢弃 |
| 谓词"名词化"（如"负责关系"） | 提示词约束 + 校验正则（`^[\\u4e00-\\u9fa5A-Za-z]{2,20}$` 排除"关系/属性"后缀） |

### 11.2 检索侧

| 边界 | 处理 |
|------|------|
| 查询抽不出实体（LLM 空/trgm 零命中） | 通道返回空，RRF 不受影响，纯向量/关键词兜底 |
| 图谱未构建 / 数据为空 | `isEnabled=false` |
| 子图展开超时/超节点上限 | 截断高分部分返回（渐进降级），不走异常 |
| 命中实体数 > query-entity-limit | 按分数取前 5 |
| 关系型问题但无关系命中 | 返回空，Agent 观察到的是普通检索结果 |
| 多 KB 场景 | 每 KB 独立展开（图谱按 kb_id 隔离），与向量/关键词 per-KB 融合一致 |
| 通道异常 | `safeSearch` 兜底空结果（既有机制），加 `GRAPH` trace 节点便于定位 |
| LLM 查询实体识别失败 | 降级 trgm（第 2 路）→ 实体向量（第 3 路，若启用） |
| 证据 chunk 被禁用/删除 | 级联删除关系（§5.6），引用映射自然失效，无悬挂引用 |

### 11.3 一致性与并发

| 边界 | 处理 |
|------|------|
| 文档删除与图查询并发 | 图写入与文档删除同事务（RocketMQ 事务消息模式复用），查询读已提交快照 |
| 全量重建与增量并发 | 重建任务用现有 `t_knowledge_document_schedule` 的锁机制（`lock_owner/lock_until`）防并发；重建期间查询继续服务旧图 |
| 图数据与 chunk 数据不一致 | extraction 缓存以 content_hash 为锚，chunk 变更必然触发关系重抽（§5.6） |
| 事务长度 | 图写入限制单批 ≤ 500 关系/次，分批提交（参照批量启停 chunk 的既有模式） |

### 11.4 成本与性能

| 项 | 控制手段 |
|----|----------|
| LLM 抽取成本 | 抽取缓存（content_hash）+ 每 chunk 单次调用 + 并发上限 + 开关默认关闭 |
| 查询成本 | 关系型问题预判（§6.3）减少无效 LLM 调用；trgm 兜底 0 成本 |
| 检索延迟 | 图通道与向量/关键词并行（同一 executor）；递归 CTE 走索引（`idx_graph_rel_src/tgt`）；全程受 per-KB 25s 超时约束 |
| 图膨胀 | 每 kb 实体上限（默认 5 万）与关系上限（默认 20 万）配置，超限告警并停止增量抽取 |
| PG 索引维护 | 关系表按 doc_id/chunk_id 索引支撑级联删除；无写放大问题 |

---

## 12. 实施阶段规划

### Phase 1：核心闭环（MVP）
1. `V3` SQL：6 张表 + 索引；
2. `GraphExtractorNode` + `GraphExtractionService` + 提示词/校验器/规范化器；
3. `GraphRetrievalChannel` + 查询实体识别（LLM + trgm 两路）；
4. `RrfHybridChannel` 接入图通道（3~5 行改动）+ `DefaultContextFormatter` 图谱证据小节；
5. 文档生命周期集成（入库/删除/改 chunk/启停）；
6. 管理 API + 前端图谱页（mermaid 可视化 + 实体管理 + 构建日志）；
7. 评测：在现有 65 题评测集上追加 ~20 道关系型题目，验证准确率与延迟（目标：关系型问题 ≥85%，整体不劣化）。

### Phase 2：增强
- 实体向量兜底通道 + 疑似重复检测（不自动合并）；
- 社区构建 + 全局检索通道（`GlobalGraphChannel`）；
- 关系型问题预判加速（`GraphQueryTypeDetector`）。

### Phase 3：打磨
- 实体合并/拆分操作审计与撤销；
- 图谱检索命中热力图入 Dashboard（`t_rag_trace_node` 已有 GRAPH 节点，聚合即可）；
- 图谱增量定时重建（调度表复用）。

---

## 13. 风险与应对

| 风险 | 影响 | 应对 |
|------|------|------|
| LLM 抽取质量不稳定（实体名漂移/谓词混乱） | 图质量下降 | 规范化 + 唯一键去重 + 人工合并闭环 + 评测集回归；抽取模型可独立配置（换强模型） |
| 图谱检索引入噪声（关系误命中） | 回答质量下降 | RRF 融合天然压低弱信号；图谱证据以"附加小节"形式呈现，不替代主上下文；`graph.enabled` 开关可秒退 |
| 大文档/多文档构建耗时 | 入库变慢 | 抽取异步化（RocketMQ 消费，复用现有消息链路）、并发 4、缓存幂等 |
| PG 递归 CTE 性能 | 检索变慢 | 节点规模受控（万级边内）、索引齐备、度数/深度上限、超时截断 |
| 与现有功能回归 | 破坏现有 97% 准确率 | 默认关闭；图通道独立可摘除；全套现有后处理/引用链路不变 |

---

## 14. 代码落地清单（对应本设计）

| 模块 | 新增文件（建议） |
|------|------------------|
| SQL | `resources/database/V3/graph_pg.sql`（含 schema + 索引 + 注释） |
| 实体/映射 | `bootstrap/.../graph/dao/entity/GraphEntityDO / GraphRelationDO / GraphExtractionDO / GraphCommunityDO / GraphBuildLogDO` + Mapper |
| 抽取 | `graph/service/GraphExtractionService`、`graph/service/impl/GraphExtractionServiceImpl`、`graph/extract/GraphEntityNormalizer`、`graph/extract/GraphSchemaValidator`、`ingestion/prompt/GraphExtractionPromptManager`、`ingestion/node/GraphExtractorNode` |
| 检索 | `rag/core/retrieve/channel/GraphRetrievalChannel`、`graph/query/GraphQueryEntityExtractor`、`graph/query/GraphSubgraphExpander`、`graph/query/GraphSubgraphScorer`、`rag/core/prompt/GraphContextFormatter` |
| 管理 | `admin/controller/GraphAdminController`、`admin/service/impl/GraphAdminServiceImpl`、`rag/config/GraphProperties` |
| 前端 | `frontend/src/services/graphService.ts`、`pages/.../GraphPage.tsx`（mermaid 渲染 + 实体管理）、聊天引用面板增强 |
| 文档 | `docs/graph-rag.md`（用户手册，参照 `multi-channel-retrieval.md` 风格） |

---

## 附：与主流方案对标

| 能力 | Microsoft GraphRAG | LightRAG | 本方案 |
|------|--------------------|----------|--------|
| 图存储 | Azure Cosmos/Neo4j | 自有存储 | PostgreSQL 原生（零新增基础设施） |
| 实体抽取 | LLM 按 chunk | LLM 按 chunk | LLM 按 chunk + content_hash 缓存 + 幂等重跑 |
| 局部检索（实体锚定） | 有 | 有（low-level） | 有（递归 CTE + 双向 K 跳） |
| 全局检索（社区摘要） | 有（Leiden + map-reduce） | 有（high-level 主题） | 有（可选 Phase 2，模块度贪心 + map-reduce） |
| 与向量检索融合 | 独立两套 | 图/向量混合打分 | **RRF 融合进现有多通道链路**，复用去重/Rerank/引用 |
| 实体管理 | 无 | 无 | 合并/拆分/别名/疑似重复（管理端闭环） |
| 增量更新 | 弱 | 弱 | 文档级增量 + 缓存免重算 |
