package com.sks.common;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 邮件告警实现（spec §3.4）。
 *
 * <p>条件降级：{@code spring.mail.host} 空 / 无 {@link JavaMailSender} bean → stub（log + no-op，不抛）。
 * 用 {@link ObjectProvider} 取 sender：host 为空字符串时 Spring Boot 的 MailSender 自动配置处于边缘态，
 * 别赌 bean 一定存在。失败 → log.warn 吞掉（告警失败不阻断主流程）。
 */
@Component
public class MailAlertNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(MailAlertNotifier.class);

    private final ObjectProvider<JavaMailSender> senderProvider;
    private final String host;
    private final String adminEmail;

    public MailAlertNotifier(
            ObjectProvider<JavaMailSender> senderProvider,
            @Value("${spring.mail.host:}") String host,
            @Value("${sks.alert.admin-email:}") String adminEmail) {
        this.senderProvider = senderProvider;
        this.host = host;
        this.adminEmail = adminEmail;
    }

    @Override
    public void notify(String subject, String content) {
        if (!isPresent(host) || !isPresent(adminEmail)) {
            log.info("[ALERT-STUB] subject={} content={} (no spring.mail.host / admin-email)", subject, content);
            return;
        }
        JavaMailSender sender = senderProvider.getIfAvailable();
        if (sender == null) {
            log.info("[ALERT-STUB] no JavaMailSender bean; subject={} content={}", subject, content);
            return;
        }
        try {
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "utf-8");
            helper.setTo(adminEmail);
            helper.setSubject(subject);
            helper.setText(content, false);
            sender.send(msg);
        } catch (Exception e) {
            log.warn("MailAlert notify failed: subject={}: {}", subject, e.getMessage());
        }
    }

    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }
}
