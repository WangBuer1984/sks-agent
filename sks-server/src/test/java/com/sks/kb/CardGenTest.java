package com.sks.kb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sks.AbstractDbTest;
import com.sks.aiclient.AiClient;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import com.sks.user.AppUser;
import com.sks.user.AppUserMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * 补卡（card_gen supplement / confirm）服务级测试。
 *
 * <p>真实 Testcontainers {@code pgvector/pgvector:pg16}，Flyway 跑 V1。{@link AiClient} 用
 * {@code @MockBean} mock——不真正调 Python，{@code verify(aiClient, ...)} 断言调用。基类
 * {@link AbstractDbTest} 是 {@code @Transactional}（每方法结束回滚，跨测试隔离）。
 *
 * <p>覆盖 brief 两条核心用例 + 边界 + IDOR：
 * <ul>
 *   <li>无冲突 → 直接建卡（B 层同步算 embedding，复用 {@link KbCardService#create}）
 *   <li>有冲突 → 返回供确认（不建任何卡）
 *   <li>确认覆盖 → 旧值归档 {@code card_history} + 更新（B 层重算 embedding）
 *   <li>确认跳过冲突 → 不建该卡；非冲突卡正常建
 *   <li>blocked → 抛 {@link ErrorCode#CONTENT_BLOCKED}，不落库
 *   <li>IDOR：跨用户 confirm 覆盖被拒
 * </ul>
 */
class CardGenTest extends AbstractDbTest {

    @Autowired KbCardService kbCardService;
    @Autowired KbCardMapper kbCardMapper;
    @Autowired CardHistoryMapper cardHistoryMapper;
    @Autowired AppUserMapper appUserMapper;
    @MockBean AiClient aiClient;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private long uid;

    @BeforeEach
    void setup() {
        when(aiClient.safetyCheck(any())).thenReturn(true);
        when(aiClient.embed(any())).thenReturn(dummyEmbedding());

        AppUser u = new AppUser();
        u.setPhone("13800000150");
        u.setDefaultPlatform("douyin");
        appUserMapper.insert(u);
        uid = u.getId();
    }

    private static float[] dummyEmbedding() {
        float[] v = new float[1024];
        for (int i = 0; i < 1024; i++) v[i] = (i % 100) / 10.0f;
        return v;
    }

    private static JsonNode content(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private AiClient.CardGenCard card(String cardType, String title, String contentJson) {
        return new AiClient.CardGenCard(cardType, title, content(contentJson));
    }

    private AiClient.CardGenConflict conflict(long cardId, int cardIndex) {
        return new AiClient.CardGenConflict(cardId, cardIndex, "重叠");
    }

    // ---- 无冲突 → 直接建卡 ---------------------------------------------------

    @Test
    void supplementNoConflictsCreatesCardsDirectly() {
        when(aiClient.cardGen(eq(uid), anyString(), eq("B")))
                .thenReturn(new AiClient.CardGenResult(
                        false,
                        List.of(
                                card("产品", "美白精华", "{\"price\":\"99元\"}"),
                                card("受众", "25-35岁女性", "{\"age\":\"25-35\"}")),
                        List.of("风格", "场景", "卖点"),
                        List.of()));

        KbCardService.SupplementResult res = kbCardService.supplement(uid, "美白精华卖给女性", "B");

        assertTrue(!res.needsConfirm());
        assertEquals(2, res.createdIds().size());
        // B 层建卡同步算 embedding：每张卡一次 embed（create 内）+ safetyCheck 每张一次
        verify(aiClient, times(2)).embed(any());
        // 两张卡都落库
        assertEquals(2, kbCardMapper.countByUser(uid));
        // gaps 透传
        assertEquals(List.of("风格", "场景", "卖点"), res.gaps());
    }

    // ---- 有冲突 → 返回供确认，不建卡 -----------------------------------------

    @Test
    void supplementWithConflictsReturnsForConfirmationAndPersistsNothing() {
        when(aiClient.cardGen(eq(uid), anyString(), eq("B")))
                .thenReturn(new AiClient.CardGenResult(
                        false,
                        List.of(card("产品", "老款精华", "{\"price\":\"99元\"}")),
                        List.of("受众", "风格"),
                        List.of(conflict(999L, 0))));

        KbCardService.SupplementResult res = kbCardService.supplement(uid, "老款精华", "B");

        assertTrue(res.needsConfirm());
        assertEquals(1, res.conflicts().size());
        assertEquals(999L, res.conflicts().get(0).cardId());
        // 冲突时不建任何卡（create 未被调）
        assertEquals(0, kbCardMapper.countByUser(uid));
        verify(aiClient, never()).embed(any());  // 不建卡 → 不算向量
    }

    // ---- 确认覆盖 → 旧值归档 card_history + 更新（B 层重算 embedding）----------

    @Test
    void confirmOverwriteArchivesOldToCardHistoryAndUpdates() {
        // 先建一张现有 B 卡（会被冲突覆盖）
        long existingId = kbCardService.create(uid, "B", "产品", "老款精华液", "{\"price\":\"49元\"}");
        assertEquals(0, cardHistoryMapper.countByCard(existingId));

        // confirm 请求回传 cards + conflicts + overwriteCardIds=[existingId]
        List<AiClient.CardGenCard> cards = List.of(card("产品", "老款精华", "{\"price\":\"99元\"}"));
        List<AiClient.CardGenConflict> conflicts = List.of(conflict(existingId, 0));

        KbCardService.ConfirmResult res =
                kbCardService.confirmSupplement(uid, "B", cards, conflicts, List.of(existingId));

        // 旧值归档 card_history 1 条（§11.4）
        assertEquals(1, cardHistoryMapper.countByCard(existingId));
        // 覆盖了 existingId
        assertEquals(List.of(existingId), res.overwrittenIds());
        // 没有新建卡（冲突卡是覆盖而非新建）
        assertEquals(0, res.createdIds().size());
        assertEquals(1, kbCardMapper.countByUser(uid)); // 总数仍 1（覆盖不是新增）
        // 内容已更新为新值
        KbCard after = kbCardMapper.findById(existingId, uid);
        assertTrue(after.getContent().contains("99"));
        assertTrue(after.getTitle().contains("老款精华"));
        // B 层覆盖路径重算 embedding：create 时 1 次 + confirm 覆盖时 1 次
        verify(aiClient, times(2)).embed(any());
    }

    // ---- 确认跳过冲突 → 不建该卡；非冲突卡正常建 -----------------------------

    @Test
    void confirmSkipConflictCreatesOnlyNonConflictCards() {
        // 现有卡（冲突对象，用户选择跳过——不覆盖也不新建）
        long existingId = kbCardService.create(uid, "B", "产品", "老款精华液", "{\"price\":\"49元\"}");

        // 两张新卡：卡0 冲突（跳过），卡1 无冲突（新建）
        List<AiClient.CardGenCard> cards =
                List.of(
                        card("产品", "老款精华", "{\"price\":\"99元\"}"),
                        card("受众", "25-35岁女性", "{\"age\":\"25-35\"}"));
        List<AiClient.CardGenConflict> conflicts = List.of(conflict(existingId, 0));

        KbCardService.ConfirmResult res =
                kbCardService.confirmSupplement(uid, "B", cards, conflicts, List.of()); // 空覆盖列表 = 全跳过

        // 卡1 新建，卡0 跳过
        assertEquals(1, res.createdIds().size());
        assertEquals(0, res.overwrittenIds().size());
        // 现有卡未被覆盖（内容不变、无归档）
        assertEquals(0, cardHistoryMapper.countByCard(existingId));
        KbCard after = kbCardMapper.findById(existingId, uid);
        assertTrue(after.getContent().contains("49")); // 旧值未变
        // 总数 = 1 现有 + 1 新建 = 2
        assertEquals(2, kbCardMapper.countByUser(uid));
    }

    // ---- blocked → 抛 CONTENT_BLOCKED，不落库 ---------------------------------

    @Test
    void supplementBlockedThrowsContentBlockedAndPersistsNothing() {
        when(aiClient.cardGen(eq(uid), anyString(), eq("B")))
                .thenReturn(new AiClient.CardGenResult(true, null, null, null));

        BizException ex =
                assertThrows(
                        BizException.class,
                        () -> kbCardService.supplement(uid, "违规内容", "B"));
        assertEquals(ErrorCode.CONTENT_BLOCKED, ex.errorCode());
        assertEquals(0, kbCardMapper.countByUser(uid));
        verify(aiClient, never()).embed(any());
    }

    // ---- IDOR：跨用户 confirm 覆盖被拒 ----------------------------------------

    @Test
    void crossUserConfirmOverwriteIsRejected() {
        long uidA = uid;
        long uidB = secondUser();
        long idA = kbCardService.create(uidA, "B", "产品", "A 的老款精华", "{\"price\":\"49元\"}");

        // B 用户冒用 A 的 card_id 做覆盖
        List<AiClient.CardGenCard> cards = List.of(card("产品", "老款精华", "{\"price\":\"99元\"}"));
        List<AiClient.CardGenConflict> conflicts = List.of(conflict(idA, 0));

        BizException ex =
                assertThrows(
                        BizException.class,
                        () ->
                                kbCardService.confirmSupplement(
                                        uidB, "B", cards, conflicts, List.of(idA)));
        assertEquals(ErrorCode.PARAM_INVALID, ex.errorCode());
        // A 的卡片未被改：旧值不变、无归档
        KbCard after = kbCardMapper.findById(idA, uidA);
        assertTrue(after.getContent().contains("49"));
        assertEquals(0, cardHistoryMapper.countByCard(idA));
    }

    private long secondUser() {
        AppUser u = new AppUser();
        u.setPhone("13800000199");
        u.setDefaultPlatform("douyin");
        appUserMapper.insert(u);
        return u.getId();
    }
}
