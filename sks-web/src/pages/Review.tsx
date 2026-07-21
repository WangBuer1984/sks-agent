import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getBizMessage } from '../api/client';
import {
  adoptScript,
  attributeScript,
  feedbackScript,
  getWeeklyReport,
  listScripts,
  playScript,
  trackScript,
  type AttributionView,
  type ScriptSummary,
  type WeeklyReportContent,
} from '../api/review';

/**
 * C 端复盘页 {@code /review}（§4.4）——发布后复盘闭环：采用 → 登记 → 填数 → 判态 → 归因 / 周卡。
 *
 * <p><b>复盘免费</b>（不扣额度）。无流式（硬不变量）——每端点一次返回 JSON。
 *
 * <p>四区：
 * <ol>
 *   <li><b>状态看板</b>：当前用户稿件按 review_state 分组（draft/pending/tracking/hot/plain/flop/rejected）。
 *   <li><b>手动 play_count</b>：tracking 稿 → 填播放量 → POST /play → 判态（hot/plain/flop）。
 *   <li><b>归因</b>：flop 稿 → POST /attribute → 诊断 + 建议。
 *   <li><b>周归因卡</b>：GET /review/weekly?week=本周一 → summary/wins/gaps/next_focus（或 blocked）。
 * </ol>
 *
 * <p>沿用纸感样式（paper palette + serif 标题），与 {@link Calibrate} / {@link Analyze} 一致。
 */
type StateGroup = 'draft' | 'pending' | 'tracking' | 'hot' | 'plain' | 'flop' | 'rejected';

const STATE_ORDER: StateGroup[] = ['hot', 'plain', 'flop', 'tracking', 'pending', 'draft', 'rejected'];

const STATE_LABEL: Record<StateGroup, string> = {
  hot: '爆款',
  plain: '平平',
  flop: '扑街',
  tracking: '已发布待填数',
  pending: '待登记链接',
  draft: '草稿',
  rejected: '已废弃',
};

const STATE_BADGE: Record<StateGroup, string> = {
  hot: 'border-[#e3c9a3] bg-[#fdf3e4] text-[#a8712e]',
  plain: 'border-paper-line bg-[#f7f2e7] text-paper-ink',
  flop: 'border-[#e4b9ab] bg-[#faf0ec] text-[#b0492f]',
  tracking: 'border-[#d8c9b2] bg-paper-card text-paper-primary',
  pending: 'border-[#d8c9b2] bg-paper-card text-paper-primary',
  draft: 'border-paper-line bg-paper-card text-paper-muted',
  rejected: 'border-paper-line bg-[#f4f1e9] text-paper-muted',
};

function isState(s: string | undefined): s is StateGroup {
  return !!s && STATE_LABEL.hasOwnProperty(s);
}

/** 计算本周一（ISO 周首）的 YYYY-MM-DD——周归因 job 写的 week_start 约定。 */
function currentWeekStart(): string {
  const now = new Date();
  // JS getDay: 0=Sun..6=Sat；ISO 周一首：周一=1..周日=7，偏移 = (getDay+6)%7
  const offset = (now.getDay() + 6) % 7;
  const monday = new Date(now);
  monday.setDate(now.getDate() - offset);
  return monday.toISOString().slice(0, 10);
}

export default function Review() {
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [banner, setBanner] = useState<string>('');
  const [playInputs, setPlayInputs] = useState<Record<number, string>>({});
  const [trackInputs, setTrackInputs] = useState<Record<number, string>>({});
  const [feedbackInputs, setFeedbackInputs] = useState<Record<number, string>>({});
  const [attributions, setAttributions] = useState<Record<number, AttributionView>>({});
  const weekStart = useMemo(currentWeekStart, []);

  const { data: scripts, isLoading } = useQuery<ScriptSummary[]>({
    queryKey: ['scripts'],
    queryFn: () => listScripts(),
  });

  const { data: weekly, isLoading: weeklyLoading } = useQuery<WeeklyReportContent | null>({
    queryKey: ['weeklyReport', weekStart],
    queryFn: () => getWeeklyReport(weekStart),
  });

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['scripts'] });
    qc.invalidateQueries({ queryKey: ['weeklyReport', weekStart] });
  };

  const adoptMut = useMutation({
    mutationFn: (id: number) => adoptScript(id),
    onSuccess: () => {
      setError(null);
      setBanner('已采用，待登记发布链接');
      refresh();
    },
    onError: (e: unknown) => setError(getBizMessage(e, '采用失败')),
  });

  const trackMut = useMutation({
    mutationFn: (vars: { id: number; url: string }) => trackScript(vars.id, vars.url),
    onSuccess: () => {
      setError(null);
      setBanner('已登记发布链接，待填播放量');
      refresh();
    },
    onError: (e: unknown) => setError(getBizMessage(e, '登记失败')),
  });

  const playMut = useMutation({
    mutationFn: (vars: { id: number; count: number }) => playScript(vars.id, vars.count),
    onSuccess: (r, vars) => {
      setError(null);
      const label = r.reviewState === 'hot' ? '爆款' : r.reviewState === 'flop' ? '扑街' : '平平';
      setBanner(`判态完成：${label}（${r.reviewState}）`);
      setPlayInputs((p) => ({ ...p, [vars.id]: '' }));
      refresh();
    },
    onError: (e: unknown) => setError(getBizMessage(e, '判态失败')),
  });

  const attrMut = useMutation({
    mutationFn: (id: number) => attributeScript(id),
    onSuccess: (r, id) => {
      setError(null);
      setAttributions((a) => ({ ...a, [id]: r }));
    },
    onError: (e: unknown) => setError(getBizMessage(e, '归因失败，请稍后重试')),
  });

  const feedbackMut = useMutation({
    mutationFn: (vars: { id: number; reason: string }) => feedbackScript(vars.id, vars.reason),
    onSuccess: () => {
      setError(null);
      setBanner('反哺已提交，已写入选题库（source=replay）');
      setFeedbackInputs((p) => ({ ...p, [0]: '' }));
      refresh();
    },
    onError: (e: unknown) => setError(getBizMessage(e, '反哺失败')),
  });

  const grouped = useMemo(() => {
    const groups: Record<StateGroup, ScriptSummary[]> = {
      hot: [], plain: [], flop: [], tracking: [], pending: [], draft: [], rejected: [],
    };
    (scripts ?? []).forEach((s) => {
      if (isState(s.reviewState)) groups[s.reviewState].push(s);
    });
    return groups;
  }, [scripts]);

  const handlePlay = (id: number) => {
    const raw = playInputs[id] ?? '';
    const n = parseInt(raw, 10);
    if (isNaN(n) || n < 0) {
      setError('播放量须为非负整数');
      return;
    }
    playMut.mutate({ id, count: n });
  };

  return (
    <main className="mx-auto min-h-full max-w-3xl px-5 py-8">
      <header className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-black text-paper-ink">复盘</h1>
          <p className="mt-1 text-sm text-paper-muted">
            采用 → 登记链接 → 填播放量 → 判态 → 归因 / 周卡 · 复盘免费
          </p>
        </div>
        <Link
          to="/"
          className="rounded-lg border border-[#d8c9b2] bg-paper-card px-3.5 py-2 text-[13px] font-bold text-paper-primary transition hover:bg-[#f7f2e7]"
        >
          返回工作台
        </Link>
      </header>

      {banner && (
        <p className="mb-4 rounded-lg border border-[#ecd4ae] bg-[#fdf3e4] px-3 py-2 text-[12px] font-semibold text-[#a8712e]">
          {banner}
        </p>
      )}
      {error && (
        <div
          role="alert"
          className="mb-4 rounded-lg border border-[#e4b9ab] bg-[#faf0ec] px-3 py-2 text-[13px] text-[#b0492f]"
        >
          {error}
        </div>
      )}

      {/* 周归因卡 */}
      <section className="mb-6 rounded-2xl border border-paper-line bg-paper-card p-6 shadow-sm">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="font-serif text-lg font-bold text-paper-ink">本周归因</h2>
          <span className="text-[11.5px] text-paper-muted">week_start {weekStart}</span>
        </div>
        {weeklyLoading ? (
          <p className="text-[13px] text-paper-muted">加载中…</p>
        ) : !weekly ? (
          <p className="text-[13px] text-paper-muted">
            本周暂无归因报告（周日定时生成，或本周无已复盘稿件）。
          </p>
        ) : weekly.blocked ? (
          <p className="text-[13px] text-[#b0492f]">
            本周归因被安全拦截，下周自动重跑。
          </p>
        ) : (
          <div className="flex flex-col gap-3">
            <div>
              <dt className="text-[11.5px] text-paper-muted">总览</dt>
              <dd className="mt-0.5 text-sm text-paper-ink whitespace-pre-wrap">
                {weekly.summary || '（无摘要）'}
              </dd>
            </div>
            <div className="grid grid-cols-2 gap-x-6 gap-y-3">
              <ListBlock label="做对的" items={weekly.wins} tone="win" />
              <ListBlock label="待改进" items={weekly.gaps} tone="gap" />
            </div>
            <div>
              <dt className="text-[11.5px] text-paper-muted">下周聚焦</dt>
              <dd className="mt-0.5 text-sm text-paper-ink whitespace-pre-wrap">
                {weekly.nextFocus || '（无建议）'}
              </dd>
            </div>
          </div>
        )}
      </section>

      {/* 状态看板 */}
      <section className="rounded-2xl border border-paper-line bg-paper-card p-6 shadow-sm">
        <h2 className="mb-4 font-serif text-lg font-bold text-paper-ink">稿件看板</h2>
        {isLoading ? (
          <p className="text-[13px] text-paper-muted">加载中…</p>
        ) : !scripts || scripts.length === 0 ? (
          <p className="text-[13px] text-paper-muted">
            还没有稿件，去
            <Link to="/create" className="mx-1 font-bold text-paper-primary underline">创作</Link>
            生成第一篇口播稿。
          </p>
        ) : (
          <div className="flex flex-col gap-4">
            {STATE_ORDER.map((st) =>
              grouped[st].length === 0 ? null : (
                <div key={st}>
                  <div className="mb-2 flex items-center gap-2">
                    <span
                      className={`rounded-full border px-2.5 py-0.5 text-[11px] font-bold ${STATE_BADGE[st]}`}
                    >
                      {STATE_LABEL[st]}
                    </span>
                    <span className="text-[11.5px] text-paper-muted">
                      {grouped[st].length} 篇
                    </span>
                  </div>
                  <ul className="flex flex-col gap-2">
                    {grouped[st].map((s) => (
                      <li
                        key={s.id}
                        className="rounded-lg border border-paper-line bg-[#fdfcf8] px-3.5 py-2.5 text-sm"
                      >
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <span className="text-paper-ink">
                            #{s.id} · {s.platform}
                            <span className="ml-2 text-[11.5px] text-paper-muted">
                              {new Date(s.createdAt).toLocaleDateString()}
                            </span>
                          </span>
                          <div className="flex flex-wrap items-center gap-2">
                            {s.reviewState === 'draft' && (
                              <button
                                type="button"
                                disabled={adoptMut.isPending}
                                onClick={() => adoptMut.mutate(s.id)}
                                className="rounded-lg bg-paper-primary px-3 py-1.5 text-[12px] font-bold text-white transition hover:bg-[#6e4620] disabled:opacity-45"
                              >
                                采用
                              </button>
                            )}
                            {s.reviewState === 'pending' && (
                              <input
                                type="url"
                                placeholder="发布链接"
                                value={trackInputs[s.id] ?? ''}
                                onChange={(e) =>
                                  setTrackInputs((p) => ({ ...p, [s.id]: e.target.value }))
                                }
                                className="w-56 rounded-md border border-[#d8d2c4] bg-[#fdfcf8] px-2.5 py-1.5 text-[12px] outline-none focus:border-paper-primary"
                              />
                            )}
                            {s.reviewState === 'pending' && (
                              <button
                                type="button"
                                disabled={trackMut.isPending || !(trackInputs[s.id] ?? '').trim()}
                                onClick={() =>
                                  trackMut.mutate({
                                    id: s.id,
                                    url: (trackInputs[s.id] ?? '').trim(),
                                  })
                                }
                                className="rounded-lg border border-[#d8c9b2] bg-paper-card px-3 py-1.5 text-[12px] font-bold text-paper-primary transition hover:bg-[#f7f2e7] disabled:opacity-45"
                              >
                                登记链接
                              </button>
                            )}
                            {s.reviewState === 'tracking' && (
                              <>
                                <input
                                  type="number"
                                  min={0}
                                  placeholder="播放量"
                                  value={playInputs[s.id] ?? ''}
                                  onChange={(e) =>
                                    setPlayInputs((p) => ({ ...p, [s.id]: e.target.value }))
                                  }
                                  className="w-28 rounded-md border border-[#d8d2c4] bg-[#fdfcf8] px-2.5 py-1.5 text-[12px] outline-none focus:border-paper-primary"
                                />
                                <button
                                  type="button"
                                  disabled={playMut.isPending || !(playInputs[s.id] ?? '').trim()}
                                  onClick={() => handlePlay(s.id)}
                                  className="rounded-lg bg-paper-primary px-3 py-1.5 text-[12px] font-bold text-white transition hover:bg-[#6e4620] disabled:opacity-45"
                                >
                                  {playMut.isPending ? '判态中…' : '填数判态'}
                                </button>
                              </>
                            )}
                            {s.reviewState === 'flop' && (
                              <button
                                type="button"
                                disabled={attrMut.isPending}
                                onClick={() => attrMut.mutate(s.id)}
                                className="rounded-lg border border-[#d8c9b2] bg-paper-card px-3 py-1.5 text-[12px] font-bold text-paper-primary transition hover:bg-[#f7f2e7] disabled:opacity-45"
                              >
                                {attrMut.isPending ? '归因中…' : attributions[s.id] ? '刷新归因' : '看归因'}
                              </button>
                            )}
                            {(s.reviewState === 'flop' || s.reviewState === 'rejected') && (
                              <input
                                type="text"
                                placeholder="回访反哺（写入选题库）"
                                value={feedbackInputs[s.id] ?? ''}
                                onChange={(e) =>
                                  setFeedbackInputs((p) => ({ ...p, [s.id]: e.target.value }))
                                }
                                className="w-56 rounded-md border border-[#d8d2c4] bg-[#fdfcf8] px-2.5 py-1.5 text-[12px] outline-none focus:border-paper-primary"
                              />
                            )}
                            {(s.reviewState === 'flop' || s.reviewState === 'rejected') && (
                              <button
                                type="button"
                                disabled={feedbackMut.isPending || !(feedbackInputs[s.id] ?? '').trim()}
                                onClick={() =>
                                  feedbackMut.mutate({
                                    id: s.id,
                                    reason: (feedbackInputs[s.id] ?? '').trim(),
                                  })
                                }
                                className="rounded-lg border border-[#d8c9b2] bg-paper-card px-3 py-1.5 text-[12px] font-bold text-paper-primary transition hover:bg-[#f7f2e7] disabled:opacity-45"
                              >
                                反哺
                              </button>
                            )}
                          </div>
                        </div>
                        {/* flop 归因展示 */}
                        {s.reviewState === 'flop' && attributions[s.id] && (
                          <div className="mt-2 rounded-md border border-paper-line bg-[#f7f2e7] px-3 py-2 text-[12.5px]">
                            <p className="text-paper-ink">
                              <span className="font-bold text-paper-muted">诊断：</span>
                              {attributions[s.id].diagnosis || '（无）'}
                            </p>
                            {attributions[s.id].suggestions.length > 0 && (
                              <ul className="mt-1 list-disc pl-5 text-paper-ink">
                                {attributions[s.id].suggestions.map((sg, i) => (
                                  <li key={i}>{sg}</li>
                                ))}
                              </ul>
                            )}
                          </div>
                        )}
                      </li>
                    ))}
                  </ul>
                </div>
              ),
            )}
          </div>
        )}
      </section>
    </main>
  );
}

function ListBlock({
  label,
  items,
  tone,
}: {
  label: string;
  items?: string[];
  tone: 'win' | 'gap';
}) {
  const toneClass =
    tone === 'win'
      ? 'border-[#e3c9a3] bg-[#fdf3e4] text-[#a8712e]'
      : 'border-[#e4b9ab] bg-[#faf0ec] text-[#b0492f]';
  return (
    <div>
      <dt className="text-[11.5px] text-paper-muted">{label}</dt>
      <dd className="mt-0.5">
        {!items || items.length === 0 ? (
          <span className="text-[12px] text-paper-muted">（无）</span>
        ) : (
          <ul className={`flex flex-col gap-1 rounded-md border px-2.5 py-1.5 ${toneClass}`}>
            {items.map((it, i) => (
              <li key={i} className="text-[12.5px]">{it}</li>
            ))}
          </ul>
        )}
      </dd>
    </div>
  );
}
