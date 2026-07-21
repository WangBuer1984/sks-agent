import { userClient } from './client';

/** 选题（对齐 Java Topic）。 */
export interface Topic {
  id: number;
  userId: number;
  source: string;
  title: string;
  rationale: string;
  pillar?: string | null;
  status: string;
  createdAt: string;
}

/** 稿件详情（对齐 Java ScriptController.ScriptDetail）。hook/body/cta 为 JSON 文本。 */
export interface ScriptDetail {
  id: number;
  topicId: number;
  hook: string;
  body: string;
  cta: string;
  platform: string;
  reviewState: string;
  citedCardIds: number[];
  createdAt: string;
  updatedAt: string;
}

/** 稿件列表项（轻量，不含 hook/body/cta）。 */
export interface ScriptSummary {
  id: number;
  topicId: number;
  platform: string;
  reviewState: string;
  createdAt: string;
  updatedAt: string;
}

/** 三段之一：{sentences:[{idx,text}]}。 */
interface Section {
  sentences: { idx: number; text: string }[];
}

/** 解析 JSONB 段文本为句数组；非法 / 空 → []。 */
export function parseSection(json: string | null | undefined): { idx: number; text: string }[] {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json) as Section;
    return Array.isArray(parsed?.sentences) ? parsed.sentences : [];
  } catch {
    return [];
  }
}

/** 列出当前用户选题。 */
export function listTopics(): Promise<Topic[]> {
  return userClient.get<Topic[], Topic[]>('/topics');
}

/** 新建选题（title 走 UGC 内容安全；source 缺省 faq）。 */
export function createTopic(title: string, rationale: string, source?: string): Promise<number> {
  return userClient.post<number, number>('/topics', { title, rationale, source });
}

/** 生成文案（§4.1 额度事务链；30-60s）。platform 缺省取用户主平台。 */
export function generateScript(topicId: number, platform?: string): Promise<ScriptDetail> {
  return userClient.post<ScriptDetail, ScriptDetail>('/scripts/generate', { topicId, platform });
}

/** 稿件列表（可选 review_state 过滤）。 */
export function listScripts(state?: string): Promise<ScriptSummary[]> {
  return userClient.get<ScriptSummary[], ScriptSummary[]>('/scripts', { params: state ? { state } : {} });
}

/** 稿件详情。 */
export function getScript(id: number): Promise<ScriptDetail> {
  return userClient.get<ScriptDetail, ScriptDetail>(`/scripts/${id}`);
}

/** 单句手改：{section, idx, text} —— 落库。 */
export function editSentence(
  id: number,
  section: 'hook' | 'body' | 'cta',
  idx: number,
  text: string,
): Promise<void> {
  return userClient.put<void, void>(`/scripts/${id}/sentence`, { section, idx, text });
}

/** 单句 AI 重写预览：{section, idx} → {preview}。不扣额度、不落库。blocked → CONTENT_BLOCKED。 */
export function rewriteSentence(
  id: number,
  section: 'hook' | 'body' | 'cta',
  idx: number,
): Promise<string> {
  return userClient
    .post<{ preview: string }, { preview: string }>(`/scripts/${id}/rewrite-sentence`, { section, idx })
    .then((r) => r.preview);
}
