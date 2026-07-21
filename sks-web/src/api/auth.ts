import { userClient } from './client';

/** C 端 `/me` 返回体（对齐 Java `UserService.MeResponse`）。`balance` 来自额度账本。 */
export interface MeResponse {
  userId: number;
  phone: string;
  nickname: string | null;
  gender: string | null;
  age: number | null;
  city: string | null;
  industry: string | null;
  identity: string | null;
  style: string | null;
  weeklyGoal: number | null;
  defaultPlatform: string | null;
  completeness: number;
  balance: number;
}

/** C 端登录返回体：user JWT + 用户 id + 是否首次注册。 */
export interface LoginResult {
  token: string;
  userId: number;
  isNew: boolean;
}

/** 发送验证码（1/1min、5/1h、10/24h 频控由后端强制）。 */
export function sendCode(phone: string): Promise<void> {
  return userClient.post<unknown, void>('/auth/send-code', { phone });
}

/** 登录（即注册）：校验验证码 → upsert app_user → 签发 user JWT。 */
export function login(phone: string, code: string): Promise<LoginResult> {
  return userClient.post<LoginResult, LoginResult>('/auth/login', { phone, code });
}

/** 拉取当前登录用户资料 + 余额。 */
export function fetchMe(): Promise<MeResponse> {
  return userClient.get<MeResponse, MeResponse>('/user/me');
}
