# 数据摄入流水线详解

## 目录

- [一、什么是数据摄入流水线？](#一什么是数据摄入流水线)
- [二、核心概念](#二核心概念)
  - [流水线（Pipeline）](#流水线pipeline)
  - [任务（Task）](#任务task)
  - [上下文（IngestionContext）](#上下文ingestioncontext)
  - [节点结果（NodeResult）](#节点结果noderesult)
- [三、节点详解](#三节点详解)
  - [FetcherNode — 从数据源获取文档原始字节流](#fetchernode--从数据源获取文档原始字节流)
  - [ParserNode — 将原始字节解析为结构化文本](#parsernode--将原始字节解析为结构化文本)
  - [EnhancerNode — 整个文档级别的 AI 增强处理](#enhancernode--整个文档级别的-ai-增强处理)
  - [ChunkerNode — 将文本按策略切分为 Chunk](#chunkernode--将文本按策略切分为-chunk)
  - [EnricherNode — 对每个 Chunk 进行 AI 元数据富化](#enrichernode--对每个-chunk-进行-ai-元数据富化)
  - [IndexerNode — 将 Chunk 向量化并写入向量库](#indexernode--将-chunk-向量化并写入向量库)
- [四、IngestionEngine — 流水线执行引擎](#四ingestionengine--流水线执行引擎)
- [五、时序图：一份 PDF 的完整旅程](#五时序图一份-pdf-的完整旅程)
- [六、配置参考](#六配置参考)
- [七、扩展指南](#七扩展指南)

---

## 一、什么是数据摄入流水线？

数据摄入流水线（Ingestion Pipeline）是 RAGStudio 中负责将**原始文档**转化为**可检索的向量数据**的处理流程。它将复杂的文档处理过程拆解为若干独立的处理节点（Node），每个节点负责一个特定的处理步骤，节点之间通过 `nextNodeId` 形成链式调用关系。

**一句话概括：** 流水线 = 一组按顺序执行的处理节点；任务 = 流水线的一次具体执行实例。

### 典型流水线

```
Fetcher → Parser → Enhancer → Chunker → Enricher → Indexer
 (获取)    (解析)    (增强)     (分块)     (富化)     (向量化)
```

其中 Enhancer 和 Enricher 是可选的，一个最简流水线只需三个节点：

```
Fetcher → Parser → Chunker → Indexer
```

---

## 二、核心概念

### 流水线（Pipeline）

流水线是一条由多个处理节点按顺序组成的**链式模板**。每个节点通过 `nextNodeId` 指向下一个节点。

**数据结构：**

```java
PipelineDefinition {
    String id;                    // 流水线唯一标识
    String name;                  // 流水线名称
    String description;           // 描述
    List<NodeConfig> nodes;       // 节点配置列表
}

NodeConfig {
    String nodeId;                // 节点唯一标识（如 "fetcher-1"）
    String nodeType;              // 节点类型（fetcher/parser/chunker/enricher/enhancer/indexer）
    JsonNode settings;            // 节点参数（JSON 格式）
    JsonNode condition;           // 执行条件表达式（可选）
    String nextNodeId;            // 下一个节点 ID（形成链）
}
```

**条件执行：** 节点可配置 `condition` 条件，满足时执行，不满足时自动跳过。

**数据库表：** `t_ingestion_pipeline`（流水线主表）、`t_ingestion_pipeline_node`（节点配置表）

### 任务（Task）

任务是一条流水线的**一次具体执行**。创建任务时指定使用哪条流水线 + 文档来源，系统立即执行并记录日志。

**状态流转：**

```
PENDING → RUNNING → COMPLETED
                   ↘ FAILED
```

**数据库表：** `t_ingestion_task`（任务主表）、`t_ingestion_task_node`（节点执行记录表）

### 上下文（IngestionContext）

上下文是贯穿流水线执行全程的**数据总线**。每个节点从上下文中读取输入，处理后把结果写回上下文，供后续节点使用。

```
FetcherNode  ──→ rawBytes, mimeType
ParserNode   ──→ rawText, document
EnhancerNode ──→ enhancedText, keywords, questions
ChunkerNode  ──→ chunks (List<VectorChunk>)
EnricherNode ──→ chunks (元数据被富化)
IndexerNode  ──→ (读取 chunks，写入向量数据库)
```

**关键字段：**

| 字段 | 类型 | 写入节点 | 读取节点 |
|------|------|----------|----------|
| `rawBytes` | `byte[]` | FetcherNode | ParserNode |
| `mimeType` | `String` | FetcherNode | ParserNode |
| `rawText` | `String` | ParserNode | EnhancerNode, ChunkerNode |
| `enhancedText` | `String` | EnhancerNode | ChunkerNode |
| `keywords` | `List<String>` | EnhancerNode | — |
| `questions` | `List<String>` | EnhancerNode | — |
| `metadata` | `Map` | EnhancerNode | EnricherNode, IndexerNode |
| `chunks` | `List<VectorChunk>` | ChunkerNode | EnricherNode, IndexerNode |
| `logs` | `List<NodeLog>` | IngestionEngine | 持久化到 DB |

### 节点结果（NodeResult）

每个节点执行后返回四种结果之一：

| 结果 | 工厂方法 | 含义 | 流水线行为 |
|------|----------|------|-----------|
| ✅ 成功继续 | `NodeResult.ok("消息")` | 执行成功 | 继续执行下一个节点 |
| ⏭️ 跳过 | `NodeResult.skip("原因")` | 条件不满足，跳过 | 继续执行下一个节点 |
| ❌ 失败 | `NodeResult.fail(e)` | 执行出错 | 终止流水线，标记任务为 FAILED |
| 🛑 终止 | `NodeResult.terminate("原因")` | 执行成功但主动终止 | 终止流水线，标记任务为 COMPLETED |

---

## 三、节点详解

### FetcherNode — 从数据源获取文档原始字节流

**位置：** 流水线第一个节点

**功能：** 根据配置的文档来源类型（SourceType），从对应的存储介质中读取文档的原始字节流（`byte[]`），并自动识别 MIME 类型。

**输入：** `DocumentSource { type, location, fileName, credentials }`

**输出：** `context.rawBytes` — 文档的原始二进制数据、`context.mimeType` — 文档格式

#### 数据源策略（策略模式）

| 数据源类型 | 实现类 | 说明 | 凭证需求 |
|-----------|--------|------|---------|
| `file` | `LocalFileFetcher` | 读取服务器本地文件系统上的文件 | 无 |
| `url` | `HttpUrlFetcher` | 从 HTTP/HTTPS 链接下载文档 | Bearer Token（可选） |
| `feishu` | `FeishuFetcher` | 从飞书文档平台获取文档 | App ID + App Secret |
| `s3` | `S3Fetcher` | 从 S3 兼容对象存储（MinIO/RustFS）获取 | Access Key + Secret Key |

#### 前端配置

Fetcher 节点本身无额外配置参数。文档来源信息（类型、路径/URL、凭证）在**创建任务时**指定。

#### 幂等性

如果上下文中已有 `rawBytes` 数据（例如通过上传文件方式触发），FetcherNode 会自动跳过获取流程，避免重复 I/O。

#### 参考源码

- [FetcherNode.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/ingestion/node/FetcherNode.java)
- [DocumentFetcher.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/ingestion/strategy/fetcher/DocumentFetcher.java)
- [LocalFileFetcher.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/ingestion/strategy/fetcher/LocalFileFetcher.java)
- [HttpUrlFetcher.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/ingestion/strategy/fetcher/HttpUrlFetcher.java)
- [FeishuFetcher.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/ingestion/strategy/fetcher/FeishuFetcher.java)
- [S3Fetcher.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/ingestion/strategy/fetcher/S3Fetcher.java)
- [SourceType.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/ingestion/domain/enums/SourceType.java)
- [DocumentSource.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/ingestion/domain/context/DocumentSource.java)

---

### ParserNode — 将原始字节解析为结构化文本

**位置：** 流水线第二个节点

**功能：** 使用 Apache Tika 文档解析器，将 FetcherNode 获取的原始二进制字节解析为纯文本，同时提取文档的结构化元数据。

**输入：** `context.rawBytes`、`context.mimeType`

**输出：** `context.rawText` — 纯文本内容、`context.document` — 结构化文档对象

#### 支持的文档格式

| 类型 | 标识 | 文件后缀 |
|------|------|----------|
| PDF | `PDF` | `.pdf` |
| Word | `WORD` | `.doc`、`.docx` |
| Excel | `EXCEL` | `.xls`、`.xlsx` |
| PowerPoint | `PPT` | `.ppt`、`.pptx` |
| Markdown | `MARKDOWN` | `.md`、`.markdown` |
| 纯文本 | `TEXT` | `.txt` |
| 图片 | `IMAGE` | `.png`、`.jpg`、`.jpeg`、`.gif`、`.bmp`、`.webp` |

#### 配置：解析规则（Parser Rules）

```json
[
  {
    "mimeType": "PDF",      // 文档类型标识（支持 ALL 通配符）
    "options": {}            // 解析器额外选项（可选）
  }
]
```

- 不配置规则时，允许所有文件类型通过
- 配置规则后，只有匹配类型的文件才会被处理，不匹配则报错
- `mimeType: "ALL"` 或 `"*"` 表示允许所有类型
- 规则按顺序匹配，取第一个匹配项

#### 前端配置

JSON 文本区域，输入解析规则数组。示例：

```json
[{"mimeType":"PDF","options":{}}]
```

#### 参考源码

- [ParserNode.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/ingestion/node/ParserNode.java)
- [ParserSettings.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/ingestion/domain/settings/ParserSettings.java)

---

### EnhancerNode — 整个文档级别的 AI 增强处理

**位置：** ParserNode 之后，ChunkerNode 之前（可选节点）

**功能：** 对整个文档的文本进行 AI 驱动的增强处理。与 EnricherNode（逐块处理）不同，EnhancerNode 是在**文档级别**（document-level）进行整体处理。

**输入：** `context.rawText`（原始文本）

**输出：** 根据任务类型写入 `context.enhancedText`、`context.keywords`、`context.questions`、`context.metadata`

#### 支持的增强类型

| 类型 | 标识 | 输入来源 | 输出目标 | 说明 |
|------|------|----------|----------|------|
| **上下文增强** | `context_enhance` | `rawText` | `enhancedText` | 对原始文本进行格式修复、结构整理，提升语义理解。例如 PDF 解析后常有换行错乱、页眉页脚等问题，LLM 可自动修复 |
| **关键词提取** | `keywords` | `enhancedText` > `rawText` | `keywords` | 从文档中提取重要的关键词或短语，用于提升检索准确率 |
| **问题生成** | `questions` | `enhancedText` > `rawText` | `questions` | 基于文档内容生成可能被问到的问题，用于构建 QA 检索对 |
| **元数据提取** | `metadata` | `enhancedText` > `rawText` | `metadata` | 提取结构化元数据信息（作者、日期、分类、标签等） |

> **注意：** 关键词、问题生成、元数据提取优先使用 `enhancedText`（如果存在），否则使用 `rawText`。

#### 配置参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `modelId` | `String` | 用于增强处理的 LLM 模型 ID（如 `qwen-plus`），为空时使用默认模型 |
| `tasks` | `Array` | 要执行的增强任务列表 |

**每个任务包含：**

| 参数 | 类型 | 说明 |
|------|------|------|
| `type` | `String` | 增强类型：`context_enhance` / `keywords` / `questions` / `metadata` |
| `systemPrompt` | `String` | 系统提示词（可选，有默认值） |
| `userPromptTemplate` | `String` | 用户提示词模板（可选），支持 `{{text}}`、`{{content}}` 等变量替换 |

#### 前端配置

- **模型 ID**：文本输入框，指定 LLM 模型
- **增强任务**：可添加多个任务，每个任务选择类型并可选配自定义提示词

#### 参考源码

- [EnhancerNode.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/ingestion/node/EnhancerNode.java)
- [EnhancerSettings.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/ingestion/domain/settings/EnhancerSettings.java)
- [EnhanceType.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/ingestion/domain/enums/EnhanceType.java)

---

### ChunkerNode — 将文本按策略切分为 Chunk

**位置：** EnhancerNode（或 ParserNode）之后

**功能：** 将完整文本按指定策略切分为多个较小的文本块（Chunk），并为每个 Chunk 生成向量嵌入（embedding），用于后续的相似度检索。

**输入：** `context.enhancedText`（优先）或 `context.rawText`

**输出：** `context.chunks` — `List<VectorChunk>`，每个 Chunk 包含 `{content, embedding, index, metadata}`

#### 分块策略

| 策略 | 标识 | 说明 | 适用场景 |
|------|------|------|----------|
| **固定大小分块** | `fixed_size` | 按固定字符数切分，可配置重叠 | 通用场景，无特殊结构要求的文档 |
| **结构感知分块** | `structure_aware` | 保留 Markdown 等文档结构，智能切分 | Markdown 文档、有层级结构的文本 |

#### 配置参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `strategy` | `String` | `fixed_size` | 分块策略：`fixed_size` / `structure_aware` |
| `chunkSize` | `Integer` | `512`（fixed） / `1400`（structure） | 每个 Chunk 的目标大小（字符数） |
| `overlapSize` | `Integer` | `128`（fixed） / `0`（structure） | 相邻 Chunk 之间的重叠大小，用于保持上下文连贯性 |
| `separator` | `String` | — | 自定义分隔符（仅部分策略支持） |

**fixed_size 模式下：**
- `chunkSize=512, overlapSize=128` 表示每 512 字符切一块，相邻块有 128 字符重叠

**structure_aware 模式下：**
- 会自动检测标题、段落等结构边界
- `chunkSize` 表示目标字符数，实际切分时会尽量在结构边界处断开
- 另有 `maxChars`（默认 1800）和 `minChars`（默认 600）作为上下限约束

#### 嵌入生成

ChunkerNode 内部调用 `ChunkEmbeddingService.embed()` 为每个 Chunk 生成向量嵌入（embedding），维度由嵌入模型决定（通常为 1536 维）。这些嵌入向量是后续向量检索的基础。

#### 参考源码

- [ChunkerNode.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/ingestion/node/ChunkerNode.java)
- [ChunkerSettings.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/ingestion/domain/settings/ChunkerSettings.java)
- [ChunkingMode.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/core/chunk/ChunkingMode.java)

---

### EnricherNode — 对每个 Chunk 进行 AI 元数据富化

**位置：** ChunkerNode 之后，IndexerNode 之前（可选节点）

**功能：** 对 ChunkerNode 切分后的**每个 Chunk** 逐块进行 AI 驱动的信息提取和元数据补充。与 EnhancerNode（整个文档）不同，EnricherNode 是**逐块**（per-chunk）处理的。

**输入：** `context.chunks`

**输出：** `context.chunks[].metadata` 被富化

#### 支持的富化类型

| 类型 | 标识 | 输出位置 | 说明 |
|------|------|----------|------|
| **关键词提取** | `keywords` | `chunk.metadata.keywords` | 从单个 Chunk 中提取关键词/短语 |
| **摘要生成** | `summary` | `chunk.metadata.summary` | 为单个 Chunk 生成简短摘要 |
| **元数据添加** | `metadata` | `chunk.metadata` | 提取额外的结构化元数据，合并到 Chunk 元数据中 |

#### 配置参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `modelId` | `String` | — | 用于富化的 LLM 模型 ID，为空时使用默认模型 |
| `attachDocumentMetadata` | `Boolean` | `true` | 是否将文档级别的元数据复制到每个 Chunk |
| `tasks` | `Array` | — | 要执行的富化任务列表 |

**每个任务配置：** 同 EnhancerNode 的任务格式（type / systemPrompt / userPromptTemplate）

#### 前端配置

- **模型 ID**：文本输入框
- **附加文档元数据**：复选框（默认启用）
- **富化任务**：可添加多个任务，支持 keywords / summary / metadata

#### 参考源码

- [EnricherNode.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/ingestion/node/EnricherNode.java)
- [EnricherSettings.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/ingestion/domain/settings/EnricherSettings.java)
- [ChunkEnrichType.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/ingestion/domain/enums/ChunkEnrichType.java)

---

### IndexerNode — 将 Chunk 向量化并写入向量库

**位置：** 流水线最后一个节点

**功能：** 将处理完成的所有 Chunk（包含文本内容、向量嵌入和元数据）批量写入 PostgreSQL 的向量数据库（pgvector），完成从原始文档到可检索向量的最终转化。

**输入：** `context.chunks` — 包含 `{chunkId, content, embedding, index, metadata}` 的列表

**输出：** 数据写入 `t_knowledge_vector` 表

#### 向量存储表结构

```sql
CREATE TABLE t_knowledge_vector (
    id          VARCHAR(20) PRIMARY KEY,    -- Chunk 唯一 ID
    content     TEXT,                        -- 分块文本内容
    metadata    JSONB,                       -- 元数据（含 collection_name, doc_id 等）
    embedding   vector(1536)                 -- 1536 维向量（pgvector 类型）
);

-- 索引
CREATE INDEX idx_kv_metadata ON t_knowledge_vector USING gin(metadata);
CREATE INDEX idx_kv_embedding ON t_knowledge_vector USING hnsw (embedding vector_cosine_ops);
```

#### 配置参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `collectionName` | `String` | **目标知识库的 Collection 名称**。对应已存在的知识库，数据写入该知识库的向量集合 |
| `embeddingModel` | `String` | 生成向量嵌入的模型标识（可选，目前由 ChunkerNode 生成嵌入，此字段预留） |
| `metadataFields` | `String[]` | 要写入向量库的元数据字段列表（如 `["keywords", "summary", "category"]`） |

#### Collection 名称的解析优先级

```
1. 节点配置 settings.collectionName   ← 用户在流水线编辑器中选定的知识库
2. IngestionContext.vectorSpaceId      ← 调用方传参指定的向量空间
3. ragDefaultProperties.collectionName ← 全局默认配置
```

> **注意：** 目前建议在前端流水线编辑器中直接选择已有知识库（Collection），系统会自动校验该知识库是否存在。如果 Collection 不存在，IndexerNode 会自动创建。

#### 元数据写入逻辑

IndexerNode 写入时，metadata 字段自动包含：

| 字段 | 说明 |
|------|------|
| `collection_name` | 知识库集合名称（用于检索时过滤） |
| `doc_id` | 文档 ID（用于按文档删除向量） |
| `chunk_index` | Chunk 序号 |
| `task_id` | 任务 ID |
| `pipeline_id` | 流水线 ID |
| `source_type` | 文档来源类型 |
| `source_location` | 文档来源位置 |

此外，`metadataFields` 中指定的自定义字段也会被写入。

#### 事务支持

- `skipIndexerWrite=true` 时，IndexerNode 只做数据准备（构建行数据、分配 chunkId），不执行实际写入，由调用方统一在事务中完成持久化

#### 参考源码

- [IndexerNode.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/ingestion/node/IndexerNode.java)
- [IndexerSettings.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/ingestion/domain/settings/IndexerSettings.java)
- [PgVectorStoreService.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/rag/core/vector/PgVectorStoreService.java)
- [VectorStoreService.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/rag/core/vector/VectorStoreService.java)

---

## 四、IngestionEngine — 流水线执行引擎

IngestionEngine 是整个流水线的核心协调器，负责按链式结构驱动各节点依次执行。

**源码：** [IngestionEngine.java](bootstrap/src/main/java/com/byteq/ai/ragstudio/ingestion/engine/IngestionEngine.java)

### 执行流程

```
execute(pipeline, context)
    │
    ├─ 1. 构建节点 ID → NodeConfig 映射
    │
    ├─ 2. 验证流水线合法性
    │      ├─ 环检测（DFS 路径追踪）
    │      └─ 引用完整性（nextNodeId 必须存在）
    │
    ├─ 3. 查找起始节点
    │      └─ 不被任何其他节点引用的节点 → 入口
    │
    └─ 4. 链式执行
           ├─ 从起始节点开始
           ├─ 按 nextNodeId 依次执行
           ├─ 条件不满足 → 跳过（NodeResult.skip）
           ├─ 节点失败 → 标记 FAILED，终止
           └─ 防死循环保护（最多执行节点总数次）
```

### 安全特性

| 特性 | 说明 |
|------|------|
| **环检测** | DFS 追踪路径，发现循环引用立即报错 |
| **引用完整性** | 确保每个 nextNodeId 都指向存在的节点 |
| **防死循环** | 最多执行节点总数次，超限自动终止 |
| **异常隔离** | 节点异常被引擎捕获，不会导致引擎崩溃 |
| **条件跳过** | 条件不满足时自动跳过，不影响后续节点 |

### 节点日志

每个节点执行完成后，引擎自动生成 `NodeLog`，包含：

| 字段 | 说明 |
|------|------|
| `nodeId` | 节点标识 |
| `nodeType` | 节点类型 |
| `message` | 处理概要 |
| `durationMs` | 执行耗时（毫秒） |
| `success` | 是否成功 |
| `error` | 错误信息 |
| `output` | 节点输出数据 |

日志持久化到 `t_ingestion_task_node` 表，可通过 API `/ingestion/tasks/{id}/nodes` 查看。

---

## 五、时序图：一份 PDF 的完整旅程

```
用户上传 contract.pdf
        │
        ▼
┌─ IngestionTaskService.execute() ──────────────────────────┐
│  1. 根据 pipelineId 获取 PipelineDefinition               │
│  2. 创建 IngestionTaskDO（status=running）                │
│  3. 构建 IngestionContext                                 │
│  4. 调用 IngestionEngine.execute(pipeline, context)       │
│  5. 保存节点日志到 t_ingestion_task_node                  │
│  6. 更新任务状态和统计信息                                │
│  7. 返回 IngestionResult                                 │
└───────────────────────────────────────────────────────────┘
        │
        ▼
┌─ IngestionEngine 链式执行 ─────────────────────────────────┐
│                                                            │
│  FetcherNode                                                │
│  ├─ 读取 contract.pdf 的二进制字节                         │
│  ├─ 检测 MIME: application/pdf                             │
│  └─ context.rawBytes = [PDF 二进制], mimeType = "pdf"      │
│                                                            │
│  ParserNode                                                 │
│  ├─ 用 Tika 解析 PDF                                       │
│  └─ context.rawText = "合同编号：XXX，甲方：..."           │
│                                                            │
│  EnhancerNode (可选)                                        │
│  ├─ 调 LLM：修复 PDF 解析导致的换行错乱                    │
│  └─ context.enhancedText = "（格式修复后的文本）"           │
│     context.keywords = ["合同", "甲方", "违约金"]           │
│                                                            │
│  ChunkerNode                                                │
│  ├─ 分块策略: fixed_size, chunkSize=512, overlap=128       │
│  ├─ 切分成 47 个 Chunk                                     │
│  ├─ 每个 Chunk 生成 embedding (1536维)                     │
│  └─ context.chunks = [VectorChunk×47]                      │
│                                                            │
│  EnricherNode (可选)                                        │
│  ├─ 对每个 Chunk 调 LLM 提取关键词/摘要                    │
│  └─ chunks[0].metadata.keywords = ["合同编号", ...]         │
│                                                            │
│  IndexerNode                                                │
│  ├─ 目标 Collection: 选定的知识库                           │
│  ├─ INSERT INTO t_knowledge_vector × 47                    │
│  └─ "已写入 47 个分块到集合 xxx"                           │
│                                                            │
└────────────────────────────────────────────────────────────┘
        │
        ▼
任务状态: COMPLETED
chunkCount: 47
```

---

## 六、配置参考

### 完整的流水线 JSON 示例（PDF 文档处理）

详见 [docs/examples/pdf-pipeline-request.json](docs/examples/pdf-pipeline-request.json)

```json
{
  "name": "pdf-ingestion-pipeline",
  "description": "PDF文档摄取流水线 - 解析、AI增强、分块、向量化",
  "nodes": [
    {
      "nodeId": "fetcher-1",
      "nodeType": "fetcher",
      "nextNodeId": "parser-1"
    },
    {
      "nodeId": "parser-1",
      "nodeType": "parser",
      "settings": { "rules": [{ "mimeType": "PDF" }] },
      "nextNodeId": "enhancer-1"
    },
    {
      "nodeId": "enhancer-1",
      "nodeType": "enhancer",
      "settings": {
        "modelId": "qwen-plus",
        "tasks": [{
          "type": "context_enhance",
          "systemPrompt": "你是一个文本排版修复器...",
          "userPromptTemplate": "请整理以下PDF文档内容：\n\n{{text}}"
        }]
      },
      "nextNodeId": "chunker-1"
    },
    {
      "nodeId": "chunker-1",
      "nodeType": "chunker",
      "settings": {
        "strategy": "fixed_size",
        "chunkSize": 512,
        "overlapSize": 128
      },
      "nextNodeId": "indexer-1"
    },
    {
      "nodeId": "indexer-1",
      "nodeType": "indexer",
      "settings": {
        "collectionName": "my_knowledge_base",
        "embeddingModel": "qwen-emb-8b",
        "metadataFields": ["keywords", "summary"]
      }
    }
  ]
}
```

---

## 七、扩展指南

### 新增数据源

实现 `DocumentFetcher` 接口并注册为 Spring Bean，自动被 FetcherNode 发现：

```java
@Component
public class DatabaseFetcher implements DocumentFetcher {
    @Override
    public SourceType supportedType() { return SourceType.DATABASE; }
    @Override
    public FetchResult fetch(DocumentSource source) {
        // 从数据库读取文档
        return new FetchResult(bytes, mimeType, fileName);
    }
}
```

同时在 `SourceType` 枚举中添加新类型。

### 新增节点类型

1. 实现 `IngestionNode` 接口
2. 在 `IngestionNodeType` 枚举中注册新类型
3. 在 `NodeConfig` 中配置对应的 settings 结构

```java
@Component
public class WatermarkNode implements IngestionNode {
    @Override
    public String getNodeType() { return "watermark"; }
    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        // 自定义处理逻辑
        return NodeResult.ok("处理完成");
    }
}
```

### 新增后置处理

节点的执行结果完全由 `NodeResult` 控制，可以：
- `NodeResult.skip("条件不满足")` — 条件性跳过
- `NodeResult.terminate("已完成校验")` — 提前终止流水线

---

## 快速参考

| 节点 | 类型值 | 必需 | 输入 | 输出 | 配置参数 |
|------|--------|------|------|------|----------|
| Fetcher | `fetcher` | ✅ | DocumentSource | rawBytes, mimeType | 无（来源信息在任务中指定） |
| Parser | `parser` | ✅ | rawBytes, mimeType | rawText, document | `rules` (MIME 过滤规则) |
| Enhancer | `enhancer` | ❌ | rawText | enhancedText, keywords, questions, metadata | `modelId`, `tasks` (增强任务列表) |
| Chunker | `chunker` | ✅ | enhancedText / rawText | chunks (带 embedding) | `strategy`, `chunkSize`, `overlapSize` |
| Enricher | `enricher` | ❌ | chunks | chunks（metadata 被富化） | `modelId`, `attachDocumentMetadata`, `tasks` |
| Indexer | `indexer` | ✅ | chunks | 写入 t_knowledge_vector | `collectionName`, `metadataFields` |

> 💡 **提示：** 最简可用流水线只需要 `fetcher → parser → chunker → indexer` 四个节点。
