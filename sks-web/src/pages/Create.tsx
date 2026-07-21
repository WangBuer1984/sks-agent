import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { BizError, getBizMessage } from '../api/client';
import { type CardSummary, listCards } from '../api/kb';
import {
  type ScriptDetail,
  type ScriptSummary,
  type Topic,
  createTopic,
  editSentence,
  generateScript,
  getScript,
  listScripts,
  listTopics,
  parseSection,
  rewriteSentence,
} from '../api/script';

/**
 * C 端创作页 {@code /create}：选选题 → 生成 → 多阶段进度动画 → 三段逐句渲染
 * （编辑 / 换个说法）+ 右栏引用卡片 + 历史稿件入口。
 *
 * <p>设计要点：
 * - 生成走 §4.1 额度事务链（30-60s），前端用进度动画「检索知识库 → 撰写 → 安全审核」掩盖等待（无流式，§5.1）。
 * - 单句「换个说法」调 rewrite-sentence 返回<b>预览</b>，可采用（→ PUT sentence 落库）/ 放弃 / 再换；
 *   被 block（CONTENT_BLOCKED）展示提示、原句保留。
 * - 引用卡片：生成响应带 citedCardIds，前端拉 B 层卡片列表匹配标题展示。
 */
type SectionKey = 'hook' | 'body' | 'cta';

const SECTION_LABELS: Record<SectionKey, string> = { hook: '钩子', body: '正文', cta: '转化' };

/** 多阶段进度动画的阶段（每 6-8s 推进，掩盖 30-60s 等待）。 */
const PROGRESS_STAGES = ['检索知识库', '撰写中', '安全审核中'];

export default function Create() {
  const queryClient = useQueryClient();

  const { data: topics, isLoading: topicsLoading } = useQuery<Topic[]>({
    queryKey: ['topics'],
    queryFn: listTopics,
  });
  const { data: history } = useQuery<ScriptSummary[]>({
    queryKey: ['scripts', 'draft'],
    queryFn: () => listScripts('draft'),
  });

  const [selectedTopicId, setSelectedTopicId] = useState<number | null>(null);
  const [script, setScript] = useState<ScriptDetail | null>(null);
  const [genError, setGenError] = useState<string | null>(null);
  const [stage, setStage] = useState(-1); // -1 闲置；0..2 进度阶段

  const { data: bCards } = useQuery<CardSummary[]>({
    queryKey: ['kb-cards', 'B'],
    queryFn: () => listCards('B'),
    enabled: script != null && script.citedCardIds.length > 0,
  });

  const genMut = useMutation({
    mutationFn: (vars: { topicId: number; platform?: string }) =>
      generateScript(vars.topicId, vars.platform),
    onMutate: () => {
      setGenError(null);
      setStage(0);
    },
    onSuccess: (s) => {
      setScript(s);
      setStage(-1);
      queryClient.invalidateQueries({ queryKey: ['scripts'] });
    },
    onError: (e: unknown) => {
      setStage(-1);
      setGenError(getBizMessage(e, '生成失败'));
    },
  });

  // 进度阶段自动推进（掩盖长等待）
  useEffect(() => {
    if (stage < 0) return;
    const t = setTimeout(() => setStage((s) => (s + 1 < PROGRESS_STAGES.length ? s + 1 : s)), 7000);
    return () => clearTimeout(t);
  }, [stage]);

  const handleGenerate = () => {
    if (selectedTopicId == null) return;
    setScript(null);
    genMut.mutate({ topicId: selectedTopicId });
  };

  const handleEdited = (s: ScriptDetail) => {
    setScript(s);
    queryClient.invalidateQueries({ queryKey: ['scripts'] });
  };

  return (
    <main className="mx-auto min-h-full max-w-5xl px-5 py-8">
      <header className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="font-serif text-2xl font-black text-paper-ink">创作</h1>
          <p className="mt-1 text-sm text-paper-muted">选选题 · 生成口播稿 · 逐句打磨</p>
        </div>
        <div className="flex items-center gap-2">
          <Link
            to="/"
            className="rounded-lg border border-[#d8c9b2] bg-paper-card px-3.5 py-2 text-[13px] font-bold text-paper-primary transition hover:bg-[#f7f2e7]"
          >
            返回工作台
          </Link>
        </div>
      </header>

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-[1fr_18rem]">
        {/* 左：主创作区 */}
        <div className="flex flex-col gap-5">
          {/* 选题区 */}
          <section className="rounded-2xl border border-paper-line bg-paper-card p-5 shadow-sm">
            <div className="mb-3 flex items-center justify-between">
              <h2 className="font-serif text-base font-bold text-paper-ink">选题</h2>
              <CreateTopicButton onCreated={(id) => setSelectedTopicId(id)} />
            </div>
            {topicsLoading && <p className="text-sm text-paper-muted">加载中…</p>}
            {topics && topics.length === 0 && (
              <p className="text-sm text-paper-muted">暂无选题，点「新建选题」添加。</p>
            )}
            {topics && topics.length > 0 && (
              <ul className="flex flex-col gap-1.5">
                {topics.map((t) => (
                  <li key={t.id}>
                    <button
                      type="button"
                      onClick={() => setSelectedTopicId(t.id)}
                      className={`w-full rounded-lg border px-3 py-2 text-left text-sm transition ${
                        selectedTopicId === t.id
                          ? 'border-paper-primary bg-[#fdf3e4] text-paper-primary'
                          : 'border-paper-line bg-paper-card text-paper-ink hover:bg-[#f7f2e7]'
                      }`}
                    >
                      <span className="font-bold">{t.title}</span>
                      {t.rationale && (
                        <span className="ml-2 text-[12px] text-paper-muted">{t.rationale}</span>
                      )}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          {/* 生成按钮 + 进度动画 */}
          <section className="rounded-2xl border border-paper-line bg-paper-card p-5 shadow-sm">
            <button
              type="button"
              onClick={handleGenerate}
              disabled={selectedTopicId == null || genMut.isPending || stage >= 0}
              className="w-full rounded-xl bg-paper-primary px-4 py-3 font-serif text-base font-bold text-white transition hover:bg-[#6e4620] disabled:cursor-not-allowed disabled:opacity-45"
            >
              {stage >= 0 ? '生成中…' : '生成文案'}
            </button>
            <p className="mt-2 text-[11.5px] text-paper-muted">1 条 / 次 · 失败自动退回额度</p>

            {/* 多阶段进度动画 */}
            {stage >= 0 && (
              <ol className="mt-4 flex flex-col gap-1.5">
                {PROGRESS_STAGES.map((label, i) => (
                  <li
                    key={label}
                    className={`flex items-center gap-2 text-sm ${
                      i <= stage ? 'text-paper-primary' : 'text-paper-muted'
                    }`}
                  >
                    <span
                      className={`inline-block h-2 w-2 rounded-full ${
                        i <= stage ? 'bg-paper-primary' : 'bg-paper-line'
                      }`}
                    />
                    {label}
                    {i === stage && <span className="ml-1 animate-pulse text-[11px]">…</span>}
                  </li>
                ))}
              </ol>
            )}

            {genError && (
              <div
                role="alert"
                className="mt-3 rounded-lg border border-[#e4b9ab] bg-[#faf0ec] px-3 py-2 text-[13px] text-[#b0492f]"
              >
                {genError}
              </div>
            )}
          </section>

          {/* 三段逐句渲染 */}
          {script && (
            <ScriptEditor script={script} bCards={bCards ?? []} onEdited={handleEdited} />
          )}
        </div>

        {/* 右：引用卡片 + 历史稿件 */}
        <aside className="flex flex-col gap-5">
          {script && script.citedCardIds.length > 0 && (
            <section className="rounded-2xl border border-paper-line bg-paper-card p-5 shadow-sm">
              <h2 className="mb-3 font-serif text-sm font-bold text-paper-ink">引用卡片</h2>
              <ul className="flex flex-col gap-1.5">
                {script.citedCardIds.map((cid) => {
                  const c = (bCards ?? []).find((x) => x.id === cid);
                  return (
                    <li
                      key={cid}
                      className="rounded-lg border border-paper-line bg-paper-base px-2.5 py-1.5 text-[12px] text-paper-ink"
                    >
                      {c ? c.title : `卡 #${cid}`}
                    </li>
                  );
                })}
              </ul>
            </section>
          )}

          <section className="rounded-2xl border border-paper-line bg-paper-card p-5 shadow-sm">
            <h2 className="mb-3 font-serif text-sm font-bold text-paper-ink">历史稿件</h2>
            {(!history || history.length === 0) && (
              <p className="text-[12px] text-paper-muted">暂无草稿稿件。</p>
            )}
            {history && history.length > 0 && (
              <ul className="flex flex-col gap-1.5">
                {history.map((s) => (
                  <li key={s.id}>
                    <Link
                      to="/create"
                      onClick={() => {
                        // 简化：历史稿件入口仅刷新本页；详情拉取留给后续增强
                        setScript(null);
                      }}
                      className="block rounded-lg border border-paper-line bg-paper-base px-2.5 py-1.5 text-[12px] text-paper-ink transition hover:bg-[#f7f2e7]"
                    >
                      #{s.id} · {s.platform} · {s.reviewState}
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </aside>
      </div>
    </main>
  );
}

/** 三段逐句编辑器：每句悬浮出「编辑」「换个说法」。 */
function ScriptEditor({
  script,
  bCards,
  onEdited,
}: {
  script: ScriptDetail;
  bCards: CardSummary[];
  onEdited: (s: ScriptDetail) => void;
}) {
  return (
    <section className="rounded-2xl border border-paper-line bg-paper-card p-6 shadow-sm">
      <div className="mb-4 flex items-center justify-between">
        <h2 className="font-serif text-lg font-bold text-paper-ink">稿件 #{script.id}</h2>
        <span className="rounded-full border border-[#ecd4ae] bg-[#fdf3e4] px-2.5 py-1 text-[11px] font-bold text-[#a8712e]">
          {script.platform}
        </span>
      </div>
      <div className="flex flex-col gap-5">
        {(['hook', 'body', 'cta'] as SectionKey[]).map((key) => (
          <SectionEditor
            key={key}
            scriptId={script.id}
            section={key}
            sentences={parseSection(script[key])}
            fullScript={script}
            onEdited={onEdited}
          />
        ))}
      </div>
    </section>
  );
}

/** 单段编辑器：逐句渲染 + 编辑 / 换个说法。 */
function SectionEditor({
  scriptId,
  section,
  sentences,
  fullScript,
  onEdited,
}: {
  scriptId: number;
  section: SectionKey;
  sentences: { idx: number; text: string }[];
  fullScript: ScriptDetail;
  onEdited: (s: ScriptDetail) => void;
}) {
  const [editingIdx, setEditingIdx] = useState<number | null>(null);
  const [draft, setDraft] = useState('');
  const [rewriteIdx, setRewriteIdx] = useState<number | null>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [rewriteMsg, setRewriteMsg] = useState<string | null>(null);

  const editMut = useMutation({
    mutationFn: (vars: { idx: number; text: string }) =>
      editSentence(scriptId, section, vars.idx, vars.text),
    onSuccess: () => {
      setEditingIdx(null);
      // 拉取最新稿件回灌
      getScript(scriptId).then(onEdited);
    },
    onError: (e: unknown) => setRewriteMsg(getBizMessage(e, '保存失败')),
  });

  const rewriteMut = useMutation({
    mutationFn: (vars: { idx: number }) => rewriteSentence(scriptId, section, vars.idx),
    onSuccess: (text) => {
      setPreview(text);
      setRewriteMsg(null);
    },
    onError: (e: unknown) => {
      if (e instanceof BizError && e.code === 5002) {
        setRewriteMsg('内容被安全拦截，原句保留');
      } else {
        setRewriteMsg(getBizMessage(e, '重写失败'));
      }
      setPreview(null);
    },
  });

  const startEdit = (idx: number, text: string) => {
    setEditingIdx(idx);
    setDraft(text);
    setRewriteIdx(null);
    setPreview(null);
    setRewriteMsg(null);
  };

  const startRewrite = (idx: number) => {
    setRewriteIdx(idx);
    setPreview(null);
    setRewriteMsg(null);
    rewriteMut.mutate({ idx });
  };

  const adoptPreview = () => {
    if (preview == null || rewriteIdx == null) return;
    editMut.mutate(
      { idx: rewriteIdx, text: preview },
      {
        onSuccess: () => {
          setRewriteIdx(null);
          setPreview(null);
        },
      },
    );
  };

  return (
    <div>
      <h3 className="mb-2 text-[12px] font-bold uppercase tracking-wide text-paper-muted">
        {SECTION_LABELS[section]}
      </h3>
      {sentences.length === 0 && <p className="text-sm text-paper-muted">（无内容）</p>}
      <ol className="flex flex-col gap-1.5">
        {sentences.map((s) => (
          <li key={s.idx} className="group">
            {/* 编辑模式 */}
            {editingIdx === s.idx ? (
              <div className="flex flex-col gap-1.5">
                <textarea
                  rows={2}
                  value={draft}
                  onChange={(e) => setDraft(e.target.value)}
                  className="w-full rounded-lg border border-[#d8d2c4] bg-[#fdfcf8] px-3 py-2 text-sm text-paper-ink outline-none focus:border-paper-primary"
                />
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => editMut.mutate({ idx: s.idx, text: draft })}
                    disabled={editMut.isPending}
                    className="rounded-lg bg-paper-primary px-3 py-1.5 text-[12px] font-bold text-white transition hover:bg-[#6e4620] disabled:opacity-45"
                  >
                    保存
                  </button>
                  <button
                    type="button"
                    onClick={() => setEditingIdx(null)}
                    className="rounded-lg border border-[#d8c9b2] bg-paper-card px-3 py-1.5 text-[12px] font-bold text-paper-primary transition hover:bg-[#f7f2e7]"
                  >
                    取消
                  </button>
                </div>
              </div>
            ) : rewriteIdx === s.idx ? (
              // 换个说法预览模式
              <div className="flex flex-col gap-1.5 rounded-lg border border-[#ecd4ae] bg-[#fdf3e4] px-3 py-2">
                {preview != null ? (
                  <p className="text-sm text-paper-ink">{preview}</p>
                ) : (
                  <p className="text-sm text-paper-muted">重写中…</p>
                )}
                {rewriteMsg && (
                  <p className="text-[12px] text-[#b0492f]">{rewriteMsg}</p>
                )}
                {preview != null && (
                  <div className="flex gap-2">
                    <button
                      type="button"
                      onClick={adoptPreview}
                      disabled={editMut.isPending}
                      className="rounded-lg bg-paper-primary px-3 py-1.5 text-[12px] font-bold text-white transition hover:bg-[#6e4620] disabled:opacity-45"
                    >
                      采用
                    </button>
                    <button
                      type="button"
                      onClick={() => startRewrite(s.idx)}
                      disabled={rewriteMut.isPending}
                      className="rounded-lg border border-[#d8c9b2] bg-paper-card px-3 py-1.5 text-[12px] font-bold text-paper-primary transition hover:bg-[#f7f2e7] disabled:opacity-45"
                    >
                      再换
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setRewriteIdx(null);
                        setPreview(null);
                      }}
                      className="rounded-lg border border-[#d8c9b2] bg-paper-card px-3 py-1.5 text-[12px] font-bold text-paper-primary transition hover:bg-[#f7f2e7]"
                    >
                      放弃
                    </button>
                  </div>
                )}
              </div>
            ) : (
              // 普通展示模式
              <div className="flex items-start gap-2 rounded-lg px-3 py-2 transition hover:bg-[#f7f2e7]/60">
                <span className="mt-0.5 text-[11px] font-bold text-paper-muted">{s.idx + 1}.</span>
                <p className="flex-1 text-[15px] leading-relaxed text-paper-ink">{s.text}</p>
                <span className="flex shrink-0 gap-1 opacity-0 transition group-hover:opacity-100">
                  <button
                    type="button"
                    onClick={() => startEdit(s.idx, s.text)}
                    className="rounded-md border border-[#d8c9b2] bg-paper-card px-2 py-1 text-[11px] font-bold text-paper-primary transition hover:bg-[#f7f2e7]"
                  >
                    编辑
                  </button>
                  <button
                    type="button"
                    onClick={() => startRewrite(s.idx)}
                    disabled={rewriteMut.isPending}
                    className="rounded-md border border-[#d8c9b2] bg-paper-card px-2 py-1 text-[11px] font-bold text-paper-primary transition hover:bg-[#f7f2e7] disabled:opacity-45"
                  >
                    换个说法
                  </button>
                </span>
              </div>
            )}
          </li>
        ))}
      </ol>
    </div>
  );
}

/** 新建选题小弹窗。title 走 UGC 内容安全（后端 safetyCheck）。 */
function CreateTopicButton({ onCreated }: { onCreated: (id: number) => void }) {
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState('');
  const [rationale, setRationale] = useState('');
  const [error, setError] = useState<string | null>(null);

  const mut = useMutation({
    mutationFn: () => createTopic(title, rationale),
    onSuccess: (id) => {
      setOpen(false);
      setTitle('');
      setRationale('');
      setError(null);
      onCreated(id);
    },
    onError: (e: unknown) => setError(getBizMessage(e, '新建失败')),
  });

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="rounded-lg border border-[#d8c9b2] bg-paper-card px-3 py-1.5 text-[12px] font-bold text-paper-primary transition hover:bg-[#f7f2e7]"
      >
        + 新建选题
      </button>
      {open && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 px-4">
          <div className="w-full max-w-md rounded-2xl border border-paper-line bg-paper-card p-6 shadow-lg">
            <h3 className="mb-4 font-serif text-lg font-bold text-paper-ink">新建选题</h3>
            {error && (
              <div
                role="alert"
                className="mb-3 rounded-lg border border-[#e4b9ab] bg-[#faf0ec] px-3 py-2 text-[13px] text-[#b0492f]"
              >
                {error}
              </div>
            )}
            <div className="mb-3">
              <label className="mb-1 block text-xs font-semibold text-paper-muted">标题</label>
              <input
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="如：如何挑选口播选题"
                className="w-full rounded-lg border border-[#d8d2c4] bg-[#fdfcf8] px-3 py-2 text-sm outline-none focus:border-paper-primary"
              />
            </div>
            <div className="mb-4">
              <label className="mb-1 block text-xs font-semibold text-paper-muted">选题理由</label>
              <textarea
                rows={2}
                value={rationale}
                onChange={(e) => setRationale(e.target.value)}
                placeholder="为什么选这个题"
                className="w-full rounded-lg border border-[#d8d2c4] bg-[#fdfcf8] px-3 py-2 text-sm outline-none focus:border-paper-primary"
              />
            </div>
            <div className="flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setOpen(false)}
                className="rounded-lg border border-[#d8c9b2] bg-paper-card px-4 py-2 text-[13px] font-bold text-paper-primary transition hover:bg-[#f7f2e7]"
              >
                取消
              </button>
              <button
                type="button"
                disabled={!title.trim() || mut.isPending}
                onClick={() => mut.mutate()}
                className="rounded-lg bg-paper-primary px-4 py-2 text-[13px] font-bold text-white transition hover:bg-[#6e4620] disabled:opacity-45"
              >
                {mut.isPending ? '提交中…' : '创建'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
