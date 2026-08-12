package com.byteq.ai.ragstudio.rag.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 系统功能配置
 *
 * <p>
 * 用于管理 RAG 系统的各项功能开关，例如查询重写等
 * </p>
 *
 * <pre>
 * 示例配置：
 *
 * rag:
 *   query-rewrite:
 *     enabled: true
 * </pre>
 */
@Data
@Configuration
public class RAGConfigProperties {

    /**
     * 查询重写功能开关
     * <p>
     * 控制是否启用查询重写功能，查询重写可以将用户的查询语句优化为更适合检索的形式
     * 默认值：{@code true}
     */
    @Value("${rag.query-rewrite.enabled:true}")
    private Boolean queryRewriteEnabled;

    /**
     * 简单问题跳过 LLM 改写开关
     * <p>
     * 无对话历史、问题较短且不含指代/追问意图时，规则归一化 + 规则拆分已足够，
     * 直接跳过一次固定 LLM 往返，降低首包延迟。默认开启。
     * </p>
     */
    @Value("${rag.query-rewrite.skip-simple:true}")
    private Boolean queryRewriteSkipSimple;
}
