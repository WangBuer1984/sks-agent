import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { type MeResponse, fetchMe } from '../api/auth';
import { getBizMessage } from '../api/client';
import { useAuthStore } from '../store/auth';

/**
 * C 端工作台：顶部展示余额（最显眼）+ 用户资料，底部退出登录。
 * TanStack Query 拉 `GET /api/user/me`，token 由 axios 拦截器自动注入。
 * 401（token 过期）→ 拦截器清 token + 跳 `/login`，本页无需处理。
 */
export default function Workbench() {
  const navigate = useNavigate();
  const logoutUser = useAuthStore((s) => s.logoutUser);
  const { data, isLoading, error } = useQuery<MeResponse>({
    queryKey: ['me'],
    queryFn: fetchMe,
  });

  const handleLogout = () => {
    logoutUser();
    navigate('/login', { replace: true });
  };

  if (isLoading) {
    return (
      <main className="flex min-h-full items-center justify-center">
        <p className="text-paper-muted">加载中…</p>
      </main>
    );
  }

  if (error) {
    return (
      <main className="flex min-h-full flex-col items-center justify-center gap-3">
        <p className="text-[#b0492f]">加载失败：{getBizMessage(error)}</p>
        <button
          type="button"
          onClick={handleLogout}
          className="rounded-lg border border-[#d8c9b2] bg-paper-card px-4 py-2 text-sm font-bold text-paper-primary hover:bg-[#f7f2e7]"
        >
          重新登录
        </button>
      </main>
    );
  }

  const nickname = data?.nickname || '未设置昵称';
  const completeness = data?.completeness ?? 0;

  return (
    <main className="mx-auto min-h-full max-w-3xl px-5 py-8">
      <header className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-black text-paper-ink">随口说 · 工作台</h1>
          <p className="mt-1 text-sm text-paper-muted">
            {nickname}
            {data?.phone ? ` · ${data.phone}` : ''}
          </p>
        </div>
        <button
          type="button"
          onClick={handleLogout}
          className="rounded-lg border border-[#d8c9b2] bg-paper-card px-3.5 py-2 text-[13px] font-bold text-paper-primary transition hover:bg-[#f7f2e7]"
        >
          退出登录
        </button>
      </header>

      {/* 余额卡 —— 顶部最显眼位置 */}
      <section className="mb-5 rounded-2xl border border-paper-line bg-paper-card p-6 shadow-sm">
        <p className="text-xs text-paper-muted">当前额度余额</p>
        <p className="mt-2 font-serif text-5xl font-black text-paper-primary">
          {data?.balance ?? 0}
          <span className="ml-2 text-base font-bold text-paper-muted">条</span>
        </p>
        <p className="mt-2 text-[12px] text-paper-muted">
          口播稿创作 1 条 / 次；拆账号、拆视频详见各功能页计费说明。
        </p>
      </section>

      {/* 资料完整度 + 关键字段 */}
      <section className="rounded-2xl border border-paper-line bg-paper-card p-6 shadow-sm">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-base font-bold text-paper-ink">个人资料</h2>
          <div className="flex items-center gap-3">
            <span className="rounded-full border border-[#ecd4ae] bg-[#fdf3e4] px-2.5 py-1 text-[11px] font-bold text-[#a8712e]">
              完整度 {completeness}%
            </span>
            <Link
              to="/calibrate"
              className="rounded-lg bg-paper-primary px-3.5 py-2 text-[13px] font-bold text-white transition hover:bg-[#6e4620]"
            >
              定位校准 →
            </Link>
            <Link
              to="/create"
              className="rounded-lg border border-[#d8c9b2] bg-paper-card px-3.5 py-2 text-[13px] font-bold text-paper-primary transition hover:bg-[#f7f2e7]"
            >
              创作 →
            </Link>
            <Link
              to="/kb"
              className="rounded-lg border border-[#d8c9b2] bg-paper-card px-3.5 py-2 text-[13px] font-bold text-paper-primary transition hover:bg-[#f7f2e7]"
            >
              知识库 →
            </Link>
            <Link
              to="/analyze"
              className="rounded-lg border border-[#d8c9b2] bg-paper-card px-3.5 py-2 text-[13px] font-bold text-paper-primary transition hover:bg-[#f7f2e7]"
            >
              拆解 →
            </Link>
          </div>
        </div>
        <dl className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm">
          <Field label="行业" value={data?.industry} />
          <Field label="身份定位" value={data?.identity} />
          <Field label="表达风格" value={data?.style} />
          <Field label="周目标" value={data?.weeklyGoal != null ? `${data.weeklyGoal} 条` : null} />
          <Field label="主平台" value={data?.defaultPlatform} />
          <Field label="所在城市" value={data?.city} />
        </dl>
        {completeness < 100 && (
          <p className="mt-4 text-[12px] text-paper-muted">
            资料越完整，AI 生成越贴合你的定位 —— 完善资料以提升创作质量。
          </p>
        )}
      </section>
    </main>
  );
}

function Field({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div>
      <dt className="text-[11.5px] text-paper-muted">{label}</dt>
      <dd className="mt-0.5 text-paper-ink">{value || <span className="text-paper-muted">未填写</span>}</dd>
    </div>
  );
}
