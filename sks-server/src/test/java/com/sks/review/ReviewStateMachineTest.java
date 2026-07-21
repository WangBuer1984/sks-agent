package com.sks.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * 纯函数单元测试 for {@link ReviewStateMachine}（classify + next）——<b>无 Spring 上下文</b>，纯 JUnit，快。
 *
 * <p>本类覆盖 §4.4 双阈值带状判态 + 全部合法迁移 + 代表性非法迁移 + avg=0 边界。
 * 集成测试（RejectSweeper 扫 draft 不扫 pending、hot 副作用、flop 归因、IDOR）在 {@link ReviewServiceTest}。
 *
 * <p><b>no AI judges state</b>（CLAUDE.md 硬不变量）：本类断言的判态全部是纯数学（双阈值带）+ 静态迁移表，
 * 不触 DB / 不调 LLM——可证伪「任何 LLM 调用影响态」的回归。
 */
class ReviewStateMachineTest {

    // ---- classify: 双阈值带状区间（§4.4）----

    /** brief verbatim: 9000 >= 2000×3 → hot。 */
    @Test
    void playCountAboveThresholdBecomesHot() {
        assertEquals("hot", ReviewStateMachine.classify(9000, 2000, 3, 0.5));
    }

    /** brief verbatim: 1000 <= 1500 < 6000 → plain。 */
    @Test
    void playCountWithinBandIsPlain() {
        assertEquals("plain", ReviewStateMachine.classify(1500, 2000, 3, 0.5));
    }

    /** brief verbatim: 500 < 2000×0.5 → flop。 */
    @Test
    void playCountBelowLowerBoundMeansFlop() {
        assertEquals("flop", ReviewStateMachine.classify(500, 2000, 3, 0.5));
    }

    /** 边界：play == avg×hotThreshold → hot（{@code >=} 含等号）。 */
    @Test
    void classifyAtHotBoundaryIsHot() {
        assertEquals("hot", ReviewStateMachine.classify(6000, 2000, 3, 0.5));
    }

    /** 边界：play == avg×flopThreshold → plain（{@code <} 严格小于，flop 不含等号）。 */
    @Test
    void classifyAtFlopBoundaryIsPlain() {
        assertEquals("plain", ReviewStateMachine.classify(1000, 2000, 3, 0.5));
    }

    /** 边界 edge：avg=0（无历史 baseline）→ plain。无法在无 baseline 下判 hot/flop。 */
    @Test
    void classifyWithZeroAvgReturnsPlain() {
        assertEquals("plain", ReviewStateMachine.classify(99999, 0, 3, 0.5));
        assertEquals("plain", ReviewStateMachine.classify(0, 0, 3, 0.5));
    }

    /** 阈值可配（非 3.0/0.5 默认值也能正确判态——证明阈值是入参、非硬编码）。 */
    @Test
    void classifyHonorsConfiguredThresholds() {
        // hot 阈值 2.0：8000 >= 2000×2 → hot（默认 3.0 下应为 plain）
        assertEquals("hot", ReviewStateMachine.classify(4000, 2000, 2, 0.5));
        // flop 阈值 0.8：1500 < 2000×0.8=1600 → flop（默认 0.5 下应为 plain）
        assertEquals("flop", ReviewStateMachine.classify(1500, 2000, 3, 0.8));
    }

    // ---- next: 合法迁移（全表覆盖）----

    /** draft + ADOPT → pending（采用）。 */
    @Test
    void draftAdoptGoesPending() {
        assertEquals("pending", ReviewStateMachine.next("draft", ReviewEvent.ADOPT, null));
    }

    /** draft + SWEEP_REJECT → rejected（RejectSweeper 扫 48h 未采用 draft）。 */
    @Test
    void draftSweepRejectGoesRejected() {
        assertEquals("rejected", ReviewStateMachine.next("draft", ReviewEvent.SWEEP_REJECT, null));
    }

    /** pending + TRACK → tracking（登记发布链接）。 */
    @Test
    void pendingTrackGoesTracking() {
        assertEquals("tracking", ReviewStateMachine.next("pending", ReviewEvent.TRACK, null));
    }

    /** tracking + PLAY_COUNT（超阈值）→ hot。 */
    @Test
    void trackingPlayCountAboveGoesHot() {
        ReviewContext ctx = new ReviewContext(9000, 2000, 3, 0.5);
        assertEquals("hot", ReviewStateMachine.next("tracking", ReviewEvent.PLAY_COUNT, ctx));
    }

    /** tracking + PLAY_COUNT（带内）→ plain。 */
    @Test
    void trackingPlayCountWithinGoesPlain() {
        ReviewContext ctx = new ReviewContext(1500, 2000, 3, 0.5);
        assertEquals("plain", ReviewStateMachine.next("tracking", ReviewEvent.PLAY_COUNT, ctx));
    }

    /** tracking + PLAY_COUNT（低于下界）→ flop。 */
    @Test
    void trackingPlayCountBelowGoesFlop() {
        ReviewContext ctx = new ReviewContext(500, 2000, 3, 0.5);
        assertEquals("flop", ReviewStateMachine.next("tracking", ReviewEvent.PLAY_COUNT, ctx));
    }

    /** tracking + PLAY_COUNT（avg=0 无历史）→ plain（首条稿无 baseline）。 */
    @Test
    void trackingPlayCountNoHistoryGoesPlain() {
        ReviewContext ctx = new ReviewContext(12345, 0, 3, 0.5);
        assertEquals("plain", ReviewStateMachine.next("tracking", ReviewEvent.PLAY_COUNT, ctx));
    }

    // ---- next: 非法迁移（代表性覆盖）----

    /** brief verbatim: draft + PLAY_COUNT → throw（未 tracking 不能填数）。 */
    @Test
    void illegalTransitionThrows() {
        assertThrows(
                IllegalStateException.class,
                () -> ReviewStateMachine.next("draft", ReviewEvent.PLAY_COUNT, null));
    }

    /** draft + TRACK → throw（须先 ADOPT 到 pending 再 TRACK）。 */
    @Test
    void draftTrackIllegal() {
        assertThrows(
                IllegalStateException.class,
                () -> ReviewStateMachine.next("draft", ReviewEvent.TRACK, null));
    }

    /** pending + PLAY_COUNT → throw（须先 TRACK 到 tracking 再填播放量）。 */
    @Test
    void pendingPlayCountIllegal() {
        assertThrows(
                IllegalStateException.class,
                () -> ReviewStateMachine.next("pending", ReviewEvent.PLAY_COUNT, null));
    }

    /**
     * pending + SWEEP_REJECT → throw（<b>承重</b>：pending 是已采用待登记，<b>不</b>是废弃——
     * RejectSweeper 不得扫 pending；状态机层面也拒绝该迁移，双重保险）。
     */
    @Test
    void pendingSweepRejectIllegal() {
        assertThrows(
                IllegalStateException.class,
                () -> ReviewStateMachine.next("pending", ReviewEvent.SWEEP_REJECT, null));
    }

    /** tracking + ADOPT → throw（已采用过，不能重复采用）。 */
    @Test
    void trackingAdoptIllegal() {
        assertThrows(
                IllegalStateException.class,
                () -> ReviewStateMachine.next("tracking", ReviewEvent.ADOPT, null));
    }

    /** tracking + TRACK → throw（已登记链接，不能重复登记）。 */
    @Test
    void trackingTrackIllegal() {
        assertThrows(
                IllegalStateException.class,
                () -> ReviewStateMachine.next("tracking", ReviewEvent.TRACK, null));
    }

    /** hot + PLAY_COUNT → throw（终态，不再迁移）。 */
    @Test
    void hotPlayCountIllegal() {
        ReviewContext ctx = new ReviewContext(99999, 2000, 3, 0.5);
        assertThrows(
                IllegalStateException.class,
                () -> ReviewStateMachine.next("hot", ReviewEvent.PLAY_COUNT, ctx));
    }

    /** plain + TRACK → throw（终态）。 */
    @Test
    void plainTrackIllegal() {
        assertThrows(
                IllegalStateException.class,
                () -> ReviewStateMachine.next("plain", ReviewEvent.TRACK, null));
    }

    /** flop + ADOPT → throw（终态；flop 的「看归因」是副作用，不是状态迁移）。 */
    @Test
    void flopAdoptIllegal() {
        assertThrows(
                IllegalStateException.class,
                () -> ReviewStateMachine.next("flop", ReviewEvent.ADOPT, null));
    }

    /** rejected + ADOPT → throw（终态，废弃不可复活）。 */
    @Test
    void rejectedAdoptIllegal() {
        assertThrows(
                IllegalStateException.class,
                () -> ReviewStateMachine.next("rejected", ReviewEvent.ADOPT, null));
    }
}
