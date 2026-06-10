# 重构总结

## 重构目标

实现一个可扩展的多通道检索架构，提升检索覆盖率和准确率：
1. **检索覆盖不足**：单一向量检索可能遗漏相关文档
2. **缺乏结果融合**：多路检索结果需要统一的去重和重排序机制
3. **扩展性差**：新增检索策略需要修改核心代码

## 重构内容

### 1. 新增文件

#### 核心接口和类型
- `SearchChannel.java` - 检索通道接口
- `SearchChannelType.java` - 检索通道类型枚举
- `SearchContext.java` - 检索上下文
- `SearchChannelResult.java` - 检索通道结果

#### 检索通道实现
- `VectorGlobalSearchChannel.java` - 向量全局检索通道
- `KnowledgeBaseSelectionChannel.java` - 知识库选择检索通道

#### 后置处理器
- `SearchResultPostProcessor.java` - 后置处理器接口
- `DeduplicationPostProcessor.java` - 去重处理器
- `RerankPostProcessor.java` - Rerank 重排序处理器

#### 核心引擎
- `MultiChannelRetrievalEngine.java` - 多通道检索引擎

### 2. 修改文件

- `RetrievalEngine.java` - 集成多通道检索引擎

## 架构设计

### 核心思想

采用**责任链 + 策略模式**的组合：
- **策略模式**：不同的检索通道实现不同的检索策略
- **责任链模式**：后置处理器形成处理链，依次处理检索结果

### 工作流程

```
用户问题
    ↓
【问题重写】
    ↓
【多通道并行检索】
    ├─→ VectorGlobalSearchChannel（始终执行）
    └─→ KnowledgeBaseSelectionChannel（选择 KB 时启用）
    ↓
【后置处理器链】
    ├─→ DeduplicationPostProcessor（去重）
    └─→ RerankPostProcessor（重排序）
    ↓
【返回最终结果】
```

### 关键特性

1. **多通道并行执行**
   - `VectorGlobalSearchChannel` 始终执行，覆盖所有知识库
   - `KnowledgeBaseSelectionChannel` 在用户选择特定知识库时追加执行

2. **并行执行**
   - 所有启用的检索通道通过 `CompletableFuture.supplyAsync` 并行执行，提升性能

3. **统一后处理**
   - 去重：合并多个通道的结果，移除重复 Chunk
   - Rerank：对合并后的结果进行重排序

4. **易于扩展**
   - 新增检索通道：实现 `SearchChannel` 接口
   - 新增后置处理器：实现 `SearchResultPostProcessor` 接口
   - 无需修改核心代码

## 解决方案对比

| 方案 | 覆盖率 | 准确率 | 性能 | 扩展性 |
|------|--------|--------|------|--------|
| **原方案**（单通道检索） | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐ |
| **新方案**（多通道检索） | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |

### 新方案优势

1. **覆盖率提升**：多通道并行检索，覆盖面更广
2. **准确率提升**：去重 + Rerank 精排确保结果质量
3. **扩展性强**：新增通道或处理器无需修改核心代码
4. **异常隔离**：单个通道或处理器失败不影响整体流程

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
    public boolean isEnabled(SearchContext context) {
        return true;
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        // 实现自定义检索逻辑
    }
}
```

## 配置说明

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

## 未来扩展

1. **ES 关键词检索通道**：基于 Elasticsearch 的全文检索
2. **缓存机制**：对检索结果进行缓存，提升性能
3. **监控和统计**：记录各通道的命中率、耗时等指标

## 测试建议

1. **单元测试**
   - 测试各检索通道的独立功能
   - 测试各后置处理器的处理逻辑

2. **集成测试**
   - 测试多通道并行执行
   - 测试后置处理器链的执行顺序

3. **性能测试**
   - 对比单通道和多通道的性能差异
   - 测试不同配置下的性能表现

## 注意事项

1. **通道优先级**：`getPriority()` 数字越小优先级越高，影响去重时的结果保留策略
2. **处理器顺序**：`getOrder()` 决定执行顺序，去重（order=1）应最先执行，Rerank（order=10）应最后执行
3. **性能考虑**：启用过多通道会增加延迟，建议根据实际需求选择性启用
4. **异常隔离**：单个通道失败记日志但不会阻断其他通道；处理器异常自动跳过

## 总结

本次重构实现了一个**可扩展的多通道检索架构**，通过多通道并行检索 + 后置处理器链，提升了检索覆盖率和准确率。

新架构的核心优势：
- ✅ **高覆盖率**：多通道并行，覆盖面广
- ✅ **高准确率**：去重 + Rerank 精排
- ✅ **易扩展**：新增通道或处理器无需修改核心代码
- ✅ **灵活配置**：通过配置文件控制行为
- ✅ **异常隔离**：单个通道/处理器故障不影响整体
