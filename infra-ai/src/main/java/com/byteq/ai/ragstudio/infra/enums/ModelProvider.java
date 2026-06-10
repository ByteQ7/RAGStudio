package com.byteq.ai.ragstudio.infra.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 模型提供商枚举
 * <p>
 * 统一管理系统所支持的所有 AI 模型提供商，避免散落的字符串常量。
 * 每个枚举值对应一个具体的模型服务提供商，包含其唯一标识 ID。
 * 在配置解析、URL 路由和模型调用等场景中通过该枚举进行提供商识别和路由分发。
 * </p>
 */
@Getter
@RequiredArgsConstructor
public enum ModelProvider {

    /**
     * Ollama 本地模型服务
     * <p>
     * 用于本地部署的开源大模型运行框架，支持在本地运行和管理各种开源 LLM 模型。
     * 适用于开发环境和数据隐私要求较高的场景。
     * </p>
     */
    OLLAMA("ollama"),

    /**
     * 阿里云百炼大模型平台
     * <p>
     * 阿里云推出的一站式大模型服务平台，提供通义千问系列模型的 API 访问。
     * 适用于生产环境中需要使用阿里云 AI 能力的场景。
     * </p>
     */
    BAI_LIAN("bailian"),

    /**
     * 硅基流动 AI 模型服务
     * <p>
     * 提供多种开源和商业模型的 API 代理服务，支持丰富的模型选择。
     * 可用于访问包括 DeepSeek、Qwen 等多种主流模型。
     * </p>
     */
    SILICON_FLOW("siliconflow"),

    /**
     * AI Hub Mix 推理服务
     * <p>
     * 聚合多模型推理服务提供商，提供统一的 API 接口访问多种模型。
     * 适用于需要灵活切换和组合不同模型提供商的场景。
     * </p>
     */
    AI_HUB_MIX("aihubmix"),

    DEEPSEEK("deepseek"),

    /**
     * 空实现（No Operation）
     * <p>
     * 用于测试、开发调试或占位场景。该实现不会发起真实的模型调用，
     * 可以在集成测试中作为桩使用，或在功能未完全配置时避免空指针异常。
     * </p>
     */
    NOOP("noop");

    /**
     * 提供商在配置文件中的唯一标识符
     * <p>
     * 对应配置文件中 providers 映射的 key 值，
     * 以及 {@link AIModelProperties.ModelCandidate#provider} 字段的值。
     * 不区分大小写。
     * </p>
     */
    private final String id;

    /**
     * 判断给定的提供商名称是否与当前枚举值匹配
     * <p>
     * 匹配时不区分大小写，用于从配置文件中读取的字符串标识符到枚举值的转换校验。
     * </p>
     *
     * @param provider 待匹配的提供商名称字符串
     * @return 如果 provider 不为 null 且与当前枚举的 id 忽略大小写相等，返回 true；否则返回 false
     */
    public boolean matches(String provider) {
        return provider != null && provider.equalsIgnoreCase(id);
    }
}
