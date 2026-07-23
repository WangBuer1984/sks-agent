package com.sks.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuotaWatchJobTest {

    private static final int SMS_THRESHOLD = 100;
    private static final int GLM_THRESHOLD = 20;

    private AlertNotifier alertNotifier;

    @BeforeEach
    void setUp() {
        alertNotifier = mock(AlertNotifier.class);
    }

    private QuotaWatchJob newJob() {
        return new QuotaWatchJob(SMS_THRESHOLD, GLM_THRESHOLD, alertNotifier);
    }

    @Test
    void bothBelowThresholdAlertsBoth() {
        List<String> reasons = newJob().checkAndAlert(Optional.of(50), Optional.of(10));
        assertEquals(2, reasons.size());
        assertTrue(reasons.get(0).contains("短信"));
        assertTrue(reasons.get(1).contains("GLM"));
    }

    @Test
    void bothAboveThresholdNoAlert() {
        assertTrue(newJob().checkAndAlert(Optional.of(200), Optional.of(30)).isEmpty());
    }

    @Test
    void exactlyAtThresholdNoAlert() {
        assertTrue(newJob().checkAndAlert(Optional.of(SMS_THRESHOLD), Optional.of(GLM_THRESHOLD)).isEmpty());
    }

    @Test
    void oneQueryFailedStillAlertsOtherIfLow() {
        List<String> reasons = newJob().checkAndAlert(Optional.empty(), Optional.of(5));
        assertEquals(1, reasons.size());
        assertTrue(reasons.get(0).contains("GLM"));
    }

    @Test
    void oneQueryFailedOtherOkNoAlert() {
        assertTrue(newJob().checkAndAlert(Optional.empty(), Optional.of(200)).isEmpty());
    }

    @Test
    void bothQueriesFailedNoAlert() {
        assertTrue(newJob().checkAndAlert(Optional.empty(), Optional.empty()).isEmpty());
    }

    @Test
    void oneBelowOneAtThresholdAlertsOnlyLow() {
        List<String> reasons = newJob().checkAndAlert(Optional.of(99), Optional.of(GLM_THRESHOLD));
        assertEquals(1, reasons.size());
        assertTrue(reasons.get(0).contains("短信"));
    }

    @Test
    void sendAlertDelegatesToAlertNotifier() {
        newJob().sendAlert("短信余额不足: 50条");
        verify(alertNotifier).notify(eq("SKS 余额告警"), eq("短信余额不足: 50条"));
    }
}
