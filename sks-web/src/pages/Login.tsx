import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { getBizMessage } from '../api/client';
import { login, sendCode } from '../api/auth';
import { useAuthStore } from '../store/auth';

/**
 * C 端登录页：手机号 + 验证码（登录即注册）。
 * 流程：输入手机号 → 发码（60s 客户端倒计时，1/1min 频控由后端强制）→ 输码 → 登录 → 存 token 跳工作台。
 * 业务错误（SMS_RATE_LIMIT / SMS_CODE_INVALID / SMS_CODE_LOCKED）从 BizError.message 取文案展示。
 */
export default function Login() {
  const [phone, setPhone] = useState('');
  const [code, setCode] = useState('');
  const [countdown, setCountdown] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();
  const setUserAuth = useAuthStore((s) => s.setUserAuth);

  // 60s 倒计时：countdown > 0 时每秒递减，到 0 停。组件卸载时自动清 timer。
  useEffect(() => {
    if (countdown <= 0) return;
    const t = setTimeout(() => setCountdown((c) => c - 1), 1000);
    return () => clearTimeout(t);
  }, [countdown]);

  const sendCodeMut = useMutation({
    mutationFn: () => sendCode(phone),
    onSuccess: () => {
      setError(null);
      setCountdown(60);
    },
    onError: (e: unknown) => setError(getBizMessage(e, '验证码发送失败')),
  });

  const loginMut = useMutation({
    mutationFn: () => login(phone, code),
    onSuccess: (data) => {
      setUserAuth(data.token, data.userId);
      setError(null);
      const ret = localStorage.getItem('sks_return_to');
      if (ret) {
        localStorage.removeItem('sks_return_to');
        navigate(ret);
      } else {
        navigate('/');
      }
    },
    onError: (e: unknown) => setError(getBizMessage(e, '登录失败')),
  });

  const phoneValid = /^1\d{10}$/.test(phone);
  const codeValid = /^\d{4,6}$/.test(code);

  return (
    <main className="flex min-h-full flex-col items-center justify-center px-4 py-10">
      <div className="w-full max-w-sm rounded-2xl border border-paper-line bg-paper-card p-8 shadow-sm">
        <header className="mb-7 text-center">
          <h1 className="text-3xl font-black tracking-wide text-paper-ink">随口说</h1>
          <p className="mt-1.5 text-[11px] tracking-[0.22em] text-paper-muted">SUIKOUSHUO</p>
        </header>

        {error && (
          <div
            role="alert"
            className="mb-4 rounded-lg border border-[#e4b9ab] bg-[#faf0ec] px-3 py-2.5 text-[13px] leading-relaxed text-[#b0492f]"
          >
            {error}
          </div>
        )}

        <div className="mb-4">
          <label className="mb-1.5 block text-xs font-semibold text-paper-muted">手机号</label>
          <input
            type="tel"
            inputMode="numeric"
            maxLength={11}
            placeholder="请输入 11 位手机号"
            value={phone}
            onChange={(e) => setPhone(e.target.value.replace(/\D/g, ''))}
            className="w-full rounded-lg border border-[#d8d2c4] bg-[#fdfcf8] px-3.5 py-2.5 text-sm text-paper-ink outline-none focus:border-paper-primary"
          />
        </div>

        <div className="mb-5">
          <label className="mb-1.5 block text-xs font-semibold text-paper-muted">验证码</label>
          <div className="flex gap-2">
            <input
              type="text"
              inputMode="numeric"
              maxLength={6}
              placeholder="6 位验证码"
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
              className="w-full rounded-lg border border-[#d8d2c4] bg-[#fdfcf8] px-3.5 py-2.5 text-sm text-paper-ink outline-none focus:border-paper-primary"
            />
            <button
              type="button"
              disabled={!phoneValid || countdown > 0 || sendCodeMut.isPending}
              onClick={() => sendCodeMut.mutate()}
              className="shrink-0 rounded-lg border border-[#d8c9b2] bg-paper-card px-3.5 text-[13px] font-bold text-paper-primary transition hover:bg-[#f7f2e7] disabled:cursor-not-allowed disabled:opacity-45"
            >
              {countdown > 0 ? `${countdown}s` : sendCodeMut.isPending ? '发送中…' : '发送验证码'}
            </button>
          </div>
        </div>

        <button
          type="button"
          disabled={!phoneValid || !codeValid || loginMut.isPending}
          onClick={() => loginMut.mutate()}
          className="w-full rounded-lg bg-paper-primary py-3 text-[13.5px] font-bold text-white transition hover:bg-[#6e4620] disabled:cursor-not-allowed disabled:opacity-45"
        >
          {loginMut.isPending ? '登录中…' : '登 录'}
        </button>

        <p className="mt-5 text-center text-[11.5px] leading-relaxed text-paper-muted">
          登录即注册，首次注册赠送 3 条体验额度。
          <br />
          验证码 10 分钟内有效，错误 5 次将锁定 10 分钟。
        </p>

        <div className="mt-4 text-center">
          <Link to="/admin/login" className="text-[12px] text-paper-primary underline">
            管理端入口 →
          </Link>
        </div>
      </div>
    </main>
  );
}
