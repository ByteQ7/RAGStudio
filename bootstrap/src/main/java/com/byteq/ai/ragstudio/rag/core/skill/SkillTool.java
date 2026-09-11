package com.byteq.ai.ragstudio.rag.core.skill;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.rag.core.tool.Tool;
import com.byteq.ai.ragstudio.rag.core.tool.ToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 将 {@link SkillDefinition} 包装为 Agent 可调用的 {@link Tool}
 * <p>
 * 根据 SKILL 的 type 字段分发到不同的执行逻辑，三种类型统一在 Docker 沙箱内执行：
 * <ul>
 *   <li>http — 沙箱内通过 curl 发起 HTTP 请求（模板解析在宿主侧完成后交给沙箱）</li>
 *   <li>script — 通过 Docker 沙箱执行 scripts/ 下的脚本文件</li>
 *   <li>command — 通过 Docker 沙箱执行命令（受命令前缀白名单约束）</li>
 * </ul>
 * <p>
 * 三种类型执行前均经过 {@link SecurityAuditor} 安全检查，然后通过
 * {@link SandboxExecutor} 在隔离容器中执行。沙箱默认开放网络
 * （rag.skills.sandbox.network-enabled），skill.yaml 可用 {@code config.network}
 * 按技能显式覆盖（true/false）。
 */
@Slf4j
public class SkillTool implements Tool {

    /** 沙箱内 curl 的单请求超时（秒）：略小于沙箱总超时（30s），留出容器启动与输出回传余量 */
    private static final long HTTP_CURL_MAX_TIME_SECONDS = 25;

    /** curl -w 追加在响应体末尾的 HTTP 状态码标记（执行后解析，用于判定 2xx 语义成功） */
    private static final String HTTP_CODE_MARKER = "__RAGSTUDIO_HTTP_CODE__";

    private final SkillDefinition definition;
    private final SandboxExecutor sandboxExecutor;
    /** 沙箱总开关（rag.skills.sandbox.enabled）：false 时三类 SKILL 全部拒绝执行 */
    private final boolean sandboxEnabled;
    /** 沙箱默认网络开关（rag.skills.sandbox.network-enabled）：skill.yaml config.network 未显式声明时生效 */
    private final boolean sandboxNetworkEnabled;
    /** command 类型命令前缀白名单（rag.skills.allowed-commands，逗号分隔）：空表示 command 类型禁用 */
    private final List<String> allowedCommandPrefixes;

    public SkillTool(SkillDefinition definition, SandboxExecutor sandboxExecutor) {
        this(definition, sandboxExecutor, true, true, List.of());
    }

    public SkillTool(SkillDefinition definition, SandboxExecutor sandboxExecutor, boolean sandboxEnabled,
                     boolean sandboxNetworkEnabled, List<String> allowedCommandPrefixes) {
        this.definition = definition;
        this.sandboxExecutor = sandboxExecutor;
        this.sandboxEnabled = sandboxEnabled;
        this.sandboxNetworkEnabled = sandboxNetworkEnabled;
        this.allowedCommandPrefixes = allowedCommandPrefixes != null ? allowedCommandPrefixes : List.of();
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
            // 纯知识型技能不注册为可调用工具；若被直接调用（防御），指引通过 tool_reader 激活
            return ToolResult.failure(name(),
                    "SKILL [" + name() + "] 为纯知识型技能（无执行配置），请使用 tool_reader 的 read 动作加载其指令");
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
        if (!sandboxEnabled) {
            log.warn("SKILL 沙箱已禁用，拒绝执行 HTTP 请求: name={}", name());
            return ToolResult.failure(name(), "沙箱已禁用（rag.skills.sandbox.enabled=false），HTTP 类型 SKILL 不可用");
        }
        Map<String, Object> config = definition.getConfig();
        if (config == null) {
            return ToolResult.failure(name(), "HTTP 类型的 SKILL 缺少 config 配置");
        }

        String url = resolveUrlTemplate((String) config.get("url"), params);
        if (StrUtil.isBlank(url)) {
            return ToolResult.failure(name(), "HTTP 类型的 SKILL 缺少 url 配置");
        }
        String method = String.valueOf(config.getOrDefault("method", "GET")).trim().toUpperCase();

        String command = buildCurlCommand(config, url, method, params);

        // 1. 安全检查（与 script/command 一致：拦截内网地址、反弹 shell 等高危操作）
        SecurityAuditor.AuditResult audit = SecurityAuditor.audit(command);
        if (!audit.allowed()) {
            log.warn("SKILL HTTP 命令被安全审计拦截: name={}, reason={}", name(), audit.reason());
            return ToolResult.failure(name(), "安全审计未通过: " + audit.reason());
        }

        // 2. Docker 可用性
        if (!sandboxExecutor.isAvailable()) {
            log.warn("Docker 不可用，无法执行沙箱命令: name={}", name());
            return ToolResult.failure(name(), "沙箱执行环境不可用（Docker 未运行），请联系管理员");
        }

        // 3. 沙箱内执行（HTTP 请求本身依赖网络，默认开放，config.network 可显式关闭）
        SandboxExecutor.SandboxResult result =
                sandboxExecutor.execute(command, resolveNetworkEnabled(config, true), List.of());

        // 4. 解析末尾状态码标记，判定 HTTP 语义成功/失败
        String output = result.getOutput();
        int markerIdx = output.lastIndexOf(HTTP_CODE_MARKER);
        if (markerIdx < 0 || !result.isSuccess()) {
            // curl 自身失败（DNS/连接/超时等），无状态码标记或进程异常退出
            String content = "HTTP 请求失败 (curl exit " + result.getExitCode() + ", "
                    + result.getDurationMs() + "ms):\n" + output;
            log.warn("SKILL HTTP 请求失败: name={}, error={}", name(), StrUtil.brief(content, 300));
            return ToolResult.failure(name(), content);
        }

        int codeStart = markerIdx + HTTP_CODE_MARKER.length();
        int codeEnd = codeStart;
        while (codeEnd < output.length() && Character.isDigit(output.charAt(codeEnd))) {
            codeEnd++;
        }
        String statusCode = output.substring(codeStart, codeEnd);
        String body = output.substring(0, markerIdx);

        String content = "HTTP " + statusCode + " (" + result.getDurationMs() + "ms):\n" + body;
        return statusCode.startsWith("2")
                ? ToolResult.success(name(), content)
                : ToolResult.failure(name(), content);
    }

    /**
     * 构造沙箱内执行的 curl 命令
     * <p>URL 模板参数已在宿主侧完成 URL 编码；所有动态片段经 {@link #shellEscape}
     * 单引号包裹防注入，并经 {@link SecurityAuditor} 审计后才进入沙箱。</p>
     */
    private String buildCurlCommand(Map<String, Object> config, String url, String method,
                                    Map<String, Object> params) {
        StringBuilder cmd = new StringBuilder("curl -sS -L --max-time ").append(HTTP_CURL_MAX_TIME_SECONDS);
        if (!"GET".equals(method)) {
            cmd.append(" -X ").append(method);
        }

        // 请求头（值支持 ${param} 模板）
        boolean hasContentTypeHeader = false;
        if (config.get("headers") instanceof Map<?, ?> headers) {
            for (Map.Entry<?, ?> entry : headers.entrySet()) {
                String headerValue = resolveTemplate(String.valueOf(entry.getValue()), params);
                if ("content-type".equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                    hasContentTypeHeader = true;
                }
                cmd.append(" -H ").append(shellEscape(entry.getKey() + ": " + headerValue));
            }
        }

        // 请求体（POST/PUT/PATCH；值支持 ${param} 模板）
        if (config.get("body") != null && !"GET".equals(method)) {
            String bodyStr = resolveTemplate(String.valueOf(config.get("body")), params);
            if (!hasContentTypeHeader) {
                String contentType = (String) config.getOrDefault("contentType", "application/json");
                cmd.append(" -H ").append(shellEscape("Content-Type: " + contentType));
            }
            cmd.append(" --data-raw ").append(shellEscape(bodyStr));
        }

        // 末尾追加 HTTP 状态码标记（写在响应体之后，执行后解析判定成功/失败）
        cmd.append(" -w ").append(shellEscape("\n" + HTTP_CODE_MARKER + "%{http_code}"));
        cmd.append(" ").append(shellEscape(url));
        return cmd.toString();
    }

    // ==================== Script 类型 ====================

    private ToolResult executeScript(Map<String, Object> params) {
        if (!sandboxEnabled) {
            log.warn("SKILL 沙箱已禁用，拒绝执行脚本: name={}", name());
            return ToolResult.failure(name(), "沙箱已禁用（rag.skills.sandbox.enabled=false），script 类型 SKILL 不可用");
        }
        Map<String, Object> config = definition.getConfig();
        if (config == null) {
            return ToolResult.failure(name(), "Script 类型的 SKILL 缺少 config 配置");
        }

        String scriptFile = resolveScriptFile(config);
        if (scriptFile == null) {
            return ToolResult.failure(name(), "Script 类型未指定 scriptFile，且 scripts/ 目录为空");
        }

        // 脚本基础路径：池化模式 = /skills/<skill>/scripts（工作区整体只读预挂载，容器常驻）；
        // 传统模式 = /scripts（每次执行按技能单独挂载）
        String basePath = sandboxExecutor.scriptBasePath(definition.getName());

        // 构造执行的命令
        String interpreter = resolveInterpreter(scriptFile, config);
        String command = interpreter + " " + basePath + "/" + scriptFile;

        // 追加参数（shell 转义，防止注入）
        if (params != null) {
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                if (entry.getValue() != null) {
                    command += " " + shellEscape(entry.getValue().toString());
                }
            }
        }

        // 沙箱默认开放网络（rag.skills.sandbox.network-enabled），config.network 可按技能显式覆盖；
        // 池化模式下网络策略在容器创建时固定，本参数仅传统模式/兜底一次性容器按次生效
        List<String> volumes = sandboxExecutor.isPooled()
                ? List.of()
                : List.of(definition.getSkillDir().resolve("scripts").toAbsolutePath() + ":/scripts:ro");
        return executeInSandbox(command, resolveNetworkEnabled(config, sandboxNetworkEnabled), volumes);
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
        if (!sandboxEnabled) {
            log.warn("SKILL 沙箱已禁用，拒绝执行命令: name={}", name());
            return ToolResult.failure(name(), "沙箱已禁用（rag.skills.sandbox.enabled=false），command 类型 SKILL 不可用");
        }
        if (allowedCommandPrefixes.isEmpty()) {
            log.warn("SKILL command 类型被白名单禁用: name={}", name());
            return ToolResult.failure(name(), "command 类型 SKILL 已禁用（rag.skills.allowed-commands 为空）");
        }
        Map<String, Object> config = definition.getConfig();
        if (config == null) {
            return ToolResult.failure(name(), "Command 类型的 SKILL 缺少 config 配置");
        }

        String command = resolveTemplate((String) config.get("command"), params);
        if (StrUtil.isBlank(command)) {
            return ToolResult.failure(name(), "Command 类型的 SKILL 缺少 command 配置");
        }

        // 命令前缀白名单校验：命令必须以白名单中的某个前缀开头
        boolean allowed = allowedCommandPrefixes.stream().anyMatch(command::startsWith);
        if (!allowed) {
            log.warn("SKILL 命令不在白名单内: name={}, command={}", name(), command);
            return ToolResult.failure(name(), "命令不在允许执行的白名单内: " + command);
        }

        // 沙箱默认开放网络（rag.skills.sandbox.network-enabled），config.network 可按技能显式覆盖
        return executeInSandbox(command, resolveNetworkEnabled(config, sandboxNetworkEnabled));
    }

    // ==================== 网络开关 ====================

    /**
     * 解析沙箱网络开关：skill.yaml {@code config.network} 显式声明（true/false，容忍字符串形式）优先，
     * 未声明时回退到传入的默认值（script/command 取全局 rag.skills.sandbox.network-enabled，http 默认 true）
     */
    private boolean resolveNetworkEnabled(Map<String, Object> config, boolean defaultEnabled) {
        Object value = config != null ? config.get("network") : null;
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s.trim());
        }
        return defaultEnabled;
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
