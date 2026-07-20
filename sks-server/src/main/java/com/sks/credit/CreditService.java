package com.sks.credit;

import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 额度账本核心服务——产品的「钱核心」，被后续 script/analyze/recharge 全部依赖。
 *
 * <p>三条不变量（tech-design §4.1，项目 #1 资金不变量）：
 *
 * <ol>
 *   <li><b>原子扣减，绝不 read-then-write。</b> {@link #deduct} 用单条 {@code UPDATE ... WHERE
 *       balance >= n} 原子完成「够不够 + 扣」，影响行数 0 即余额不足，抛 {@link
 *       ErrorCode#INSUFFICIENT_BALANCE}。并发下绝不超扣为负。
 *   <li><b>退款 / 充值幂等，绝不重复加额度。</b> 幂等键 = 唯一约束 {@code UNIQUE(biz_id,
 *       biz_type, type)}。采用「先查 {@code (biz_id, biz_type, type)} 是否已入账 → 存在直接
 *       return」的快速守卫；<b>再以「插流水在前、改余额在后」</b>把唯一约束冲突挡在余额变动之前——
 *       并发重复时，输家在插流水处即抛 {@link DuplicateKeyException}（此时尚未触碰余额），被 catch
 *       后静默 return，事务可正常提交（无余额变动，无需 setRollbackOnly，也不会触发
 *       UnexpectedRollbackException）。唯一约束是并发重复的最终兜底。
 *   <li><b>所有余额变动 + 流水写入在同一 {@code @Transactional}。</b> 余额与流水要么一起提交、
 *       要么一起回滚，永不出现「流水写了、余额没动」或反之。
 * </ol>
 *
 * <p><b>幂等策略为何是「插流水在前」而非「改余额在前」：</b>若先改余额再插流水，并发重复时输家已改余额、
 * 插流水抛 {@link DuplicateKeyException}——此时要么让异常逃逸（非静默）、要么 {@code setRollbackOnly}
 * （会在方法正常返回时被 Spring 抛 {@code UnexpectedRollbackException}，同样非静默）。把顺序倒过来，
 * 让唯一约束冲突发生在余额变动<b>之前</b>，catch 即可静默 no-op，且余额从未被触碰，无须任何回滚魔法。
 */
@Service
public class CreditService {

    private final CreditAccountMapper accountMapper;
    private final CreditLedgerMapper ledgerMapper;

    public CreditService(CreditAccountMapper accountMapper, CreditLedgerMapper ledgerMapper) {
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
    }

    /**
     * 原子扣减 + 写借记流水。余额不足抛 {@link BizException}（不写流水）。成功返回 {@code true}。
     *
     * <p>单条原子 {@code UPDATE} 判定 + 扣减；影响行数 0 即不足。同一事务内扣 account 成功、再插
     * {@code ledger(delta=-n, type='debit')}。扣减<b>不做幂等</b>——每个 {@code biz_id} 对应一次扣费，
     * 由调用方（script/analyze 编排）保证不重复扣同一笔。
     */
    @Transactional
    public boolean deduct(long userId, int n, String bizType, String bizId) {
        int affected = accountMapper.deduct(userId, n);
        if (affected == 0) {
            throw new BizException(ErrorCode.INSUFFICIENT_BALANCE);
        }
        CreditLedger row = new CreditLedger();
        row.setUserId(userId);
        row.setDelta(-n);
        row.setBizType(bizType);
        row.setBizId(bizId);
        row.setType("debit");
        ledgerMapper.insert(row);
        return true;
    }

    /**
     * 幂等退款：把 {@code n} 加回余额 + 写 {@code ledger(delta=+n, type='refund')}。
     *
     * <p>幂等键 = {@code (biz_id, biz_type, 'refund')}。先查已存在则 return；否则<b>先插流水</b>，
     * 唯一约束冲突即并发重复——输家尚未改余额，catch 静默 return；赢家再 addBalance。重复调用静默 no-op。
     */
    @Transactional
    public void refund(long userId, int n, String bizType, String bizId) {
        if (ledgerMapper.countByBizKey(bizId, bizType, "refund") > 0) {
            return;
        }
        CreditLedger row = new CreditLedger();
        row.setUserId(userId);
        row.setDelta(n);
        row.setBizType(bizType);
        row.setBizId(bizId);
        row.setType("refund");
        try {
            ledgerMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发重复：另一事务已退过，输家尚未触碰余额，静默 no-op。
            return;
        }
        accountMapper.addBalance(userId, n);
    }

    /**
     * 幂等充值 / 赠送加额度（注册体验、开通充值、补偿用）。
     *
     * <p>幂等键 = {@code (biz_id, biz_type, 'credit')}——管理员双击 / 请求重试重复 open 同一订单，
     * 只入账一次。先查已存在则 return；否则 {@link #ensureAccount}（首充前建账，可重复）→ <b>先插流水</b>
     * （唯一约束冲突即并发重复，catch 静默 return）→ 赢家再 addBalance。
     */
    @Transactional
    public void credit(long userId, int n, String bizType, String bizId, String memo) {
        if (ledgerMapper.countByBizKey(bizId, bizType, "credit") > 0) {
            return;
        }
        ensureAccount(userId);
        CreditLedger row = new CreditLedger();
        row.setUserId(userId);
        row.setDelta(n);
        row.setBizType(bizType);
        row.setBizId(bizId);
        row.setType("credit");
        row.setMemo(memo);
        try {
            ledgerMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发重复：另一事务已入账，输家尚未触碰余额，静默 no-op。
            return;
        }
        accountMapper.addBalance(userId, n);
    }

    /**
     * 幂等建账：{@code credit_account} 不存在则插入 {@code balance=0} 一行（{@code ON CONFLICT DO
     * NOTHING}）。注册钩子（Task 0.7）与 {@link #credit} 首充前调用，可重复调用。
     */
    public void ensureAccount(long userId) {
        accountMapper.ensureAccount(userId);
    }

    /**
     * 读取余额；无账户行返回 0（与 {@link #ensureAccount} 语义一致：账户不存在 ≡ 余额 0）。
     */
    public int balance(long userId) {
        Integer b = accountMapper.selectBalance(userId);
        return b == null ? 0 : b;
    }
}
