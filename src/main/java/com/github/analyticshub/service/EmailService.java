package com.github.analyticshub.service;

import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 邮件服务
 * 支持阿里云邮件推送（DirectMail）
 */
@Service
public class EmailService {

    private static final System.Logger log = System.getLogger(EmailService.class.getName());

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromEmail;

    @Value("${app.email.alert-recipient:}")
    private String alertRecipient;

    @Value("${spring.mail.enabled:false}")
    private boolean emailEnabled;

    public EmailService(org.springframework.beans.factory.ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.mailSender = mailSenderProvider.getIfAvailable();
    }

    /**
     * 发送安全告警邮件
     */
    public EmailDeliveryStatus sendSecurityAlert(String subject, String content) {
        return sendToAlertRecipient("[Analytics Hub 安全告警] " + subject, content);
    }

    /**
     * 发送暴力破解告警
     */
    public EmailDeliveryStatus sendBruteForceAlert(String ip, int failureCount) {
        String subject = "检测到暴力破解尝试";
        String content = String.format(
                "检测到 IP 地址 %s 在尝试暴力破解 Admin Token。\n\n" +
                        "失败次数: %d\n" +
                        "时间: %s\n" +
                        "状态: 该 IP 已被临时封禁 15 分钟\n\n" +
                        "如果这不是你的操作，请立即检查服务器安全。",
                ip,
                failureCount,
                Instant.now()
        );

        return sendSecurityAlert(subject, content);
    }

    /**
     * 隐私请求提交后，通知运营处理邮箱。
     */
    public EmailDeliveryStatus sendPrivacyRequestSubmittedAlert(String requestId,
                                                                String projectId,
                                                                String userId,
                                                                String requestType,
                                                                String processor,
                                                                String contactEmail,
                                                                Instant requestedAt) {
        String subject = "新的隐私请求工单: " + requestType + " / " + requestId;
        String content = String.format(
                "已收到新的隐私请求工单，请尽快人工处理。\n\n" +
                        "requestId: %s\n" +
                        "projectId: %s\n" +
                        "userId: %s\n" +
                        "requestType: %s\n" +
                        "processor: %s\n" +
                        "contactEmail: %s\n" +
                        "requestedAt: %s\n\n" +
                        "处理建议:\n" +
                        "1) 在对应平台（AnalyticsHub/PostHog）完成导出或删除\n" +
                        "2) 回到管理端更新工单状态\n" +
                        "3) 发送结果邮件给用户",
                requestId,
                projectId,
                userId,
                requestType,
                processor,
                contactEmail,
                requestedAt
        );

        return sendToAlertRecipient("[Analytics Hub 隐私工单] " + subject, content);
    }

    /**
     * 给用户发送隐私请求处理结果。
     */
    public EmailDeliveryStatus sendPrivacyUserNotification(String toEmail, String subject, String content) {
        return sendEmail(toEmail, subject, content);
    }

    /**
     * Whether background delivery can currently reach an SMTP sender.
     * The outbox scheduler uses this guard so disabled mail does not consume
     * retry attempts or turn queued notifications into dead letters.
     */
    public boolean isDeliveryEnabled() {
        return emailEnabled && mailSender != null && fromEmail != null && !fromEmail.isBlank();
    }

    private EmailDeliveryStatus sendToAlertRecipient(String subject, String content) {
        if (alertRecipient == null || alertRecipient.isBlank()) {
            log.log(System.Logger.Level.WARNING, "Alert recipient not configured, skip email");
            return EmailDeliveryStatus.INVALID_RECIPIENT;
        }
        return sendEmail(alertRecipient, subject, content);
    }

    private EmailDeliveryStatus sendEmail(String to, String subject, String content) {
        String normalizedRecipient = normalizeRecipient(to);
        if (normalizedRecipient == null) {
            log.log(System.Logger.Level.WARNING, "Skipped email because recipient is invalid");
            return EmailDeliveryStatus.INVALID_RECIPIENT;
        }
        if (!isDeliveryEnabled()) {
            log.log(System.Logger.Level.WARNING, "Mail disabled or sender unavailable, skip email");
            return EmailDeliveryStatus.DISABLED;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(normalizedRecipient);
            message.setSubject(subject);
            message.setText(content);
            mailSender.send(message);
            // Recipient addresses are deliberately excluded from logs.
            log.log(System.Logger.Level.INFO, "Email sent");
            return EmailDeliveryStatus.SENT;
        } catch (Exception e) {
            // SMTP exception text can contain the full recipient address.
            log.log(System.Logger.Level.ERROR,
                    "Email send failed: failureType={0}",
                    e.getClass().getSimpleName());
            return EmailDeliveryStatus.FAILED;
        }
    }

    private static String normalizeRecipient(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String candidate = raw.trim();
        if (candidate.length() > 255 || candidate.contains("\r") || candidate.contains("\n")) {
            return null;
        }
        try {
            InternetAddress address = new InternetAddress(candidate, true);
            address.validate();
            return address.getAddress().equals(candidate) ? candidate : null;
        } catch (Exception exception) {
            return null;
        }
    }
}
