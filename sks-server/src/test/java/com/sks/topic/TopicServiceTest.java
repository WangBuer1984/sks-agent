package com.sks.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sks.AbstractDbTest;
import com.sks.aiclient.AiClient;
import com.sks.kb.KbCardService;
import com.sks.user.AppUser;
import com.sks.user.AppUserMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * {@link TopicService} 服务级集成测试——选题库四路聚合（Task 1.7）。
 *
 * <p>真实 Testcontainers {@code pgvector/pgvector:pg16}（非 H2），Flyway 跑 V1 建 topic / kb_card。
 * {@link AiClient} 用 {@code @MockBean} mock——不真正调 Python，{@code verify(aiClient, ...)} 断言调用。
 * 基类 {@link AbstractDbTest} 是 {@code @Transactional}（每方法结束回滚，跨测试隔离）。
 *
 * <p>覆盖 brief 三组用例：
 * <ol>
 *   <li>四路来源查询：插入四路 topic，断言 {@code ?source=hot} 只返回 hot、不传 source 返回全部四路。
 *   <li>pillar 排序：按 pillar ASC NULLS LAST + 同 pillar 内 created_at DESC。
 *   <li>hot 路打分（真实逻辑 + P1 空数据）：mock hotBoard→[fakeTitle]、mock embed→1024 维、插一张 B 卡，
 *       调 {@link TopicService#scoreHotTopicsForUser} 后断言入了一条 {@code source='hot'} 选题，
 *       且 pgvector 匹配打分真实走了 embed + B 层余弦匹配。
 * </ol>
 *
 * <p><b>四路聚合的设计（brief cross-task 决策 #1）</b>：四路（hot/faq/benchmark/replay）的<b>读取聚合</b>是
 * 均一的——都从 topic 表按 user_id + source 查询；差异在「如何入库」（hot=HotTopicJob、faq=用户自建、
 * benchmark=拆解 P3、replay=复盘 P4）。P1 期 benchmark/replay 无数据，查询路径真实但返回空。
 */
class TopicServiceTest extends AbstractDbTest {

    @Autowired TopicService topicService;
    @Autowired TopicMapper topicMapper;
    @Autowired KbCardService kbCardService;
    @Autowired AppUserMapper appUserMapper;
    @MockBean AiClient aiClient;

    private long uid;

    @BeforeEach
    void setup() {
        when(aiClient.safetyCheck(any())).thenReturn(true);
        when(aiClient.embed(any())).thenReturn(dummyEmbedding());
        AppUser u = new AppUser();
        u.setPhone("13800000077");
        u.setDefaultPlatform("douyin");
        appUserMapper.insert(u);
        uid = u.getId();
    }

    /** 1024 维 dummy 向量——让 B 卡 embedding 与 hot 标题 embed 结果相同 → pgvector 距离 0 = 完美匹配。 */
    private static float[] dummyEmbedding() {
        float[] v = new float[1024];
        for (int i = 0; i < 1024; i++) {
            v[i] = (i % 64) / 10.0f;
        }
        return v;
    }

    /** 直接用 mapper 插一条指定 source 的 topic（绕过 service.create 的 safetyCheck——hot/benchmark/replay 非用户 UGC）。 */
    private long insertTopic(String source, String title, String pillar) {
        Topic t = new Topic();
        t.setUserId(uid);
        t.setSource(source);
        t.setTitle(title);
        t.setRationale("rationale:" + title);
        t.setPillar(pillar);
        topicMapper.insert(t);
        return t.getId();
    }

    // ---- brief 用例 1：四路来源查询 ----

    /** 四路各插一条；?source=hot 只返回 hot；不传 source 返回全部四路。 */
    @Test
    void fourSourceAggregationFiltersBySource() {
        insertTopic("hot", "热点选题", null);
        insertTopic("faq", "FAQ选题", null);
        insertTopic("benchmark", "拆解选题", null);
        insertTopic("replay", "续集选题", null);

        List<Topic> hotOnly = topicService.list(uid, "hot");
        assertEquals(1, hotOnly.size());
        assertEquals("热点选题", hotOnly.get(0).getTitle());

        List<Topic> all = topicService.list(uid, null);
        assertEquals(4, all.size());
        assertTrue(all.stream().allMatch(t -> t.getUserId() == uid)); // IDOR：只含自己的
    }

    /** 空白 source 视为不传（聚合全部）。 */
    @Test
    void blankSourceAggregatesAll() {
        insertTopic("faq", "a", null);
        insertTopic("benchmark", "b", null);
        assertEquals(2, topicService.list(uid, "  ").size());
    }

    /** IDOR：另一用户的选题不可见。 */
    @Test
    void otherUsersTopicsAreInvisible() {
        AppUser o = new AppUser();
        o.setPhone("13900000099");
        o.setDefaultPlatform("douyin");
        appUserMapper.insert(o);
        insertTopicForUser(o.getId(), "faq", "别人的选题", null);
        insertTopic("faq", "我的选题", null);
        List<Topic> mine = topicService.list(uid, null);
        assertTrue(mine.stream().noneMatch(t -> "别人的选题".equals(t.getTitle())));
        assertEquals(1, mine.size());
    }

    private void insertTopicForUser(long userId, String source, String title, String pillar) {
        Topic t = new Topic();
        t.setUserId(userId);
        t.setSource(source);
        t.setTitle(title);
        t.setRationale("x");
        t.setPillar(pillar);
        topicMapper.insert(t);
    }

    // ---- brief 用例 2：pillar 排序 ----

    /** pillar ASC NULLS LAST 为主序，同 pillar 内 created_at DESC 为次序。 */
    @Test
    void pillarOrderingGroupsByPillarThenRecency() {
        // 插入顺序（created_at 递增）：a1, a2, b, null
        insertTopic("faq", "a1", "a");
        insertTopic("faq", "a2", "a"); // a2 比 a1 新
        insertTopic("faq", "b1", "b");
        insertTopic("faq", "n1", null); // pillar 空最后

        List<Topic> all = topicService.list(uid, null);
        assertEquals(4, all.size());
        // a 组（a2 新→旧），b 组，null 最后
        assertEquals("a2", all.get(0).getTitle());
        assertEquals("a1", all.get(1).getTitle());
        assertEquals("b1", all.get(2).getTitle());
        assertEquals("n1", all.get(3).getTitle());
    }

    // ---- brief 用例 3：hot 路打分（真实逻辑，P1 stub 返回空 → 此处 mock 出一条）----

    /**
     * mock hotBoard→[fakeTitle]、mock embed→1024 维、插一张 B 卡（embedding 同 dummy），
     * 调 scoreHotTopicsForUser 后断言入了一条 source='hot' 选题，且 embed 真实被调（打分逻辑真实）。
     */
    @Test
    void hotPathScoringInsertsHotTopicWhenBMatchFound() {
        // 插一张 B 卡：KbCardService.create 会调 embed(title+" "+content)→dummyEmbedding
        kbCardService.create(uid, "B", "产品", "口播选题指南", "{\"tag\":\"good\"}");

        String hotTitle = "新手如何挑选口播选题";
        when(aiClient.hotBoard()).thenReturn(List.of(new AiClient.HotItem(hotTitle, "热点理由", null)));

        int n = topicService.scoreHotTopicsForUser(uid);

        assertEquals(1, n); // 入库 1 条
        verify(aiClient).hotBoard();
        verify(aiClient).embed(eq(hotTitle)); // hot 标题真实 embed 打分
        List<Topic> hotTopics = topicService.list(uid, "hot");
        assertEquals(1, hotTopics.size());
        assertEquals(hotTitle, hotTopics.get(0).getTitle());
    }

    /** 无 B 卡匹配（用户无 B 层卡片）→ 不入 hot 选题。 */
    @Test
    void hotPathSkipsWhenNoBMatch() {
        when(aiClient.hotBoard()).thenReturn(List.of(new AiClient.HotItem("不相干的热点", "x", null)));
        int n = topicService.scoreHotTopicsForUser(uid);
        assertEquals(0, n);
        verify(aiClient).embed(any());
        assertEquals(0, topicService.list(uid, "hot").size());
    }

    /** hotBoard 返回空（P1 stub 行为）→ 打分循环 0 次，embed 不被调（针对 hot 路而言）。 */
    @Test
    void hotPathNoOpsWhenHotBoardEmpty() {
        when(aiClient.hotBoard()).thenReturn(List.of());
        assertEquals(0, topicService.scoreHotTopicsForUser(uid));
        // hot 路的 embed 不应被调（无热点标题可打分）
        verify(aiClient, never()).embed(any());
    }

    // ---- benchmark / replay 路径在 P1 返回空（查询路径真实）----

    @Test
    void benchmarkPathReturnsEmptyInP1() {
        assertEquals(0, topicService.list(uid, "benchmark").size());
    }

    @Test
    void replayPathReturnsEmptyInP1() {
        assertEquals(0, topicService.list(uid, "replay").size());
    }
}
