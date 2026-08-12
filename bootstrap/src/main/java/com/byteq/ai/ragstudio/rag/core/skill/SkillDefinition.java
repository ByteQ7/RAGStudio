package com.byteq.ai.ragstudio.rag.core.skill;

import lombok.Data;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * SKILL 定义 — 元数据来自 SKILL.md frontmatter（Agent Skills 标准），
 * 执行配置来自可选的 skill.yaml，目录扫描结果由 SkillLoader 填充。
 * <p>
 * SKILL.md frontmatter 字段：
 * <ul>
 *   <li>name — 必填，技能唯一标识（小写+连字符，须与目录名一致）</li>
 *   <li>description — 必填，技能做什么 + 何时使用（注入 System Prompt）</li>
 *   <li>license / compatibility / metadata — 可选元信息</li>
 * </ul>
 * skill.yaml 可选字段（缺省则为纯知识型技能，不注册为可调用工具）：
 * <ul>
 *   <li>type — http / script / command</li>
 *   <li>config — 类型相关配置</li>
 *   <li>parameters — 参数定义 {type, properties, required} 格式</li>
 * </ul>
 */
@Data
public class SkillDefinition {

    // ==================== 从 SKILL.md frontmatter 解析 ====================

    /** 技能名称（唯一标识，须与目录名一致） */
    private String name;

    /** 技能描述（注入 System Prompt，帮助 LLM 判断何时触发） */
    private String description;

    /** 许可证（可选） */
    private String license;

    /** 环境兼容性要求（可选） */
    private String compatibility;

    /** 附加元数据（可选），标准约定 version 放在 metadata.version */
    private Map<String, String> metadata = Map.of();

    // ==================== 从 skill.yaml 解析（可选） ====================

    /** 执行类型：http / script / command；null 表示纯知识型技能 */
    private String type;

    /** 类型相关的配置参数 */
    private Map<String, Object> config;

    /** 参数定义（标准 JSON Schema 格式：{type, properties{...}, required[...]}） */
    private Map<String, Object> parameters;

    // ==================== 由 SkillLoader 扫描填充 ====================

    /** 该 SKILL 在磁盘上的目录路径 */
    private Path skillDir;

    /** SKILL.md 正文（frontmatter 已剥离，仅指令部分） */
    private String skillDoc;

    /** scripts/ 目录下的文件名列表 */
    private List<String> scriptFiles;

    /** references/ 目录下的文件名列表 */
    private List<String> referenceFiles;

    /** 版本号（metadata.version，无则 null） */
    private String version;

    /** 内容指纹（SKILL.md + skill.yaml 的 SHA-256） */
    private String contentHash;

    /** 加载与校验诊断（WARN 不影响加载，ERROR 不会进入技能列表） */
    private List<SkillIssue> issues = List.of();

    /** 是否为可执行技能（有 type 配置，可注册为 Agent 工具） */
    public boolean isExecutable() {
        return type != null && !type.isBlank();
    }
}
