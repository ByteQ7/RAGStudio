package com.byteq.ai.ragstudio.ingestion.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.ingestion.domain.context.IngestionContext;
import com.byteq.ai.ragstudio.ingestion.domain.context.StructuredDocument;
import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionNodeType;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.NodeConfig;
import com.byteq.ai.ragstudio.ingestion.domain.result.NodeResult;
import com.byteq.ai.ragstudio.ingestion.domain.settings.ParserSettings;
import com.byteq.ai.ragstudio.ingestion.util.MimeTypeDetector;
import com.byteq.ai.ragstudio.core.parser.DocumentParser;
import com.byteq.ai.ragstudio.core.parser.DocumentParserSelector;
import com.byteq.ai.ragstudio.core.parser.ParseResult;
import com.byteq.ai.ragstudio.core.parser.ParserType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 文档解析节点（ParserNode）
 * <p>
 * 数据摄入流水线的第二个处理节点，负责将 FetcherNode 获取的原始字节流解析为结构化文本内容。
 * 支持的文档格式包括：PDF、Word（doc/docx）、Excel（xls/xlsx）、PowerPoint（ppt/pptx）、
 * Markdown、纯文本以及常见图片格式等。
 * </p>
 * <p>
 * 核心处理逻辑：
 * <ol>
 *   <li>通过 MIME 类型检测或文件后缀名识别文档格式</li>
 *   <li>根据流水线配置的解析规则校验文件类型是否在允许范围内</li>
 *   <li>使用 Tika 文档解析器将二进制内容解析为纯文本</li>
 *   <li>将解析结果封装为 {@link StructuredDocument} 存入上下文</li>
 * </ol>
 * </p>
 */
@Component
public class ParserNode implements IngestionNode {

    /**
     * Jackson JSON 对象映射器，用于解析节点配置
     */
    private final ObjectMapper objectMapper;

    /**
     * 文档解析器选择器，根据文档类型选择合适的解析策略
     */
    private final DocumentParserSelector parserSelector;

    public ParserNode(ObjectMapper objectMapper, DocumentParserSelector parserSelector) {
        this.objectMapper = objectMapper;
        this.parserSelector = parserSelector;
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.PARSER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        if (context.getRawBytes() == null || context.getRawBytes().length == 0) {
            return NodeResult.fail(new ClientException("解析器缺少原始字节"));
        }

        String mimeType = context.getMimeType();
        if (!StringUtils.hasText(mimeType)) {
            String fileName = context.getSource() == null ? null : context.getSource().getFileName();
            mimeType = MimeTypeDetector.detect(context.getRawBytes(), fileName);
            context.setMimeType(mimeType);
        }

        ParserSettings settings = parseSettings(config.getSettings());
        String fileName = context.getSource() == null ? null : context.getSource().getFileName();

        // 根据配置的规则验证文件类型是否允许被解析
        validateMimeType(settings, mimeType, fileName);

        ParserSettings.ParserRule rule = matchRule(settings, mimeType, fileName);
        DocumentParser parser = parserSelector.select(ParserType.TIKA.getType());
        if (parser == null) {
            return NodeResult.fail(new ClientException("未配置 Tika 解析器"));
        }

        Map<String, Object> options = rule == null ? Collections.emptyMap() : rule.getOptions();
        ParseResult result = parser.parse(context.getRawBytes(), mimeType, options);
        context.setRawText(result.text());

        // 将解析结果转换为结构化文档对象，包括文本内容和元数据
        StructuredDocument document = StructuredDocument.builder()
                .text(result.text())
                .metadata(result.metadata())
                .build();
        context.setDocument(document);

        return NodeResult.ok("解析文本长度=" + (result.text() == null ? 0 : result.text().length()));
    }

    /**
     * 验证文件类型是否符合配置的解析规则
     * <p>
     * 如果流水线配置了 MIME 类型过滤规则，则检查当前文档的类型是否在允许列表中。
     * 没有配置规则时允许所有文件类型通过。
     * </p>
     *
     * @param settings 解析器配置，包含 MIME 类型规则列表
     * @param mimeType 文档的 MIME 类型
     * @param fileName 文档文件名（用于辅助类型识别）
     * @throws ClientException 如果文件类型不匹配且规则不允许
     */
    private void validateMimeType(ParserSettings settings, String mimeType, String fileName) {
        if (settings == null || settings.getRules() == null || settings.getRules().isEmpty()) {
            return;
        }

        String resolvedType = resolveType(mimeType, fileName);

        boolean hasMatch = checkMimeTypeMatch(settings, resolvedType);

        if (!hasMatch) {
            List<String> allowedTypes = settings.getRules().stream()
                    .filter(rule -> rule != null && StringUtils.hasText(rule.getMimeType()))
                    .map(rule -> normalizeType(rule.getMimeType()))
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();

            throw new ClientException(
                    String.format("文件类型不符合要求。当前文件类型: %s，允许的类型: %s",
                            resolvedType,
                            String.join(", ", allowedTypes))
            );
        }
    }

    /**
     * 检查解析规则中是否有与当前文档类型匹配的规则
     */
    private boolean checkMimeTypeMatch(ParserSettings settings, String resolvedType) {
        for (ParserSettings.ParserRule rule : settings.getRules()) {
            if (rule == null || !StringUtils.hasText(rule.getMimeType())) {
                continue;
            }
            String configured = normalizeType(rule.getMimeType());
            if (!StringUtils.hasText(configured)) {
                continue;
            }
            if ("ALL".equals(configured) || configured.equalsIgnoreCase(resolvedType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析节点配置中的 settings JSON 为 ParserSettings 对象
     *
     * @param node JSON 配置节点
     * @return 解析器配置对象
     */
    private ParserSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return ParserSettings.builder().rules(List.of()).build();
        }
        return objectMapper.convertValue(node, ParserSettings.class);
    }

    /**
     * 匹配当前文档类型对应的解析规则
     * <p>
     * 在配置的规则列表中查找与文档类型匹配的规则，用于获取该类型的特定解析选项。
     * </p>
     */
    private ParserSettings.ParserRule matchRule(ParserSettings settings, String mimeType, String fileName) {
        if (settings == null || settings.getRules() == null || settings.getRules().isEmpty()) {
            return null;
        }
        String resolvedType = resolveType(mimeType, fileName);
        for (ParserSettings.ParserRule rule : settings.getRules()) {
            if (rule == null || !StringUtils.hasText(rule.getMimeType())) {
                continue;
            }
            String configured = normalizeType(rule.getMimeType());
            if (!StringUtils.hasText(configured)) {
                continue;
            }
            if ("ALL".equals(configured) || configured.equalsIgnoreCase(resolvedType)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * 根据 MIME 类型和文件名解析文档的标准化类型名称
     */
    private String resolveType(String mimeType, String fileName) {
        String byName = resolveTypeByName(fileName);
        if (StringUtils.hasText(byName)) {
            return byName;
        }
        if (!StringUtils.hasText(mimeType)) {
            return "UNKNOWN";
        }
        String lower = mimeType.trim().toLowerCase();
        if (lower.contains("pdf")) return "PDF";
        if (lower.contains("markdown")) return "MARKDOWN";
        if (lower.contains("word") || lower.contains("msword") || lower.contains("wordprocessingml")) return "WORD";
        if (lower.contains("excel") || lower.contains("spreadsheetml")) return "EXCEL";
        if (lower.contains("powerpoint") || lower.contains("presentation")) return "PPT";
        if (lower.startsWith("image/")) return "IMAGE";
        if (lower.startsWith("text/")) return "TEXT";
        return "UNKNOWN";
    }

    /**
     * 根据文件扩展名解析文档类型
     */
    private String resolveTypeByName(String fileName) {
        if (!StringUtils.hasText(fileName)) return null;
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "MARKDOWN";
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "WORD";
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return "EXCEL";
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return "PPT";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp")) return "IMAGE";
        if (lower.endsWith(".txt")) return "TEXT";
        return null;
    }

    /**
     * 将配置中的类型名称标准化为内部使用的类型标识
     * <p>
     * 支持通配符（*、ALL、DEFAULT）以及各种类型名称的别名映射。
     * </p>
     */
    private String normalizeType(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        String value = raw.trim().toUpperCase();
        return switch (value) {
            case "*", "ALL", "DEFAULT" -> "ALL";
            case "MD", "MARKDOWN" -> "MARKDOWN";
            case "DOC", "DOCX", "WORD" -> "WORD";
            case "XLS", "XLSX", "EXCEL" -> "EXCEL";
            case "PPT", "PPTX", "POWERPOINT" -> "PPT";
            case "TXT", "TEXT" -> "TEXT";
            case "PNG", "JPG", "JPEG", "GIF", "BMP", "WEBP", "IMAGE", "IMG" -> "IMAGE";
            case "PDF" -> "PDF";
            default -> value;
        };
    }
}
