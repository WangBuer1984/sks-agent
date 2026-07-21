package com.sks.review;

/**
 * 复盘状态机事件（§4.4）。
 *
 * <ul>
 *   <li>{@link #ADOPT} —— 用户采用稿件：{@code draft → pending}。
 *   <li>{@link #TRACK} —— 用户登记发布链接：{@code pending → tracking}。
 *   <li>{@link #PLAY_COUNT} —— 用户填播放量：{@code tracking → classify → hot/plain/flop}。
 *   <li>{@link #SWEEP_REJECT} —— RejectSweeper 扫 48h 未采用 draft：{@code draft → rejected}。
 *       <b>仅 draft</b> 可被扫——pending（已采用待登记）<b>不</b>是废弃，状态机拒绝该迁移（见
 *       {@link ReviewStateMachine#next} 的 {@code pending + SWEEP_REJECT → throw}）。
 * </ul>
 *
 * <p><b>注意</b>：flop 的「看归因」与 hot 的「标记爆款素材 / 出续集」<b>不</b>是事件——它们是
 * {@link ReviewService} 在态已定后编排的<b>副作用</b>，不改态（no AI judges state）。
 */
public enum ReviewEvent {
    ADOPT,
    TRACK,
    PLAY_COUNT,
    SWEEP_REJECT
}
