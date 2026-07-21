import axios, { type AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';

/**
 * 后端统一响应体（Java `ApiResponse<T>`）：`code === 0` 成功，`code !== 0` 业务错误。
 * 401 是裸 HTTP 401（无 body），不进入响应拦截器的成功分支——由错误拦截器处理。
 */
export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

/**
 * 业务错误：携带后端 `code` + `message`，供表单展示。
 * 401 / 网络异常也包装成 BizError（code=401 或 -1），便于调用方统一处理。
 */
export class BizError extends Error {
  readonly code: number;
  constructor(code: number, message: string) {
    super(message);
    this.name = 'BizError';
    this.code = code;
  }
}

export const USER_TOKEN_KEY = 'sks_token';
export const ADMIN_TOKEN_KEY = 'sks_admin_token';
const USER_RETURN_KEY = 'sks_return_to';
const ADMIN_RETURN_KEY = 'sks_admin_return_to';

type AuthKind = 'user' | 'admin';

/**
 * 把后端业务错误码翻译成用户可读文案（对齐 PRD 异常表）。
 * 未命中的 code 直接回显后端 message。
 */
function readableBizMessage(code: number, message: string): string {
  switch (code) {
    case 4002: return '发送过于频繁，请稍后再试';
    case 4003: return '验证码错误';
    case 4004: return '验证码错误次数过多，请 10 分钟后再试';
    case 4011: return '账号或密码错误';
    default: return message || '操作失败';
  }
}

/**
 * 两个 axios 实例：
 * - userClient: C 端，baseURL `/api`，注入 `sks_token`，401 → 清 token + 跳 `/login`
 * - adminClient: 管理端，baseURL `/api/admin`，注入 `sks_admin_token`，401 → 清 token + 跳 `/admin/login`
 *
 * 响应拦截器：`code === 0` → 返回 `data`（调用方拿到解包后的 payload）；
 *             `code !== 0` → 抛 BizError（进入错误分支后原样 reject）。
 * 错误拦截器：401（裸、无 body）→ 清 token + 存回跳路径 + 跳对应登录页；其他 → BizError。
 */
function attachInterceptors(instance: ReturnType<typeof axios.create>, kind: AuthKind) {
  const tokenKey = kind === 'user' ? USER_TOKEN_KEY : ADMIN_TOKEN_KEY;
  const loginPath = kind === 'user' ? '/login' : '/admin/login';
  const returnKey = kind === 'user' ? USER_RETURN_KEY : ADMIN_RETURN_KEY;

  instance.interceptors.request.use((config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem(tokenKey);
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  });

  instance.interceptors.response.use(
    (res: AxiosResponse<ApiResponse<unknown>>) => {
      const body = res.data;
      if (body && typeof body === 'object' && 'code' in body && typeof body.code === 'number') {
        if (body.code === 0) {
          // 解包：调用方通过 `client.get<T, T>(...)` 拿到 `body.data`
          return body.data as unknown as AxiosResponse;
        }
        throw new BizError(body.code, readableBizMessage(body.code, body.message));
      }
      // 非 ApiResponse 结构（理论上不应出现），原样返回
      return res;
    },
    (error: unknown) => {
      // 成功分支抛出的 BizError（code !== 0）原样向上抛
      if (error instanceof BizError) {
        return Promise.reject(error);
      }
      const axiosErr = error as AxiosError<ApiResponse<unknown>>;
      const status = axiosErr?.response?.status;
      // 401：裸响应、无 body —— 绝不尝试读 data.code，直接清 token + 跳登录
      if (status === 401) {
        localStorage.removeItem(tokenKey);
        const cur = window.location.pathname + window.location.search;
        if (!cur.startsWith(loginPath)) {
          localStorage.setItem(returnKey, cur);
          window.location.href = loginPath;
        }
        return Promise.reject(new BizError(401, '登录已过期，请重新登录'));
      }
      // 其它 HTTP 错误：若 body 是 ApiResponse，翻译其 code/message
      const body = axiosErr?.response?.data;
      if (body && typeof body === 'object' && 'code' in body && typeof body.code === 'number' && body.code !== 0) {
        return Promise.reject(new BizError(body.code, readableBizMessage(body.code, body.message)));
      }
      const msg = axiosErr?.message ?? '网络异常，请稍后重试';
      return Promise.reject(new BizError(-1, msg));
    },
  );

  return instance;
}

export const userClient = attachInterceptors(axios.create({ baseURL: '/api' }), 'user');
export const adminClient = attachInterceptors(axios.create({ baseURL: '/api/admin' }), 'admin');

/** 从 rejected promise 提取可读文案，供表单 error 展示。 */
export function getBizMessage(e: unknown, fallback = '操作失败'): string {
  if (e instanceof BizError) return e.message || fallback;
  if (e instanceof Error) return e.message || fallback;
  return fallback;
}
