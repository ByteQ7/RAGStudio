package com.byteq.ai.ragstudio.rag.core.skill;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.rag.core.tool.Tool;
import com.byteq.ai.ragstudio.rag.core.tool.ToolNameUtil;
import com.byteq.ai.ragstudio.rag.core.tool.ToolResult;
import com.byteq.ai.ragstudio.rag.core.mcp.McpToolExecutor;
import com.byteq.ai.ragstudio.rag.core.mcp.McpToolRegistry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具阅读器 — 浏览和读取 SKILL + MCP 工具的详细信息
 */
@Slf4j
public class ToolReaderTool implements Tool {

    private static final String TOOL_NAME = "tool_reader";
    private static final int MAX_CONTENT_LENGTH = 5000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final SkillLoader skillLoader;
    private final McpToolRegistry mcpToolRegistry;

    /** 原始工具名 → Agent 注册的规范化名映射（null 时退回静态规范化） */
    private final Map<String, String> exposedNameMapping;

    public ToolReaderTool(SkillLoader skillLoader, McpToolRegistry mcpToolRegistry) {
        this(skillLoader, mcpToolRegistry, null);
    }

    public ToolReaderTool(SkillLoader skillLoader, McpToolRegistry mcpToolRegistry,
                          Map<String, String> exposedNameMapping) {
        this.skillLoader = skillLoader;
        this.mcpToolRegistry = mcpToolRegistry;
        this.exposedNameMapping = exposedNameMapping;
    }

    /**
     * 原始工具名 → Agent 中注册的规范化名（与模型可见/可调用的名称一致）：
     * MCP/SKILL 工具名可能含中文、点号等非法字符，注册时已被清洗（见 ProjectToolAdapter），
     * 此处必须展示同一名称，否则模型会尝试调用不存在的原始名。
     */
    private String exposedName(String original) {
        if (exposedNameMapping != null) {
            String mapped = exposedNameMapping.get(original);
            if (mapped != null) {
                return mapped;
            }
        }
        return ToolNameUtil.sanitize(original);
    }

    /** 按规范化名或原始名查找 SKILL */
    private SkillDefinition findSkill(String toolName) {
        SkillDefinition direct = skillLoader.getSkill(toolName);
        if (direct != null) {
            return direct;
        }
        for (SkillDefinition def : skillLoader.getAllSkills()) {
            if (exposedName(def.getName()).equalsIgnoreCase(toolName)) {
                return def;
            }
        }
        return null;
    }

    @Override
    public String name() { return TOOL_NAME; }

    @Override
    public String description() {
        return "浏览和读取工具（MCP 服务 + SKILL 技能）的详细信息。" +
               "当你需要发现可用工具、了解某个工具的参数或使用方法时使用此工具。" +
               "支持的操作: search(搜索工具) / read(读工具详情) / list_scripts / read_script / list_refs / read_ref";
    }

    @Override
    public McpSchema.JsonSchema inputSchema() {
        return new McpSchema.JsonSchema("object",
            Map.of(
                "action", Map.of("type", "string",
                    "description", "search(按关键词搜索)/read(读工具详情)/list_scripts/read_script/list_refs/read_ref"),
                "keyword", Map.of("type", "string",
                    "description", "搜索关键词（search 时必填）"),
                "tool_name", Map.of("type", "string",
                    "description", "工具名称（read 时必填，MCP或SKILL名称）"),
                "skill", Map.of("type", "string",
                    "description", "SKILL 名称（list_scripts/read_script/list_refs/read_ref 时必填）"),
                "file", Map.of("type", "string",
                    "description", "文件名（read_script/read_ref 时必填）")
            ),
            List.of("action"), null, null, null
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        if (params == null) return ToolResult.failure(TOOL_NAME, "缺少参数");
        String action = params.get("action") instanceof String a ? a.trim().toLowerCase() : "";
        if (StrUtil.isBlank(action)) return ToolResult.failure(TOOL_NAME, "缺少必填参数: action");

        return switch (action) {
            case "search" -> search(params);
            case "read" -> read(params);
            case "list_scripts" -> skillAction(params, this::listScripts);
            case "read_script" -> skillAction(params, this::readScript);
            case "list_refs" -> skillAction(params, this::listRefs);
            case "read_ref" -> skillAction(params, this::readRef);
            default -> ToolResult.failure(TOOL_NAME,
                "不支持的 action: " + action + "，可选: search/read/list_scripts/read_script/list_refs/read_ref");
        };
    }

    // ==================== search（搜索 MCP + SKILL） ====================

    private ToolResult search(Map<String, Object> params) {
        String keyword = params.get("keyword") instanceof String k ? k.trim().toLowerCase() : "";
        if (StrUtil.isBlank(keyword)) {
            return ToolResult.failure(TOOL_NAME, "缺少 keyword 参数");
        }

        StringBuilder sb = new StringBuilder("=== 工具搜索结果 (关键词: " + keyword + ") ===\n\n");
        int count = 0;

        // MCP 工具
        List<String> mcpMatches = new ArrayList<>();
        for (McpToolExecutor exe : mcpToolRegistry.listAllExecutors()) {
            var tool = exe.getToolDefinition();
            String name = tool.name() != null ? tool.name().toLowerCase() : "";
            String desc = tool.description() != null ? tool.description().toLowerCase() : "";
            if (name.contains(keyword) || desc.contains(keyword)) {
                mcpMatches.add("- " + exposedName(exe.getToolDefinition().name()) + ": " +
                    truncate(exe.getToolDefinition().description(), 100));
                count++;
            }
        }
        if (!mcpMatches.isEmpty()) {
            sb.append("【MCP 工具】\n").append(String.join("\n", mcpMatches)).append("\n\n");
        }

        // SKILL
        List<String> skillMatches = new ArrayList<>();
        for (SkillDefinition def : skillLoader.getAllSkills()) {
            String name = def.getName() != null ? def.getName().toLowerCase() : "";
            String desc = def.getDescription() != null ? def.getDescription().toLowerCase() : "";
            if (name.contains(keyword) || desc.contains(keyword)) {
                skillMatches.add("- " + exposedName(def.getName()) + ": " + truncate(def.getDescription(), 100));
                count++;
            }
        }
        if (!skillMatches.isEmpty()) {
            sb.append("【SKILL 技能】\n").append(String.join("\n", skillMatches)).append("\n\n");
        }

        if (count == 0) {
            sb.append("未找到匹配的工具。尝试更宽泛的关键词。\n");
            // 列出所有工具名供参考
            List<String> allNames = new ArrayList<>();
            mcpToolRegistry.listAllExecutors().forEach(e -> allNames.add("[MCP] " + e.getToolDefinition().name()));
            skillLoader.getAllSkills().forEach(s -> allNames.add("[SKILL] " + s.getName()));
            if (!allNames.isEmpty()) {
                sb.append("\n当前可用工具: ").append(String.join(", ", allNames));
            }
        } else {
            sb.append("共 ").append(String.valueOf(count)).append(" 个结果。使用 tool_reader read 查看详情。");
        }
        return ToolResult.success(TOOL_NAME, sb.toString());
    }

    // ==================== read（读 MCP + SKILL 详情） ====================

    private ToolResult read(Map<String, Object> params) {
        String toolName = params.get("tool_name") instanceof String t ? t.trim() : "";
        if (StrUtil.isBlank(toolName)) return ToolResult.failure(TOOL_NAME, "缺少 tool_name 参数");

        StringBuilder sb = new StringBuilder();

        // 查 MCP
        List<McpToolExecutor> mcpExecutors = mcpToolRegistry.listAllExecutors().stream()
            .filter(e -> e.getToolDefinition().name().equalsIgnoreCase(toolName)
                    || exposedName(e.getToolDefinition().name()).equalsIgnoreCase(toolName))
            .toList();
        for (McpToolExecutor exe : mcpExecutors) {
            var tool = exe.getToolDefinition();
            sb.append("=== MCP 工具: ").append(exposedName(tool.name())).append(" ===\n");
            if (tool.description() != null) sb.append("描述: ").append(tool.description()).append("\n");
            if (tool.inputSchema() != null) {
                sb.append("参数: ").append(GSON.toJson(tool.inputSchema().properties())).append("\n");
            }
            sb.append("\n");
        }

        // 查 SKILL
        SkillDefinition def = findSkill(toolName);
        if (def != null) {
            sb.append("=== SKILL: ").append(exposedName(def.getName())).append(" ===\n");
            if (def.getDescription() != null) sb.append("描述: ").append(def.getDescription()).append("\n");
            if (StrUtil.isNotBlank(def.getVersion())) sb.append("版本: ").append(def.getVersion()).append("\n");
            if (StrUtil.isNotBlank(def.getLicense())) sb.append("许可证: ").append(def.getLicense()).append("\n");
            if (StrUtil.isNotBlank(def.getType())) sb.append("执行类型: ").append(def.getType()).append("\n");

            if (StrUtil.isNotBlank(def.getSkillDoc())) {
                String doc = def.getSkillDoc();
                if (doc.length() > MAX_CONTENT_LENGTH) doc = doc.substring(0, MAX_CONTENT_LENGTH) + "\n...（截断）";
                sb.append("\n<skill_content name=\"").append(exposedName(def.getName())).append("\">\n")
                        .append(doc)
                        .append("\nSkill directory: ").append(def.getSkillDir())
                        .append("\nRelative paths in this skill are relative to the skill directory.")
                        .append("\n</skill_content>\n");
            } else {
                sb.append("\n（该技能无 SKILL.md 指令正文）\n");
            }

            // 资源清单（不读取内容，由模型按需 list/read）
            List<String> scripts = def.getScriptFiles();
            List<String> refs = def.getReferenceFiles();
            if ((scripts != null && !scripts.isEmpty()) || (refs != null && !refs.isEmpty())) {
                sb.append("\n<skill_resources>\n");
                if (scripts != null) {
                    for (String s : scripts) {
                        sb.append("  <file>scripts/").append(s).append("</file>\n");
                    }
                }
                if (refs != null) {
                    for (String r : refs) {
                        sb.append("  <file>references/").append(r).append("</file>\n");
                    }
                }
                sb.append("</skill_resources>\n");
            }
        }

        if (sb.isEmpty()) {
            // Not found in either, suggest available tools
            List<String> allNames = new ArrayList<>();
            mcpToolRegistry.listAllExecutors().forEach(e -> allNames.add("[MCP] " + exposedName(e.getToolDefinition().name())));
            skillLoader.getAllSkills().forEach(s -> allNames.add("[SKILL] " + exposedName(s.getName())));
            return ToolResult.failure(TOOL_NAME, "未找到工具: " + toolName + "。可用: " + String.join(", ", allNames));
        }

        return ToolResult.success(TOOL_NAME, sb.toString());
    }

    // ==================== SKILL 专用操作 ====================

    private ToolResult skillAction(Map<String, Object> params,
                                    java.util.function.BiFunction<SkillDefinition, Map<String, Object>, ToolResult> fn) {
        String skillName = params.get("skill") instanceof String s ? s.trim() : "";
        if (StrUtil.isBlank(skillName)) return ToolResult.failure(TOOL_NAME, "缺少 skill 参数");
        SkillDefinition def = findSkill(skillName);
        if (def == null) {
            return ToolResult.failure(TOOL_NAME, "未找到 SKILL: " + skillName +
                "。可用 SKILL: " + skillLoader.getAllSkills().stream()
                    .map(s -> exposedName(s.getName())).collect(Collectors.joining(", ")));
        }
        return fn.apply(def, params);
    }

    private ToolResult listScripts(SkillDefinition def, Map<String, Object> params) {
        List<String> scripts = def.getScriptFiles();
        if (scripts == null || scripts.isEmpty()) return ToolResult.success(TOOL_NAME, "SKILL [" + def.getName() + "] 无脚本文件");
        return ToolResult.success(TOOL_NAME, "SKILL [" + def.getName() + "] 脚本:\n" + String.join("\n", scripts));
    }

    private ToolResult readScript(SkillDefinition def, Map<String, Object> params) {
        String file = params.get("file") instanceof String f ? f.trim() : "";
        if (StrUtil.isBlank(file)) return ToolResult.failure(TOOL_NAME, "缺少 file 参数");
        byte[] content = skillLoader.getScriptContent(def.getName(), file);
        if (content == null) {
            List<String> scripts = def.getScriptFiles();
            String av = scripts != null && !scripts.isEmpty() ? "可用: " + String.join(", ", scripts) : "无脚本";
            return ToolResult.failure(TOOL_NAME, "脚本不存在: " + file + "。" + av);
        }
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.length() > MAX_CONTENT_LENGTH) text = text.substring(0, MAX_CONTENT_LENGTH) + "\n...（截断）";
        return ToolResult.success(TOOL_NAME, "=== " + file + " ===\n\n" + text);
    }

    private ToolResult listRefs(SkillDefinition def, Map<String, Object> params) {
        List<String> refs = def.getReferenceFiles();
        if (refs == null || refs.isEmpty()) return ToolResult.success(TOOL_NAME, "SKILL [" + def.getName() + "] 无参考资料");
        return ToolResult.success(TOOL_NAME, "SKILL [" + def.getName() + "] 参考资料:\n" + String.join("\n", refs));
    }

    private ToolResult readRef(SkillDefinition def, Map<String, Object> params) {
        String file = params.get("file") instanceof String f ? f.trim() : "";
        if (StrUtil.isBlank(file)) return ToolResult.failure(TOOL_NAME, "缺少 file 参数");
        byte[] content = skillLoader.getReferenceContent(def.getName(), file);
        if (content == null) {
            List<String> refs = def.getReferenceFiles();
            String av = refs != null && !refs.isEmpty() ? "可用: " + String.join(", ", refs) : "无参考";
            return ToolResult.failure(TOOL_NAME, "参考文件不存在: " + file + "。" + av);
        }
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.length() > MAX_CONTENT_LENGTH) text = text.substring(0, MAX_CONTENT_LENGTH) + "\n...（截断）";
        return ToolResult.success(TOOL_NAME, "=== " + file + " ===\n\n" + text);
    }

    private static String truncate(String s, int len) {
        if (s == null || s.isEmpty() || s.length() <= len) return s != null ? s : "";
        return s.substring(0, len) + "...";
    }
}
