import { userClient } from './client';

/**
 * 拆解相关接口（对齐 Java AnalyzeController，§4.3）。
 *
 * 三种模式：
 * - video/text（同步）：粘文案 → 结构化拆解，扣 1，一次返回。
 * - video/link（异步）：粘链接 → 返回 taskId，轮询 GET /tasks/{id}。
 * - account（异步）：粘账号 → precheck → 扣 max(1,min(10,floor(N/2))) → 返回 taskId，轮询。
 *
 * 无流式（硬不变量）：异步任务前端轮询 GET /tasks/{id}，进度直写 analyze_task。
 */

/** 拆视频（粘文案）同步结果：{structure, why_hot, framework, diff_hint}。 */
export interface VideoTextResponse {
  structure: string;
  whyHot: string;
  framework: string;
  diffHint: string;
}

/** 异步任务受理：{taskId}。 */
export interface TaskAccepted {
  taskId: number;
}

/** 拆账号 TOP20 明细行。structure 为 JSON 文本（structure/why_hot/framework/diff_hint）。 */
export interface BenchmarkVideoView {
  id: number;
  title: string;
  playCount: number | null;
  favCount: number | null;
  transcript: string | null;
  structure: string | null;
  createdAt: string;
}

/** 拆账号三层 result（解析自 analyze_task.result JSONB）。 */
export interface AccountResult {
  account_profile?: string;
  patterns?: string;
  migration_advice?: string;
  videos?: { title: string; play_count: number; fav_count: number }[];
}

/** 任务详情（对齐 AnalyzeController.TaskDetail）。result 为 JSON 文本。 */
export interface TaskDetail {
  id: number;
  taskType: string;
  status: string; // queued / running / done / partial / failed
  progress: number;
  charged: number;
  result: string | null;
  error: string | null;
  updatedAt: string;
  createdAt: string;
  videos: BenchmarkVideoView[];
}

/** 拆视频（粘文案）——同步，扣 1。 */
export function analyzeVideoText(transcript: string): Promise<VideoTextResponse> {
  return userClient.post<VideoTextResponse, VideoTextResponse>('/analyze/video/text', { transcript });
}

/** 拆视频（粘链接）——异步，扣 1，返回 taskId。 */
export function analyzeVideoLink(url: string): Promise<TaskAccepted> {
  return userClient.post<TaskAccepted, TaskAccepted>('/analyze/video/link', { url });
}

/** 拆账号——异步，precheck → 扣 max(1,min(10,floor(N/2)))，返回 taskId。 */
export function analyzeAccount(url: string): Promise<TaskAccepted> {
  return userClient.post<TaskAccepted, TaskAccepted>('/analyze/account', { url });
}

/** 任务详情（轮询进度/结果，IDOR 校验）。 */
export function getAnalyzeTask(id: number): Promise<TaskDetail> {
  return userClient.get<TaskDetail, TaskDetail>(`/analyze/tasks/${id}`);
}

/** 安全解析 result JSON 文本为三层对象；非法 / 空 → null。 */
export function parseAccountResult(json: string | null | undefined): AccountResult | null {
  if (!json) return null;
  try {
    return JSON.parse(json) as AccountResult;
  } catch {
    return null;
  }
}

/** 安全解析 benchmark_video.structure JSON 文本为四字段对象。 */
export function parseStructure(json: string | null | undefined): {
  structure?: string;
  why_hot?: string;
  framework?: string;
  diff_hint?: string;
} | null {
  if (!json) return null;
  try {
    return JSON.parse(json) as {
      structure?: string;
      why_hot?: string;
      framework?: string;
      diff_hint?: string;
    };
  } catch {
    return null;
  }
}
