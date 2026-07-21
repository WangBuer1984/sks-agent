package com.sks.topic;

import com.sks.aiclient.AiClient;
import com.sks.kb.KbCardService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 热点选题定时入库（Task 1.7）——按 user 轮询拉热点榜 + pgvector 打分入 {@code source='hot'} 选题。
 *
 * <p><b>P1 运行行为</b>：{@link AiClient#hotBoard} 是空桩（返回 {@link List#of()} + WARN），所以每个
 * 用户的 {@link TopicService#scoreHotTopicsForUser} 都是 no-op（循环 0 次）——Job 跑了也不入库、不报错，
 * 仅日志。真实热点榜接线（Python 调 TikHub）在 <b>P3 Task 3.3 Step 3.5</b>。
 *
 * <p><b>调度间隔</b>：{@code fixedDelay = 6h}（MVP YAGNI，热点榜半衰约 6-12h；P3 接真实数据后可调）。
 * 用 {@code fixedDelay}（上次结束→下次开始间隔）而非 {@code fixedRate}，避免长打分任务堆积。
 *
 * <p><b>不持事务跨长调用</b>（§4.1 教训）：{@link TopicService#scoreHotTopicsForUser} 非
 * {@code @Transactional}，embed HTTP + pgvector 读 + 单行 insert 各自独立；Job 本身也不加事务。
 * 单用户失败不波及其他用户（per-user try/catch）。
 *
 * <p><b>轮询范围</b>：仅有 B 层卡片的用户（{@link KbCardService#findUserIdsWithBCards}）——无 B 卡的用户
 * 跑打分也必无匹配，跳过省一次 hotBoard 调用。P1 桩为空，整体仍为 no-op。
 *
 * <p><b>需要 {@code @EnableScheduling}</b>（见 {@link com.sks.SksServerApplication}）。
 */
@Component
public class HotTopicJob {

    private static final Logger log = LoggerFactory.getLogger(HotTopicJob.class);

    /** fixedDelay 间隔：6 小时（毫秒）。 */
    static final long FIXED_DELAY_MS = 6L * 60 * 60 * 1000;

    private final TopicService topicService;
    private final KbCardService kbCardService;

    public HotTopicJob(TopicService topicService, KbCardService kbCardService) {
        this.topicService = topicService;
        this.kbCardService = kbCardService;
    }

    /**
     * 每 6h 跑一次：遍历有 B 层卡的用户，各自调 {@link TopicService#scoreHotTopicsForUser}。
     *
     * <p>per-user try/catch：单用户打分异常（embed 超时 / pgvector 查询失败）只记 WARN，不中断 Job、
     * 不影响其他用户。整 Job 任何异常都不向上抛（避免 {@code @Scheduled} 默认吞异常后静默停调度）。
     */
    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    public void refreshHotTopics() {
        List<Long> userIds;
        try {
            userIds = kbCardService.findUserIdsWithBCards();
        } catch (Exception e) {
            log.warn("HotTopicJob: failed to list users with B cards: {}", e.getMessage());
            return;
        }
        if (userIds.isEmpty()) {
            log.debug("HotTopicJob tick: no users with B cards, skip");
            return;
        }
        log.info("HotTopicJob tick: scoring hot topics for {} users", userIds.size());
        int totalInserted = 0;
        for (Long uid : userIds) {
            try {
                int n = topicService.scoreHotTopicsForUser(uid);
                totalInserted += n;
            } catch (Exception e) {
                log.warn("HotTopicJob: hot scoring failed for user {}: {}", uid, e.getMessage());
            }
        }
        log.info("HotTopicJob tick done: {} hot topics inserted across {} users", totalInserted, userIds.size());
    }
}
