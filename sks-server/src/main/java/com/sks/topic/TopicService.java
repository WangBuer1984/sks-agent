package com.sks.topic;

import com.sks.aiclient.AiClient;
import com.sks.common.BizException;
import com.sks.common.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 选题服务（MVP 最小集）。
 *
 * <p>四路选题（hot/faq/benchmark/replay）自动抓取系统为 P2；本任务仅支持用户自建 + 列出 + 取详情。
 *
 * <p><b>UGC 安全审核（§5.1）</b>：用户自建选题的 title 属直接编辑文本，与 KB 卡片内容 / card_gen
 * LLM 输出同标准过审——{@link #create} 先 {@link AiClient#safetyCheck} title，不安全抛
 * {@link ErrorCode#CONTENT_BLOCKED} 且<b>不落库</b>。
 */
@Service
public class TopicService {

    private final TopicMapper topicMapper;
    private final AiClient aiClient;

    public TopicService(TopicMapper topicMapper, AiClient aiClient) {
        this.topicMapper = topicMapper;
        this.aiClient = aiClient;
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

    /** 当前用户的全部选题（创作页选题列表）。 */
    public List<Topic> list(long userId) {
        return topicMapper.listByUser(userId);
    }
}
