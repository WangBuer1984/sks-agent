package com.sks.review;

import com.sks.script.ScriptMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 废弃稿扫描（§4.4「rejected = 48h 未采用 via scheduled scan」）——{@code @Scheduled} 定时把
 * 超 48h 仍未采用的 {@code draft} 稿置为 {@code rejected}。
 *
 * <p><b>承重：仅扫 draft，<b>不扫 pending</b></b>（brief 明确：「注意不能扫 pending」——pending 是
 * 已采用待登记的稿子，不是废弃）。SQL {@link ScriptMapper#findDraftIdsOlderThan} 只查
 * {@code review_state='draft'}，{@link ScriptMapper#markRejected} 带
 * {@code AND review_state='draft'} 守卫——双重保险，pending 永不被扫 / 误置 rejected。
 * 状态机层面也拒绝 {@code pending + SWEEP_REJECT} 迁移（见 {@link ReviewStateMachine#next} 的非法分支）。
 *
 * <p><b>幂等</b>：{@code markRejected} 带 {@code AND review_state='draft'} 守卫，对已 rejected 的行是
 * no-op（0 行）——重复 reconcile 安全。单条失败 try/catch 不中断调度。
 *
 * <p><b>无 Redis/MQ</b>（CLAUDE.md）：用 Postgres + {@code @Scheduled}，与
 * {@link com.sks.analyze.AnalyzeTaskPoller} 同模式。
 */
@Component
public class RejectSweeper {

    private static final Logger log = LoggerFactory.getLogger(RejectSweeper.class);

    /** draft 超时阈值：48h 未采用 → rejected。 */
    static final Duration REJECT_AFTER = Duration.ofHours(48);

    private final ScriptMapper scriptMapper;

    public RejectSweeper(ScriptMapper scriptMapper) {
        this.scriptMapper = scriptMapper;
    }

    /** 每 5 分钟扫一次（{@code fixedDelay}：上次结束→下次开始间隔，避免堆积）。 */
    @Scheduled(fixedDelay = 300_000)
    public void sweep() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(REJECT_AFTER);
        List<Long> ids = scriptMapper.findDraftIdsOlderThan(cutoff);
        if (ids.isEmpty()) {
            return;
        }
        for (Long id : ids) {
            try {
                int rows = scriptMapper.markRejected(id);
                if (rows > 0) {
                    log.info("rejected stale draft: script={} (48h unadopted)", id);
                }
            } catch (Exception e) {
                log.warn("reject sweep failed for script {}: {}", id, e.getMessage());
            }
        }
    }
}
