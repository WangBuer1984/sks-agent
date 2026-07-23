package com.sks.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link QuotaWatchJob} 纯逻辑单测（P5 Task 5.3 余额监控）——只测可单测部分：
 * {@link QuotaWatchJob#checkAndAlert(Optional, Optional)} 阈值判定。
 *
 * <p>与 {@link JwtUtilTest} 同档：纯 JUnit，不引入 Spring 上下文 / Testcontainers。
 * 余额查询 seam（{@link QuotaWatchJob#querySmsBalance} / {@link QuotaWatchJob#queryGlmBalance}）
 * 是<b>联调项</b>（外部 key-gated BSS API），不在本测试覆盖——只测「给定两个余额，是否按规则告警」的纯函数。
 * 告警 seam（{@link QuotaWatchJob#sendAlert}）经 mock {@link SmsClient} 验证委托。
 *
 * <p><b>承重断言（brief 指定）：</b>
 * <ul>
 *   <li>below-threshold → 告警触发；
 *   <li>above-threshold → 不告警；
 *   <li>exactly-at-threshold → 不告警（严格 {@code <}，避免边界抖动）；
 *   <li>一个余额查询失败（{@code Optional.empty}）→ Job 继续，<b>仅当</b>另一个低于阈值才告警。
 * </ul>
 */
class QuotaWatchJobTest {

    private static final int SMS_THRESHOLD = 100;
    private static final int GLM_THRESHOLD = 20;

    private SmsClient smsClient;

    @BeforeEach
    void setUp() {
        smsClient = mock(SmsClient.class);
    }

    private QuotaWatchJob newJob() {
        return new QuotaWatchJob(SMS_THRESHOLD, GLM_THRESHOLD, "13900000000", smsClient);
    }

    @Test
    void bothBelowThresholdAlertsBoth() {
        List<String> reasons = newJob().checkAndAlert(Optional.of(50), Optional.of(10));
        assertEquals(2, reasons.size(), "两个都低于阈值 → 两条告警");
        assertTrue(reasons.get(0).contains("短信"), "第一条是短信告警");
        assertTrue(reasons.get(1).contains("GLM"), "第二条是 GLM 告警");
    }

    @Test
    void bothAboveThresholdNoAlert() {
        List<String> reasons = newJob().checkAndAlert(Optional.of(200), Optional.of(30));
        assertTrue(reasons.isEmpty(), "两个都高于阈值 → 不告警");
    }

    @Test
    void exactlyAtThresholdNoAlert() {
        // 严格 <: 恰好等于阈值不告警（避免边界抖动）
        List<String> reasons =
                newJob().checkAndAlert(Optional.of(SMS_THRESHOLD), Optional.of(GLM_THRESHOLD));
        assertTrue(reasons.isEmpty(), "恰好等于阈值不告警（strict <）");
    }

    @Test
    void oneQueryFailedStillAlertsOtherIfLow() {
        // 短信查询失败（empty），GLM 低于阈值 → 仅告警 GLM
        List<String> reasons = newJob().checkAndAlert(Optional.empty(), Optional.of(5));
        assertEquals(1, reasons.size(), "一个查询失败、另一个低 → 仅一条告警");
        assertTrue(reasons.get(0).contains("GLM"));
    }

    @Test
    void oneQueryFailedOtherOkNoAlert() {
        List<String> reasons = newJob().checkAndAlert(Optional.empty(), Optional.of(200));
        assertTrue(reasons.isEmpty(), "一个查询失败、另一个正常 → 不告警");
    }

    @Test
    void bothQueriesFailedNoAlert() {
        List<String> reasons = newJob().checkAndAlert(Optional.empty(), Optional.empty());
        assertTrue(reasons.isEmpty(), "两个查询都失败 → 不告警（失败本身不触发，只跳过）");
    }

    @Test
    void oneBelowOneAtThresholdAlertsOnlyLow() {
        // SMS=99 (<100) 告警；GLM=20 (==阈值) 不告警
        List<String> reasons = newJob().checkAndAlert(Optional.of(99), Optional.of(GLM_THRESHOLD));
        assertEquals(1, reasons.size());
        assertTrue(reasons.get(0).contains("短信"));
    }

    @Test
    void sendAlertDelegatesToSmsClient() {
        QuotaWatchJob job = newJob();
        job.sendAlert("短信余额不足: 50条");
        verify(smsClient).sendAlert(eq("13900000000"), eq("短信余额不足: 50条"));
    }
}
