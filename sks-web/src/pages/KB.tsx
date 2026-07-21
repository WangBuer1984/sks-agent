import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { BizError, getBizMessage } from '../api/client';
import { type CardLayer, type CardSummary, createCard, deleteCard, listCards, updateCard } from '../api/kb';

/**
 * C 端知识库管理页 {@code /kb}：A/B/C 三层 tab + 卡片列表 + 新建/编辑弹窗 + 删除引用保护。
 *
 * <p>B 层卡保存时提示「已重算向量，立即生效」（§7.4）；删除时若后端返回 code=4006（CARD_IN_USE），
 * 弹二次确认展示「有 N 篇稿件引用此卡」+「仍然删除」按钮（调 force=true 软删）。
 * 沿用纸感样式（paper palette + serif 标题），token 由 userClient 拦截器自动注入。
 */

const LAYER_LABELS: Record<CardLayer, string> = { A: '人物画像', B: '产品知识', C: '金句素材' };
const LAYER_HINTS: Record<CardLayer, string> = {
  A: 'A 层：定位画像，不做语义检索。',
  B: 'B 层：产品/选题知识，保存即重算向量，立即生效。',
  C: 'C 层：金句素材，不做语义检索。',
};

/** 把后端 JSONB content（JSON 文本）显示为可读文本；非字符串 JSON 反序列化后 pretty-print。 */
function displayContent(raw: string): string {
  if (!raw) return '';
  try {
    const parsed = JSON.parse(raw);
    return typeof parsed === 'string' ? parsed : JSON.stringify(parsed, null, 2);
  } catch {
    return raw;
  }
}

/** 把 textarea 文本包装成合法 JSON 字符串存 JSONB（用户输入纯文本也能存）。 */
function wrapContent(text: string): string {
  return JSON.stringify(text);
}

export default function KB() {
  const queryClient = useQueryClient();
  const [layer, setLayer] = useState<CardLayer>('B');
  const [showModal, setShowModal] = useState(false);
  const [editing, setEditing] = useState<CardSummary | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<CardSummary | null>(null);
  const [deleteMsg, setDeleteMsg] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  const { data: cards, isLoading, error } = useQuery<CardSummary[]>({
    queryKey: ['kb-cards', layer],
    queryFn: () => listCards(layer),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['kb-cards'] });

  const createMut = useMutation({
    mutationFn: (vars: { layer: CardLayer; cardType: string; title: string; content: string }) =>
      createCard(vars.layer, vars.cardType, vars.title, vars.content),
    onSuccess: () => {
      setShowModal(false);
      setFormError(null);
      invalidate();
    },
    onError: (e: unknown) => setFormError(getBizMessage(e, '新建失败')),
  });

  const updateMut = useMutation({
    mutationFn: (vars: { id: number; title: string; content: string }) =>
      updateCard(vars.id, vars.title, vars.content),
    onSuccess: () => {
      setShowModal(false);
      setEditing(null);
      setFormError(null);
      invalidate();
    },
    onError: (e: unknown) => setFormError(getBizMessage(e, '保存失败')),
  });

  const deleteMut = useMutation({
    mutationFn: (vars: { id: number; force: boolean }) => deleteCard(vars.id, vars.force),
    onSuccess: () => {
      setConfirmDelete(null);
      setDeleteMsg(null);
      invalidate();
    },
    onError: (e: unknown) => {
      if (e instanceof BizError && e.code === 4006) {
        setDeleteMsg(e.message);
      } else {
        setDeleteMsg(getBizMessage(e, '删除失败'));
      }
    },
  });

  const openCreate = () => {
    setEditing(null);
    setFormError(null);
    setShowModal(true);
  };

  const openEdit = (card: CardSummary) => {
    setEditing(card);
    setFormError(null);
    setShowModal(true);
  };

  const handleDelete = (card: CardSummary) => {
    setConfirmDelete(card);
    setDeleteMsg(null);
    deleteMut.mutate({ id: card.id, force: false });
  };

  const handleForceDelete = () => {
    if (confirmDelete) deleteMut.mutate({ id: confirmDelete.id, force: true });
  };

  return (
    <main className="mx-auto min-h-full max-w-3xl px-5 py-8">
      <header className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-black text-paper-ink">知识库</h1>
          <p className="mt-1 text-sm text-paper-muted">三层卡片 · 供 AI 创作检索引用</p>
        </div>
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={openCreate}
            className="rounded-lg bg-paper-primary px-4 py-2 text-[13px] font-bold text-white transition hover:bg-[#6e4620]"
          >
            + 新建卡片
          </button>
          <Link
            to="/"
            className="rounded-lg border border-[#d8c9b2] bg-paper-card px-3.5 py-2 text-[13px] font-bold text-paper-primary transition hover:bg-[#f7f2e7]"
          >
            返回工作台
          </Link>
        </div>
      </header>

      {/* A/B/C 三层 tab */}
      <nav className="mb-5 flex gap-2">
        {(['A', 'B', 'C'] as CardLayer[]).map((l) => (
          <button
            key={l}
            type="button"
            onClick={() => setLayer(l)}
            className={`rounded-lg border px-4 py-2 text-[13px] font-bold transition ${
              layer === l
                ? 'border-paper-primary bg-paper-primary text-white'
                : 'border-[#d8c9b2] bg-paper-card text-paper-primary hover:bg-[#f7f2e7]'
            }`}
          >
            {l} 层 · {LAYER_LABELS[l]}
          </button>
        ))}
      </nav>

      <p className="mb-4 rounded-lg border border-paper-line bg-paper-card px-3 py-2 text-[12px] text-paper-muted">
        {LAYER_HINTS[layer]}
      </p>

      {isLoading && <p className="py-10 text-center text-sm text-paper-muted">加载中…</p>}
      {error && (
        <p className="py-10 text-center text-sm text-[#b0492f]">加载失败：{getBizMessage(error)}</p>
      )}

      {/* 卡片列表 */}
      {cards && cards.length === 0 && (
        <p className="py-10 text-center text-sm text-paper-muted">
          暂无 {LAYER_LABELS[layer]}卡片，点击「新建卡片」开始。
        </p>
      )}
      {cards && cards.length > 0 && (
        <ul className="flex flex-col gap-3">
          {cards.map((card) => (
            <li
              key={card.id}
              className="rounded-2xl border border-paper-line bg-paper-card p-5 shadow-sm"
            >
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="rounded-full border border-[#ecd4ae] bg-[#fdf3e4] px-2 py-0.5 text-[11px] font-bold text-[#a8712e]">
                      {card.cardType || '未分类'}
                    </span>
                    <h3 className="truncate font-serif text-base font-bold text-paper-ink">
                      {card.title}
                    </h3>
                  </div>
                  <p className="mt-2 whitespace-pre-wrap break-words text-sm text-paper-muted">
                    {displayContent(card.content)}
                  </p>
                  <p className="mt-2 text-[11px] text-paper-muted">
                    更新于 {new Date(card.updatedAt).toLocaleString('zh-CN')}
                  </p>
                </div>
                <div className="flex shrink-0 flex-col gap-2">
                  <button
                    type="button"
                    onClick={() => openEdit(card)}
                    className="rounded-lg border border-[#d8c9b2] bg-paper-card px-3 py-1.5 text-[12px] font-bold text-paper-primary transition hover:bg-[#f7f2e7]"
                  >
                    编辑
                  </button>
                  <button
                    type="button"
                    onClick={() => handleDelete(card)}
                    disabled={deleteMut.isPending}
                    className="rounded-lg border border-[#e4b9ab] bg-[#faf0ec] px-3 py-1.5 text-[12px] font-bold text-[#b0492f] transition hover:bg-[#f5e0d8] disabled:opacity-45"
                  >
                    删除
                  </button>
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}

      {/* 新建/编辑弹窗 */}
      {showModal && (
        <CardModal
          layer={layer}
          editing={editing}
          pending={createMut.isPending || updateMut.isPending}
          error={formError}
          onClose={() => {
            setShowModal(false);
            setEditing(null);
            setFormError(null);
          }}
          onSubmit={(cardType, title, content) => {
            if (editing) {
              updateMut.mutate({ id: editing.id, title, content });
            } else {
              createMut.mutate({ layer, cardType, title, content });
            }
          }}
        />
      )}

      {/* 删除二次确认（有引用时） */}
      {confirmDelete && deleteMsg && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 px-4">
          <div className="w-full max-w-sm rounded-2xl border border-paper-line bg-paper-card p-6 shadow-lg">
            <h3 className="mb-2 font-serif text-lg font-bold text-paper-ink">删除确认</h3>
            <p className="mb-4 text-sm leading-relaxed text-[#b0492f]">{deleteMsg}</p>
            <p className="mb-5 text-[12px] text-paper-muted">
              强制删除后，引用此卡的稿件将无法追溯其来源。是否继续？
            </p>
            <div className="flex justify-end gap-2">
              <button
                type="button"
                onClick={() => {
                  setConfirmDelete(null);
                  setDeleteMsg(null);
                }}
                className="rounded-lg border border-[#d8c9b2] bg-paper-card px-4 py-2 text-[13px] font-bold text-paper-primary transition hover:bg-[#f7f2e7]"
              >
                取消
              </button>
              <button
                type="button"
                onClick={handleForceDelete}
                disabled={deleteMut.isPending}
                className="rounded-lg bg-[#b0492f] px-4 py-2 text-[13px] font-bold text-white transition hover:bg-[#8a3a25] disabled:opacity-45"
              >
                {deleteMut.isPending ? '删除中…' : '仍然删除'}
              </button>
            </div>
          </div>
        </div>
      )}
    </main>
  );
}

/** 新建/编辑弹窗。B 层保存后显示「已重算向量」提示。 */
function CardModal({
  layer,
  editing,
  pending,
  error,
  onClose,
  onSubmit,
}: {
  layer: CardLayer;
  editing: CardSummary | null;
  pending: boolean;
  error: string | null;
  onClose: () => void;
  onSubmit: (cardType: string, title: string, content: string) => void;
}) {
  const [cardType, setCardType] = useState(editing?.cardType ?? '');
  const [title, setTitle] = useState(editing?.title ?? '');
  const [content, setContent] = useState(editing ? displayContent(editing.content) : '');

  const canSubmit = title.trim().length > 0 && content.trim().length > 0;
  const isBLayer = layer === 'B' || editing?.layer === 'B';

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 px-4">
      <div className="w-full max-w-lg rounded-2xl border border-paper-line bg-paper-card p-6 shadow-lg">
        <h3 className="mb-4 font-serif text-lg font-bold text-paper-ink">
          {editing ? '编辑卡片' : `新建 ${LAYER_LABELS[layer]}卡片`}
        </h3>

        {error && (
          <div
            role="alert"
            className="mb-4 rounded-lg border border-[#e4b9ab] bg-[#faf0ec] px-3 py-2 text-[13px] text-[#b0492f]"
          >
            {error}
          </div>
        )}

        <div className="mb-4">
          <label className="mb-1.5 block text-xs font-semibold text-paper-muted">卡片类型</label>
          <input
            type="text"
            placeholder="如：产品 / 人物 / 金句"
            value={cardType}
            onChange={(e) => setCardType(e.target.value)}
            className="w-full rounded-lg border border-[#d8d2c4] bg-[#fdfcf8] px-3.5 py-2.5 text-sm text-paper-ink outline-none focus:border-paper-primary"
          />
        </div>

        <div className="mb-4">
          <label className="mb-1.5 block text-xs font-semibold text-paper-muted">标题</label>
          <input
            type="text"
            placeholder="卡片标题（100 字内）"
            maxLength={100}
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            className="w-full rounded-lg border border-[#d8d2c4] bg-[#fdfcf8] px-3.5 py-2.5 text-sm text-paper-ink outline-none focus:border-paper-primary"
          />
        </div>

        <div className="mb-4">
          <label className="mb-1.5 block text-xs font-semibold text-paper-muted">内容</label>
          <textarea
            rows={5}
            placeholder="卡片内容（纯文本，保存时自动包装为 JSON）"
            value={content}
            onChange={(e) => setContent(e.target.value)}
            className="w-full rounded-lg border border-[#d8d2c4] bg-[#fdfcf8] px-3.5 py-2.5 text-sm text-paper-ink outline-none focus:border-paper-primary"
          />
        </div>

        {isBLayer && (
          <p className="mb-4 rounded-lg border border-[#ecd4ae] bg-[#fdf3e4] px-3 py-2 text-[12px] text-[#a8712e]">
            {LAYER_HINTS.B}
          </p>
        )}

        <div className="flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg border border-[#d8c9b2] bg-paper-card px-4 py-2 text-[13px] font-bold text-paper-primary transition hover:bg-[#f7f2e7]"
          >
            取消
          </button>
          <button
            type="button"
            disabled={!canSubmit || pending}
            onClick={() => onSubmit(cardType.trim() || '未分类', title.trim(), wrapContent(content))}
            className="rounded-lg bg-paper-primary px-4 py-2 text-[13px] font-bold text-white transition hover:bg-[#6e4620] disabled:cursor-not-allowed disabled:opacity-45"
          >
            {pending ? '保存中…' : '保存'}
          </button>
        </div>
      </div>
    </div>
  );
}
