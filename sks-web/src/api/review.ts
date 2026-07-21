import { userClient } from './client';

/**
 * 复盘 API（§4.4，Task 4.2 + 4.3）。复盘<b>免费</b>（不扣额度）。所有端点落在 C 端
 * user SecurityFilterChain（{@code /api/review/**}），token 由 userClient 拦截器自动注入。
 *
 * <ul>
 *   <li>{@code POST /api/review/{scriptId}/adopt} —— 采用：draft→pending。
 *   <li>{@code POST /api/review/{scriptId}/track} —— 登记发布链接：pending→tracking。
 *   <li>{@code POST /api/review/{scriptId}/play} —— 填播放量：tracking→classify→hot/plain/flop。
 *   <li>{@code POST /api/review/{scriptId}/attribute} —— 看归因（仅 flop）：返回诊断/建议。
 *   <li>{@code POST /api/review/{scriptId}/feedback} —— rejected 回访反哺：写 source=replay 选题。
 *   <li>{@code GET /api/review/weekly?week=YYYY-MM-DD} —— 周归因卡（Task 4.3）。
 * </ul>
 */

/** 稿件列表项（轻量，复盘看板用；对齐 Java ScriptMapper.listByUser）。 */
export interface ScriptSummary {
  id: number;
  topicId: number;
  platform: string;
  reviewState: string;
  createdAt: string;
  updatedAt: string;
}

/** play 端点响应：判定后的复盘态。 */
export interface PlayResponse {
  reviewState: string;
}

/** flop 归因视图：诊断 + 建议列表。 */
export interface AttributionView {
  diagnosis: string | null;
  suggestions: string[];
  blocked?: boolean;
}

/** 周归因 content（对齐 Java WeeklyReportMapper 写入的 JSONB）。 */
export interface WeeklyReportContent {
  summary?: string | null;
  wins?: string[];
  gaps?: string[];
  nextFocus?: string | null;
  blocked?: boolean;
}

/** 采用稿件：draft→pending。 */
export function adoptScript(scriptId: number): Promise<void> {
  return userClient.post<void, void>(`/review/${scriptId}/adopt`, {});
}

/** 登记发布链接：pending→tracking。 */
export function trackScript(scriptId: number, url: string): Promise<void> {
  return userClient.post<void, void>(`/review/${scriptId}/track`, { url });
}

/** 填播放量：tracking→classify→hot/plain/flop。返回判定态。 */
export function playScript(scriptId: number, count: number): Promise<PlayResponse> {
  return userClient.post<PlayResponse, PlayResponse>(`/review/${scriptId}/play`, { count });
}

/** 看归因（仅 flop）：诊断 + 建议。FREE。blocked → CONTENT_BLOCKED。 */
export function attributeScript(scriptId: number): Promise<AttributionView> {
  return userClient.post<AttributionView, AttributionView>(`/review/${scriptId}/attribute`, {});
}

/** rejected 回访反哺：写 source=replay 选题。 */
export function feedbackScript(scriptId: number, reason: string): Promise<void> {
  return userClient.post<void, void>(`/review/${scriptId}/feedback`, { reason });
}

/** 列出当前用户稿件（可选 review_state 过滤）。 */
export function listScripts(state?: string): Promise<ScriptSummary[]> {
  return userClient.get<ScriptSummary[], ScriptSummary[]>(
    '/scripts',
    { params: state ? { state } : {} },
  );
}

/** 取某周归因报告（week=ISO 周一 YYYY-MM-DD）。无报告 → null。 */
export function getWeeklyReport(week: string): Promise<WeeklyReportContent | null> {
  return userClient.get<WeeklyReportContent | null, WeeklyReportContent | null>(
    '/review/weekly',
    { params: { week } },
  );
}
