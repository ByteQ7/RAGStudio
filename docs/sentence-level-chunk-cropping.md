# 检索后句子级 Chunk 裁剪方案（整合版）

> 本方案将现有项目基于 Python 微服务的语义裁剪（`zilliz/semantic-highlight-bilingual-v1`）整合为进程内实现，落地《句子级Chunk裁剪方案（传统RAG）》的核心设计，并保留项目原有的缓存、句数上限、阈值跳过等优化。

## 1. 概述

在传统 RAG 流程中，向量检索返回的 Chunk 常包含大量与查询无关的冗余信息，增加重排序（Reranker）与 LLM 推理开销并引入噪声。本方案在检索与重排序之间增加一道轻量级语义过滤层，精确剔除不相关的自然语言句子，同时完整保留代码块等结构化内容。

- **轻量高效**：进程内加载量化小模型，CPU 环境毫秒级响应。
- **结构安全**：严格保护代码块等不可分割实体，避免语义破坏。
- **原生集成**：全链路 Java 组件，移除对 Python 微服务的网络依赖。

## 2. 核心组件选型

| 组件类型 | 推荐选择 | 选型理由 |
|:---------|:---------|:---------|
| 裁剪模型 | bge-small-zh-v1.5（量化版） | 中文句子级语义裁剪效果好；模型轻量（约 90MB）；ONNX Runtime 进程内加载，CPU 单句推理约 10ms。 |
| 分句器 | HanLP 1.x portable（`com.hankcs:hanlp:portable-1.8.6`） | Java 原生实现，无需额外环境依赖；中文标点识别准确；Maven 直接引入。 |
| 推理引擎 | ONNX Runtime Java（`com.microsoft.onnxruntime:onnxruntime`） | 进程内加载 bge-small-zh-v1.5 量化 ONNX 模型，功能等价于 FastEmbed-Java 的底层方案，可稳定从 Maven Central 引入。 |
| 代码块处理 | 正则提取 + 占位符 + 位置重组 | 针对 Markdown 格式，使用 `__INLINECODETWO{n}__` 占位符隔离，代码块不参与语义裁剪，保证技术内容完整。 |

## 3. 裁剪处理流程

句子级裁剪作为检索后、重排序前的关键中间件，标准处理流程如下：

1. **获取候选集**：接收用户查询 Q，通过多通道向量/关键词检索获取候选 Chunks（每个 Chunk 包含自然语言与代码块的混合内容）。
2. **代码块隔离**：对每个 Chunk 执行正则匹配，提取代码块（```...```），替换为唯一占位符，记录原始位置索引。
3. **文本分句**：将剥离代码块后的剩余自然语言文本输入 HanLP 分句器，生成句子列表（带引号/括号配对跟踪，异常时回退正则 `[。！？!?\n]`）。
4. **批量向量化**：将句子列表与用户查询 Q 组合，输入 bge-small-zh-v1.5 模型进行批量 Embedding 计算。
5. **相似度过滤**：计算各句子向量与查询向量的余弦相似度，保留相似度超过设定阈值（默认 0.35）的句子。
6. **内容重组**：将保留的自然语言句子与原始代码块按初始位置索引重组，生成最终裁剪后的 Chunk。
7. **下游传递**：裁剪后的 Chunks 送入重排序模型（Reranker，走百炼 API），最终取 Top-N 结果输入 LLM 生成答案。

## 4. 流水线位置

- **调整前**：检索 → 多通道后处理链（含 Rerank）→ 裁剪。
- **调整后**：检索 → 裁剪 → Rerank → Top-N 入 LLM。

裁剪前移后，Reranker 收到的是裁剪后的文本，减少 Rerank API 的 Token 消耗。

## 5. 关键参数配置

| 参数名称 | 建议值 | 说明 |
|:---------|:-------|:-----|
| 裁剪开关 `rag.search.crop.enabled` | false（默认关闭） | 开启后本地模型缺失或加载失败时应用启动报错（fail-fast）。 |
| 模型路径 `rag.search.crop.model-path` | 本地模型目录 | bge-small-zh-v1.5 量化 ONNX 模型文件目录。 |
| 余弦相似度阈值 `rag.search.crop.threshold` | 0.35（初始） | 语义保留基准线，上线后按召回率/准确率微调。 |
| 裁剪最少字符数 `rag.search.crop.min-chars` | 800 | 参与裁剪文本总字符数低于该值时跳过裁剪保原文。 |
| 每 Chunk 最大句数 `rag.search.crop.max-sentences-per-chunk` | 20 | 长 Chunk 尾部句子贡献边际递减，限制后线性降低时延。 |
| 结果缓存 `rag.search.crop.cache-enabled` | true | Redis 缓存，key 含文本指纹，幂等复用。 |
| 缓存 TTL `rag.search.crop.cache-ttl-hours` | 6 | 与 embedding 缓存保持一致。 |

## 6. 代码改动清单

### 6.1 依赖（根 `pom.xml`）

- 新增 `com.hankcs:hanlp:portable-1.8.6`（HanLP 1.x portable）。
- 新增 `com.microsoft.onnxruntime:onnxruntime`（进程内 ONNX 推理）。

### 6.2 infra-ai 新增组件（包 `com.byteq.ai.ragstudio.infra.crop`）

- **`HanlpSentenceSplitter`**：基于 HanLP 分词器（StandardTokenizer）term 流分句，只在句末标点处切分并保留分隔符；引号/括号配对跟踪（“”「」（）()【】内不切句）；正确处理数字小数点/URL；异常时回退正则切分。
- **`CodeBlockIsolator`**：正则提取 Markdown 代码块并替换为 `__INLINECODETWO{n}__` 占位符，记录位置；裁剪后按位置重组，保证代码零损耗。
- **`BgeSentenceScorer`**：Spring 组件（`@ConfigurationProperties("rag.search.crop")`），启动时校验并加载本地 bge-small-zh-v1.5 量化 ONNX 模型（`onnx/model_quantized.onnx` 或 `onnx/model.onnx`），缺失或加载失败则启动报错（fail-fast）；对「问题 + 句子列表」批量编码，计算余弦相似度，返回各句子分数与保留索引。
- **`BertWordPieceTokenizer`**：从模型目录 `vocab.txt` 读取词表，完成 BERT WordPiece 基础分词与 CLS/SEP/PAD 编码。

### 6.3 ContextCropper 改造（bootstrap）

- 移除对 `SemanticHighlightClient` 的 HTTP 调用，改为调用进程内 scorer（进程内毫秒级推理，简化异步/超时复杂度）。
- 新增代码块隔离步骤（分句前）。
- 阈值 0.3 → 0.35（配置化）。
- 保留：`min-chars` 跳过、`max-sentences-per-chunk` 句数上限、Redis 缓存、日志与链路追踪。

### 6.4 流程顺序调整

- 将 ContextCropper 注册为 `SearchResultPostProcessor`（order < 10），排在 `RerankPostProcessor` 之前。
- 从 `RetrievalEngine.doRetrieve` 移除原 `crop()` 直接调用。
- 裁剪使用上下文中的 rewrittenQuestion 打分，Rerank 仍使用 userOriginalQuestion。

### 6.5 配置更新

- `application.yaml`：新增 `rag.search.crop.enabled` / `threshold` / `model-path`；移除 `rag.semantic-highlight.*`。
- `SearchChannelProperties.Crop`：新增 `enabled`、`threshold`、`modelPath` 字段。
- `.env` / 环境变量：新增 `RAG_CROP_ENABLED`、`RAG_CROP_MODEL_PATH`、`RAG_CROP_THRESHOLD`；移除 `SEMANTIC_HIGHLIGHT_*`。

### 6.6 删除 Python 微服务

- 删除 `resources/docker/semantic-highlight/` 全部内容。
- 删除 `infra-ai` 下 `SemanticHighlightClient`、`SemanticHighlightRequest/Response`、`RerankRequest/Response` 等仅在裁剪链路使用、确认无其他引用的类。
- 模型下载：执行 `resources/models/bge-small-zh-v1.5/download.sh` 将 bge-small-zh-v1.5 量化 ONNX 模型下载到 `model-path` 目录（模型文件不入库，已在 `.gitignore` 排除）。

### 6.7 文档同步

- 更新 `docs/deployment.md`、`README_cn.md`、`docs/multi-channel-retrieval.md`、`docs/graph-rag-design.md`、`resources/docs/knowledge/group/rd/系统架构设计.md`。

## 7. 测试与验证

- 新增单测：`HanlpSentenceSplitter`（中英混合/引号内句号/数字小数点/占位符）、`CodeBlockIsolator`（隔离-重组 round-trip 保序保内容）、`BgeSentenceScorer`（余弦计算与阈值过滤，mock 或极简测试模型）。
- 更新 `ContextCropper` 相关测试。
- 执行 `mvn compile`、spotless 校验及相关单测。

## 8. 方案优势

- 显著提升上下文质量：句子级细粒度过滤，精准剔除噪声。
- 极致性能：量化小模型 + 进程内加载，单句约 10ms，链路延迟影响极小。
- 零网络开销：无外部 API 调用，规避网络延迟与不稳定风险。
- 代码零损耗：正则隔离 + 占位符机制，代码块不被拆分、不被过滤。
- 高可用与易集成：全链路 Java 依赖，Maven 直接引入，正则回退保障分句鲁棒性。
- 部署简化：移除 Python 微服务，单进程启动，模型本地化 + 开关控制 + fail-fast 校验。