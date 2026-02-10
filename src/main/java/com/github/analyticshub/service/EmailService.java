package com.github.analyticshub.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 邮件服务
 * 支持阿里云邮件推送（DirectMail）
 */
@Service
public class EmailService {

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
    public void sendSecurityAlert(String subject, String content) {
        if (!emailEnabled || alertRecipient == null || alertRecipient.isBlank() || mailSender == null) {
            System.err.println("[邮件系统未就绪/未启用] 安全告警: " + subject + " - " + content);
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(alertRecipient);
            message.setSubject("[Analytics Hub 安全告警] " + subject);
            message.setText(content);
            
            mailSender.send(message);
            System.out.println("[邮件已发送] " + subject);
        } catch (Exception e) {
            System.err.println("[邮件发送失败] " + e.getMessage());
        }
    }

    /**
     * 发送暴力破解告警
     */
    public void sendBruteForceAlert(String ip, int failureCount) {
        String subject = "检测到暴力破解尝试";
        String content = String.format(
                "检测到 IP 地址 %s 在尝试暴力破解 Admin Token。\n\n" +
                "失败次数: %d\n" +
                "时间: %s\n" +
                "状态: 该 IP 已被临时封禁 15 分钟\n\n" +
                "如果这不是你的操作，请立即检查服务器安全。",
                ip,
                failureCount,
                java.time.Instant.now()
        );
        
        sendSecurityAlert(subject, content);
    }
}
