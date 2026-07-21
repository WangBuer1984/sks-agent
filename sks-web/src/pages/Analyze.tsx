import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { getBizMessage } from '../api/client';
import {
  analyzeAccount,
  analyzeVideoLink,
  analyzeVideoText,
  getAnalyzeTask,
  parseAccountResult,
  parseStructure,
  type TaskDetail,
  type VideoTextResponse,
} from '../api/analyze';

/**
 * C 端拆解页 {@code /analyze}（§4.3 拆视频 / 拆账号）。
 *
 * <p>三种入口：
 * <ol>
 *   <li>拆视频·粘文案（同步）：贴文案 → 结构化拆解，扣 1，一次返回（无流式，等待动画掩盖 30-60s）。
 *   <li>拆视频·粘链接（异步）：贴链接 → 扣 1 → 返回 taskId，进度条轮询 → 四字段结构。
 *   <li>拆账号（异步）：贴账号链接 → precheck → 扣 max(1,min(10,floor(N/2))) → 返回 taskId，
 *       进度条轮询 → TOP20 清单 + 三层归纳。对齐原型「可先去别处稍后回来看结果」。
 * </ol>
 *
 * <p>逐条「深拆/仿写」按钮为 V1.1（benchmark_video 行已为其预留）。纸感样式（paper palette）。
 */
type Mode = 'videoText' | 'videoLink' | 'account';

const POLL_INTERVAL = 3000;

export default function Analyze() {
  const [mode, setMode] = useState<Mode>('videoText');
  const [input, setInput] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [syncResult, setSyncResult] = useState<VideoTextResponse | null>(null);
  const [taskId, setTaskId] = useState<number | null>(null);
  const pollRef = useRef<boolean>(false);

  // 同步：拆视频·粘文案（扣 1，一次返回）
  const textMut = useMutation({
    mutationFn: (transcript: string) => analyzeVideoText(transcript),
    onSuccess: (r) => {
      setError(null);
      setSyncResult(r);
    },
    onError: (e: unknown) => {
      setSyncResult(null);
      setError(getBizMessage(e, '拆解失败，请稍后重试'));
    },
  });

  // 异步受理：拆视频·粘链接 / 拆账号
  const startMut = useMutation({
    mutationFn: async (vars: { url: string; kind: 'videoLink' | 'account' }) => {
      return vars.kind === 'videoLink'
        ? analyzeVideoLink(vars.url)
        : analyzeAccount(vars.url);
    },
    onSuccess: (r) => {
      setError(null);
      setSyncResult(null);
      setTaskId(r.taskId);
      pollRef.current = true;
    },
    onError: (e: unknown) => {
      setTaskId(null);
      setError(getBizMessage(e, '受理失败，请稍后重试'));
    },
  });

  // 轮询任务详情
  const { data: task } = useQuery<TaskDetail>({
    queryKey: ['analyzeTask', taskId],
    queryFn: () => getAnalyzeTask(taskId as number),
    enabled: taskId !== null,
    refetchInterval: (query) => {
      const d = query.state.data;
      // 终态停止轮询
      if (d && (d.status === 'done' || d.status === 'partial' || d.status === 'failed')) {
        return false;
      }
      return POLL_INTERVAL;
    },
  });

  // 终态时停止轮询标志
  useEffect(() => {
    if (task && (task.status === 'done' || task.status === 'partial' || task.status === 'failed')) {
      pollRef.current = false;
    }
  }, [task]);

  const pending = textMut.isPending || startMut.isPending;
  const asyncRunning =
    taskId !== null && task && !['done', 'partial', 'failed'].includes(task.status);

  const submit = () => {
    const text = input.trim();
    if (!text) {
      setError('请先输入内容');
      return;
    }
    setSyncResult(null);
    setTaskId(null);
    setError(null);
    if (mode === 'videoText') {
      textMut.mutate(text);
    } else if (mode === 'videoLink') {
      startMut.mutate({ url: text, kind: 'videoLink' });
    } else {
      startMut.mutate({ url: text, kind: 'account' });
    }
  };

  const placeholder =
    mode === 'videoText'
      ? '粘贴视频完整文案…'
      : mode === 'videoLink'
        ? '粘贴单条视频链接…'
        : '粘贴账号主页链接…';

  const chargeHint =
    mode === 'videoText' || mode === 'videoLink'
      ? '扣 1 条 / 次'
      : '扣 max(1, min(10, floor(视频数/2))) 条 / 次，预检不扣费';

  return (
    <main className="mx-auto min-h-full max-w-4xl px-5 py-8">
      <header className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-black text-paper-ink">拆解</h1>
          <p className="mt-1 text-sm text-paper-muted">
            拆视频（粘文案 / 粘链接）·拆账号 · {chargeHint}
          </p>
        </div>
        <Link
          to="/"
          className="rounded-lg border border-[#d8c9b2] bg-paper-card px-3.5 py-2 text-[13px] font-bold text-paper-primary transition hover:bg-[#f7f2e7]"
        >
          返回工作台
        </Link>
      </header>

      {error && (
        <div
          role="alert"
          className="mb-4 rounded-lg border border-[#e4b9ab] bg-[#faf0ec] px-3 py-2 text-[13px] text-[#b0492f]"
        >
          {error}
        </div>
      )}

      {/* 模式切换 */}
      <section className="mb-4 flex flex-wrap gap-2">
        {(
          [
            ['videoText', '拆视频·粘文案'],
            ['videoLink', '拆视频·粘链接'],
            ['account', '拆账号'],
          ] as [Mode, string][]
        ).map(([m, label]) => (
          <button
            key={m}
            type="button"
            onClick={() => {
              setMode(m);
              setSyncResult(null);
              setTaskId(null);
              setError(null);
            }}
            className={`rounded-lg border px-3.5 py-2 text-[13px] font-bold transition ${
              mode === m
                ? 'border-paper-primary bg-paper-primary text-white'
                : 'border-[#d8c9b2] bg-paper-card text-paper-primary hover:bg-[#f7f2e7]'
            }`}
          >
            {label}
          </button>
        ))}
      </section>

      {/* 输入区 */}
      <section className="mb-5 rounded-2xl border border-paper-line bg-paper-card p-6 shadow-sm">
        <textarea
          rows={mode === 'videoText' ? 8 : 3}
          placeholder={placeholder}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          className="mb-4 w-full rounded-lg border border-[#d8d2c4] bg-[#fdfcf8] px-3.5 py-2.5 text-sm text-paper-ink outline-none focus:border-paper-primary"
        />
        <button
          type="button"
          disabled={pending || !input.trim()}
          onClick={submit}
          className="rounded-lg bg-paper-primary px-4 py-2 text-[13px] font-bold text-white transition hover:bg-[#6e4620] disabled:cursor-not-allowed disabled:opacity-45"
        >
          {pending
            ? 'AI 思考中…'
            : mode === 'videoText'
              ? '开始拆解'
              : '提交拆解'}
        </button>
        {mode !== 'videoText' && (
          <p className="mt-2 text-[11.5px] text-paper-muted">
            异步任务受理后可先去别处稍后回来看结果——进度条会持续更新。
          </p>
        )}
      </section>

      {/* 同步结果：拆视频·粘文案 */}
      {syncResult && (
        <section className="mb-5 rounded-2xl border border-paper-line bg-paper-card p-6 shadow-sm">
          <h2 className="mb-4 font-serif text-lg font-bold text-paper-ink">结构化拆解</h2>
          <FourFieldView
            structure={syncResult.structure}
            whyHot={syncResult.whyHot}
            framework={syncResult.framework}
            diffHint={syncResult.diffHint}
          />
        </section>
      )}

      {/* 异步任务：进度条 + 结果 */}
      {task && (
        <section className="rounded-2xl border border-paper-line bg-paper-card p-6 shadow-sm">
          <div className="mb-4 flex items-center justify-between">
            <h2 className="font-serif text-lg font-bold text-paper-ink">
              {task.taskType === 'account' ? '拆账号结果' : '拆视频结果'}
            </h2>
            <span
              className={`rounded-full px-2.5 py-1 text-[11px] font-bold ${statusBadge(task.status)}`}
            >
              {statusLabel(task.status)}
            </span>
          </div>

          {/* 进度条 */}
          {(task.status === 'running' || task.status === 'queued') && (
            <div className="mb-5">
              <div className="mb-1.5 flex items-center justify-between text-[12px] text-paper-muted">
                <span>{task.status === 'queued' ? '排队受理中…' : '拆解进行中…'}</span>
                <span>{task.progress}%</span>
              </div>
              <div className="h-2 w-full overflow-hidden rounded-full bg-[#efe8d6]">
                <div
                  className="h-full rounded-full bg-paper-primary transition-all"
                  style={{ width: `${task.progress}%` }}
                />
              </div>
              <p className="mt-2 text-[11.5px] text-paper-muted">
                可先去别处稍后回来看结果——逐条转写 + 结构化较慢。
              </p>
            </div>
          )}

          {/* 失败 */}
          {task.status === 'failed' && (
            <p className="mb-3 rounded-lg border border-[#e4b9ab] bg-[#faf0ec] px-3 py-2 text-[13px] text-[#b0492f]">
              拆解失败{task.error ? `：${task.error}` : ''}。已自动退款。可改用「拆视频（粘链接/粘文案）」逐条拆解。
            </p>
          )}

          {/* 拆视频·粘链接 结果（done，四字段在 result JSONB） */}
          {task.status === 'done' && task.taskType === 'video' && task.result && (
            <VideoLinkResultView resultJson={task.result} />
          )}

          {/* 拆账号 结果（done / partial，TOP20 + 三层归纳） */}
          {(task.status === 'done' || task.status === 'partial') &&
            task.taskType === 'account' && (
              <>
                <AccountSummaryView resultJson={task.result} />
                {task.videos.length > 0 && (
                  <div className="mt-5">
                    <h3 className="mb-3 text-sm font-bold text-paper-ink">TOP 视频清单</h3>
                    <ul className="flex flex-col gap-3">
                      {task.videos.map((v) => (
                        <BenchmarkVideoRow key={v.id} v={v} />
                      ))}
                    </ul>
                    <p className="mt-3 text-[11.5px] text-paper-muted">
                      逐条「深拆/仿写」将在 V1.1 开放。
                    </p>
                  </div>
                )}
              </>
            )}
        </section>
      )}
    </main>
  );
}

/** 拆视频四字段结构化视图（sync 与 link done 共用）。 */
function FourFieldView({
  structure,
  whyHot,
  framework,
  diffHint,
}: {
  structure: string;
  whyHot: string;
  framework: string;
  diffHint: string;
}) {
  return (
    <dl className="grid grid-cols-1 gap-3 md:grid-cols-2">
      <FieldBlock label="文案结构" value={structure} />
      <FieldBlock label="爆火原因" value={whyHot} />
      <FieldBlock label="可复用框架" value={framework} />
      <FieldBlock label="差异化提示" value={diffHint} />
    </dl>
  );
}

function FieldBlock({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-paper-line bg-[#fdfcf8] px-3.5 py-3">
      <dt className="mb-1 text-[11.5px] font-bold text-paper-muted">{label}</dt>
      <dd className="whitespace-pre-wrap break-words text-[13px] leading-relaxed text-paper-ink">
        {value || <span className="text-paper-muted">—</span>}
      </dd>
    </div>
  );
}

/** 拆视频·粘链接 done：从 result JSONB 解析四字段。 */
function VideoLinkResultView({ resultJson }: { resultJson: string }) {
  const r = parseStructure(resultJson);
  if (!r) {
    return <p className="text-[13px] text-paper-muted">结果解析失败。</p>;
  }
  return (
    <FourFieldView
      structure={r.structure ?? ''}
      whyHot={r.why_hot ?? ''}
      framework={r.framework ?? ''}
      diffHint={r.diff_hint ?? ''}
    />
  );
}

/** 拆账号三层归纳视图（account_profile / patterns / migration_advice）。 */
function AccountSummaryView({ resultJson }: { resultJson: string | null }) {
  const r = parseAccountResult(resultJson);
  if (!r) {
    return (
      <p className="mb-3 text-[13px] text-paper-muted">
        归纳结果暂未生成（部分失败时可能只有明细）。
      </p>
    );
  }
  return (
    <dl className="mb-5 grid grid-cols-1 gap-3 md:grid-cols-3">
      <FieldBlock label="账号画像" value={r.account_profile ?? ''} />
      <FieldBlock label="规律归纳" value={r.patterns ?? ''} />
      <FieldBlock label="迁移建议" value={r.migration_advice ?? ''} />
    </dl>
  );
}

/** TOP20 单行：标题 / 播放 / 完整文案 / 结构标注。 */
function BenchmarkVideoRow({
  v,
}: {
  v: {
    id: number;
    title: string;
    playCount: number | null;
    favCount: number | null;
    transcript: string | null;
    structure: string | null;
  };
}) {
  const [expanded, setExpanded] = useState(false);
  const struct = parseStructure(v.structure);
  return (
    <li className="rounded-lg border border-paper-line bg-[#fdfcf8] px-3.5 py-3">
      <button
        type="button"
        onClick={() => setExpanded((x) => !x)}
        className="flex w-full items-center justify-between gap-3 text-left"
      >
        <span className="text-[13.5px] font-bold text-paper-ink">{v.title || '（无标题）'}</span>
        <span className="shrink-0 text-[11.5px] text-paper-muted">
          播放 {fmtCount(v.playCount)} · 收藏 {fmtCount(v.favCount)}
        </span>
      </button>
      {expanded && (
        <div className="mt-3">
          {v.transcript && (
            <p className="mb-3 whitespace-pre-wrap break-words rounded bg-[#f7f2e7] px-3 py-2 text-[12.5px] leading-relaxed text-paper-ink">
              {v.transcript}
            </p>
          )}
          {struct && (
            <FourFieldView
              structure={struct.structure ?? ''}
              whyHot={struct.why_hot ?? ''}
              framework={struct.framework ?? ''}
              diffHint={struct.diff_hint ?? ''}
            />
          )}
        </div>
      )}
    </li>
  );
}

function fmtCount(n: number | null): string {
  if (n == null) return '—';
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万`;
  return String(n);
}

function statusBadge(status: string): string {
  switch (status) {
    case 'done':
      return 'border-[#ecd4ae] bg-[#fdf3e4] text-[#a8712e]';
    case 'partial':
      return 'border-[#e4b9ab] bg-[#faf0ec] text-[#b0492f]';
    case 'failed':
      return 'border-[#e4b9ab] bg-[#faf0ec] text-[#b0492f]';
    case 'running':
      return 'border-[#d8c9b2] bg-[#f7f2e7] text-paper-primary';
    default:
      return 'border-paper-line bg-paper-card text-paper-muted';
  }
}

function statusLabel(status: string): string {
  switch (status) {
    case 'queued':
      return '排队中';
    case 'running':
      return '拆解中';
    case 'done':
      return '完成';
    case 'partial':
      return '部分完成';
    case 'failed':
      return '失败';
    default:
      return status;
  }
}
