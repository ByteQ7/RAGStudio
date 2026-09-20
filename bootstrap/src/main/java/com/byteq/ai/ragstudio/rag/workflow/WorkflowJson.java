package com.byteq.ai.ragstudio.rag.workflow;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.rag.workflow.model.WorkflowDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 工作流 JSON 编解码与校验
 * <p>
 * 存储结构：{@code {"steps":[{"action":"...","tool":"...","when":"..."}],"notes":"..."}}。
 * 校验规则（ERROR 拒绝保存）：name 格式、title/description 非空且长度、步骤数量与字段长度。
 */
@Slf4j
public final class WorkflowJson {

    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_TITLE_LENGTH = 128;
    public static final int MAX_DESCRIPTION_LENGTH = 1024;
    public static final int MAX_ACTION_LENGTH = 2000;
    public static final int MAX_TOOL_LENGTH = 64;
    public static final int MAX_WHEN_LENGTH = 500;
    public static final int MAX_NOTES_LENGTH = 2000;
    public static final int MIN_STEPS = 1;
    public static final int MAX_STEPS = 30;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkflowJson() {
    }

    /** 解析步骤 JSON；解析失败返回空定义（调用方按校验错误处理） */
    public static WorkflowDefinition parse(String name, String title, String description, String stepsJson) {
        List<WorkflowDefinition.WorkflowStep> steps = new ArrayList<>();
        String notes = null;
        if (StrUtil.isNotBlank(stepsJson)) {
            try {
                Map<String, Object> root = MAPPER.readValue(stepsJson, new TypeReference<>() {});
                Object rawSteps = root.get("steps");
                if (rawSteps instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m) {
                            steps.add(new WorkflowDefinition.WorkflowStep(
                                    str(m.get("action")), str(m.get("tool")), str(m.get("when"))));
                        }
                    }
                }
                notes = str(root.get("notes"));
            } catch (Exception e) {
                log.warn("工作流步骤 JSON 解析失败: {}", e.getMessage());
            }
        }
        return new WorkflowDefinition(name, title, description, steps, notes);
    }

    /** 序列化步骤为存储 JSON */
    public static String toJson(List<WorkflowDefinition.WorkflowStep> steps, String notes) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (steps != null) {
            for (WorkflowDefinition.WorkflowStep step : steps) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("action", step.action());
                if (StrUtil.isNotBlank(step.tool())) {
                    m.put("tool", step.tool());
                }
                if (StrUtil.isNotBlank(step.when())) {
                    m.put("when", step.when());
                }
                list.add(m);
            }
        }
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("steps", list);
        if (StrUtil.isNotBlank(notes)) {
            root.put("notes", notes);
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            // 结构简单，序列化失败属不可恢复的编程错误
            throw new IllegalStateException("工作流步骤序列化失败", e);
        }
    }

    /** 校验并返回错误列表（空列表 = 通过） */
    public static List<String> validate(String name, String title, String description,
                                        List<WorkflowDefinition.WorkflowStep> steps, String notes) {
        List<String> errors = new ArrayList<>();
        if (StrUtil.isBlank(name)) {
            errors.add("name 不能为空");
        } else if (name.length() > MAX_NAME_LENGTH) {
            errors.add("name 长度不能超过 " + MAX_NAME_LENGTH);
        } else if (!NAME_PATTERN.matcher(name).matches()) {
            errors.add("name 必须为 kebab-case（小写字母/数字/连字符，如 refund-dispute）");
        }
        if (StrUtil.isBlank(title)) {
            errors.add("title 不能为空");
        } else if (title.length() > MAX_TITLE_LENGTH) {
            errors.add("title 长度不能超过 " + MAX_TITLE_LENGTH);
        }
        if (StrUtil.isBlank(description)) {
            errors.add("description 不能为空");
        } else if (description.length() > MAX_DESCRIPTION_LENGTH) {
            errors.add("description 长度不能超过 " + MAX_DESCRIPTION_LENGTH);
        }
        if (steps == null || steps.size() < MIN_STEPS) {
            errors.add("steps 至少需要 " + MIN_STEPS + " 个步骤");
        } else if (steps.size() > MAX_STEPS) {
            errors.add("steps 数量不能超过 " + MAX_STEPS);
        } else {
            for (int i = 0; i < steps.size(); i++) {
                WorkflowDefinition.WorkflowStep step = steps.get(i);
                String prefix = "第 " + (i + 1) + " 步";
                if (step == null || StrUtil.isBlank(step.action())) {
                    errors.add(prefix + " 的 action 不能为空");
                    continue;
                }
                if (step.action().length() > MAX_ACTION_LENGTH) {
                    errors.add(prefix + " action 长度不能超过 " + MAX_ACTION_LENGTH);
                }
                if (step.tool() != null && step.tool().length() > MAX_TOOL_LENGTH) {
                    errors.add(prefix + " tool 长度不能超过 " + MAX_TOOL_LENGTH);
                }
                if (step.when() != null && step.when().length() > MAX_WHEN_LENGTH) {
                    errors.add(prefix + " when 长度不能超过 " + MAX_WHEN_LENGTH);
                }
            }
        }
        if (notes != null && notes.length() > MAX_NOTES_LENGTH) {
            errors.add("notes 长度不能超过 " + MAX_NOTES_LENGTH);
        }
        return errors;
    }

    /**
     * 将任意文本规范为合法 name：非法字符替换为连字符、转小写、折叠；
     * 结果为空或过短时回退为 {@code wf-<hash>}。
     */
    public static String normalizeName(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "wf-" + Integer.toHexString(String.valueOf(System.nanoTime()).hashCode());
        }
        String s = raw.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "")
                .replaceAll("-{2,}", "-");
        if (s.isEmpty() || !NAME_PATTERN.matcher(s).matches() || s.length() < 3) {
            return "wf-" + Integer.toHexString(raw.hashCode());
        }
        return s.length() > MAX_NAME_LENGTH ? s.substring(0, MAX_NAME_LENGTH) : s;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }
}
