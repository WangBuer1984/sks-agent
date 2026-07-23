package com.sks.common;

/** 告警/站长通知 seam（spec §3.4）。实现 {@link MailAlertNotifier}：邮件 SMTP。 */
public interface AlertNotifier {
    /** 发告警。未 configured→stub 不抛；configured 但发送失败→吞掉不抛（不阻断主流程）。 */
    void notify(String subject, String content);
}
