package com.byteq.ai.ragstudio.rag.core.skill;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 命令安全审计器
 * <p>
 * 在脚本/命令执行前进行安全检查，拦截高危操作。
 * 黑名单规则覆盖：
 * <ul>
 *   <li>破坏性操作：rm -rf /、mkfs、dd if=</li>
 *   <li>提权操作：sudo、chmod 777、chown root</li>
 *   <li>反弹 Shell：nc -e、bash -i >&、base64 decode pipe to sh</li>
 *   <li>网络探测：对内网 IP 的扫描</li>
 * </ul>
 */
@Slf4j
public final class SecurityAuditor {

    private SecurityAuditor() {}

    /** 高危命令模式 */
    private static final List<Pattern> BLOCKED_PATTERNS = List.of(
            // 破坏性文件操作
            Pattern.compile("\\brm\\s+-rf\\s+/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmkfs\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdd\\s+if=", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[>&|]\\s*/dev/", Pattern.CASE_INSENSITIVE),
            // 提权与权限修改
            Pattern.compile("\\bsudo\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bchmod\\s+777\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bchown\\s+root\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpasswd\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\buseradd\\b", Pattern.CASE_INSENSITIVE),
            // 反弹 Shell 与隐蔽通道
            Pattern.compile("\\bnc\\s+-e\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("bash\\s+-i\\s+[>&]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("base64\\s+-d\\s*\\|\\s*bash", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpython\\S*\\s+-c\\s+['\"].*socket", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcurl\\s+.*\\|\\s*(sh|bash)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bwget\\s+-O\\s*-\\s*.*\\|\\s*(sh|bash)", Pattern.CASE_INSENSITIVE),
            // 内网地址探测（前面有空格或引号，减少注释中的假阳性）
            Pattern.compile("(?<=[\\s\"'`])10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"),
            Pattern.compile("(?<=[\\s\"'`])172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}"),
            Pattern.compile("(?<=[\\s\"'`])192\\.168\\.\\d{1,3}\\.\\d{1,3}"),
            Pattern.compile("(?<=[\\s\"'`])(127\\.0\\.0\\.1|localhost)")
    );

    /**
     * 审计命令是否安全
     *
     * @param command 待执行的命令
     * @return 审计结果
     */
    public static AuditResult audit(String command) {
        if (command == null || command.isBlank()) {
            return AuditResult.pass();
        }

        for (Pattern pattern : BLOCKED_PATTERNS) {
            if (pattern.matcher(command).find()) {
                log.warn("命令命中安全黑名单, pattern={}, command={}", pattern, command);
                return AuditResult.reject("命令包含被禁止的操作，已拦截");
            }
        }

        return AuditResult.pass();
    }

    /**
     * 审计结果
     */
    public record AuditResult(boolean allowed, String reason) {
        public static AuditResult pass() {
            return new AuditResult(true, null);
        }

        public static AuditResult reject(String reason) {
            return new AuditResult(false, reason);
        }
    }
}
