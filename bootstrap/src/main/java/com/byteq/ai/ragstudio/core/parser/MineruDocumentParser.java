package com.byteq.ai.ragstudio.core.parser;

import com.byteq.ai.ragstudio.core.parser.mineru.MineruClient;
import com.byteq.ai.ragstudio.core.parser.mineru.MineruConfigService;
import com.byteq.ai.ragstudio.core.parser.mineru.MineruEndpoint;
import com.byteq.ai.ragstudio.core.parser.mineru.MineruProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * MinerU 文档解析器
 * <p>
 * 基于 MinerU 服务（本地/远程）解析复杂文档（PDF/扫描件/含公式表格的文档）。
 * 作为 {@link DocumentParserSelector} 策略体系的一员，由 {@link ParseEngineResolver}
 * 在用户选择「本地MinerU / 远程MinerU / AUTO」时优先选用。
 * </p>
 * <p>
 * 回退策略：MinerU 解析失败 / 结果过短 / 端点不可用时，自动降级到
 * {@link TikaDocumentParser}（其内部对含表格/图片的 PDF 会再走多模态 LLM 兜底），
 * 保证解析链路不中断、内容不丢失。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MineruDocumentParser implements DocumentParser {

    private final MineruClient mineruClient;
    private final MineruConfigService mineruConfigService;
    private final MineruProperties mineruProperties;
    private final TikaDocumentParser tikaParser;

    /**
     * 当前线程待用的解析引擎（由业务层在调用前设置，避免并发串扰）
     */
    private static final ThreadLocal<ParseEngine> EFFECTIVE_ENGINE = new ThreadLocal<>();

    @Override
    public String getParserType() {
        return ParserType.MINERU.getType();
    }

    /**
     * 为当前线程指定实际解析引擎（LOCAL_MINERU / REMOTE_MINERU / AUTO）
     */
    public static void useEngine(ParseEngine engine) {
        EFFECTIVE_ENGINE.set(engine == null ? ParseEngine.AUTO : engine);
    }

    /**
     * 清除当前线程的引擎标记
     */
    public static void clearEngine() {
        EFFECTIVE_ENGINE.remove();
    }

    @Override
    public boolean supports(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        String lower = mimeType.toLowerCase();
        return lower.contains("pdf") || lower.startsWith("image/");
    }

    /**
     * 提取 Markdown：优先 MinerU，失败回退 Tika
     */
    @Override
    public String extractAsMarkdown(InputStream stream, String fileName) {
        byte[] bytes = readAll(stream);
        if (bytes.length == 0) {
            return "";
        }

        ParseEngine engine = EFFECTIVE_ENGINE.get();
        if (engine == null) {
            engine = ParseEngine.AUTO;
        }
        EFFECTIVE_ENGINE.remove();

        try {
            MineruEndpoint endpoint = mineruConfigService.resolveEndpoint(engine);
            if (endpoint == null) {
                log.warn("MinerU 端点不可用，回退 Tika 解析: file={}, engine={}", fileName, engine);
                return tikaParser.extractAsMarkdown(new ByteArrayInputStream(bytes), fileName);
            }

            long start = System.currentTimeMillis();
            String md = mineruClient.parse(bytes, fileName, endpoint, mineruProperties.getTimeoutSeconds());
            long cost = System.currentTimeMillis() - start;

            if (md == null || md.isBlank() || md.trim().length() < mineruProperties.getMinTextLength()) {
                log.warn("MinerU 解析结果过短({}字符)或为空，回退 Tika: file={}, engine={}, cost={}ms",
                        md == null ? 0 : md.trim().length(), fileName, engine, cost);
                return tikaParser.extractAsMarkdown(new ByteArrayInputStream(bytes), fileName);
            }

            log.info("MinerU 解析成功: file={}, engine={}, chars={}, cost={}ms",
                    fileName, engine, md.trim().length(), cost);
            return md;
        } catch (Exception e) {
            log.warn("MinerU 解析异常，回退 Tika: file={}, msg={}", fileName, e.getMessage());
            try {
                return tikaParser.extractAsMarkdown(new ByteArrayInputStream(bytes), fileName);
            } catch (Exception ex) {
                log.warn("Tika 回退解析失败: file={}, msg={}", fileName, ex.getMessage());
                return "";
            }
        }
    }

    private static byte[] readAll(InputStream stream) {
        try {
            return stream.readAllBytes();
        } catch (Exception e) {
            return new byte[0];
        }
    }
}