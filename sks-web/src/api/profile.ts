import { userClient } from './client';

/**
 * 定位校准 API（Task 2.2）。校准免费（PRD §4.2），无额度逻辑。
 * 所有端点落在 C 端 user SecurityFilterChain（/api/profile/**），token 由 userClient 拦截器自动注入。
 */

/**
 * /api/profile/interview 响应（对齐 Java InterviewStepView）。
 * - stage: guess_persona / await_feedback / ask / summarize
 * - question: 本轮问题（stage=ask/await_feedback 时非空）
 * - profileDraft: summarize 完成时返回最终档案（JSON 对象）；前端据 done=true 展示 + 确认
 * - done: 图跑完（summarize 完成）
 * - blocked: UGC / LLM 产出命中安全（状态机未推进），前端提示调整后重试
 * - banner: 工作台横幅文案（PRD §11.4「校准进行中，第 X 步」）
 */
export interface InterviewStepView {
  stage: string | null;
  question: string | null;
  profileDraft: Record<string, unknown> | null;
  done: boolean;
  blocked?: boolean;
  banner?: string;
}

/** 推进访谈一轮。首轮传 materials（粘贴的素材文本）、reply 省略；后续轮传 reply、materials 省略。 */
export function interviewStep(
  sessionId: string,
  reply?: string,
  materials?: string,
): Promise<InterviewStepView> {
  return userClient.post<InterviewStepView, InterviewStepView>('/profile/interview', {
    sessionId,
    reply: reply ?? null,
    materials: materials ?? null,
  });
}

/**
 * 语音 → 文字（仅转写，不提交）。返回文本由前端回显 / 编辑后再作为 reply 走 interviewStep。
 * ASR 失败抛 BizError（前端提示改用文字输入，不阻断访谈）。
 */
export function asrVoice(audio: Blob): Promise<string> {
  const form = new FormData();
  form.append('audio', audio, 'audio.webm');
  return userClient.post<string, string>('/profile/voice', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

/** 校准生效：写 active 档案 + 批量建 A 层卡。访谈未完成抛 PARAM_INVALID。 */
export function confirmProfile(sessionId: string): Promise<void> {
  return userClient.post<void, void>('/profile/confirm', { sessionId });
}
