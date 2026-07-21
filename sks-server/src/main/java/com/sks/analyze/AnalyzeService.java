package com.sks.analyze;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sks.aiclient.AiClient;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import com.sks.credit.CreditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 拆解编排服务——§4.3 异步任务额度事务链（产品 #1 资金不变量）。
 *
 * <p><b>§4.3 事务边界（照 §4.1 教训写，勿动）：</b>
 *
 * <ol>
 *   <li>编排方法本身<b>不加 {@code @Transactional}</b>。长 HTTP 调用（{@link AiClient#analyzeAccount}
 *       等 30-60s+，拆账号逐条转写可达数分钟）必须在任何事务之外——否则长事务占住连接池连接，
 *       且失败时 {@link CreditService#refund} 会在 rollback-only 事务里执行（静默 no-op，漏退额度）。
 *   <li>扣费 / 退款各走 {@link CreditService#deduct} / {@link CreditService#refund}（均
 *       {@code @Transactional(REQUIRED)}）——从非事务的编排方法调用 → 各自开独立短事务提交。
 *   <li>稳定 biz_id 模式（§4.1 Task 1.4 占位同款）：<b>先插 {@code analyze_task} 占位行拿 taskId</b>，
 *       再扣费——退款幂等键 {@code (biz_id=taskId, biz_type, type='refund')} 靠它。没有占位行，
 *       同步 video/text 失败路径的退款就没有可用 biz_id。
 * </ol>
 *
 * <p><b>三种模式：</b>
 * <ul>
 *   <li><b>video/text（同步）</b>：插占位 → 扣 1 → 调 {@link AiClient#analyzeVideoText} → 成功 backfill
 *       done+result；blocked/失败 → failed + refund 1 + 抛。
 *   <li><b>video/link（异步）</b>：插占位 → 扣 1 → 调 {@link AiClient#analyzeVideoLink}（Python 202）
 *       → 返回 taskId。Python BackgroundTasks 写进度，{@link AnalyzeTaskPoller} 轮询。
 *   <li><b>account（异步）</b>：precheck（免费，不扣）→ 不可达/N=0 拒绝不扣；N=precheck.video_count
 *       → charge = {@code max(1, min(10, floor(N/2)))} → 插占位 → 扣 charge → 调
 *       {@link AiClient#analyzeAccount}（202）→ 返回 taskId。轮询器负责 done→benchmark 选题 + 三态退款。
 * </ul>
 */
@Service
public class AnalyzeService {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(AnalyzeService.class);

    private final AnalyzeTaskMapper analyzeTaskMapper;
    private final BenchmarkVideoMapper benchmarkVideoMapper;
    private final CreditService creditService;
    private final AiClient aiClient;

    public AnalyzeService(
            AnalyzeTaskMapper analyzeTaskMapper,
            BenchmarkVideoMapper benchmarkVideoMapper,
            CreditService creditService,
            AiClient aiClient) {
        this.analyzeTaskMapper = analyzeTaskMapper;
        this.benchmarkVideoMapper = benchmarkVideoMapper;
        this.creditService = creditService;
        this.aiClient = aiClient;
    }

    /**
     * 拆视频（粘文案）——同步模式：transcript → 结构化拆解。扣 1。
     *
     * <p>顺序（§4.1 占位模式）：插占位 {@code analyze_task(task_type='video', input=transcript)} 拿 taskId
     * → 扣 1（独立短事务）→ 事务外调 {@link AiClient#analyzeVideoText} → 成功 backfill done+result；
     * blocked/失败 → failed + refund 1 + 抛。
     */
    public AiClient.VideoTextResult startVideoText(long userId, String transcript) {
        if (transcript == null || transcript.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文案不能为空");
        }

        // 1. 占位行拿稳定 taskId（退款幂等键）
        AnalyzeTask placeholder = new AnalyzeTask();
        placeholder.setUserId(userId);
        placeholder.setTaskType("video");
        placeholder.setCharged(1);
        placeholder.setInput(toJson("transcript", transcript));
        analyzeTaskMapper.insert(placeholder);
        long taskId = placeholder.getId();

        // 2. 扣 1（独立短事务）。余额不足 → failed + 抛 INSUFFICIENT_BALANCE（不退，没扣过）。
        try {
            creditService.deduct(userId, 1, "analyze_video", String.valueOf(taskId));
        } catch (BizException e) {
            analyzeTaskMapper.markFailed(taskId, e.errorCode().msg());
            throw e;
        }

        // 3. 事务外调 Python（同步结构化）。Python 内部也写 done+result，Java 重写幂等。
        AiClient.VideoTextResult result;
        try {
            result = aiClient.analyzeVideoText(taskId, transcript);
        } catch (RuntimeException e) {
            // 超时 / 连接中断 / 非 2xx（已由 AiClient 翻译为 BizException(AI_FAILED)） / 解析失败
            failAndRefund(userId, taskId, "analyze_video", 1, "video/text HTTP failed: " + e.getMessage());
            throw e instanceof BizException be ? be : new BizException(ErrorCode.AI_FAILED);
        }

        // 4. blocked → failed + refund + CONTENT_BLOCKED（Python 不写 result，Java 决策退）
        if (result.blocked()) {
            failAndRefund(userId, taskId, "analyze_video", 1, "blocked by content safety");
            throw new BizException(ErrorCode.CONTENT_BLOCKED);
        }

        // 5. 成功 → backfill done+result（幂等重写 Python 的 done）
        String resultJson = videoTextResultJson(result);
        analyzeTaskMapper.markDone(taskId, resultJson);
        return result;
    }

    /**
     * 拆视频（粘链接）——异步模式：url → 转写 → 结构化。扣 1。返回 taskId，Python 后台跑，前端轮询。
     */
    public long startVideoLink(long userId, String url) {
        if (url == null || url.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "链接不能为空");
        }
        return startAsyncTask(userId, "video", url, 1, "analyze_video");
    }

    /**
     * 拆账号——异步模式：precheck（免费）→ charge = max(1, min(10, floor(N/2))) → 扣 → 调 Python 202。
     *
     * <p>预检不可达 / N=0 → 拒绝，<b>不扣费不建任务</b>（brief {@code accountPrecheckFailureDoesNotCharge}）。
     * 抓取阶段整体失败（Python BackgroundTasks failed）由轮询器全额退款 + 提示改用拆视频（PRD §11.3）。
     */
    public long startAccount(long userId, String url) {
        if (url == null || url.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "链接不能为空");
        }

        // 1. 预检（免费，不扣）。不可达 / N=0 → 拒绝，不扣费不建任务。
        AiClient.Precheck precheck;
        try {
            precheck = aiClient.precheck(url);
        } catch (RuntimeException e) {
            // 预检本身 AI_FAILED（TikHub 不可达等）→ 不扣费，提示改用拆视频逐条
            log.warn("account precheck failed for user {}: {}", userId, e.getMessage());
            throw e instanceof BizException be ? be : new BizException(ErrorCode.AI_FAILED);
        }
        if (!precheck.reachable() || precheck.videoCount() <= 0) {
            throw new BizException(ErrorCode.AI_FAILED, "账号不可达或无视频，请改用拆视频（粘链接/粘文案）逐条拆解");
        }

        // 2. charge = max(1, min(10, floor(N/2)))——下限 1 防 N=1 白嫖，上限 10 封顶。
        int charge = Math.max(1, Math.min(10, precheck.videoCount() / 2));

        return startAsyncTask(userId, "account", url, charge, "analyze_account");
    }

    /**
     * 异步任务公共编排（video/link + account）：插占位 → 扣 charge → 调 Python 202 → 返回 taskId。
     *
     * <p>Python 202 受理后 BackgroundTasks 跑；{@link AnalyzeTaskPoller} 每 5s 轮询 analyze_task 表
     * 处理 running-timeout / partial-proportional / stale-queued 三态退款。
     */
    private long startAsyncTask(long userId, String taskType, String url, int charge, String bizType) {
        // 1. 占位行拿稳定 taskId
        AnalyzeTask placeholder = new AnalyzeTask();
        placeholder.setUserId(userId);
        placeholder.setTaskType(taskType);
        placeholder.setCharged(charge);
        placeholder.setInput(toJson("url", url));
        analyzeTaskMapper.insert(placeholder);
        long taskId = placeholder.getId();

        // 2. 扣 charge（独立短事务）。余额不足 → failed + 抛 INSUFFICIENT_BALANCE（不退，没扣过）。
        try {
            creditService.deduct(userId, charge, bizType, String.valueOf(taskId));
        } catch (BizException e) {
            analyzeTaskMapper.markFailed(taskId, e.errorCode().msg());
            throw e;
        }

        // 3. 事务外调 Python 202。失败 → failed + 全额退 + 抛（Python 未受理，整笔失败）。
        try {
            if ("video".equals(taskType)) {
                aiClient.analyzeVideoLink(taskId, url);
            } else {
                aiClient.analyzeAccount(taskId, url);
            }
        } catch (RuntimeException e) {
            failAndRefund(userId, taskId, bizType, charge, "async dispatch failed: " + e.getMessage());
            throw e instanceof BizException be ? be : new BizException(ErrorCode.AI_FAILED);
        }

        return taskId;
    }

    /**
     * 取任务详情（IDOR：跨用户 → PARAM_INVALID，§5.1 不泄露存在性）。供 {@link AnalyzeController} 轮询端点。
     */
    public AnalyzeTask getTask(long userId, long taskId) {
        AnalyzeTask t = analyzeTaskMapper.findById(taskId, userId);
        if (t == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "任务不存在");
        }
        return t;
    }

    /** 列出某任务的 TOP20 明细（供 controller 展示，IDOR 由 getTask 保证）。 */
    public java.util.List<BenchmarkVideo> listBenchmarkVideos(long taskId) {
        // benchmark_video 无 user_id 列——IDOR 由调用方先 getTask(userId, taskId) 校验所有权后调用。
        return benchmarkVideoMapper.listByTask(taskId);
    }

    // ---- 内部 ----

    /** 退款 + 占位行置 failed——失败路径用（扣过必退）。<b>顺序：先 refund 后 markFailed</b>（§4.1 教训）。 */
    private void failAndRefund(long userId, long taskId, String bizType, int charged, String error) {
        String truncated = error == null ? null : (error.length() > 290 ? error.substring(0, 290) : error);
        creditService.refund(userId, charged, bizType, String.valueOf(taskId));
        analyzeTaskMapper.markFailed(taskId, truncated);
    }

    private static String toJson(String key, String value) {
        try {
            return OM.createObjectNode().put(key, value).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String videoTextResultJson(AiClient.VideoTextResult r) {
        try {
            return OM.createObjectNode()
                    .put("structure", r.structure() == null ? "" : r.structure())
                    .put("why_hot", r.whyHot() == null ? "" : r.whyHot())
                    .put("framework", r.framework() == null ? "" : r.framework())
                    .put("diff_hint", r.diffHint() == null ? "" : r.diffHint())
                    .toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
