package com.byteq.ai.ragstudio.rag.core.skill;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.rag.core.agent.Tool;
import com.byteq.ai.ragstudio.rag.core.agent.ToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.List;
import java.util.Map;

/**
 * 将 {@link SkillDefinition} 包装为 Agent 可调用的 {@link Tool}
 * <p>
 * 根据 SKILL 的 type 字段分发到不同的执行逻辑：
 * <ul>
 *   <li>http — 通过 OkHttp 发起 HTTP 请求</li>
 *   <li>script — 通过 Docker 沙箱执行 scripts/ 下的脚本文件</li>
 *   <li>command — 通过 Docker 沙箱执行命令（默认禁用）</li>
 * </ul>
 * <p>
 * script 和 command 类型执行前会经过 {@link SecurityAuditor} 安全检查，
 * 然后通过 {@link SandboxExecutor} 在隔离容器中执行。
 */
@Slf4j
public class SkillTool implements Tool {

    private final SkillDefinition definition;
    private final SandboxExecutor sandboxExecutor;
    private final OkHttpClient httpClient;

    public SkillTool(SkillDefinition definition, OkHttpClient httpClient) {
        this(definition, httpClient, SandboxExecutor.builder().build());
    }

    public SkillTool(SkillDefinition definition, OkHttpClient httpClient, SandboxExecutor sandboxExecutor) {
        this.definition = definition;
        this.sandboxExecutor = sandboxExecutor;
        this.httpClient = httpClient;
    }

    @Override
    public String name() {
        return definition.getName();
    }

    @Override
    public String description() {
        return definition.getDescription();
    }

    @Override
    public JsonSchema inputSchema() {
        Map<String, Object> params = definition.getParameters();
        if (params == null || params.isEmpty()) {
            return new JsonSchema("object", Map.of(), List.of(), null, null, null);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) params.getOrDefault("properties", Map.of());
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) params.getOrDefault("required", List.of());
        return new JsonSchema("object", properties, required, null, null, null);
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String type = definition.getType();
        if (StrUtil.isBlank(type)) {
            return ToolResult.failure(name(), "SKILL 类型未定义（type 字段为空）");
        }

        return switch (type.trim().toLowerCase()) {
            case "http" -> executeHttp(params);
            case "script" -> executeScript(params);
            case "command" -> executeCommand(params);
            default -> ToolResult.failure(name(), "不支持的 SKILL 类型: " + type);
        };
    }

    // ==================== HTTP 类型 ====================

    @SuppressWarnings("unchecked")
    private ToolResult executeHttp(Map<String, Object> params) {
        Map<String, Object> config = definition.getConfig();
        if (config == null) {
            return ToolResult.failure(name(), "HTTP 类型的 SKILL 缺少 config 配置");
        }

        String url = resolveUrlTemplate((String) config.get("url"), params);
        if (StrUtil.isBlank(url)) {
            return ToolResult.failure(name(), "HTTP 类型的 SKILL 缺少 url 配置");
        }
        String method = (String) config.getOrDefault("method", "GET");

        try {
            Request.Builder reqBuilder = new Request.Builder();

            // 设置请求体（POST/PUT/PATCH）
            Object bodyObj = config.get("body");
            if (bodyObj != null && !method.equalsIgnoreCase("GET")) {
                String bodyTemplate = String.valueOf(bodyObj);
                String bodyStr = resolveTemplate(bodyTemplate, params);
                String contentType = (String) config.getOrDefault("contentType", "application/json");
                reqBuilder.method(method, RequestBody.create(bodyStr, MediaType.parse(contentType)));
            } else {
                reqBuilder.url(url);
            }

            // 设置请求头
            Object headersObj = config.get("headers");
            if (headersObj instanceof Map) {
                Map<String, Object> headers = (Map<String, Object>) headersObj;
                for (Map.Entry<String, Object> entry : headers.entrySet()) {
                    String value = resolveTemplate(String.valueOf(entry.getValue()), params);
                    reqBuilder.header(entry.getKey(), value);
                }
            }

            Request request = reqBuilder.build();
            long start = System.currentTimeMillis();

            try (Response response = httpClient.newCall(request).execute()) {
                long duration = System.currentTimeMillis() - start;
                String respBody = response.body() != null ? response.body().string() : "";
                String content = "HTTP " + response.code() + " (" + duration + "ms):\n" + respBody;
                return response.isSuccessful()
                        ? ToolResult.success(name(), content)
                        : ToolResult.failure(name(), content);
            }
        } catch (Exception e) {
            log.warn("SKILL HTTP 请求失败: name={}, error={}", name(), e.getMessage());
            return ToolResult.failure(name(), "HTTP 请求失败: " + e.getMessage());
        }
    }

    // ==================== Script 类型 ====================

    private ToolResult executeScript(Map<String, Object> params) {
        Map<String, Object> config = definition.getConfig();
        if (config == null) {
            return ToolResult.failure(name(), "Script 类型的 SKILL 缺少 config 配置");
        }

        String scriptFile = resolveScriptFile(config);
        if (scriptFile == null) {
            return ToolResult.failure(name(), "Script 类型未指定 scriptFile，且 scripts/ 目录为空");
        }

        // 脚本目录挂载到容器的 /scripts/ 下
        java.nio.file.Path scriptsDir = definition.getSkillDir().resolve("scripts");
        String volume = scriptsDir.toAbsolutePath().toString() + ":/scripts:ro";

        // 构造执行的命令
        String interpreter = resolveInterpreter(scriptFile, config);
        String command = interpreter + " /scripts/" + scriptFile;

        // 追加参数（shell 转义，防止注入）
        if (params != null) {
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                if (entry.getValue() != null) {
                    command += " " + shellEscape(entry.getValue().toString());
                }
            }
        }

        return executeInSandbox(command, false, List.of(volume));
    }

    private String resolveScriptFile(Map<String, Object> config) {
        String scriptFile = (String) config.get("scriptFile");
        if (StrUtil.isNotBlank(scriptFile)) {
            return scriptFile;
        }
        List<String> files = definition.getScriptFiles();
        return (files != null && !files.isEmpty()) ? files.get(0) : null;
    }

    private String resolveInterpreter(String scriptFile, Map<String, Object> config) {
        // skill.yaml 中 config.interpreter 可以显式指定解释器
        if (config != null && config.get("interpreter") instanceof String i && !i.isBlank()) {
            return i;
        }
        // 根据扩展名推断解释器（alpine 只有 sh，没有 bash）
        if (scriptFile.endsWith(".py")) return "python3";
        if (scriptFile.endsWith(".sh")) return "sh";
        if (scriptFile.endsWith(".js")) return "node";
        if (scriptFile.endsWith(".rb")) return "ruby";
        if (scriptFile.endsWith(".php")) return "php";
        return "sh";
    }

    // ==================== Command 类型 ====================

    private ToolResult executeCommand(Map<String, Object> params) {
        Map<String, Object> config = definition.getConfig();
        if (config == null) {
            return ToolResult.failure(name(), "Command 类型的 SKILL 缺少 config 配置");
        }

        String command = resolveTemplate((String) config.get("command"), params);
        if (StrUtil.isBlank(command)) {
            return ToolResult.failure(name(), "Command 类型的 SKILL 缺少 command 配置");
        }

        return executeInSandbox(command, false);
    }

    // ==================== 沙箱执行 ====================

    /**
     * 在 Docker 沙箱中执行命令，经过安全审计
     */
    private ToolResult executeInSandbox(String command, boolean enableNet) {
        return executeInSandbox(command, enableNet, List.of());
    }

    /**
     * 在 Docker 沙箱中执行命令，经过安全审计
     *
     * @param command   要执行的命令
     * @param enableNet 是否启用网络
     * @param volumes   卷挂载列表
     */
    private ToolResult executeInSandbox(String command, boolean enableNet, List<String> volumes) {
        // 1. 安全检查
        SecurityAuditor.AuditResult audit = SecurityAuditor.audit(command);
        if (!audit.allowed()) {
            log.warn("SKILL 命令被安全审计拦截: name={}, reason={}", name(), audit.reason());
            return ToolResult.failure(name(), "安全审计未通过: " + audit.reason());
        }

        // 2. 检查 Docker 是否可用
        if (!sandboxExecutor.isAvailable()) {
            log.warn("Docker 不可用，无法执行沙箱命令: name={}", name());
            return ToolResult.failure(name(), "沙箱执行环境不可用（Docker 未运行），请联系管理员");
        }

        // 3. 沙箱执行
        SandboxExecutor.SandboxResult result = sandboxExecutor.execute(command, enableNet, volumes);

        String output = "exit: " + result.getExitCode() + " (" + result.getDurationMs() + "ms)\n" + result.getOutput();

        if (result.isSuccess()) {
            return ToolResult.success(name(), output);
        }
        return ToolResult.failure(name(), output);
    }

    // ==================== 通用方法 ====================

    /**
     * Shell 转义：将参数值安全地包裹在单引号中，处理内部的单引号
     * <p>
     * 防止参数值中的空格、特殊字符导致命令注入。
     * 使用 POSIX shell 单引号转义规则：a'b → 'a'\''b'
     */
    private String shellEscape(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private String resolveTemplate(String template, Map<String, Object> params) {
        if (template == null) return null;
        if (params == null || params.isEmpty()) return template;
        String result = template;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String value = entry.getValue() != null ? String.valueOf(entry.getValue()) : "";
            result = result.replace("${" + entry.getKey() + "}", value);
        }
        return result;
    }

    /** 对 URL 模板中的参数值进行 URL 编码 */
    private String resolveUrlTemplate(String template, Map<String, Object> params) {
        if (template == null) return null;
        if (params == null || params.isEmpty()) return template;
        String result = template;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String value = entry.getValue() != null ? String.valueOf(entry.getValue()) : "";
            try {
                value = java.net.URLEncoder.encode(value, "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                // UTF-8 总是支持的
            }
            result = result.replace("${" + entry.getKey() + "}", value);
        }
        return result;
    }
}
