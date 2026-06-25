package com.byteq.ai.ragstudio.rag.core.skill;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.rag.core.agent.Tool;
import com.byteq.ai.ragstudio.rag.core.agent.ToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * SKILL 阅读器工具
 * <p>
 * 内置工具，注册到 ToolRegistry 中，让 LLM 在 Agent 循环中通过
 * 此工具查看 SKILL 的详细信息，包括 SKILL.md 文档、scripts 和
 * references 目录内容。
 * <p>
 * 此工具跟 TimeTool 一样，不依赖 MCP 服务，始终可用。
 */
@Slf4j
public class SkillReaderTool implements Tool {

    private static final String TOOL_NAME = "skill_reader";
    private static final String TOOL_DESCRIPTION =
            "读取 SKILL（技能）的详细信息。每个 SKILL 包含 skill.yaml 定义、SKILL.md 说明文档、" +
            "可执行脚本（scripts）和参考资料（references）。" +
            "当你需要了解某个 SKILL 的详细用途、参数、使用示例、参考材料时使用此工具。";

    /** 返回内容的最大字符数 */
    private static final int MAX_CONTENT_LENGTH = 5000;

    private final SkillLoader skillLoader;

    public SkillReaderTool(SkillLoader skillLoader) {
        this.skillLoader = skillLoader;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return TOOL_DESCRIPTION;
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(
                "object",
                Map.of(
                        "action", Map.of(
                                "type", "string",
                                "description", "操作类型：read_doc / list_scripts / read_script / list_refs / read_ref"
                        ),
                        "skill", Map.of(
                                "type", "string",
                                "description", "SKILL 名称，如 weather、ip-info"
                        ),
                        "file", Map.of(
                                "type", "string",
                                "description", "文件名（read_script / read_ref 时需要），如 fetch.py、api-doc.md"
                        )
                ),
                List.of("action", "skill"),
                null, null, null
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        if (params == null) {
            return ToolResult.failure(TOOL_NAME, "缺少参数");
        }

        String action = params.get("action") instanceof String a ? a.trim().toLowerCase() : "";
        String skillName = params.get("skill") instanceof String s ? s.trim() : "";

        if (StrUtil.isBlank(action)) {
            return ToolResult.failure(TOOL_NAME, "缺少必填参数: action（read_doc/list_scripts/read_script/list_refs/read_ref）");
        }
        if (StrUtil.isBlank(skillName)) {
            return ToolResult.failure(TOOL_NAME, "缺少必填参数: skill");
        }

        SkillDefinition def = skillLoader.getSkill(skillName);
        if (def == null) {
            return ToolResult.failure(TOOL_NAME, "未找到 SKILL: " + skillName +
                    "。可用 SKILL: " + String.join(", ", skillLoader.listSkillSummaries()
                            .stream().map(s -> s.get("name")).toList()));
        }

        return switch (action) {
            case "read_doc" -> readDoc(def);
            case "list_scripts" -> listScripts(def);
            case "read_script" -> readScript(def, params);
            case "list_refs" -> listRefs(def);
            case "read_ref" -> readRef(def, params);
            default -> ToolResult.failure(TOOL_NAME,
                    "不支持的 action: " + action + "，可选值: read_doc / list_scripts / read_script / list_refs / read_ref");
        };
    }

    private ToolResult readDoc(SkillDefinition def) {
        if (StrUtil.isBlank(def.getSkillDoc())) {
            return ToolResult.success(TOOL_NAME, "SKILL [" + def.getName() + "] 没有 SKILL.md 文档。");
        }
        String doc = def.getSkillDoc();
        if (doc.length() > MAX_CONTENT_LENGTH) {
            doc = doc.substring(0, MAX_CONTENT_LENGTH) +
                    "\n\n...（文档过长，截断至 " + MAX_CONTENT_LENGTH + " 字符）";
        }
        return ToolResult.success(TOOL_NAME, "=== " + def.getName() + " 的 SKILL.md ===\n\n" + doc);
    }

    private ToolResult listScripts(SkillDefinition def) {
        List<String> scripts = def.getScriptFiles();
        if (scripts == null || scripts.isEmpty()) {
            return ToolResult.success(TOOL_NAME, "SKILL [" + def.getName() + "] 没有可执行脚本。");
        }
        return ToolResult.success(TOOL_NAME, "SKILL [" + def.getName() + "] 的脚本列表：\n" +
                String.join("\n", scripts));
    }

    private ToolResult readScript(SkillDefinition def, Map<String, Object> params) {
        String fileName = params.get("file") instanceof String f ? f.trim() : "";
        if (StrUtil.isBlank(fileName)) {
            return ToolResult.failure(TOOL_NAME, "缺少必填参数: file（脚本文件名）");
        }

        byte[] content = skillLoader.getScriptContent(def.getName(), fileName);
        if (content == null) {
            List<String> scripts = def.getScriptFiles();
            String available = (scripts != null && !scripts.isEmpty())
                    ? "可用脚本: " + String.join(", ", scripts)
                    : "该 SKILL 没有脚本文件";
            return ToolResult.failure(TOOL_NAME, "脚本文件不存在: " + fileName + "。" + available);
        }

        String text = new String(content, StandardCharsets.UTF_8);
        if (text.length() > MAX_CONTENT_LENGTH) {
            text = text.substring(0, MAX_CONTENT_LENGTH) +
                    "\n\n...（文件过长，截断至 " + MAX_CONTENT_LENGTH + " 字符）";
        }
        return ToolResult.success(TOOL_NAME, "=== " + fileName + " ===\n\n" + text);
    }

    private ToolResult listRefs(SkillDefinition def) {
        List<String> refs = def.getReferenceFiles();
        if (refs == null || refs.isEmpty()) {
            return ToolResult.success(TOOL_NAME, "SKILL [" + def.getName() + "] 没有参考资料。");
        }
        return ToolResult.success(TOOL_NAME, "SKILL [" + def.getName() + "] 的参考资料列表：\n" +
                String.join("\n", refs));
    }

    private ToolResult readRef(SkillDefinition def, Map<String, Object> params) {
        String fileName = params.get("file") instanceof String f ? f.trim() : "";
        if (StrUtil.isBlank(fileName)) {
            return ToolResult.failure(TOOL_NAME, "缺少必填参数: file（参考文件名）");
        }

        byte[] content = skillLoader.getReferenceContent(def.getName(), fileName);
        if (content == null) {
            List<String> refs = def.getReferenceFiles();
            String available = (refs != null && !refs.isEmpty())
                    ? "可用参考文件: " + String.join(", ", refs)
                    : "该 SKILL 没有参考文件";
            return ToolResult.failure(TOOL_NAME, "参考文件不存在: " + fileName + "。" + available);
        }

        String text = new String(content, StandardCharsets.UTF_8);
        if (text.length() > MAX_CONTENT_LENGTH) {
            text = text.substring(0, MAX_CONTENT_LENGTH) +
                    "\n\n...（文件过长，截断至 " + MAX_CONTENT_LENGTH + " 字符）";
        }
        return ToolResult.success(TOOL_NAME, "=== " + fileName + " ===\n\n" + text);
    }
}
