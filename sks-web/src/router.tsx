import type { ReactNode } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import Login from './pages/Login';
import Workbench from './pages/Workbench';
import KB from './pages/KB';
import Create from './pages/Create';
import Calibrate from './pages/Calibrate';
import Analyze from './pages/Analyze';
import Review from './pages/Review';
import AdminLogin from './pages/admin/AdminLogin';
import AdminConsole from './pages/admin/AdminConsole';
import { useAuthStore } from './store/auth';

/**
 * 路由表 + 守卫。
 * - C 端：`/login` 登录，`/` 工作台（需 user token）
 * - 管理端：`/admin/login` 登录，`/admin` 后台（需 admin token）
 * - 无 token 访问受保护页 → 重定向到对应登录页。
 *   401 拦截器也会清 token + 跳登录（双重保险：守卫拦前端导航，拦截器拦后端 401）。
 */
function RequireUser({ children }: { children: ReactNode }) {
  const token = useAuthStore((s) => s.userToken);
  if (!token) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

function RequireAdmin({ children }: { children: ReactNode }) {
  const token = useAuthStore((s) => s.adminToken);
  if (!token) return <Navigate to="/admin/login" replace />;
  return <>{children}</>;
}

export default function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/" element={<RequireUser><Workbench /></RequireUser>} />
      <Route path="/kb" element={<RequireUser><KB /></RequireUser>} />
      <Route path="/create" element={<RequireUser><Create /></RequireUser>} />
      <Route path="/calibrate" element={<RequireUser><Calibrate /></RequireUser>} />
      <Route path="/analyze" element={<RequireUser><Analyze /></RequireUser>} />
      <Route path="/review" element={<RequireUser><Review /></RequireUser>} />
      <Route path="/admin/login" element={<AdminLogin />} />
      <Route path="/admin" element={<RequireAdmin><AdminConsole /></RequireAdmin>} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
