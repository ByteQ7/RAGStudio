# 快速开始指南

## 多通道检索架构

本项目实现了一个**多通道检索 + 后置处理器**的可扩展检索引擎，支持多个检索通道并行执行，结果经后置处理器链融合后输出。

## 核心文件

### 1. 检索通道接口与类型
```
bootstrap/src/main/java/com/byteq/ai/ragstudio/rag/core/retrieve/channel/
├── SearchChannel.java                  # 检索通道接口
├── SearchChannelType.java              # 通道类型枚举
├── SearchContext.java                  # 检索上下文
├── SearchChannelResult.java            # 通道结果
├── VectorGlobalSearchChannel.java      # 向量全局检索
├── KnowledgeBaseSelectionChannel.java  # 知识库选择检索
└── AbstractParallelRetriever.java      # 并行检索抽象类
```

### 2. 后置处理器
```
bootstrap/src/main/java/com/byteq/ai/ragstudio/rag/core/retrieve/postprocessor/
├── SearchResultPostProcessor.java      # 后置处理器接口
├── DeduplicationPostProcessor.java     # 去重处理器
└── RerankPostProcessor.java            # Rerank 重排序处理器
```

### 3. 多通道检索引擎
```
bootstrap/src/main/java/com/byteq/ai/ragstudio/rag/core/retrieve/
└── MultiChannelRetrievalEngine.java    # 多通道检索引擎
```

## 工作原理

### 1. 检索流程

```
用户问题
    ↓
【问题重写】
    ↓
【多通道并行检索】
    ├─→ 向量全局检索（VectorGlobalSearchChannel）
    └─→ 知识库选择检索（KnowledgeBaseSelectionChannel，选择知识库时启用）
    ↓
【后置处理器链】
    ├─→ 去重（合并多通道结果）
    └─→ Rerank（重排序）
    ↓
【返回 Top-K 结果】
```

### 2. 通道启用逻辑

| 通道 | 启用条件 |
|------|---------|
| `VectorGlobalSearchChannel` | 始终启用，在所有知识库中执行向量检索 |
| `KnowledgeBaseSelectionChannel` | 仅当用户在前端选择了特定知识库时启用 |

## 如何使用

### 1. 现有代码无需修改

`RetrievalEngine` 已经集成了多通道检索引擎，现有的调用代码无需修改：

```java
// 原有代码继续工作
RetrievalContext context = retrievalEngine.retrieve(subIntents, topK);
```

### 2. 配置调整（可选）

在 `application.yaml` 中配置检索参数：

```yaml
rag:
  search:
    channels:
      vector-global:
        enabled: true
        top-k-multiplier: 3        # 调整召回倍数
      knowledge-base-selection:
        enabled: true
        top-k-multiplier: 3
```

**注意**：后置处理器（去重、Rerank）是代码层面的逻辑，不需要配置文件控制。如需新增或修改后置处理器，直接实现 `SearchResultPostProcessor` 接口即可。

### 3. 查看日志

启动应用后，可以在日志中看到检索通道的执行情况：

```
INFO  启用的检索通道：[VectorGlobalSearch, KnowledgeBaseSelection]
INFO  执行检索通道：VectorGlobalSearch
INFO  执行检索通道：KnowledgeBaseSelection
INFO  通道 VectorGlobalSearch 完成，检索到 20 个 Chunk，耗时：150ms
INFO  通道 KnowledgeBaseSelection 完成，检索到 10 个 Chunk，耗时：120ms
INFO  启用的后置处理器：[Deduplication, Rerank]
INFO  执行后置处理器：Deduplication
INFO  去重完成，输入 Chunk 数：30，输出 Chunk 数：25
INFO  执行后置处理器：Rerank
INFO  Rerank 完成，输出 Chunk 数：5
```

## 扩展示例

### 1. 新增自定义检索通道

```java
@Component
public class CustomSearchChannel implements SearchChannel {

    @Override
    public String getName() {
        return "CustomSearch";
    }

    @Override
    public int getPriority() {
        return 5;  // 越小优先级越高
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        // 实现自定义检索逻辑
        List<RetrievedChunk> chunks = ...;
        return SearchChannelResult.builder()
            .channelType(SearchChannelType.HYBRID)
            .channelName(getName())
            .chunks(chunks)
            .build();
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.HYBRID;
    }
}
```

### 2. 新增自定义后置处理器

```java
@Component
public class CustomPostProcessor implements SearchResultPostProcessor {

    @Override
    public String getName() {
        return "CustomProcessor";
    }

    @Override
    public int getOrder() {
        return 5;  // 去重(1)之后、Rerank(10)之前
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                         List<SearchChannelResult> results,
                                         SearchContext context) {
        // 实现自定义处理逻辑
        return chunks.stream()
            .filter(chunk -> chunk.getScore() > 0.5)
            .toList();
    }
}
```

## 测试建议

### 1. 测试多通道并行检索

```java
// 不选知识库 → 仅 VectorGlobalSearchChannel 执行
// 选择知识库 → VectorGlobalSearchChannel + KnowledgeBaseSelectionChannel 并行执行
```

### 2. 测试去重效果

```java
// 预期：同一个 Chunk 在多个通道中出现时，只保留一份
```

### 3. 测试 Rerank 效果

```java
// 预期：最终返回的 Top-K 结果是经过 Rerank 优化的
```

## 性能优化建议

1. **调整召回倍数**：根据 Rerank 效果调整 `top-k-multiplier`
2. **选择性启用通道**：根据实际需求启用或禁用特定通道
3. **监控性能指标**：记录各通道的耗时和命中率

## 常见问题

### Q1: 如何禁用某个检索通道？

A: 在 `application.yaml` 中设置：
```yaml
rag:
  search:
    channels:
      vector-global:
        enabled: false
```

### Q2: 如何新增自定义的检索通道？

A: 实现 `SearchChannel` 接口，并用 `@Component` 注册为 Spring Bean，引擎会自动发现。

### Q3: 如何新增自定义的后置处理器？

A: 实现 `SearchResultPostProcessor` 接口，并用 `@Component` 注册为 Spring Bean，引擎会自动发现。

## 下一步

1. **运行应用**：启动应用，观察日志中的检索流程
2. **测试效果**：测试多通道检索的覆盖率和准确率
3. **调优配置**：根据实际效果调整 `top-k-multiplier` 等参数
4. **扩展功能**：根据需求新增检索通道或后置处理器

## 参考文档

- [架构说明文档](./multi-channel-retrieval.md)
- [重构总结](./refactoring-summary.md)
