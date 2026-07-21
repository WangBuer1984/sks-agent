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
        kbCardService.update(id, "新价格", contentV2);
        assertEquals(1, cardHistoryMapper.countByCard(id)); // 旧值归档
        verify(aiClient, times(2)).embed(any()); // 建+改各算一次
    }

    @Test
    void deleteWithCitationsRequiresForce() {
        long id = kbCardService.create(uid, "B", "产品", "t", contentV1);
        cardCitationMapper.insert(new CardCitation(999L, id));
        assertThrows(BizException.class, () -> kbCardService.delete(id, false));
        kbCardService.delete(id, true); // force 通过
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
        kbCardService.update(id, "新标题", "{\"text\":\"改\"}");
        verify(aiClient, never()).embed(any());
        assertEquals(0, cardHistoryMapper.countByCard(id)); // C 层不归档
    }

    /** force 删除带引用的卡片应通过（软删）。 */
    @Test
    void forceDeleteWithCitationsSucceeds() {
        long id = kbCardService.create(uid, "C", "金句", "标题", "{}");
        cardCitationMapper.insert(new CardCitation(888L, id));
        kbCardService.delete(id, true); // 不抛
        assertEquals(0, kbCardMapper.countByUser(uid)); // 已软删，count 不含
    }
}
