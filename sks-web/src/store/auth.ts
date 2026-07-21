import { create } from 'zustand';
import { ADMIN_TOKEN_KEY, USER_TOKEN_KEY } from '../api/client';

/**
 * 鉴权 store（Zustand）：C 端 + 管理端两套 token 分开存储。
 * - C 端 token 存 `sks_token`，管理端 token 存 `sks_admin_token`，两者互不可用（后端 audience 隔离）。
 * - 401 拦截器会直接清 localStorage；本 store 仅同步内存态。登录成功时写入。
 * - 路由守卫读 `userToken` / `adminToken` 判定是否放行。
 */
interface AuthState {
  userToken: string | null;
  userId: number | null;
  adminToken: string | null;
  adminId: number | null;
  adminName: string | null;
  setUserAuth: (token: string, userId: number) => void;
  setAdminAuth: (token: string, adminId: number, name: string) => void;
  logoutUser: () => void;
  logoutAdmin: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  userToken: localStorage.getItem(USER_TOKEN_KEY),
  userId: null,
  adminToken: localStorage.getItem(ADMIN_TOKEN_KEY),
  adminId: null,
  adminName: null,
  setUserAuth: (token, userId) => {
    localStorage.setItem(USER_TOKEN_KEY, token);
    set({ userToken: token, userId });
  },
  setAdminAuth: (token, adminId, name) => {
    localStorage.setItem(ADMIN_TOKEN_KEY, token);
    set({ adminToken: token, adminId, adminName: name });
  },
  logoutUser: () => {
    localStorage.removeItem(USER_TOKEN_KEY);
    set({ userToken: null, userId: null });
  },
  logoutAdmin: () => {
    localStorage.removeItem(ADMIN_TOKEN_KEY);
    set({ adminToken: null, adminId: null, adminName: null });
  },
}));
