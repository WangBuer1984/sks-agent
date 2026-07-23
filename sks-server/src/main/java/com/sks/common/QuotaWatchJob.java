package com.sks.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 余额监控定时（P5 Task 5.1）——每日 9:00 查阿里云短信余额 + 智谱账户余额，低于阈值告警站长
 * （PRD §11.5 / 设计 §5.3 监控收尾）。
 *
 * <p><b>告警通道已接线，余额查询与外部拨测为联调项</b>：
 * <ul>
 *   <li>{@link #sendAlert(String)}：<b>已接线</b>——经 {@link AlertNotifier} 发邮件到站长（{@link
 *       MailAlertNotifier} 读 {@code sks.alert.admin-email} + {@code spring.mail.host}，host 空→stub
 *       不抛；configured→真发邮件，失败由 {@link MailAlertNotifier} 吞掉不抛，被 {@link #sweep}
 *       try/catch 兜底不中断 Job）。
 *   <li>{@link #querySmsBalance()} / {@link #queryGlmBalance()}：<b>仍为桩</b>——各厂商余额查询 API
 *       （阿里云 BSS OpenAPI / 智谱 BigModel，需 access-key / glm api-key via {@code .env}）。MVP 留桩返回
 *       {@link Optional#empty()} + 联调 TODO。
 *   <li>UptimeRobot 外部拨测：控制台配置，无代码（见 {@code deploy/OPS.md}），联调期接入。
 * </ul>
 *
 * <p><b>可单测部分</b>：{@link #checkAndAlert(Optional, Optional)} 是纯函数——给定两个余额
 * （{@code Optional} 表「查询成功/失败」），返回应触发的告警理由列表。阈值语义严格 {@code <}
 * （at-threshold 不告警，避免边界抖动）。余额查询失败（{@code Optional.empty}）<b>不</b>本身触发告警
 * ——只在成功且低于阈值时告警；单个查询异常不中断 Job（{@link #sweep} try/catch 兜底）。
 *
 * <p><b>不引入新基础设施</b>（CLAUDE.md 硬不变量）：用 Postgres + {@code @Scheduled}，与
 * {@link com.sks.topic.HotTopicJob} / {@link com.sks.review.WeeklyReportJob} /
 * {@link com.sks.analyze.AnalyzeTaskPoller} 同模式。无 Redis/MQ/K8s。
 *
 * <p><b>需要 {@code @EnableScheduling}</b>（P1 已加，见 {@link com.sks.SksServerApplication}）。
 */
@Component
public class QuotaWatchJob {

    private static final Logger log = LoggerFactory.getLogger(QuotaWatchJob.class);

    /** 每日 9:00 跑（brief 指定值）。固定 cron——MVP 不做配置化（YAGNI，mirror WeeklyReportJob）。 */
    static final String CRON = "0 0 9 * * *";

    private final int smsThreshold;
    private final int glmThreshold;
    private final AlertNotifier alertNotifier;

    public QuotaWatchJob(
            @Value("${sks.quota.sms-threshold:100}") int smsThreshold,
            @Value("${sks.quota.glm-threshold:20}") int glmThreshold,
            AlertNotifier alertNotifier) {
        this.smsThreshold = smsThreshold;
        this.glmThreshold = glmThreshold;
        this.alertNotifier = alertNotifier;
    }

    /**
     * 每日 9:00：查两个余额 → 阈值判定 → 告警。整 Job 任何异常都不向上抛（顶层 try/catch，
     * 避免 {@code @Scheduled} 默认吞异常后静默停调度，mirror {@link com.sks.topic.HotTopicJob}）。
     *
     * <p>单个余额查询抛异常 → catch 记 WARN + 当作 {@code Optional.empty()}（跳过该项检查，继续另一项）。
     * 单条告警发送抛异常 → catch 记 WARN，不阻断后续告警。
     */
    @Scheduled(cron = CRON)
    public void sweep() {
        try {
            Optional<Integer> sms;
            try {
                sms = querySmsBalance();
            } catch (Exception e) {
                log.warn("QuotaWatchJob: SMS balance query failed: {}", e.getMessage());
                sms = Optional.empty();
            }
            Optional<Integer> glm;
            try {
                glm = queryGlmBalance();
            } catch (Exception e) {
                log.warn("QuotaWatchJob: GLM balance query failed: {}", e.getMessage());
                glm = Optional.empty();
            }
            List<String> reasons = checkAndAlert(sms, glm);
            for (String reason : reasons) {
                try {
                    sendAlert(reason);
                } catch (Exception e) {
                    log.warn("QuotaWatchJob: sendAlert failed for '{}': {}", reason, e.getMessage());
                }
            }
            if (reasons.isEmpty()) {
                log.info("QuotaWatchJob tick: balances OK (sms={}, glm={})", sms, glm);
            } else {
                log.warn("QuotaWatchJob tick: {} alert(s) fired", reasons.size());
            }
        } catch (Exception e) {
            // 顶层兜底：@Scheduled 默认吞异常后静默停调度——绝不向上抛。
            log.error("QuotaWatchJob sweep failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 纯函数（可单测）：给定两个余额（{@code Optional.empty} = 查询失败/未配置），返回应触发的告警理由列表。
     *
     * <p>阈值严格 {@code <}（at-threshold 不告警）。查询失败的项不触发告警——只跳过该项检查。
     * 调用方 {@link #sweep} 决定如何发告警（{@link #sendAlert}）。
     */
    public List<String> checkAndAlert(Optional<Integer> smsBalance, Optional<Integer> glmBalance) {
        List<String> reasons = new ArrayList<>(2);
        if (smsBalance.isPresent() && smsBalance.get() < smsThreshold) {
            reasons.add("短信余额不足: " + smsBalance.get() + "条 (阈值 " + smsThreshold + "条)");
        }
        if (glmBalance.isPresent() && glmBalance.get() < glmThreshold) {
            reasons.add("GLM余额不足: ¥" + glmBalance.get() + " (阈值 ¥" + glmThreshold + ")");
        }
        return reasons;
    }

    /**
     * 查阿里云短信余额（条数）。MVP 留桩返回 {@link Optional#empty()}——<b>联调</b>时替换为真实 API
     * （阿里云 BSS OpenAPI {@code QueryAccountBalance}，需 access-key-id / access-key-secret via {@code .env}；
     * dysmsapi 无余额查询接口，余额走 BSS——见方法体内联 TODO）。
     * 调用方 {@link #sweep} catch 一切异常并记 WARN，不中断 Job。
     */
    protected Optional<Integer> querySmsBalance() {
        // 联调 TODO: 接阿里云 SMS 余额查询 API。
        // 余额查询在 BSS OpenAPI（com.aliyun:bssopenapi20171214），非 dysmsapi；下个任务接。
        log.debug("[STUB] querySmsBalance not wired (联调替换)");
        return Optional.empty();
    }

    /**
     * 查智谱账户余额（元）。MVP 留桩返回 {@link Optional#empty()}——<b>联调</b>时替换为真实 API
     * （智谱 BigModel 用户余额查询，需 glm api-key via {@code .env}）。
     * 调用方 {@link #sweep} catch 一切异常并记 WARN，不中断 Job。
     */
    protected Optional<Integer> queryGlmBalance() {
        // 联调 TODO: 接智谱账户余额查询 API。
        log.debug("[STUB] queryGlmBalance not wired (联调替换)");
        return Optional.empty();
    }

    /**
     * 发告警给站长（经 {@link AlertNotifier} 发邮件，{@link MailAlertNotifier} 内部读
     * {@code sks.alert.admin-email} + {@code spring.mail.host}，host 空→stub 不抛；
     * configured→真发邮件，失败由 {@link MailAlertNotifier} 吞掉不抛，由 {@link #sweep} try/catch 兜底不中断 Job）。
     */
    protected void sendAlert(String reason) {
        alertNotifier.notify("SKS 余额告警", reason);
    }
}
