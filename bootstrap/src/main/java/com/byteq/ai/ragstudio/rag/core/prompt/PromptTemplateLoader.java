package com.byteq.ai.ragstudio.rag.core.prompt;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.rag.prompt.config.PromptConfigService;
import com.byteq.ai.ragstudio.rag.prompt.config.PromptKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示模板加载器
 * <p>
 * 负责加载提示模板并支持模板变量填充。模板内容读取策略：
 * <b>DB 优先、classpath 兜底</b>——已纳入 {@code t_prompt_config} 管理的模板
 * （由 {@link com.byteq.ai.ragstudio.rag.prompt.config.PromptKeys} 注册）优先取 DB 快照
 * （后管「提示词管理」页编辑后热重载生效），否则回退 classpath 下 {@code resources/prompt/*.st} 默认值。
 * </p>
 * <p>
 * 所有现有调用方（Agent 主链路、上下文格式化、查询改写、记忆等）无需改动即可获得热重载能力。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateLoader {

    private final ResourceLoader resourceLoader;
    private final PromptConfigService promptConfigService;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> sectionCache = new ConcurrentHashMap<>();

    /**
     * 加载指定路径的提示模板（DB 优先，classpath 兜底）
     *
     * @param path 模板文件路径，支持classpath:前缀
     * @return 模板内容字符串
     * @throws IllegalArgumentException 当路径为空时抛出
     * @throws IllegalStateException    当模板文件不存在或读取失败时抛出
     */
    public String load(String path) {
        if (StrUtil.isBlank(path)) {
            throw new IllegalArgumentException("提示模板路径为空");
        }
        String key = PromptKeys.fromClasspathPath(path).map(PromptKeys::key).orElse(null);
        if (key != null) {
            String managed = promptConfigService.getEffectiveContent(key);
            if (managed != null && !managed.isEmpty()) {
                return managed;
            }
        }
        return cache.computeIfAbsent(path, this::readResource);
    }

    /**
     * 渲染提示模板，将模板中的占位符替换为实际值
     *
     * @param path  模板文件路径
     * @param slots 占位符映射表，键为占位符名称，值为替换内容
     * @return 渲染后的完整提示文本
     */
    public String render(String path, Map<String, String> slots) {
        String template = load(path);
        String filled = PromptTemplateUtils.fillSlots(template, slots);
        return PromptTemplateUtils.cleanupPrompt(filled);
    }

    /**
     * 加载模板文件中指定 section 的原始内容（DB 优先，classpath 兜底）
     *
     * @param path    模板文件路径
     * @param section section 名称（对应 {@code --- section: name ---} 中的 name）
     * @return section 的原始模板内容
     * @throws IllegalStateException 当 section 不存在时抛出
     */
    public String loadSection(String path, String section) {
        String key = PromptKeys.fromClasspathPath(path).map(PromptKeys::key).orElse(null);
        if (key != null) {
            String managed = promptConfigService.getSectionContent(key, section);
            if (managed != null) {
                return managed;
            }
        }
        Map<String, String> sections = sectionCache.computeIfAbsent(path, p -> {
            String content = cache.computeIfAbsent(p, this::readResource);
            return PromptTemplateUtils.parseSections(content);
        });
        String template = sections.get(section);
        if (template == null) {
            throw new IllegalStateException("模板 section 不存在：" + path + " -> " + section);
        }
        return template;
    }

    /**
     * 渲染模板文件中指定 section，并填充占位符
     *
     * @param path    模板文件路径
     * @param section section 名称
     * @param slots   占位符映射表
     * @return 渲染后的文本
     */
    public String renderSection(String path, String section, Map<String, String> slots) {
        String template = loadSection(path, section);
        String filled = PromptTemplateUtils.fillSlots(template, slots);
        return PromptTemplateUtils.cleanupPrompt(filled);
    }

    /**
     * 从资源路径读取模板内容（classpath 兜底，仅读取不落缓存）
     *
     * @param path 模板文件路径
     * @return 模板内容字符串
     * @throws IllegalStateException 当模板文件不存在或读取失败时抛出
     */
    private String readResource(String path) {
        String location = path.startsWith("classpath:") ? path : "classpath:" + path;
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("提示词模板路径不存在：" + path);
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("读取提示模板失败，路径：{}", path, e);
            throw new IllegalStateException("读取提示模板失败，路径：" + path, e);
        }
    }
}
