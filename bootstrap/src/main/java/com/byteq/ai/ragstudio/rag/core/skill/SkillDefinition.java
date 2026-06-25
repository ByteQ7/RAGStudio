package com.byteq.ai.ragstudio.rag.core.skill;

import lombok.Data;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * SKILL 定义 — 从 skill.yaml + SKILL.md + 目录扫描得到的完整技能描述
 * <p>
 * skill.yaml 中的字段：
 * <ul>
 *   <li>name — 工具名称，Agent Action 中引用</li>
 *   <li>description — 工具描述，注入 System Prompt</li>
 *   <li>prompt — 附加提示词（可选）</li>
 *   <li>type — http / script / command</li>
 *   <li>config — 类型相关配置</li>
 *   <li>parameters — 参数定义 {type, properties, required} 格式</li>
 * </ul>
 * 其余字段由 SkillLoader 在扫描目录时填充。
 */
@Data
public class SkillDefinition {

    // ==================== 从 skill.yaml 解析 ====================

    /** 工具名称（唯一标识，Agent Action 中引用） */
    private String name;

    /** 工具描述（注入 System Prompt，帮助 LLM 理解用途） */
    private String description;

    /** 附加提示词（可选，注入 System Prompt） */
    private String prompt;

    /** 技能类型：http / script / command */
    private String type;

    /** 类型相关的配置参数 */
    private Map<String, Object> config;

    /** 参数定义（标准 JSON Schema 格式：{type, properties{...}, required[...]}） */
    private Map<String, Object> parameters;

    // ==================== 由 SkillLoader 扫描填充 ====================

    /** 该 SKILL 在磁盘上的目录路径 */
    private Path skillDir;

    /** SKILL.md 全文（可选） */
    private String skillDoc;

    /** scripts/ 目录下的文件名列表 */
    private List<String> scriptFiles;

    /** references/ 目录下的文件名列表 */
    private List<String> referenceFiles;
}
