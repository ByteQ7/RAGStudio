package com.byteq.ai.ragstudio.rag.core.harness.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态上下文注册表（Harness · 上下文子模块）
 * <p>
 * 每请求一个实例（非 Spring 单例）。工具执行线程（AgentScope 工具调用线程）与
 * 模型调用线程（中间件 onReasoning）会并发访问，因此内部使用同步控制。
 * <p>
 * 语义：
 * <ul>
 *   <li>{@link #register(ContextBlock)} 注册/覆盖一个上下文块（默认 active）</li>
 *   <li>{@link #deactivate(String)} 使某个块失效——下一轮模型调用起不再注入，
 *       但保留在表中以便重新激活</li>
 *   <li>{@link #activeBlocks()} 返回当前生效块（按 priority 排序），供中间件每轮重建 system message</li>
 * </ul>
 */
public class DynamicContextRegistry {

    private final Map<String, Entry> blocks = new LinkedHashMap<>();

    /** 注册上下文块（同名覆盖，active=true） */
    public synchronized void register(ContextBlock block) {
        blocks.put(block.id(), new Entry(block, true));
    }

    /** 注册并立即激活（register 的别名，语义更明确） */
    public synchronized void activate(ContextBlock block) {
        register(block);
    }

    /** 使块失效：保留内容但不再注入（下一轮迭代起生效） */
    public synchronized void deactivate(String id) {
        Entry entry = blocks.get(id);
        if (entry != null) {
            blocks.put(id, new Entry(entry.block, false));
        }
    }

    /** 移除块 */
    public synchronized void remove(String id) {
        blocks.remove(id);
    }

    /** 是否已注册（无论 active） */
    public synchronized boolean contains(String id) {
        return blocks.containsKey(id);
    }

    /** 是否处于激活状态 */
    public synchronized boolean isActive(String id) {
        Entry entry = blocks.get(id);
        return entry != null && entry.active;
    }

    /** 当前生效的上下文块（按 priority 升序） */
    public synchronized List<ContextBlock> activeBlocks() {
        List<ContextBlock> result = new ArrayList<>();
        for (Entry entry : blocks.values()) {
            if (entry.active) {
                result.add(entry.block);
            }
        }
        result.sort(Comparator.comparingInt(ContextBlock::priority));
        return result;
    }

    /** 清空全部块 */
    public synchronized void clear() {
        blocks.clear();
    }

    private record Entry(ContextBlock block, boolean active) {
    }
}
