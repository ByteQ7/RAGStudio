package com.byteq.ai.ragstudio.core.parser;

import com.byteq.ai.ragstudio.core.parser.mineru.MineruConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 解析引擎决策器
 * <p>
 * 根据「知识库级解析引擎 + 文档级覆盖 + 引擎可用性」决策实际使用的解析器。
 * 决策结果是一个 {@link DocumentParser}，业务层直接调用其 {@code extractAsMarkdown} 即可。
 * </p>
 * <p>
 * 决策规则：
 * <ul>
 *   <li>文档级覆盖优先（非空时覆盖知识库级）。</li>
 *   <li>{@code MULTIMODAL_LLM}：显式走 Tika（其内部对复杂 PDF 触发多模态 LLM）。</li>
 *   <li>{@code LOCAL_MINERU} / {@code REMOTE_MINERU} / {@code AUTO}：MinerU 可用则走 MinerU，否则回退 Tika。</li>
 *   <li>非 PDF / 非 MinerU 可处理格式：一律回退 Tika，避免改变现有 Office/图片处理行为。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParseEngineResolver {

    private final MineruConfigService mineruConfigService;
    private final TikaDocumentParser tikaParser;
    private final MineruDocumentParser mineruParser;

    /**
     * 决策实际使用的解析器
     *
     * @param kbEngine   知识库级解析引擎（可空）
     * @param docEngine  文档级解析引擎覆盖（可空，优先于 kbEngine）
     * @param mimeType   文档 MIME 类型（用于判断是否为 MinerU 可处理格式）
     * @return 实际解析器（永不返回 null）
     */
    public DocumentParser resolveParser(ParseEngine kbEngine, ParseEngine docEngine, String mimeType) {
        // 1) 合并两级配置：文档级优先
        ParseEngine effective = docEngine != null ? docEngine : kbEngine;
        if (effective == null) {
            effective = ParseEngine.AUTO;
        }

        // 2) 非 MinerU 可处理格式 → 一律回退 Tika（PDF/图片才考虑 MinerU）
        if (!isMineruDocType(mimeType)) {
            return tikaParser;
        }

        // 3) 显式多模态 → Tika（内部对复杂 PDF 触发多模态 LLM）
        if (effective == ParseEngine.MULTIMODAL_LLM) {
            log.debug("解析引擎=多模态LLM，走 Tika 解析（内部触发多模态）");
            return tikaParser;
        }

        // 4) MinerU 类引擎（LOCAL/REMOTE/AUTO）→ 可用则 MinerU，否则 Tika
        if (effective.isMineru() || effective == ParseEngine.AUTO) {
            boolean usable = mineruConfigService.hasUsableEndpoint();
            if (usable) {
                // 为 MinerU 解析器注入当前线程待用引擎
                MineruDocumentParser.useEngine(effective);
                return mineruParser;
            }
            log.warn("MinerU 端点不可用，回退 Tika: engine={}", effective);
            return tikaParser;
        }

        // 5) 其他未知值 → 默认 Tika
        return tikaParser;
    }

    /**
     * 判断是否为 MinerU 可处理的文档格式（PDF / 图片）
     */
    private boolean isMineruDocType(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        String lower = mimeType.toLowerCase();
        return lower.contains("pdf") || lower.startsWith("image/");
    }
}