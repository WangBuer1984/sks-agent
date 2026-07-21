package com.sks.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sks.credit.CreditService;
import com.sks.topic.TopicService;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 拆解任务轮询调度（§4.3）——每 5s 扫描 {@code analyze_task} 处理三种终态/停滞情况。
 *
 * <p><b>三种情况（缺一都会吞用户额度，§4.3 timeout/stale 注释）：</b>
 *
 * <ol>
 *   <li><b>running-timeout</b>：{@code status='running'} 且 {@code updated_at < now()-5min} → 判
 *       {@code failed} + <b>全额退</b>（Python 转写卡死 / 进程崩溃，心跳停了）。refund 幂等挡双退。
 *   <li><b>partial（终态，按比例退一次）</b>：{@code status='partial'} → {@code refundN = charged×(100-progress)/100}
 *       （整数；progress 语义 = 已完成条数比例，<b>非</b>阶段进度——退款数学依赖此口径）。
 *       refundN==0 跳过；refundN>0 退一次（{@code (biz_id, biz_type, 'refund')} 唯一约束 + count 守卫
 *       挡住重复 reconcile 双退）。<b>不改 status</b>——partial 是终态，Python 不再更新该行。
 *   <li><b>stale-queued</b>：{@code status='queued'} 且 {@code updated_at < now()-1min} → 判
 *       {@code failed} + 全额退（Python 返回 202 后即崩，未写 running）。
 * </ol>
 *
 * <p><b>幂等安全</b>：重复 reconcile 对已退过的任务无副作用——{@link CreditService#refund} 先查
 * {@code credit_ledger(biz_id, biz_type, 'refund')} 计数，>0 即 return；credit_ledger 唯一约束是并发兜底。
 * partial 不改 status，故每次 reconcile 都会扫到 partial 行——靠 refund 内部 count 守卫挡住双退
 * （{@link #idempotentReconcileDoesNotDoubleRefund} 钉死此回归）。
 *
 * <p><b>done → benchmark 选题</b>：account 任务 done 后，把 {@code result.videos} 列表写入
 * {@code topic(source='benchmark')}（Task 1.7 benchmark 路数据来源）。幂等——按
 * {@code (user_id, source, title)} 查重，重复 reconcile 不双插。
 *
 * <p><b>不加事务</b>（§4.1 教训）：每条任务独立处理（refund 独立短事务 + markFailed 自动提交），
 * 单任务失败 try/catch 不波及其他任务 / 不中断调度。
 */
@Component
public class AnalyzeTaskPoller {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(AnalyzeTaskPoller.class);

    /** running 超时阈值：5 分钟无 updated_at 更新 → 判 failed。短于 Python transcribe 心跳 60s 的累积。 */
    static final Duration RUNNING_TIMEOUT = Duration.ofMinutes(5);
    /** stale-queued 阈值：1 分钟未转 running → Python 202 后即崩。 */
    static final Duration STALE_QUEUED_TIMEOUT = Duration.ofMinutes(1);

    private final AnalyzeTaskMapper analyzeTaskMapper;
    private final CreditService creditService;
    private final TopicService topicService;

    public AnalyzeTaskPoller(
            AnalyzeTaskMapper analyzeTaskMapper,
            CreditService creditService,
            TopicService topicService) {
        this.analyzeTaskMapper = analyzeTaskMapper;
        this.creditService = creditService;
        this.topicService = topicService;
    }

    /** 每 5s 跑一次（{@code fixedDelay}：上次结束→下次开始间隔，避免长扫堆积）。 */
    @Scheduled(fixedDelay = 5000)
    public void reconcile() {
        OffsetDateTime now = OffsetDateTime.now();
        try {
            reconcileStaleRunning(now);
        } catch (Exception e) {
            log.warn("reconcile stale-running failed: {}", e.getMessage());
        }
        try {
            reconcilePartial();
        } catch (Exception e) {
            log.warn("reconcile partial failed: {}", e.getMessage());
        }
        try {
            reconcileStaleQueued(now);
        } catch (Exception e) {
            log.warn("reconcile stale-queued failed: {}", e.getMessage());
        }
        try {
            reconcileDoneAccount();
        } catch (Exception e) {
            log.warn("reconcile done-account (benchmark) failed: {}", e.getMessage());
        }
    }

    /** running-timeout：updated_at < now-5min → failed + 全额退。 */
    private void reconcileStaleRunning(OffsetDateTime now) {
        OffsetDateTime cutoff = now.minus(RUNNING_TIMEOUT);
        for (AnalyzeTask t : analyzeTaskMapper.findStaleRunning(cutoff)) {
            try {
                fullRefund(t, "running-timeout (5min no updated_at)");
            } catch (Exception e) {
                log.warn("stale-running refund failed for task {}: {}", t.getId(), e.getMessage());
            }
        }
    }

    /** stale-queued：queued 且 updated_at < now-1min → failed + 全额退。 */
    private void reconcileStaleQueued(OffsetDateTime now) {
        OffsetDateTime cutoff = now.minus(STALE_QUEUED_TIMEOUT);
        for (AnalyzeTask t : analyzeTaskMapper.findStaleQueued(cutoff)) {
            try {
                fullRefund(t, "stale-queued (1min no running after acceptance)");
            } catch (Exception e) {
                log.warn("stale-queued refund failed for task {}: {}", t.getId(), e.getMessage());
            }
        }
    }

    /** partial（终态）：按未完成比例退一次 refundN = charged×(100-progress)/100。不改 status。 */
    private void reconcilePartial() {
        for (AnalyzeTask t : analyzeTaskMapper.findPartial()) {
            try {
                int charged = t.getCharged() == null ? 0 : t.getCharged();
                int progress = t.getProgress() == null ? 0 : t.getProgress();
                // progress 语义 = 已完成条数比例（0-100）；refundN = 未完成比例 × charged，整数截断
                int refundN = charged * (100 - progress) / 100;
                if (refundN <= 0) {
                    continue; // 全完成或 charged=0——无需退
                }
                String bizType = bizTypeOf(t.getTaskType());
                // refund 内部 count 守卫挡住重复 reconcile 双退（credit_ledger 唯一约束兜底）
                creditService.refund(t.getUserId(), refundN, bizType, String.valueOf(t.getId()));
                log.info(
                        "partial proportional refund: task={}, charged={}, progress={}, refundN={}",
                        t.getId(),
                        charged,
                        progress,
                        refundN);
            } catch (Exception e) {
                log.warn("partial refund failed for task {}: {}", t.getId(), e.getMessage());
            }
        }
    }

    /** done account → 写 topic(source=benchmark)（幂等，TopicService 按标题查重）。 */
    private void reconcileDoneAccount() {
        for (AnalyzeTask t : analyzeTaskMapper.findDoneAccount()) {
            try {
                JsonNode result = parseJson(t.getResult());
                topicService.writeBenchmarkTopics(t.getUserId(), result);
            } catch (Exception e) {
                log.warn("benchmark topic write failed for task {}: {}", t.getId(), e.getMessage());
            }
        }
    }

    /** 全额退 + markFailed（running-timeout / stale-queued）。refund 幂等挡双退。顺序：先 refund 后 markFailed。 */
    private void fullRefund(AnalyzeTask t, String reason) {
        int charged = t.getCharged() == null ? 0 : t.getCharged();
        if (charged <= 0) {
            // 没扣过（不应出现，防御）——仅 markFailed
            analyzeTaskMapper.markFailed(t.getId(), reason);
            return;
        }
        String bizType = bizTypeOf(t.getTaskType());
        creditService.refund(t.getUserId(), charged, bizType, String.valueOf(t.getId()));
        analyzeTaskMapper.markFailed(t.getId(), truncate(reason));
        log.info(
                "full refund ({}): task={}, user={}, charged={}",
                reason,
                t.getId(),
                t.getUserId(),
                charged);
    }

    private static String bizTypeOf(String taskType) {
        return "account".equals(taskType) ? "analyze_account" : "analyze_video";
    }

    private static String truncate(String s) {
        return s == null ? null : (s.length() > 290 ? s.substring(0, 290) : s);
    }

    private static JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OM.readTree(json);
        } catch (Exception e) {
            log.warn("failed to parse analyze_task.result JSON: {}", e.getMessage());
            return null;
        }
    }
}
