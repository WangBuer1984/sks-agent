package com.sks.review;

/**
 * 复盘状态机（§4.4）——<b>纯规则判态，无 AI 参与判态</b>（CLAUDE.md 硬不变量）。
 *
 * <p>复盘七态：
 * <ul>
 *   <li>{@link #DRAFT} draft —— 生成后未采用（ScriptService 生成成功置 draft）。
 *   <li>{@link #PENDING} pending —— 已采用，待登记发布链接。
 *   <li>{@link #TRACKING} tracking —— 已登记链接，待填播放量。
 *   <li>{@link #HOT} hot —— 爆款（play &gt;= avg×hotThreshold，默认 ×3）。
 *   <li>{@link #PLAIN} plain —— 平平（带内）。
 *   <li>{@link #FLOP} flop —— 扑街（play &lt; avg×flopThreshold，默认 ×0.5）。
 *   <li>{@link #REJECTED} rejected —— 48h 未采用 draft（RejectSweeper 扫描）。
 * </ul>
 * （{@code generating/failed} 是生成期前置态，由 ScriptService 管，不在本状态机内。）
 *
 * <p><b>双阈值带状区间判态（§4.4）</b>：填播放量后，
 * {@code play >= avg × hotThreshold}（默认 3）→ hot；
 * {@code play < avg × flopThreshold}（默认 0.5）→ flop；之间 → plain。
 * {@code avg} = 近 30 天已复盘稿（hot/plain/flop 且 play_count 非空）的播放量均值（baseline）。
 * 阈值可配（{@code sks.review.hot-threshold} / {@code sks.review.flop-threshold}），由 {@link ReviewService}
 * 从配置读后传入本纯函数——本类<b>不读 Spring 配置</b>，保持可作纯单元测试。
 *
 * <p><b>纯函数</b>：{@link #classify} 与 {@link #next} 均为静态、无副作用、不触 DB / 不调 AI——
 * 所有 DB 写 / HTTP 调用由 {@link ReviewService} 编排。hot 副作用（cardGen C 层卡 + 续集选题）与
 * flop 归因（attributionSingle 返回诊断）是<b>副作用</b>，不是状态迁移——态已定后才编排，不影响状态。
 * 由此可证伪「任何 LLM 调用影响态」的回归（no AI judges state）。
 *
 * <p><b>合法迁移表</b>：
 * <ul>
 *   <li>{@code draft + ADOPT → pending}
 *   <li>{@code draft + SWEEP_REJECT → rejected}（RejectSweeper 扫 48h 未采用 draft；<b>不扫 pending</b>）
 *   <li>{@code pending + TRACK → tracking}
 *   <li>{@code tracking + PLAY_COUNT → classify → hot/plain/flop}
 * </ul>
 * 其余迁移非法，抛 {@link IllegalStateException}。hot/plain/flop/rejected 为终态，不再迁移。
 */
public final class ReviewStateMachine {

    public static final String DRAFT = "draft";
    public static final String PENDING = "pending";
    public static final String TRACKING = "tracking";
    public static final String HOT = "hot";
    public static final String PLAIN = "plain";
    public static final String FLOP = "flop";
    public static final String REJECTED = "rejected";

    private ReviewStateMachine() {}

    /**
     * 按双阈值带状区间判态。
     *
     * @param play          播放量
     * @param avg           近 30 天均值（baseline）；{@code <=0} 视为无历史 → {@link #PLAIN}
     *                      （无 baseline 无法判 hot/flop；首条稿判 plain）
     * @param hotThreshold  热款上界倍数（默认 3.0）
     * @param flopThreshold 扑街下界倍数（默认 0.5）
     */
    public static String classify(long play, double avg, double hotThreshold, double flopThreshold) {
        if (avg <= 0) {
            return PLAIN;
        }
        if (play >= avg * hotThreshold) {
            return HOT;
        }
        if (play < avg * flopThreshold) {
            return FLOP;
        }
        return PLAIN;
    }

    /**
     * 纯函数迁移：{@code (current, event, ctx) → next}。
     *
     * @param ctx 仅 {@code TRACKING + PLAY_COUNT} 需要（classify 输入）；其余事件可传 null
     * @return 下一态
     * @throws IllegalStateException 非法迁移（含终态上的任何事件）
     */
    public static String next(String current, ReviewEvent event, ReviewContext ctx) {
        return switch (current) {
            case DRAFT -> switch (event) {
                case ADOPT -> PENDING;
                case SWEEP_REJECT -> REJECTED;
                default -> throw illegal(current, event);
            };
            case PENDING -> switch (event) {
                case TRACK -> TRACKING;
                default -> throw illegal(current, event);
            };
            case TRACKING -> switch (event) {
                case PLAY_COUNT ->
                    classify(ctx.playCount(), ctx.avg30d(), ctx.hotThreshold(), ctx.flopThreshold());
                default -> throw illegal(current, event);
            };
            default -> throw illegal(current, event); // hot/plain/flop/rejected 终态
        };
    }

    private static IllegalStateException illegal(String current, ReviewEvent event) {
        return new IllegalStateException("非法复盘状态迁移: " + current + " + " + event);
    }
}
