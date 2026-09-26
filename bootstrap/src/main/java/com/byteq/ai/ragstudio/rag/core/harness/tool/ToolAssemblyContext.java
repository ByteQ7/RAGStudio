package com.byteq.ai.ragstudio.rag.core.harness.tool;

import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.rag.core.harness.observation.ObservationStore;
import com.byteq.ai.ragstudio.rag.core.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * 工具组装运行时状态（Harness · 工具子模块）
 * <p>
 * 一次 Agent 运行内，工具注册与执行回调所需共享的可变状态：
 * 规范化工具名清单、重名消解集合、原始名映射、引用 Chunk 收集、图片收集。
 * 由 {@link ToolRegistryAssembler} 写入，Agent 执行器读取。
 */
public class ToolAssemblyContext {

    /** 已注册的规范化工具名（按注册顺序） */
    private final List<String> toolNames = new ArrayList<>();

    /** 已注册工具名集合（碰撞消解用） */
    private final Set<String> usedToolNames = ConcurrentHashMap.newKeySet();

    /** 原始工具名 → 规范化名（供 tool_reader 等与模型可见名称保持一致） */
    private final Map<String, String> toolNameMapping = new ConcurrentHashMap<>();

    /** 检索 Chunk 收集回调（引用溯源） */
    private Consumer<List<RetrievedChunk>> chunksConsumer;

    /** 引用编号起始偏移提供者 */
    private IntSupplier citationStartIndexSupplier;

    /** 工具执行结果回调（图片 URL 收集等） */
    private Consumer<ToolResult> resultConsumer;

    /** 观察存储（Observation Mask 开启时非空；用于注册 observation_reader 回读工具） */
    private ObservationStore observationStore;

    public List<String> getToolNames() {
        return toolNames;
    }

    public Set<String> getUsedToolNames() {
        return usedToolNames;
    }

    public Map<String, String> getToolNameMapping() {
        return toolNameMapping;
    }

    public Consumer<List<RetrievedChunk>> getChunksConsumer() {
        return chunksConsumer;
    }

    public void setChunksConsumer(Consumer<List<RetrievedChunk>> chunksConsumer) {
        this.chunksConsumer = chunksConsumer;
    }

    public IntSupplier getCitationStartIndexSupplier() {
        return citationStartIndexSupplier;
    }

    public void setCitationStartIndexSupplier(IntSupplier citationStartIndexSupplier) {
        this.citationStartIndexSupplier = citationStartIndexSupplier;
    }

    public Consumer<ToolResult> getResultConsumer() {
        return resultConsumer;
    }

    public void setResultConsumer(Consumer<ToolResult> resultConsumer) {
        this.resultConsumer = resultConsumer;
    }

    public ObservationStore getObservationStore() {
        return observationStore;
    }

    public void setObservationStore(ObservationStore observationStore) {
        this.observationStore = observationStore;
    }

    /** 已注册的规范化工具名（不可变快照） */
    public List<String> toolNamesSnapshot() {
        return List.copyOf(toolNames);
    }
}
