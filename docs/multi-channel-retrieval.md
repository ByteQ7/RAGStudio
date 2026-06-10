# 多通道检索架构说明

## 概述

本项目实现了一个可扩展的多通道检索架构，支持：
- **多种检索策略**：向量全局检索、知识库选择检索（按需扩展）
- **灵活的后置处理**：去重、Rerank 重排序
- **易于扩展**：新增检索通道或后置处理器只需实现接口并注册为 Spring Bean

## 架构设计

```
用户问题
    ↓
【问题重写】
    ↓
【多通道并行检索】
    ├─→ VectorGlobalSearchChannel（向量全局检索，始终执行）
    └─→ KnowledgeBaseSelectionChannel（知识库选择检索，选择 KB 时启用）
    ↓
【结果聚合】
    ↓
【后置处理器链】
    ├─→ DeduplicationPostProcessor（去重）
    └─→ RerankPostProcessor（Rerank 重排序）
    ↓
【上下文格式化】
    ↓
【LLM 生成答案】
```

## 核心组件

### 1. 检索通道（SearchChannel）

检索通道负责执行具体的检索策略。

**接口定义**：
```java
public interface SearchChannel {
    String getName();                              // 通道名称
    int getPriority();                             // 优先级（越小越高）
    boolean isEnabled(SearchContext context);      // 是否启用
    SearchChannelResult search(SearchContext context);  // 执行检索
    SearchChannelType getType();                   // 通道类型
}
```

**已实现的通道**：

| 通道 | 类名 | 说明 |
|------|------|------|
| 向量全局检索 | `VectorGlobalSearchChannel` | 在所有知识库中执行向量检索，始终启用 |
| 知识库选择检索 | `KnowledgeBaseSelectionChannel` | 仅当用户在前端选择了特定知识库时启用 |

**扩展示例**：
```java
@Component
public class CustomSearchChannel implements SearchChannel {
    @Override
    public String getName() {
        return "CustomSearch";
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        // 实现自定义检索逻辑
    }
}
```

### 2. 后置处理器（SearchResultPostProcessor）

后置处理器对检索结果进行统一的后处理。

**接口定义**：
```java
public interface SearchResultPostProcessor {
    String getName();                              // 处理器名称
    int getOrder();                                // 执行顺序（越小越先）
    boolean isEnabled(SearchContext context);      // 是否启用
    List<RetrievedChunk> process(                  // 处理结果
        List<RetrievedChunk> chunks,
        List<SearchChannelResult> results,
        SearchContext context
    );
}
```

**已实现的处理器**：

| 处理器 | 类名 | 说明 |
|--------|------|------|
| 去重 | `DeduplicationPostProcessor` | 合并多个通道的结果，移除重复 Chunk |
| Rerank 重排序 | `RerankPostProcessor` | 使用 Rerank 模型对结果进行精排 |

**扩展示例**：
```java
@Component
public class CustomPostProcessor implements SearchResultPostProcessor {
    @Override
    public String getName() {
        return "CustomProcessor";
    }

    @Override
    public int getOrder() {
        return 5;  // 去重之后、Rerank 之前执行
    }

    @Override
    public List<RetrievedChunk> process(
        List<RetrievedChunk> chunks,
        List<SearchChannelResult> results,
        SearchContext context
    ) {
        // 实现自定义处理逻辑
    }
}
```

### 3. 多通道检索引擎（MultiChannelRetrievalEngine）

协调多个检索通道和后置处理器的执行。

**核心流程**：
```
MultiChannelRetrievalEngine.retrieveKnowledgeChannels()
    │
    ├─ Phase 1: 并行执行所有启用的检索通道
    │    └─ CompletableFuture.supplyAsync() + ragRetrievalExecutor
    │
    └─ Phase 2: 依次执行后置处理器链
         └─ 按 order 排序，前一个输出作为后一个输入
```

## 配置说明

在 `application.yaml` 中配置：

```yaml
rag:
  search:
    channels:
      vector-global:
        enabled: true
        top-k-multiplier: 3
      knowledge-base-selection:
        enabled: true
        top-k-multiplier: 3
```

**注意**：后置处理器（去重、Rerank）由代码自动管理，无需配置文件控制。

## 使用示例

### 1. 基本使用

```java
@Service
public class RAGService {
    @Autowired
    private MultiChannelRetrievalEngine retrievalEngine;

    public RetrievalContext search(String mainQuestion, List<String> subQuestions,
                                    List<String> collectionNames, int topK) {
        List<RetrievedChunk> chunks = retrievalEngine.retrieveKnowledgeChannels(
            collectionNames, subQuestions, mainQuestion, topK
        );
        // 使用检索结果
    }
}
```

### 2. 新增检索通道

```java
@Component
public class CustomSearchChannel implements SearchChannel {
    @Override
    public String getName() {
        return "CustomSearch";
    }

    @Override
    public int getPriority() {
        return 5;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        // 自定义启用条件
        return context.getMainQuestion().contains("关键词");
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        // 实现自定义检索逻辑
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

## 工作流程

### 1. 检索通道执行

所有启用的检索通道会**并行执行**，互不影响：

```
VectorGlobalSearchChannel     ─┐
                               ├─→ 并行执行
KnowledgeBaseSelectionChannel ─┘
```

每个通道返回 `SearchChannelResult`，包含：
- 检索到的 Chunk 列表
- 检索耗时
- 元数据

### 2. 后置处理器链执行

后置处理器按照 `order` 顺序**依次执行**，形成处理链：

```
原始 Chunks
    ↓
DeduplicationPostProcessor (order=1) — 去重
    ↓
RerankPostProcessor (order=10) — Rerank 重排序
    ↓
最终 Chunks
```

## 扩展点

### 1. 新增检索通道类型

在 `SearchChannelType` 枚举中添加新类型：

```java
public enum SearchChannelType {
    VECTOR_GLOBAL,
    KNOWLEDGE_BASE_SELECTION,
    KEYWORD_ES,    // ES 关键词检索（预留）
    HYBRID         // 混合检索（预留）
}
```

### 2. 自定义通道启用条件

在 `isEnabled` 方法中实现自定义逻辑：

```java
@Override
public boolean isEnabled(SearchContext context) {
    String question = context.getMainQuestion();
    return question.contains("实时") || question.contains("最新");
}
```

## 优势

1. **高覆盖率**：多通道并行检索，全局检索 + 知识库选择检索覆盖全面
2. **高准确率**：Rerank 重排序进一步优化结果排序
3. **易扩展**：新增通道或处理器只需实现接口，无需修改核心代码
4. **灵活配置**：通过配置文件控制通道启用状态
5. **性能优化**：通道并行执行，处理器按需启用

## 注意事项

1. **通道优先级**：`getPriority()` 数字越小优先级越高，影响去重时的结果保留策略
2. **处理器顺序**：`getOrder()` 决定执行顺序，去重应最先执行，Rerank 应最后执行
3. **性能考虑**：启用过多通道会增加延迟，建议根据实际需求选择性启用
4. **异常隔离**：单个通道失败不影响其他通道；单个处理器失败自动跳过

## 未来扩展

1. **ES 关键词检索通道**：基于 Elasticsearch 的全文检索
2. **缓存机制**：对检索结果进行缓存，提升性能
3. **监控和统计**：记录各通道的命中率、耗时等指标
