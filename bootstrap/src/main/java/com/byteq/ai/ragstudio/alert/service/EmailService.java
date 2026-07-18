package com.byteq.ai.ragstudio.alert.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 邮件发送服务
 * <p>
 * 动态创建 JavaMailSender（从数据库读取 SMTP 配置），
 * 发送 HTML 格式的告警邮件。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final AlertConfigService configService;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 发送告警邮件（非阻塞，异常只记录日志不抛出）
     */
    public void sendAlert(String subject, String htmlBody) {
        var config = configService.getConfig();
        if (config.getEnabled() != 1) {
            log.debug("告警邮件未启用，跳过发送");
            return;
        }

        try {
            JavaMailSender mailSender = createMailSender(config);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(config.getFromAddress());
            helper.setTo(config.getToAddress());
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.info("告警邮件已发送至 {}", config.getToAddress());
        } catch (Exception e) {
            log.error("发送告警邮件失败: {}", e.getMessage());
        }
    }

    /**
     * 发送测试邮件
     */
    public void sendTestEmail() {
        String now = LocalDateTime.now().format(DTF);
        String subject = "RAG Studio 告警配置测试邮件";
        String body = buildTestHtml(now);
        sendAlert(subject, body);
    }

    /**
     * 构建全失败告警 HTML 邮件
     */
    public String buildAllFailedHtml(String now, List<Map<String, String>> failures) {
        StringBuilder rows = new StringBuilder();
        for (var f : failures) {
            rows.append("<tr>")
                    .append("<td style=\"padding:10px 14px;border-bottom:1px solid #e8eaed;font-size:14px;color:#202124;\">")
                    .append(escHtml(f.getOrDefault("provider", "-"))).append("</td>")
                    .append("<td style=\"padding:10px 14px;border-bottom:1px solid #e8eaed;font-size:14px;color:#202124;\">")
                    .append(escHtml(f.getOrDefault("model", "-"))).append("</td>")
                    .append("<td style=\"padding:10px 14px;border-bottom:1px solid #e8eaed;font-size:13px;color:#d93025;\">")
                    .append(escHtml(f.getOrDefault("error", "-"))).append("</td>")
                    .append("</tr>");
        }
        return buildBaseHtml(
                "模型调用全面失败",
                "所有候选模型均调用失败，请检查模型服务状态",
                now,
                """
                <p style="margin:0 0 6px;font-size:14px;color:#5f6368;">
                    以下模型全部不可用，系统已无法处理当前对话请求：
                </p>
                <table style="width:100%%;border-collapse:collapse;margin-top:4px;">
                    <thead>
                        <tr style="background:#f8f9fa;">
                            <th style="padding:10px 14px;text-align:left;font-size:13px;font-weight:600;color:#5f6368;border-bottom:2px solid #dadce0;">供应商</th>
                            <th style="padding:10px 14px;text-align:left;font-size:13px;font-weight:600;color:#5f6368;border-bottom:2px solid #dadce0;">模型</th>
                            <th style="padding:10px 14px;text-align:left;font-size:13px;font-weight:600;color:#5f6368;border-bottom:2px solid #dadce0;">失败原因</th>
                        </tr>
                    </thead>
                    <tbody>
                        %s
                    </tbody>
                </table>
                """.formatted(rows.toString())
        );
    }

    /**
     * 构建熔断告警 HTML 邮件
     */
    public String buildCircuitBreakerHtml(String now, String modelId, String provider,
                                           int openCount, int windowHours, int threshold) {
        return buildBaseHtml(
                "模型频繁熔断告警",
                "模型 " + escHtml(modelId) + " 频繁进入熔断状态",
                now,
                """
                <table style="width:100%%;border-collapse:collapse;">
                    <tr>
                        <td style="padding:8px 14px;font-size:14px;color:#5f6368;width:120px;">供应商</td>
                        <td style="padding:8px 14px;font-size:14px;color:#202124;font-weight:500;">%s</td>
                    </tr>
                    <tr>
                        <td style="padding:8px 14px;font-size:14px;color:#5f6368;">模型</td>
                        <td style="padding:8px 14px;font-size:14px;color:#202124;font-weight:500;">%s</td>
                    </tr>
                    <tr>
                        <td style="padding:8px 14px;font-size:14px;color:#5f6368;">最近 %d 小时内熔断次数</td>
                        <td style="padding:8px 14px;font-size:14px;color:#d93025;font-weight:600;">%d 次</td>
                    </tr>
                    <tr>
                        <td style="padding:8px 14px;font-size:14px;color:#5f6368;">告警阈值</td>
                        <td style="padding:8px 14px;font-size:14px;color:#202124;">≥ %d 次</td>
                    </tr>
                </table>
                """.formatted(escHtml(provider), escHtml(modelId), windowHours, openCount, threshold)
        );
    }

    // ==================== 内部方法 ====================

    private JavaMailSender createMailSender(com.byteq.ai.ragstudio.alert.dao.entity.AlertConfig config) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.getSmtpHost());
        sender.setPort(config.getSmtpPort() != null ? config.getSmtpPort() : 465);
        if (config.getSmtpUsername() != null) sender.setUsername(config.getSmtpUsername());
        if (config.getSmtpPassword() != null) sender.setPassword(config.getSmtpPassword());
        sender.setDefaultEncoding("UTF-8");

        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        if (config.getSmtpPort() != null && config.getSmtpPort() == 465) {
            props.put("mail.smtp.socketFactory.port", "465");
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
        }
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");

        return sender;
    }

    private String buildBaseHtml(String title, String subtitle, String time, String contentBlock) {
        return """
        <!DOCTYPE html>
        <html>
        <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0"></head>
        <body style="margin:0;padding:0;background:#f2f4f7;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;">
            <table style="width:100%%;max-width:600px;margin:40px auto;background:#ffffff;border-radius:12px;box-shadow:0 1px 4px rgba(0,0,0,0.08);" cellpadding="0" cellspacing="0">
                <tr>
                    <td style="padding:32px 32px 0;">
                        <div style="width:40px;height:40px;background:#fce8e6;border-radius:10px;display:flex;align-items:center;justify-content:center;margin-bottom:16px;">
                            <span style="font-size:20px;line-height:1;">⚠</span>
                        </div>
                        <h1 style="margin:0 0 4px;font-size:20px;font-weight:600;color:#202124;">%s</h1>
                        <p style="margin:0 0 20px;font-size:14px;color:#5f6368;">%s</p>
                    </td>
                </tr>
                <tr>
                    <td style="padding:0 32px 24px;">
                        <div style="background:#f8f9fa;border-radius:8px;padding:12px 16px;margin-bottom:20px;">
                            <span style="font-size:13px;color:#5f6368;">🕐 告警时间</span>
                            <span style="display:block;font-size:14px;color:#202124;font-weight:500;margin-top:2px;">%s</span>
                        </div>
                        %s
                    </td>
                </tr>
                <tr>
                    <td style="padding:20px 32px;border-top:1px solid #e8eaed;text-align:center;">
                        <p style="margin:0;font-size:12px;color:#9aa0a6;">RAG Studio · 系统自动通知</p>
                    </td>
                </tr>
            </table>
        </body>
        </html>
        """.formatted(title, subtitle, time, contentBlock);
    }

    private String buildTestHtml(String now) {
        return buildBaseHtml(
                "告警配置测试邮件",
                "如果您收到此邮件，说明 SMTP 配置正确，可以正常接收告警",
                now,
                "<p style=\"margin:0;font-size:14px;color:#34a853;font-weight:500;\">✅ 配置验证通过</p>"
        );
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
