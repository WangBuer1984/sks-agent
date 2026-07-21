import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { getBizMessage } from '../../api/client';
import { adminLogin } from '../../api/admin';
import { useAuthStore } from '../../store/auth';

/**
 * 管理端登录页：独立账号密码体系，与 C 端手机号隔离，无自助注册/找回。
 * 视觉复刻原型 `随口说后台管理原型-admin.html` 的 #loginScreen（暖炭背景 + 纸卡登录框）。
 */
export default function AdminLogin() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();
  const setAdminAuth = useAuthStore((s) => s.setAdminAuth);

  const loginMut = useMutation({
    mutationFn: () => adminLogin(username, password),
    onSuccess: (data) => {
      setAdminAuth(data.token, data.adminId, data.name);
      setError(null);
      const ret = localStorage.getItem('sks_admin_return_to');
      if (ret) {
        localStorage.removeItem('sks_admin_return_to');
        navigate(ret);
      } else {
        navigate('/admin');
      }
    },
    onError: (e: unknown) => setError(getBizMessage(e, '登录失败')),
  });

  const canSubmit = username.trim().length > 0 && password.length > 0 && !loginMut.isPending;

  return (
    <main
      className="flex min-h-full items-center justify-center px-4 py-10"
      style={{ backgroundImage: 'radial-gradient(circle at 20% 20%, #2c2a24 0%, #23231f 55%)' }}
    >
      <div className="w-full max-w-[372px] rounded-2xl bg-[#fdfcf8] p-9 shadow-[0_24px_70px_rgba(0,0,0,0.4)]">
        <header className="mb-6 text-center">
          <h1 className="font-serif text-[22px] font-black tracking-[0.04em] text-[#23231f]">
            随口说 · 站长后台
          </h1>
          <p className="mt-1.5 text-[11px] tracking-[0.22em] text-[#a09a8a]">ADMIN CONSOLE</p>
        </header>

        {error && (
          <div
            role="alert"
            className="mb-3.5 rounded-lg border border-[#e4b9ab] bg-[#faf0ec] px-3 py-2.5 text-[12.5px] leading-relaxed text-[#b0492f]"
          >
            {error}
          </div>
        )}

        <div className="mb-3.5">
          <label className="mb-1.5 block text-xs font-semibold text-[#8a8578]">账号</label>
          <input
            type="text"
            autoComplete="username"
            placeholder="管理员账号"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            className="w-full rounded-lg border border-[#d8d2c4] bg-[#fdfcf8] px-3.5 py-2.5 text-sm text-[#23231f] outline-none focus:border-paper-primary"
          />
        </div>

        <div className="mb-3.5">
          <label className="mb-1.5 block text-xs font-semibold text-[#8a8578]">密码</label>
          <input
            type="password"
            autoComplete="current-password"
            placeholder="登录密码"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && canSubmit) loginMut.mutate();
            }}
            className="w-full rounded-lg border border-[#d8d2c4] bg-[#fdfcf8] px-3.5 py-2.5 text-sm text-[#23231f] outline-none focus:border-paper-primary"
          />
        </div>

        <button
          type="button"
          disabled={!canSubmit}
          onClick={() => loginMut.mutate()}
          className="mt-2 w-full rounded-lg bg-paper-primary py-3 text-[13.5px] font-bold text-white transition hover:bg-[#6e4620] disabled:cursor-not-allowed disabled:opacity-45"
        >
          {loginMut.isPending ? '登录中…' : '登 录'}
        </button>

        <p className="mt-4 text-center text-[11.5px] leading-relaxed text-[#a09a8a]">
          管理端为独立账号体系，与 C 端手机号登录隔离。
          <br />
          账号由后台种子写入，无自助注册 / 找回。
        </p>

        <div className="mt-4 text-center">
          <Link to="/login" className="text-[12px] text-paper-primary underline">
            ← C 端登录
          </Link>
        </div>
      </div>
    </main>
  );
}
