package com.sks.topic;

import com.sks.aiclient.AiClient;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import com.sks.kb.BMatch;
import com.sks.kb.KbCardService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 选题服务——选题库四路聚合（Task 1.7）。
 *
 * <p><b>四路来源（hot / faq / benchmark / replay）</b>（brief cross-task 决策 #1）：四路<b>读取聚合</b>
 * 均一——都从 {@code topic} 表按 {@code user_id}（IDOR 隔离）+ 可选 {@code source} 过滤，按 {@code pillar}
 * 排序。差异在「如何<b>入</b>库」：
 * <ul>
 *   <li>{@code hot}：{@link HotTopicJob} 定时拉热点榜 → {@link #scoreHotTopicsForUser} 打分入库（本任务实现，
 *       P1 {@link AiClient#hotBoard} 是空桩 → 跑了也不入库）。
 *   <li>{@code faq}：用户自建（{@link #create}，title 过 UGC 安全）。
 *   <li>{@code benchmark}：拆解结果转选题（P3 Task 3.3）。
 *   <li>{@code replay}：爆款续集（P4 复盘）。
 * </ul>
 * P1 期 benchmark / replay 无数据，查询路径真实但返回空。
 *
 * <p><b>UGC 安全审核（§5.1）</b>：用户自建（{@code faq}）选题的 title 属直接编辑文本，与 KB 卡片内容 /
 * card_gen LLM 输出同标准过审——{@link #create} 先 {@link AiClient#safetyCheck} title，不安全抛
 * {@link ErrorCode#CONTENT_BLOCKED} 且<b>不落库</b>。{@code hot} 路选题<b>不</b>双重过审——标题来自平台
 * 热榜（TikHub，非 UGC），与 {@code faq} 区别对待。
 *
 * <p><b>跨模块依赖</b>：hot 路打分需在 {@code kb_card} B 层做 pgvector 匹配，走 {@link KbCardService}
 * （kb 模块 owns kb_card）——不直查 KbCardMapper，避免 topic → kb mapper 的耦合。
 */
@Service
public class TopicService {

    private static final Logger log = LoggerFactory.getLogger(TopicService.class);

    private final TopicMapper topicMapper;
    private final AiClient aiClient;
    private final KbCardService kbCardService;

    public TopicService(TopicMapper topicMapper, AiClient aiClient, KbCardService kbCardService) {
        this.topicMapper = topicMapper;
        this.aiClient = aiClient;
        this.kbCardService = kbCardService;
    }

    /**
     * 新建选题。title 先过内容安全；source 缺省 {@code faq}（用户自建）；status 走 DB 默认 'open'。
     *
     * @return 新选题 id
     */
    public long create(long userId, String title, String rationale, String source) {
        if (title == null || title.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标题不能为空");
        }
        if (!aiClient.safetyCheck(title)) {
            throw new BizException(ErrorCode.CONTENT_BLOCKED);
        }
        Topic t = new Topic();
        t.setUserId(userId);
        t.setTitle(title);
        t.setRationale(rationale);
        t.setSource(source == null || source.isBlank() ? "faq" : source);
        topicMapper.insert(t);
        return t.getId();
    }

    /** 取选题详情（IDOR 防护：跨用户返回 PARAM_INVALID）。 */
    public Topic get(long userId, long topicId) {
        Topic t = topicMapper.findById(topicId, userId);
        if (t == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "选题不存在");
        }
        return t;
    }

    /**
     * 当前用户的全部选题（四路聚合，按 pillar 排序）。source 为 null / 空白 → 聚合四路；否则过滤单路。
     *
     * <p>IDOR 防护：WHERE 带 {@code user_id}，跨用户不可见（{@link TopicMapper#listByUserWithSource}）。
     */
    public List<Topic> list(long userId, String source) {
        String src = (source == null || source.isBlank()) ? null : source;
        return topicMapper.listByUserWithSource(userId, src);
    }

    /** 当前用户的全部选题（创作页选题列表，四路聚合）。等价 {@link #list(long, String) list(uid, null)}。 */
    public List<Topic> list(long userId) {
        return list(userId, null);
    }

    /**
     * hot 路打分入库（Task 1.7 核心逻辑）——对当前用户执行一次热点榜打分。
     *
     * <p>流程（brief cross-task 决策 #3）：
     * <ol>
     *   <li>取热点榜 {@link AiClient#hotBoard}（P1 桩返回空 → 循环 0 次，本方法为 no-op）。
     *   <li>对每条热点标题调 {@link AiClient#embed} 算 1024 维向量（真实 embed，非桩）。
     *   <li>在当前用户 B 层 kb_card 上做 pgvector 余弦匹配（{@link KbCardService#findBestBMatch}，
     *       阈值 0.25 = 相似度 0.75，镜像 {@code rag/retrieve.py}）。命中 → 入一条
     *       {@code source='hot'} 选题；未命中 → 跳过（该热点与用户知识库不相关，不入库）。
     * </ol>
     *
     * <p><b>非 {@code @Transactional}</b>：embed 是 HTTP 调用（虽快），匹配是 DB 读，入库是单行 insert
     * ——按 §4.1 教训，不在长调用外持事务；每条热点独立 insert（mapper.insert 自动提交），失败一条不影响
     * 其他条。P1 hotBoard 桩为空，整方法除 hotBoard 调用外无副作用。
     *
     * <p><b>不入库则返回 0</b>：P1 运行期恒为 0（桩空）。测试以 mocked hotBoard 验证真实打分路径。
     *
     * @return 实际入库的 hot 选题条数
     */
    public int scoreHotTopicsForUser(long userId) {
        List<AiClient.HotItem> items = aiClient.hotBoard();
        if (items.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (AiClient.HotItem item : items) {
            String title = item.title();
            if (title == null || title.isBlank()) {
                continue;
            }
            float[] vec = aiClient.embed(title);
            java.util.Optional<BMatch> match = kbCardService.findBestBMatch(userId, vec);
            if (match.isEmpty()) {
                log.debug("hot title '{}' matched no B card for user {} — skipped", title, userId);
                continue;
            }
            BMatch m = match.get();
            Topic t = new Topic();
            t.setUserId(userId);
            t.setSource("hot");
            t.setTitle(title);
            t.setRationale(item.rationale() != null ? item.rationale() : ("热点匹配:" + m.title()));
            t.setPillar(item.pillar());
            topicMapper.insert(t);
            inserted++;
            log.info(
                    "hot topic inserted: user={}, title='{}', matched_card={}, distance={}",
                    userId,
                    title,
                    m.id(),
                    m.distance());
        }
        return inserted;
    }
}

