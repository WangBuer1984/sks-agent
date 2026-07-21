import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { getBizMessage } from '../api/client';
import { asrVoice, confirmProfile, interviewStep, type InterviewStepView } from '../api/profile';

/**
 * C 端定位校准页 {@code /calibrate}（§5 / §4.2 校准免费）。
 *
 * <p>多轮对话式问答（文字输入 + 按住录音的语音输入 → ASR 转文字先回显可改后提交），走完 5 轮 →
 * summarize 出最终档案草稿 → 用户确认生效（写 active 档案 + A 层卡）。一次 /interview 一次 JSON
 * 返回（无流式——硬不变量），等待 30-60s 时显示「思考中…」进度动画。
 *
 * <p>流程：
 * <ol>
 *   <li>贴素材 → 首轮 /interview（materials 非空、reply 空）→ AI 猜人设 + 反馈问题。
 *   <li>确认 / 调整人设 → 进入多轮提问（5 轮），每轮可文字或语音回答。
 *   <li>语音回答：按住录音 → 松开调 /voice → 文本回显可编辑 → 提交走 /interview（reply=文本）。
 *   <li>done=true → 展示档案草稿 → 确认生效（/confirm）→ 跳回工作台。
 * </ol>
 *
 * <p>沿用纸感样式（paper palette + serif 标题），token 由 userClient 拦截器自动注入。
 */
type Phase = 'materials' | 'await_feedback' | 'ask' | 'summarize' | 'done';

interface Turn {
  role: 'ai' | 'user';
  text: string;
}

export default function Calibrate() {
  const navigate = useNavigate();
  const [sessionId] = useState(() => crypto.randomUUID());
  const [phase, setPhase] = useState<Phase>('materials');
  const [materials, setMaterials] = useState('');
  const [turns, setTurns] = useState<Turn[]>([]);
  const [draft, setDraft] = useState<Record<string, unknown> | null>(null);
  const [input, setInput] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [banner, setBanner] = useState<string>('');
  const [asrPending, setAsrPending] = useState(false);
  const [recording, setRecording] = useState(false);
  const mediaRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);

  // 首轮 / 后续轮统一走 interviewStep mutation
  const stepMut = useMutation({
    mutationFn: (vars: { reply?: string; materials?: string }) =>
      interviewStep(sessionId, vars.reply, vars.materials),
    onSuccess: (resp: InterviewStepView) => {
      setError(null);
      setBanner(resp.banner ?? '');
      if (resp.blocked) {
        setError('内容被安全拦截，请调整后重试');
        return;
      }
      if (resp.question) {
        setTurns((t) => [...t, { role: 'ai', text: resp.question as string }]);
      }
      if (resp.done) {
        setDraft(resp.profileDraft);
        setPhase('done');
      } else if (resp.stage === 'await_feedback') {
        setPhase('await_feedback');
      } else if (resp.stage === 'ask') {
        setPhase('ask');
      } else if (resp.stage === 'summarize') {
        setPhase('summarize');
      }
    },
    onError: (e: unknown) => setError(getBizMessage(e, '访谈推进失败')),
  });

  const confirmMut = useMutation({
    mutationFn: () => confirmProfile(sessionId),
    onSuccess: () => {
      setError(null);
      navigate('/');
    },
    onError: (e: unknown) => setError(getBizMessage(e, '生效失败')),
  });

  // --- 首轮：贴素材 ---
  const submitMaterials = () => {
    if (!materials.trim()) {
      setError('请先粘贴素材（主页说明 / 过往文案 / 朋友圈长文）');
      return;
    }
    setTurns([]);
    setPhase('ask');
    stepMut.mutate({ materials: materials.trim() });
  };

  // --- 文字回答提交 ---
  const submitReply = () => {
    if (!input.trim()) {
      setError('请输入回答');
      return;
    }
    setTurns((t) => [...t, { role: 'user', text: input.trim() }]);
    const reply = input.trim();
    setInput('');
    setPhase('ask');
    stepMut.mutate({ reply });
  };

  // --- 语音：按住录音 → 松开 ASR → 回显可改 ---
  const startRecording = async () => {
    setError(null);
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const mr = new MediaRecorder(stream);
      chunksRef.current = [];
      mr.ondataavailable = (e) => {
        if (e.data.size > 0) chunksRef.current.push(e.data);
      };
      mr.onstop = async () => {
        stream.getTracks().forEach((t) => t.stop());
        const blob = new Blob(chunksRef.current, { type: 'audio/webm' });
        if (blob.size === 0) {
          setRecording(false);
          return;
        }
        setAsrPending(true);
        try {
          const text = await asrVoice(blob);
          setInput(text); // 转出文字先回显给用户确认 / 编辑再提交（brief）
        } catch (e) {
          setError('语音识别失败，请改用文字输入（不阻断访谈）');
        } finally {
          setAsrPending(false);
          setRecording(false);
        }
      };
      mr.start();
      mediaRef.current = mr;
      setRecording(true);
    } catch {
      setError('无法访问麦克风，请改用文字输入');
      setRecording(false);
    }
  };

  const stopRecording = () => {
    if (mediaRef.current && mediaRef.current.state === 'recording') {
      mediaRef.current.stop();
    }
  };

  // 组件卸载时停录音
  useEffect(() => {
    return () => stopRecording();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const pending = stepMut.isPending || confirmMut.isPending;
  const showQA = phase === 'await_feedback' || phase === 'ask' || phase === 'summarize';

  return (
    <main className="mx-auto min-h-full max-w-3xl px-5 py-8">
      <header className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-black text-paper-ink">定位校准</h1>
          <p className="mt-1 text-sm text-paper-muted">
            贴素材 → AI 猜人设 → 5 轮问答 → 归纳档案 · 校准免费
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

      {/* 第一步：贴素材 */}
      {phase === 'materials' && (
        <section className="rounded-2xl border border-paper-line bg-paper-card p-6 shadow-sm">
          <h2 className="mb-3 font-serif text-lg font-bold text-paper-ink">第一步 · 贴素材</h2>
          <p className="mb-3 text-[13px] text-paper-muted">
            粘贴你的主页说明 / 过往文案 / 朋友圈长文（纯文本拼接），AI 据此先猜一版人设。
          </p>
          <textarea
            rows={8}
            placeholder="把素材文本贴在这里…"
            value={materials}
            onChange={(e) => setMaterials(e.target.value)}
            className="mb-4 w-full rounded-lg border border-[#d8d2c4] bg-[#fdfcf8] px-3.5 py-2.5 text-sm text-paper-ink outline-none focus:border-paper-primary"
          />
          <button
            type="button"
            disabled={pending || !materials.trim()}
            onClick={submitMaterials}
            className="rounded-lg bg-paper-primary px-4 py-2 text-[13px] font-bold text-white transition hover:bg-[#6e4620] disabled:cursor-not-allowed disabled:opacity-45"
          >
            {stepMut.isPending ? 'AI 思考中…' : '开始校准'}
          </button>
        </section>
      )}

      {/* 多轮问答 */}
      {showQA && (
        <section className="rounded-2xl border border-paper-line bg-paper-card p-6 shadow-sm">
          <h2 className="mb-4 font-serif text-lg font-bold text-paper-ink">
            {phase === 'await_feedback' ? '确认人设' : '访谈问答'}
          </h2>

          {turns.length > 0 && (
            <ul className="mb-4 flex flex-col gap-3">
              {turns.map((t, i) => (
                <li
                  key={i}
                  className={`rounded-lg px-3.5 py-2.5 text-sm ${
                    t.role === 'ai'
                      ? 'border border-paper-line bg-[#f7f2e7] text-paper-ink'
                      : 'border border-[#ecd4ae] bg-[#fdf3e4] text-paper-ink'
                  }`}
                >
                  <span className="mr-2 text-[11px] font-bold text-paper-muted">
                    {t.role === 'ai' ? 'AI' : '我'}
                  </span>
                  <span className="whitespace-pre-wrap break-words">{t.text}</span>
                </li>
              ))}
            </ul>
          )}

          {stepMut.isPending && (
            <p className="mb-3 text-[13px] text-paper-muted">AI 思考中…（约 30-60s）</p>
          )}

          {/* 回答输入区：文字 + 按住录音 */}
          <textarea
            rows={3}
            placeholder={asrPending ? '识别中…' : '输入回答（可手打，或按住下方按钮录音）'}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            disabled={asrPending}
            className="mb-3 w-full rounded-lg border border-[#d8d2c4] bg-[#fdfcf8] px-3.5 py-2.5 text-sm text-paper-ink outline-none focus:border-paper-primary disabled:bg-[#f4f1e9]"
          />
          <div className="flex items-center gap-2">
            <button
              type="button"
              disabled={pending || asrPending || !input.trim()}
              onClick={submitReply}
              className="rounded-lg bg-paper-primary px-4 py-2 text-[13px] font-bold text-white transition hover:bg-[#6e4620] disabled:cursor-not-allowed disabled:opacity-45"
            >
              提交回答
            </button>
            <button
              type="button"
              disabled={pending || asrPending}
              onMouseDown={startRecording}
              onMouseUp={stopRecording}
              onTouchStart={startRecording}
              onTouchEnd={stopRecording}
              className={`rounded-lg border px-4 py-2 text-[13px] font-bold transition disabled:cursor-not-allowed disabled:opacity-45 ${
                recording
                  ? 'border-[#b0492f] bg-[#b0492f] text-white'
                  : 'border-[#d8c9b2] bg-paper-card text-paper-primary hover:bg-[#f7f2e7]'
              }`}
            >
              {recording ? '录音中…松开识别' : asrPending ? '识别中…' : '按住录音'}
            </button>
          </div>
          <p className="mt-2 text-[11.5px] text-paper-muted">
            语音回答先转出文字回显，可编辑后再提交；识别失败可改用文字输入，不阻断访谈。
          </p>
        </section>
      )}

      {/* 完成：档案草稿 + 确认生效 */}
      {phase === 'done' && draft && (
        <section className="rounded-2xl border border-paper-line bg-paper-card p-6 shadow-sm">
          <h2 className="mb-3 font-serif text-lg font-bold text-paper-ink">定位档案草稿</h2>
          <p className="mb-4 text-[13px] text-paper-muted">
            归纳完成，确认生效后写入你的定位档案 + A 层卡，后续创作将注入此档案。
          </p>
          <pre className="mb-5 overflow-x-auto rounded-lg border border-paper-line bg-[#fdfcf8] p-4 text-[12.5px] leading-relaxed text-paper-ink">
            {JSON.stringify(draft, null, 2)}
          </pre>
          <div className="flex items-center gap-2">
            <button
              type="button"
              disabled={pending}
              onClick={() => confirmMut.mutate()}
              className="rounded-lg bg-paper-primary px-4 py-2 text-[13px] font-bold text-white transition hover:bg-[#6e4620] disabled:cursor-not-allowed disabled:opacity-45"
            >
              {confirmMut.isPending ? '生效中…' : '确认生效'}
            </button>
            <button
              type="button"
              onClick={() => setPhase('ask')}
              className="rounded-lg border border-[#d8c9b2] bg-paper-card px-4 py-2 text-[13px] font-bold text-paper-primary transition hover:bg-[#f7f2e7]"
            >
              再改改（继续访谈）
            </button>
          </div>
        </section>
      )}
    </main>
  );
}
