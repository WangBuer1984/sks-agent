import { adminClient } from './client';

/** 管理端登录返回体：admin JWT + 管理员 id + 姓名。 */
export interface AdminLoginResult {
  token: string;
  adminId: number;
  name: string;
}

/** 尾号搜用户的命中行：`[{userId, phoneMasked, balance, latestOrderStatus}]`。 */
export interface AdminUser {
  userId: number;
  phoneMasked: string;
  balance: number;
  latestOrderStatus: string | null;
}

/**
 * 订单行（对齐 Java `RechargeOrderService.listOrders` 的 Map 字段）。
 * `orderType` ∈ recharge / compensate；`status` ∈ trial / done。
 * `openedAt` / `createdAt` 为 ISO 字符串（Spring Boot 默认 WRITE_DATES_AS_TIMESTAMPS=false）。
 */
export interface AdminOrder {
  orderId: number;
  userId: number;
  phoneTail: string | null;
  orderType: string;
  pkg: string | null;
  amount: number;
  status: string;
  isFirstCharge: boolean | null;
  adminUserId: number | null;
  memo: string | null;
  openedAt: string | null;
  createdAt: string | null;
}

/** 管理端登录。 */
export function adminLogin(username: string, password: string): Promise<AdminLoginResult> {
  return adminClient.post<AdminLoginResult, AdminLoginResult>('/auth/login', { username, password });
}

/** 按手机尾号（后 4-6 位）搜用户。 */
export function fetchUsers(phoneTail: string): Promise<AdminUser[]> {
  return adminClient.get<AdminUser[], AdminUser[]>('/users', { params: { phoneTail } });
}

/** 订单列表；`status` 为空 → 全部。 */
export function fetchOrders(status?: string): Promise<AdminOrder[]> {
  return adminClient.get<AdminOrder[], AdminOrder[]>('/orders', { params: status ? { status } : {} });
}

/** 开通：trial→done（首充 +10 bonus）或复购新建 done 单。返回更新后余额。`pkg` ∈ p50 / p150。 */
export function openOrder(userId: number, pkg: 'p50' | 'p150'): Promise<number> {
  return adminClient.post<number, number>('/orders/open', { userId, pkg });
}

/** 补偿额度：建 compensate 单留痕 + credit。返回更新后余额。 */
export function compensate(userId: number, n: number, memo: string): Promise<number> {
  return adminClient.post<number, number>('/compensate', { userId, n, memo });
}
