import { useMemo, useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  type AdminOrder,
  type AdminUser,
  compensate,
  fetchOrders,
  fetchUsers,
  openOrder,
} from '../../api/admin';
import { getBizMessage } from '../../api/client';
import { useAuthStore } from '../../store/auth';

type Pkg = 'p50' | 'p150';
const PKG_INFO: Record<Pkg, { name: string; price: number; per: string; n: number }> = {
  p50: { name: '50 条 · ¥49', price: 49, per: '约 ¥0.98/条', n: 50 },
  p150: { name: '150 条 · ¥129', price: 129, per: '约 ¥0.86/条 · 主推', n: 150 },
};

/**
 * 管理端后台 —— 移植自 `随口说后台管理原型-admin.html`。
 * 核心链路：尾号搜索 → 多人逐一确认 → 选套餐开通 / 补偿额度 → 刷新余额 + 订单。
 * 视觉保留原型的纸感暖炭双栏 + 衬线标题 + 状态色标签。
 */
export default function AdminConsole() {
  const qc = useQueryClient();
  const adminName = useAuthStore((s) => s.adminName) ?? '站长';
  const logoutAdmin = useAuthStore((s) => s.logoutAdmin);

  // 尾号搜索
  const [tailInput, setTailInput] = useState('');
  const [searchTail, setSearchTail] = useState<string | null>(null);
  const [selectedUser, setSelectedUser] = useState<AdminUser | null>(null);
  const [selectedPkg, setSelectedPkg] = useState<Pkg | null>(null);
  const [hitError, setHitError] = useState<string | null>(null);

  // 订单过滤
  const [orderFilter, setOrderFilter] = useState<'all' | 'trial' | 'done'>('all');

  // 补偿弹窗
  const [compUser, setCompUser] = useState<AdminUser | null>(null);
  const [compN, setCompN] = useState('');
  const [compMemo, setCompMemo] = useState('');

  // 开通确认弹窗
  const [showOpenModal, setShowOpenModal] = useState(false);

  const [toast, setToast] = useState<string | null>(null);
  const showToast = (m: string) => {
    setToast(m);
    setTimeout(() => setToast(null), 2600);
  };

  // 订单列表（全量，前端按 tab + 用户过滤）
  const ordersQuery = useQuery<AdminOrder[]>({
    queryKey: ['admin', 'orders'],
    queryFn: () => fetchOrders(),
  });

  // 尾号搜用户
  const usersQuery = useQuery<AdminUser[]>({
    queryKey: ['admin', 'users', searchTail],
    queryFn: () => fetchUsers(searchTail!),
    enabled: !!searchTail,
  });

  // 是否首充：该用户名下无 status=done 且 orderType=recharge 的单（与后端 countPriorDoneRecharge 同口径）
  const isFirstCharge = (userId: number): boolean => {
    const orders = ordersQuery.data ?? [];
    return !orders.some((o) => o.userId === userId && o.orderType === 'recharge' && o.status === 'done');
  };

  // 开通
  const openMut = useMutation({
    mutationFn: () => openOrder(selectedUser!.userId, selectedPkg!),
    onSuccess: (balance) => {
      setShowOpenModal(false);
      showToast(`已开通 ${PKG_INFO[selectedPkg!].n} 条${isFirstCharge(selectedUser!.userId) ? '（含首充赠送 10 条）' : ''}，短信通知已发送 ✓`);
      qc.invalidateQueries({ queryKey: ['admin', 'orders'] });
      qc.invalidateQueries({ queryKey: ['admin', 'users', searchTail] });
      setSelectedPkg(null);
      void balance;
    },
    onError: (e: unknown) => showToast(getBizMessage(e, '开通失败')),
  });

  // 补偿
  const compMut = useMutation({
    mutationFn: () => compensate(compUser!.userId, Number(compN), compMemo),
    onSuccess: () => {
      showToast(`已补偿 ${compN} 条`);
      setCompUser(null);
      setCompN('');
      setCompMemo('');
      qc.invalidateQueries({ queryKey: ['admin', 'orders'] });
      qc.invalidateQueries({ queryKey: ['admin', 'users', searchTail] });
    },
    onError: (e: unknown) => showToast(getBizMessage(e, '补偿失败')),
  });

  const handleSearch = () => {
    const t = tailInput.trim();
    if (!t) {
      showToast('先输入转账备注的手机尾号');
      return;
    }
    setSelectedUser(null);
    setSelectedPkg(null);
    setHitError(null);
    setSearchTail(t);
  };

  const handleSelectUser = (u: AdminUser) => {
    setSelectedUser(u);
    setSelectedPkg(null);
  };

  const filteredOrders = useMemo(() => {
    const all = ordersQuery.data ?? [];
    const byFilter = all.filter((o) => orderFilter === 'all' || o.status === orderFilter);
    return byFilter;
  }, [ordersQuery.data, orderFilter]);

  // 统计卡数据（从订单表实时算）
  const stats = useMemo(() => {
    const all = ordersQuery.data ?? [];
    const trial = all.filter((o) => o.status === 'trial').length;
    const todayStr = new Date().toISOString().slice(0, 10);
    const isToday = (s: string | null) => !!s && s.slice(0, 10) === todayStr;
    const todayDone = all.filter(
      (o) => o.status === 'done' && o.orderType === 'recharge' && isToday(o.openedAt),
    );
    const todayAmount = todayDone.reduce((s, o) => s + (o.amount ?? 0), 0);
    const todayFirst = todayDone.filter((o) => o.isFirstCharge).length;
    return { trial, todayDone: todayDone.length, todayAmount, todayFirst };
  }, [ordersQuery.data]);

  const selFirst = selectedUser ? isFirstCharge(selectedUser.userId) : false;
  const previewBalance = selectedUser && selectedPkg
    ? selectedUser.balance + PKG_INFO[selectedPkg].n + (selFirst ? 10 : 0)
    : null;

  return (
    <div className="flex min-h-full">
      {/* 侧边栏（暖炭） */}
      <aside className="sticky top-0 flex h-full min-h-screen w-[208px] shrink-0 flex-col bg-[#23231f] py-[22px] text-[#b5ae9a]">
        <div className="border-b border-[#3a382f] px-[22px] pb-5">
          <div className="font-serif text-[17px] font-black tracking-[0.05em] text-[#f4f1e9]">随口说 · 站长后台</div>
          <div className="mt-[3px] text-[11px] tracking-[0.15em] text-[#8a8578]">ADMIN CONSOLE</div>
        </div>
        <nav className="flex flex-col">
          <NavItem active label="⚡ 开通额度" badge={stats.trial} />
          <NavItem label="📋 订单记录" onClick={() => showToast('原型仅演示「开通额度」页')} />
          <NavItem label="👤 用户查询" onClick={() => showToast('原型仅演示「开通额度」页')} />
          <NavItem label="🎁 补偿额度" onClick={() => showToast('原型仅演示「开通额度」页')} />
          <NavItem label="📊 经营看板" onClick={() => showToast('原型仅演示「开通额度」页')} />
        </nav>
        <div className="mt-auto border-t border-[#3a382f] px-[22px] pt-4 text-[11.5px] leading-relaxed text-[#6b6558]">
          操作人：<span className="text-[#b5ae9a]">{adminName}</span>
          <br />
          承诺 SLA：工作时间 <strong className="text-[#c89b6a]">10 分钟内</strong>开通
          <br />
          <button
            type="button"
            onClick={() => {
              logoutAdmin();
              window.location.href = '/admin/login';
            }}
            className="mt-1 cursor-pointer text-[#c89b6a] hover:underline"
          >
            ↩ 退出登录
          </button>
        </div>
      </aside>

      {/* 主区 */}
      <main className="flex-1 px-[34px] py-7 max-w-[1120px]">
        <h1 className="font-serif text-[21px] font-black text-[#23231f]">人工开通额度</h1>
        <p className="mt-1 mb-5 text-[13px] leading-relaxed text-[#8a8578]">
          用户注册即自动创建订单（状态「免费体验」）。用户微信转账并备注手机尾号后 → 在此核对开通。
          无法核对的转账<strong className="text-[#b0492f]">不默认入账</strong>，按微信聊天记录主动询问（PRD §11.1）。
        </p>

        {/* 统计卡 */}
        <div className="mb-6 grid grid-cols-4 gap-3.5">
          <StatCard label="免费体验中" value={stats.trial} hint="注册未充值，待转化" warn />
          <StatCard label="今日已开通" value={stats.todayDone} hint="最近一笔" ok />
          <StatCard label="今日充值金额" value={`¥${stats.todayAmount}`} hint="套餐累加" />
          <StatCard label="今日首充（送10条）" value={stats.todayFirst} hint="首充 +10 bonus" />
        </div>

        {/* 快速开通 */}
        <section className="mb-5 rounded-xl border border-[#e2dccd] bg-paper-card p-[22px]">
          <div className="flex items-center gap-2">
            <h2 className="text-[15px] font-bold text-[#23231f]">快速开通</h2>
            <span className="rounded bg-[#f3e9dc] px-2 py-0.5 text-[11px] font-bold text-paper-primary">核对 → 选套餐 → 开通</span>
          </div>
          <p className="mt-1 mb-4 text-[12.5px] text-[#8a8578]">
            收到微信转账后：按备注的手机尾号搜索用户 → 确认金额与套餐 → 一键开通（自动写入额度账本，开通成功给用户发短信通知）
          </p>

          {/* flow steps */}
          <div className="mb-4 flex items-center gap-3 text-[12px] text-[#a09a8a]">
            <Step n={1} on={!selectedUser}>核对尾号</Step>
            <Arrow />
            <Step n={2} on={!!selectedUser}>确认用户</Step>
            <Arrow />
            <Step n={3} on={!!selectedUser && !!selectedPkg}>选套餐开通</Step>
          </div>

          {/* 搜索 */}
          <div className="flex gap-2.5">
            <input
              type="text"
              inputMode="numeric"
              maxLength={6}
              placeholder="输入转账备注的手机尾号，如 6688（支持后 4-6 位）"
              value={tailInput}
              onChange={(e) => setTailInput(e.target.value.replace(/\D/g, ''))}
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleSearch();
              }}
              className="flex-1 rounded-lg border border-[#d8d2c4] bg-[#fdfcf8] px-3.5 py-2.5 text-sm text-[#23231f] outline-none focus:border-paper-primary"
            />
            <button
              type="button"
              onClick={handleSearch}
              className="rounded-lg bg-paper-primary px-5 py-2.5 text-[13.5px] font-bold text-white transition hover:bg-[#6e4620]"
            >
              搜索用户
            </button>
          </div>

          {/* 搜索结果 */}
          <div className="mt-3">
            {usersQuery.isLoading && <p className="py-4 text-center text-[13px] text-[#a09a8a]">搜索中…</p>}
            {usersQuery.error && (
              <div className="rounded-lg border border-[#e4b9ab] bg-[#faf0ec] px-3.5 py-2.5 text-[12.5px] text-[#b0492f]">
                搜索失败：{getBizMessage(usersQuery.error)}
              </div>
            )}
            {usersQuery.data && usersQuery.data.length === 0 && (
              <div className="rounded-lg border border-[#e4b9ab] bg-[#faf0ec] px-3.5 py-2.5 text-[12.5px] leading-relaxed text-[#b0492f]">
                尾号「{searchTail}」未匹配到任何用户。<strong>不要默认入账</strong>——请按微信聊天记录人工核对，主动询问用户后再操作。
              </div>
            )}
            {usersQuery.data && usersQuery.data.length > 0 && (
              <>
                {usersQuery.data.length > 1 && (
                  <div className="mb-2 rounded-lg border border-[#e4b9ab] bg-[#faf0ec] px-3.5 py-2.5 text-[12.5px] leading-relaxed text-[#b0492f]">
                    尾号「{searchTail}」匹配到 <strong>{usersQuery.data.length} 个用户</strong>，请结合转账时间与微信昵称人工确认，选错即入错账。
                  </div>
                )}
                {usersQuery.data.map((u) => {
                  const fc = isFirstCharge(u.userId);
                  return (
                    <UserHit
                      key={u.userId}
                      user={u}
                      firstCharge={fc}
                      selected={selectedUser?.userId === u.userId}
                      onSelect={() => handleSelectUser(u)}
                    />
                  );
                })}
              </>
            )}
            {hitError && <div className="mt-3 rounded-lg border border-[#e4b9ab] bg-[#faf0ec] px-3.5 py-2.5 text-[12.5px] text-[#b0492f]">{hitError}</div>}
          </div>

          {/* 选套餐 + 开通 / 补偿 */}
          {selectedUser && (
            <div className="mt-4">
              <div className="grid grid-cols-2 gap-3">
                {(Object.keys(PKG_INFO) as Pkg[]).map((p) => {
                  const info = PKG_INFO[p];
                  return (
                    <button
                      key={p}
                      type="button"
                      onClick={() => setSelectedPkg(p)}
                      className={`rounded-[10px] border bg-[#fdfcf8] px-4 py-3.5 text-left transition hover:border-[#c89b6a] ${
                        selectedPkg === p ? 'border-paper-primary bg-[#f7f0e4] shadow-[0_0_0_1px_#8a5a2b_inset]' : 'border-[#e2dccd]'
                      }`}
                    >
                      <div className="font-serif text-base font-black text-[#23231f]">{info.name}</div>
                      <div className="mt-0.5 text-sm font-bold text-paper-primary">微信到账应为 ¥{info.price}</div>
                      <div className="mt-1 text-[11.5px] text-[#a09a8a]">{info.per}</div>
                    </button>
                  );
                })}
              </div>

              {/* 确认条 */}
              {selectedPkg && previewBalance != null && (
                <div className="mt-4 flex items-center gap-3.5 rounded-[10px] bg-[#23231f] px-[18px] py-3.5 text-[13px] text-[#e9e3d3]">
                  <div className="leading-relaxed">
                    开通对象：<strong className="text-white">{selectedUser.phoneMasked}</strong>
                    <br />
                    套餐：<strong className="text-white">{PKG_INFO[selectedPkg!].name}</strong>
                    {selFirst && <span className="font-bold text-[#7fc492]"> + 首充赠送 10 条</span>}
                    {' → '}开通后余额 <strong className="text-white">{previewBalance} 条</strong>
                  </div>
                  <button
                    type="button"
                    onClick={() => setShowOpenModal(true)}
                    className="ml-auto shrink-0 rounded-lg bg-[#4a8c5c] px-5 py-2.5 text-[13.5px] font-bold text-white transition hover:bg-[#3c744c]"
                  >
                    确认开通
                  </button>
                </div>
              )}

              <div className="mt-2.5 flex items-center justify-between">
                <p className="text-[11.5px] leading-relaxed text-[#a09a8a]">
                  开通后自动：① 写入额度流水（充值 +{selectedPkg ? PKG_INFO[selectedPkg].n : 0}
                  {selFirst ? '，首充另 +10 赠送' : ''}）② 更新用户余额 ③ 发送短信「额度已开通」。全部操作留痕于订单记录，可追溯。
                </p>
                <button
                  type="button"
                  onClick={() => setCompUser(selectedUser)}
                  className="shrink-0 rounded-lg border border-[#d8c9b2] bg-paper-card px-3.5 py-2 text-[12.5px] font-bold text-paper-primary transition hover:bg-[#f7f2e7]"
                >
                  补偿额度
                </button>
              </div>
            </div>
          )}
        </section>

        {/* 订单记录 */}
        <section className="rounded-xl border border-[#e2dccd] bg-paper-card p-[22px]">
          <h2 className="text-[15px] font-bold text-[#23231f]">开通订单记录</h2>
          <p className="mt-1 mb-3.5 text-[12.5px] text-[#8a8578]">
            对账、审计、纠纷追溯全靠这张表；「免费体验」为用户注册时自动创建的订单，转账核对后开通转为「已开通」
          </p>

          <div className="mb-3.5 flex gap-2">
            {(['all', 'trial', 'done'] as const).map((f) => (
              <button
                key={f}
                type="button"
                onClick={() => setOrderFilter(f)}
                className={`rounded-full border px-4 py-1.5 text-[12.5px] font-semibold transition ${
                  orderFilter === f
                    ? 'border-[#23231f] bg-[#23231f] text-[#f4f1e9]'
                    : 'border-[#e2dccd] bg-paper-card text-[#6b6558]'
                }`}
              >
                {f === 'all' ? '全部' : f === 'trial' ? '免费体验' : '已开通'}
              </button>
            ))}
            <button
              type="button"
              onClick={() => qc.invalidateQueries({ queryKey: ['admin', 'orders'] })}
              className="ml-auto rounded-full border border-[#e2dccd] bg-paper-card px-3 py-1.5 text-[12px] text-[#6b6558] hover:bg-[#f7f2e7]"
            >
              刷新
            </button>
          </div>

          <table className="w-full border-collapse text-[13px]">
            <thead>
              <tr>
                {['时间', '用户', '备注尾号', '套餐 / 金额', '首充', '状态', '操作人 / 备注', '操作'].map((h) => (
                  <th key={h} className="border-b border-[#e9e3d3] px-2.5 py-2 text-left text-[11.5px] font-semibold tracking-wide text-[#a09a8a]">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {filteredOrders.length === 0 && (
                <tr>
                  <td colSpan={8} className="py-5 text-center text-[13px] text-[#a09a8a]">
                    {ordersQuery.isLoading ? '加载中…' : '该状态下暂无订单'}
                  </td>
                </tr>
              )}
              {filteredOrders.map((o) => {
                const pkgLabel = o.orderType === 'compensate'
                  ? `补偿 +${o.memo ?? ''}`
                  : o.pkg ? `${o.pkg === 'p50' ? '50 条' : '150 条'}` : '—';
                const amt = o.amount ?? 0;
                return (
                  <tr key={o.orderId}>
                    <td className="border-b border-[#f0ece0] px-2.5 py-3 font-mono text-[#6b6558] tabular-nums">
                      {formatTime(o.openedAt ?? o.createdAt)}
                    </td>
                    <td className="border-b border-[#f0ece0] px-2.5 py-3 text-[#23231f]">
                      {o.phoneTail ?? '—'}
                    </td>
                    <td className="border-b border-[#f0ece0] px-2.5 py-3 font-mono text-[#6b6558] tabular-nums">
                      {o.phoneTail ?? '—'}
                    </td>
                    <td className="border-b border-[#f0ece0] px-2.5 py-3">
                      {pkgLabel} / ¥{amt}
                    </td>
                    <td className="border-b border-[#f0ece0] px-2.5 py-3">
                      {o.orderType === 'recharge' ? (o.isFirstCharge ? '是' : '否') : '—'}
                    </td>
                    <td className="border-b border-[#f0ece0] px-2.5 py-3">
                      <StatusTag status={o.status} />
                    </td>
                    <td className="border-b border-[#f0ece0] px-2.5 py-3 text-[12px] text-[#8a8578]">
                      {o.memo ?? (o.adminUserId != null ? '站长' : '注册自动创建')}
                    </td>
                    <td className="border-b border-[#f0ece0] px-2.5 py-3">
                      {o.status === 'trial' ? (
                        <button
                          type="button"
                          onClick={() => {
                            setTailInput(o.phoneTail ?? '');
                            setSearchTail(null);
                            setSelectedUser(null);
                            setSelectedPkg(null);
                            window.scrollTo({ top: 0, behavior: 'smooth' });
                            setTimeout(() => setSearchTail(o.phoneTail ?? ''), 50);
                            showToast(`已按订单尾号 ${o.phoneTail} 搜索，请确认用户后开通`);
                          }}
                          className="rounded bg-paper-primary px-3 py-1 text-[11.5px] font-bold text-white hover:bg-[#6e4620]"
                        >
                          去开通
                        </button>
                      ) : (
                        <span className="text-[#c9c3b2]">—</span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </section>
      </main>

      {/* 确认开通弹窗 */}
      {showOpenModal && selectedUser && selectedPkg && (
        <Overlay onClose={() => setShowOpenModal(false)}>
          <div className="w-[420px] rounded-[14px] bg-[#fdfcf8] p-7 shadow-[0_20px_60px_rgba(0,0,0,0.25)]">
            <h3 className="font-serif text-base font-black text-[#23231f]">确认开通</h3>
            <p className="mt-2 mb-4 text-[13px] leading-relaxed text-[#6b6558]">
              请确认微信<strong className="text-[#23231f]">实际到账 ¥{PKG_INFO[selectedPkg].price}</strong>，且转账人与「{selectedUser.phoneMasked}」对应。
              <br />
              <br />
              将执行：额度流水 <strong className="text-[#23231f]">+{PKG_INFO[selectedPkg].n}（充值）</strong>
              {selFirst && <strong className="text-[#4a8c5c]"> + +10（首充赠送）</strong>}
              ，并向用户发送开通成功短信。此操作写入订单记录，可追溯、不可删除。
            </p>
            <div className="flex justify-end gap-2.5">
              <button
                type="button"
                onClick={() => setShowOpenModal(false)}
                className="rounded-lg border border-[#d8c9b2] bg-paper-card px-5 py-2.5 text-[13.5px] font-bold text-paper-primary hover:bg-[#f7f2e7]"
              >
                再核对一下
              </button>
              <button
                type="button"
                disabled={openMut.isPending}
                onClick={() => openMut.mutate()}
                className="rounded-lg bg-[#4a8c5c] px-5 py-2.5 text-[13.5px] font-bold text-white hover:bg-[#3c744c] disabled:opacity-45"
              >
                {openMut.isPending ? '开通中…' : '确认开通并发短信'}
              </button>
            </div>
          </div>
        </Overlay>
      )}

      {/* 补偿弹窗 */}
      {compUser && (
        <Overlay onClose={() => { setCompUser(null); setCompN(''); setCompMemo(''); }}>
          <div className="w-[420px] rounded-[14px] bg-[#fdfcf8] p-7 shadow-[0_20px_60px_rgba(0,0,0,0.25)]">
            <h3 className="font-serif text-base font-black text-[#23231f]">补偿额度</h3>
            <p className="mt-2 mb-4 text-[13px] leading-relaxed text-[#6b6558]">
              向 <strong className="text-[#23231f]">{compUser.phoneMasked}</strong>（当前余额 {compUser.balance} 条）补偿额度，留痕为 compensate 订单。
            </p>
            <div className="mb-3">
              <label className="mb-1.5 block text-[12px] font-semibold text-[#8a8578]">补偿条数</label>
              <input
                type="number"
                min={1}
                placeholder="如 5"
                value={compN}
                onChange={(e) => setCompN(e.target.value.replace(/\D/g, ''))}
                className="w-full rounded-lg border border-[#d8d2c4] bg-[#fdfcf8] px-3.5 py-2.5 text-sm text-[#23231f] outline-none focus:border-paper-primary"
              />
            </div>
            <div className="mb-4">
              <label className="mb-1.5 block text-[12px] font-semibold text-[#8a8578]">备注（原因 / 场景）</label>
              <textarea
                rows={3}
                placeholder="如 7/18 服务不可用补偿"
                value={compMemo}
                onChange={(e) => setCompMemo(e.target.value)}
                className="w-full rounded-lg border border-[#d8d2c4] bg-[#fdfcf8] px-3.5 py-2.5 text-sm text-[#23231f] outline-none focus:border-paper-primary"
              />
            </div>
            <div className="flex justify-end gap-2.5">
              <button
                type="button"
                onClick={() => { setCompUser(null); setCompN(''); setCompMemo(''); }}
                className="rounded-lg border border-[#d8c9b2] bg-paper-card px-5 py-2.5 text-[13.5px] font-bold text-paper-primary hover:bg-[#f7f2e7]"
              >
                取消
              </button>
              <button
                type="button"
                disabled={!compN || Number(compN) < 1 || compMut.isPending}
                onClick={() => compMut.mutate()}
                className="rounded-lg bg-paper-primary px-5 py-2.5 text-[13.5px] font-bold text-white hover:bg-[#6e4620] disabled:opacity-45"
              >
                {compMut.isPending ? '补偿中…' : '确认补偿'}
              </button>
            </div>
          </div>
        </Overlay>
      )}

      {/* toast */}
      {toast && (
        <div className="fixed bottom-8 left-1/2 z-[99] -translate-x-1/2 rounded-lg bg-[#23231f] px-5 py-2.5 text-[13px] text-[#f4f1e9]">
          {toast}
        </div>
      )}
    </div>
  );
}

/* ── 子组件 ───────────────────────── */

function NavItem({ label, active, badge, onClick }: { label: string; active?: boolean; badge?: number; onClick?: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`flex items-center gap-2.5 border-l-[3px] px-[22px] py-2.5 text-left text-[13.5px] transition ${
        active
          ? 'border-l-[#c89b6a] bg-[#2c2a24] text-[#f4f1e9]'
          : 'border-l-transparent text-[#b5ae9a] hover:bg-[#2c2a24] hover:text-[#f4f1e9]'
      }`}
    >
      {label}
      {badge != null && badge > 0 && (
        <span className="ml-auto rounded-[9px] bg-[#b0492f] px-1.5 py-px text-[11px] font-bold text-white">{badge}</span>
      )}
    </button>
  );
}

function StatCard({ label, value, hint, warn, ok }: { label: string; value: string | number; hint: string; warn?: boolean; ok?: boolean }) {
  return (
    <div className="rounded-xl border border-[#e2dccd] bg-paper-card px-4 py-3">
      <div className="mb-1.5 text-[12px] text-[#8a8578]">{label}</div>
      <div
        className={`font-serif text-2xl font-black ${
          warn ? 'text-[#b0492f]' : ok ? 'text-[#4a8c5c]' : 'text-[#23231f]'
        }`}
      >
        {value}
      </div>
      <div className="mt-1 text-[11.5px] text-[#a09a8a]">{hint}</div>
    </div>
  );
}

function Step({ n, on, children }: { n: number; on: boolean; children: ReactNode }) {
  return (
    <div className={`flex items-center gap-1.5 ${on ? 'font-bold text-paper-primary' : ''}`}>
      <span
        className={`flex h-[18px] w-[18px] items-center justify-center rounded-full text-[11px] font-bold ${
          on ? 'bg-paper-primary text-white' : 'bg-[#e9e3d3] text-[#8a8578]'
        }`}
      >
        {n}
      </span>
      {children}
    </div>
  );
}

function Arrow() {
  return <span className="mx-3 text-[#d8d2c4]">→</span>;
}

function UserHit({ user, firstCharge, selected, onSelect }: { user: AdminUser; firstCharge: boolean; selected: boolean; onSelect: () => void }) {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={`mt-3 flex w-full items-center gap-4 rounded-[10px] border bg-[#fdfcf8] px-4 py-3.5 text-left transition hover:border-[#c89b6a] ${
        selected ? 'border-paper-primary bg-[#f7f0e4] shadow-[0_0_0_1px_#8a5a2b_inset]' : 'border-[#e2dccd]'
      }`}
    >
      <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-[#e9e3d3] font-serif text-[15px] font-black text-paper-primary">
        {user.phoneMasked.replace(/\D/g, '').slice(-1) || '?'}
      </span>
      <div>
        <div className="font-bold text-[#23231f]">{user.phoneMasked}</div>
        <div className="mt-0.5 text-[12px] text-[#8a8578]">当前余额 {user.balance} 条 · 最近订单 {user.latestOrderStatus ?? '无'}</div>
      </div>
      {firstCharge && (
        <span className="ml-auto shrink-0 rounded border border-[#c4dcc9] bg-[#edf5ef] px-2 py-0.5 text-[11px] font-bold text-[#4a8c5c]">
          首充 · 送 10 条
        </span>
      )}
    </button>
  );
}

function StatusTag({ status }: { status: string }) {
  const map: Record<string, { label: string; cls: string }> = {
    trial: { label: '免费体验', cls: 'bg-[#fdf3e4] text-[#a8712e] border-[#ecd4ae]' },
    done: { label: '已开通', cls: 'bg-[#edf5ef] text-[#4a8c5c] border-[#c4dcc9]' },
  };
  const info = map[status] ?? { label: status, cls: 'bg-gray-100 text-gray-600 border-gray-300' };
  return (
    <span className={`inline-block rounded border px-2 py-0.5 text-[11px] font-bold ${info.cls}`}>
      {info.label}
    </span>
  );
}

function Overlay({ children, onClose }: { children: ReactNode; onClose: () => void }) {
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-[rgba(35,35,31,0.45)]"
      onClick={onClose}
    >
      <div onClick={(e) => e.stopPropagation()}>{children}</div>
    </div>
  );
}

function formatTime(s: string | null): string {
  if (!s) return '—';
  const d = new Date(s);
  if (Number.isNaN(d.getTime())) return s;
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${d.getMonth() + 1}/${d.getDate()} ${hh}:${mm}`;
}
