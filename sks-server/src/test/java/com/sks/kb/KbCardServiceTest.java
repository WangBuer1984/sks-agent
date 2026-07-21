package com.sks.kb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sks.AbstractDbTest;
import com.sks.aiclient.AiClient;
import com.sks.common.BizException;
import com.sks.user.AppUser;
import com.sks.user.AppUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * {@link KbCardService} 服务级集成测试。
 *
 * <p>真实 Testcontainers {@code pgvector/pgvector:pg16}（非 H2），Flyway 跑 V1 建 kb_card / card_history /
 * card_citation。{@link AiClient} 用 {@code @MockBean} mock——不真正调 Python，{@code verify(aiClient, ...)}
 * 断言调用次数。基类 {@link AbstractDbTest} 是 {@code @Transactional}（每方法结束回滚，跨测试隔离）。
 *
 * <p>覆盖 brief 两个 verbatim 用例 + 边界：UGC 拦截不落库、A/C 层不算向量、force 删除带引用通过。
 */
class KbCardServiceTest extends AbstractDbTest {

    @Autowired KbCardService kbCardService;
    @Autowired KbCardMapper kbCardMapper;
    @Autowired CardHistoryMapper cardHistoryMapper;
    @Autowired CardCitationMapper cardCitationMapper;
    @Autowired AppUserMapper appUserMapper;
    @MockBean AiClient aiClient;

    private static final String contentV1 = "{\"price\":\"99元\"}";
    private static final String contentV2 = "{\"price\":\"199元\"}";

    private long uid;

    @BeforeEach
    void setup() {
        when(aiClient.safetyCheck(any())).thenReturn(true);
        when(aiClient.embed(any())).thenReturn(dummyEmbedding());

        AppUser u = new AppUser();
        u.setPhone("13800000012");
        u.setDefaultPlatform("douyin");
        appUserMapper.insert(u);
        uid = u.getId();
    }

    /** 1024 维 dummy 向量（不调真 GLM，只验证 TypeHandler 能把 float[] 写进 vector(1024) 列）。 */
    private static float[] dummyEmbedding() {
        float[] v = new float[1024];
        for (int i = 0; i < 1024; i++) {
            v[i] = (i % 100) / 10.0f;
        }
        return v;
    }

    // ---- brief verbatim 用例 ----

    @Test
    void editingBLayerCardRecomputesEmbeddingAndArchivesOld() {
        long id = kbCardService.create(uid, "B", "产品", "老价格", contentV1);
        kbCardService.update(uid, id, "新价格", contentV2);
        assertEquals(1, cardHistoryMapper.countByCard(id)); // 旧值归档
        verify(aiClient, times(2)).embed(any()); // 建+改各算一次
    }

    @Test
    void deleteWithCitationsRequiresForce() {
        long id = kbCardService.create(uid, "B", "产品", "t", contentV1);
        cardCitationMapper.insert(new CardCitation(999L, id));
        assertThrows(BizException.class, () -> kbCardService.delete(uid, id, false));
        kbCardService.delete(uid, id, true); // force 通过
    }

    // ---- 边界用例 ----

    /** UGC 拦截：safetyCheck=false 时抛 CONTENT_BLOCKED，且什么都不落库。 */
    @Test
    void createBlockedBySafetyCheckPersistsNothing() {
        when(aiClient.safetyCheck(any())).thenReturn(false);
        assertThrows(
                BizException.class,
                () -> kbCardService.create(uid, "A", "人物", "违规标题", "{}"));
        assertEquals(0, kbCardMapper.countByUser(uid)); // 没落库
    }

    /** A/C 层卡片不算向量。 */
    @Test
    void aLayerCardDoesNotComputeEmbedding() {
        kbCardService.create(uid, "A", "人物", "测试", "{}");
        verify(aiClient, never()).embed(any());
    }

    /** C 层卡片编辑同样不算向量、不归档。 */
    @Test
    void cLayerUpdateDoesNotEmbedOrArchive() {
        long id = kbCardService.create(uid, "C", "金句", "标题", "{}");
        org.mockito.Mockito.clearInvocations(aiClient);
        kbCardService.update(uid, id, "新标题", "{\"text\":\"改\"}");
        verify(aiClient, never()).embed(any());
        assertEquals(0, cardHistoryMapper.countByCard(id)); // C 层不归档
    }

    /** force 删除带引用的卡片应通过（软删）。 */
    @Test
    void forceDeleteWithCitationsSucceeds() {
        long id = kbCardService.create(uid, "C", "金句", "标题", "{}");
        cardCitationMapper.insert(new CardCitation(888L, id));
        kbCardService.delete(uid, id, true); // 不抛
        assertEquals(0, kbCardMapper.countByUser(uid)); // 已软删，count 不含
    }

    // ---- IDOR 防护（设计 §5.1）----

    /**
     * 注册第二个用户（不同手机号，避开 app_user.phone 唯一约束）。
     *
     * <p>每个测试方法 @Transactional 回滚，所以不同测试用相同手机号也不会冲突；
     * 但本类 @BeforeEach 固定插 13800000012，第二用户必须在测试方法内插、用不同号。
     */
    private long secondUser() {
        AppUser u = new AppUser();
        u.setPhone("13800000099");
        u.setDefaultPlatform("douyin");
        appUserMapper.insert(u);
        return u.getId();
    }

    /** 跨用户 update 应被拒：B 用户改 A 用户的卡片 → 抛 PARAM_INVALID，A 卡片内容不变。 */
    @Test
    void crossUserUpdateIsRejected() {
        long uidA = uid;
        long uidB = secondUser();
        long idA = kbCardService.create(uidA, "B", "产品", "A 的标题", contentV1);
        // B 冒用 A 的卡片 id 更新
        BizException ex =
                assertThrows(
                        BizException.class,
                        () -> kbCardService.update(uidB, idA, "B 篡改", contentV2));
        assertEquals(com.sks.common.ErrorCode.PARAM_INVALID, ex.errorCode());
        // A 的卡片未被改：标题不变、内容仍是 V1（99元，非 V2 的 199元）、未归档历史
        KbCard after = kbCardMapper.findById(idA, uidA);
        assertEquals("A 的标题", after.getTitle());
        org.assertj.core.api.Assertions.assertThat(after.getContent()).contains("99");
        org.assertj.core.api.Assertions.assertThat(after.getContent()).doesNotContain("199");
        assertEquals(0, cardHistoryMapper.countByCard(idA));
    }

    /** 跨用户 delete 应被拒：B 用户删 A 用户的卡片 → 抛 PARAM_INVALID，A 卡片未软删。 */
    @Test
    void crossUserDeleteIsRejected() {
        long uidA = uid;
        long uidB = secondUser();
        long idA = kbCardService.create(uidA, "C", "金句", "A 的标题", "{}");
        BizException ex =
                assertThrows(BizException.class, () -> kbCardService.delete(uidB, idA, true));
        assertEquals(com.sks.common.ErrorCode.PARAM_INVALID, ex.errorCode());
        // A 的卡片仍在
        assertEquals(1, kbCardMapper.countByUser(uidA));
        KbCard after = kbCardMapper.findById(idA, uidA);
        assertEquals("A 的标题", after.getTitle());
    }
}
