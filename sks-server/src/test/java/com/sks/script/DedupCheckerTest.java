package com.sks.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sks.AbstractDbTest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link DedupChecker} 测试——本地 SimHash 查重（命中仅告警，不阻断，PRD §11.2）。
 *
 * <p>分两层：
 *
 * <ol>
 *   <li><b>纯单测</b>（{@link #identicalTextSimilarityIsOne} / {@link #nearDuplicateAboveThreshold}
 *       / {@link #differentTextBelowThreshold}）：{@link DedupChecker#similarity} 是无状态的纯函数，
 *       直接调静态方法，不开 Spring 上下文——快、不依赖 Docker。验证 SimHash 语义：相同→1.0、
 *       近乎相同→≥阈值、明显不同→&lt;阈值。
 *   <li><b>DB 集成</b>（{@link DedupCheckerDbTest}）：{@link DedupChecker#findSimilar} 是 READ——
 *       查同用户历史成功稿、逐条比对、返回最相似且 ≥ 阈值者。{@link AbstractDbTest} 的
 *       {@code @Transactional} 回滚对纯读无影响（无 REQUIRES_NEW、无掩盖风险，cross-task decision 确认）。
 * </ol>
 *
 * <p><b>SimHash 算法 + 中文分词选择</b>（cross-task decision #1）：依赖零成本——不引入 jieba，
 * 中文按<b>字符 bigram</b> 切分（"这是正文"→"这是/是正/正文"），对任意语种通用且无需词典。
 * 每个 token 取 64 位 FNV-1a 哈希（{@code String.hashCode()} 仅 32 位，SimHash 需 64 位以降低碰撞），
 * 按词频加权、逐 bit 投票（+1/-1）累加得到 64 位指纹。相似度 = {@code 1 - hammingDistance/64}。
 *
 * <p><b>阈值默认 0.8</b>（near-duplicate）——{@link DedupChecker#getDefaultThreshold()} 暴露，
 * 可经 {@code sks.dedup.threshold} 配置覆盖。
 */
class DedupCheckerTest {

    // ---- 纯单测：similarity（无 Spring / 无 DB）----

    /** 相同文本 → 1.0。SimHash 指纹完全一致 → 汉明距离 0。 */
    @Test
    void identicalTextSimilarityIsOne() {
        String a = "如何挑选口播选题的三个核心方法，新手最常卡在选题环节。";
        assertEquals(1.0, DedupChecker.similarity(a, a), 0.0001);
    }

    /** 近乎相同（少改一两字）→ ≥ 0.8（near-duplicate 阈值）。 */
    @Test
    void nearDuplicateAboveThreshold() {
        String a = "如何挑选口播选题的三个核心方法，新手最常卡在选题环节。";
        // 仅去掉「最」「，」并加一短句——bigram 大量重叠
        String b = "如何挑选口播选题的三个核心方法，新手常卡在选题环节，分享给新手。";
        double sim = DedupChecker.similarity(a, b);
        assertTrue(sim >= 0.8, "near-duplicate 应 ≥ 0.8，实际=" + sim);
    }

    /** 明显不同（话题无关）→ &lt; 0.8（不命中）。 */
    @Test
    void differentTextBelowThreshold() {
        String a = "如何挑选口播选题的三个核心方法，新手最常卡在选题环节。";
        String b = "周末去哪里玩最合适，旅游美食探店攻略分享全记录。";
        double sim = DedupChecker.similarity(a, b);
        assertTrue(sim < 0.8, "无关文本应 < 0.8，实际=" + sim);
    }
}

/**
 * {@link DedupChecker#findSimilar} 的 DB 集成测试——同用户历史成功稿比对、返回最相似且 ≥ 阈值者。
 *
 * <p>真实 Testcontainers {@code pgvector/pgvector:pg16}，Flyway 跑 V1。{@link AbstractDbTest} 的
 * {@code @Transactional} 每方法回滚——对纯读无掩盖风险（无 deduct/refund/REQUIRES_NEW，cross-task
 * decision 确认）。插入两份历史稿（A 近复、B 无关），断言 findSimilar 命中 A；无关新稿返回 empty；
 * 排除刚创建稿（命中也不返回自身）。
 */
class DedupCheckerDbTest extends AbstractDbTest {

    @Autowired DedupChecker dedupChecker;
    @Autowired JdbcTemplate jdbcTemplate;

    private long uid;

    /**
     * 命中近复稿：新稿文本 = A 的文本 → 相似度 1.0 ≥ 阈值 → 返回 A 的 id（B 无关不命中）。
     *
     * <p>{@link DedupChecker#findSimilar} 取<b>最相似</b>且 ≥ 阈值者，故即便 B 也在结果集里，返回的仍是 A。
     */
    @Test
    void findSimilarReturnsNearDuplicateScriptId() {
        long userId = insertUser();
        long aId = insertDraftScript(userId, "如何挑选口播选题", "如何挑选口播选题的三个核心方法", "关注我");
        insertDraftScript(userId, "美食探店开场", "周末去哪里玩最合适旅游美食探店攻略分享全记录", "点赞收藏");

        // 新稿正文 = A 的正文 → 近复
        String newBody = "如何挑选口播选题的三个核心方法";
        Optional<Long> hit =
                dedupChecker.findSimilar(userId, -1L, newBody, dedupChecker.getDefaultThreshold());
        assertTrue(hit.isPresent(), "应命中近复稿 A");
        assertEquals(aId, hit.get());
    }

    /** 无关新稿 → 无历史稿相似度 ≥ 阈值 → 返回 empty。 */
    @Test
    void findSimilarReturnsEmptyForDifferentContent() {
        long userId = insertUser();
        insertDraftScript(userId, "如何挑选口播选题", "如何挑选口播选题的三个核心方法", "关注我");
        insertDraftScript(userId, "美食探店开场", "周末去哪里玩最合适旅游美食探店攻略分享全记录", "点赞收藏");

        String newBody = "区块链投资入门指南，加密货币钱包安全注意事项。";
        Optional<Long> hit =
                dedupChecker.findSimilar(userId, -1L, newBody, dedupChecker.getDefaultThreshold());
        assertTrue(hit.isEmpty(), "无关内容不应命中查重");
    }

    /**
     * 排除刚创建稿：excludeScriptId=A 且新稿正文=A 正文 → A 被排除（即便相似度 1.0），
     * B 无关不命中 → 返回 empty。证伪「查重返回自身」的回归。
     */
    @Test
    void findSimilarExcludesJustCreatedScript() {
        long userId = insertUser();
        long aId = insertDraftScript(userId, "如何挑选口播选题", "如何挑选口播选题的三个核心方法", "关注我");
        insertDraftScript(userId, "美食探店开场", "周末去哪里玩最合适旅游美食探店攻略分享全记录", "点赞收藏");

        String newBody = "如何挑选口播选题的三个核心方法";
        Optional<Long> hit =
                dedupChecker.findSimilar(userId, aId, newBody, dedupChecker.getDefaultThreshold());
        assertTrue(hit.isEmpty(), "排除刚创建稿后即便相似也不应命中自身");
    }

    // ---- helpers ----

    private long insertUser() {
        String phone = "1390" + System.nanoTime();
        jdbcTemplate.update(
                "INSERT INTO app_user(phone, default_platform) VALUES(?, 'douyin')", phone);
        Long id =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM app_user WHERE phone = ?", Long.class, phone);
        return id;
    }

    private long insertDraftScript(long userId, String hookText, String bodyText, String ctaText) {
        jdbcTemplate.update(
                "INSERT INTO script(user_id, platform, review_state, hook, body, cta) "
                        + "VALUES(?, 'douyin', 'draft', ?::jsonb, ?::jsonb, ?::jsonb)",
                userId,
                sectionJson(hookText),
                sectionJson(bodyText),
                sectionJson(ctaText));
        Long id =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM script WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                        Long.class,
                        userId);
        return id;
    }

    private static String sectionJson(String text) {
        return "{\"sentences\":[{\"idx\":0,\"text\":\"" + text + "\"}]}";
    }
}
