package com.byteq.ai.ragstudio.core.parser;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文档解析器选择器（策略模式）
 * <p>
 * 负责管理和选择合适的文档解析策略。根据解析器类型或 MIME 类型，
 * 动态选择最合适的解析器实现。该选择器体现了策略模式的核心思想，
 * 将解析算法的选择与使用分离。
 * </p>
 * <p>
 * 支持两种选择方式：
 * <ul>
 *   <li>按类型选择：通过 {@link #select(String)} 指定解析器类型
 *   （如 {@link ParserType#TIKA}、{@link ParserType#MARKDOWN}）</li>
 *   <li>按 MIME 类型选择：通过 {@link #selectByMimeType(String)} 自动匹配
 *   支持该 MIME 类型的解析器，匹配失败时默认返回 Tika 解析器</li>
 * </ul>
 * </p>
 *
 * @see DocumentParser
 * @see ParserType
 */
@Component
public class DocumentParserSelector {

    /**
     * 所有可用的解析器列表
     */
    private final List<DocumentParser> strategies;

    /**
     * 解析器类型到实现的映射表
     * key 为解析器类型标识，value 为对应的解析器实例
     */
    private final Map<String, DocumentParser> strategyMap;

    // 初始化解析器列表和类型映射表，将注入的所有解析器按类型注册到 Map 中（重复类型保留先注册的）
    public DocumentParserSelector(List<DocumentParser> parsers) {
        this.strategies = parsers;
        this.strategyMap = parsers.stream()
                .collect(Collectors.toMap(
                        DocumentParser::getParserType,
                        Function.identity(),
                        (existing, replacement) -> existing
                ));
    }

    /**
     * 根据解析器类型选择对应的解析策略
     *
     * @param parserType 解析器类型标识（如 {@link ParserType#TIKA}、{@link ParserType#MARKDOWN}）
     * @return 解析器实例，如果指定类型不存在则返回 null
     */
    public DocumentParser select(String parserType) {
        return strategyMap.get(parserType);
    }

    /**
     * 根据 MIME 类型自动选择合适的解析策略
     * <p>
     * 遍历所有可用的解析器，返回第一个声明支持该 MIME 类型的解析器。
     * 如果没有找到匹配的解析器，则返回默认的 Tika 通用解析器。
     * </p>
     *
     * @param mimeType MIME 类型（如 "application/pdf"、"text/markdown"）
     * @return 支持该 MIME 类型的解析器，默认回退到 Tika 解析器
     */
    public DocumentParser selectByMimeType(String mimeType) {
        return strategies.stream()
                .filter(parser -> parser.supports(mimeType))
                .findFirst()
                .orElseGet(() -> select(ParserType.TIKA.getType()));
    }

    /**
     * 获取所有可用的解析策略列表
     *
     * @return 不可变的所有解析器列表
     */
    public List<DocumentParser> getAllStrategies() {
        return List.copyOf(strategies);
    }

    /**
     * 获取所有可用的解析器类型列表
     *
     * @return 解析器类型标识列表
     */
    public List<String> getAvailableTypes() {
        return strategies.stream()
                .map(DocumentParser::getParserType)
                .toList();
    }
}
