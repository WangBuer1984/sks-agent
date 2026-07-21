package com.sks.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 本地 SimHash 查重（PRD §11.2「命中不阻断」）——Java 本地零成本，不引入 jieba / 不调 LLM。
 *
 * <p><b>算法（cross-task decision #1）：</b>
 *
 * <ol>
 *   <li><b>分词</b>：字符 bigram（"这是正文"→"这是/是正/正文"）。对中文 / 任意语种通用，无需词典——
 *       故<b>依赖零成本</b>（无 jieba）。单字符文本退化为其自身一个 token（保证极短文本仍有指纹）。
 *   <li><b>哈希</b>：每个 token 取 <b>64 位 FNV-1a</b>（{@code 0xcbf29ce484222325} 初值 / {@code 0x100000001b3}
 *       质）。{@link String#hashCode} 仅 32 位，碰撞率高；SimHash 需 64 位以稳健区分无关文本。
 *   <li><b>加权 + 投票</b>：token 按词频加权（重复 token 权重高，反映文本侧重），逐 bit 投票——
 *       该 bit 在 token 哈希中为 1 则 {@code +w}，为 0 则 {@code -w}，累加得 64 个 {@code int}。
 *   <li><b>指纹</b>：投票和 &gt; 0 的 bit 置 1，其余 0，组成 64 位指纹。
 *   <li><b>相似度</b>：{@code 1 - hammingDistance(fingerprintA, fingerprintB) / 64.0}，范围 [0,1]，
 *       1=完全相同、0=指纹互补（实际无关文本约 0.3-0.5）。
 * </ol>
 *
 * <p><b>阈值默认 0.8</b>（near-duplicate）——经 {@code sks.dedup.threshold} 可配置。低于此值不告警，
 * 高于此值则在生成响应体带 {@code dedupWarnScriptId}（前端黄条 + 「换角度」按钮，前端后续 task）。
 *
 * <p><b>不阻断</b>（PRD §11.2 / cross-task decision #4）：查重仅告警，不退额度、不改 {@code review_state}、
 * 不抛异常——稿件仍为 {@code draft}，用户只是被提示。无 DB 写、无额度介入。查重为纯读 + 本地计算，
 * 在 {@link ScriptService#generate} 回填成功后、事务之外执行（快，不占长事务连接）。
 *
 * <p><b>JSONB 扁平化</b>（cross-task decision #3）：{@code hook/body/cta} 各为 {@code {sentences:[{idx,text}]}}，
 * 查重前抽 plain text——解析 sentences 数组、拼接各 {@code text}。<b>取 hook+body+cta 全文拼接</b>
 * （非仅 body）——更厚的信号覆盖全文雷同，误判更低。文档化选择：full-script-text。
 */
@Component
public class DedupChecker {

    private static final ObjectMapper OM = new ObjectMapper();

    /** FNV-1a 64-bit offset basis。 */
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    /** FNV-1a 64-bit prime。 */
    private static final long FNV_PRIME = 0x100000001b3L;

    private final ScriptMapper scriptMapper;
    private final double defaultThreshold;

    public DedupChecker(
            ScriptMapper scriptMapper, @Value("${sks.dedup.threshold:0.8}") double defaultThreshold) {
        this.scriptMapper = scriptMapper;
        this.defaultThreshold = defaultThreshold;
    }

    /** 默认查重阈值（near-duplicate），可经 {@code sks.dedup.threshold} 覆盖。 */
    public double getDefaultThreshold() {
        return defaultThreshold;
    }

    // ---- SimHash 核心（纯函数，无 DB）----

    /** 64 位 FNV-1a 哈希（依赖零成本，比 {@link String#hashCode} 的 32 位更稳健）。 */
    static long fnv1a64(String s) {
        long h = FNV_OFFSET;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= FNV_PRIME;
        }
        return h;
    }

    /**
     * 字符 bigram 分词（中文 / 任意语种通用，无词典依赖）。{@code "这是正文"}→{@code ["这是","是正","正文"]}。
     *
     * <p>单字符文本（长度 1）退化为含其自身一个 token——保证极短文本仍可计算指纹，避免空指纹导致
     * 任意两短文本相似度恒为 1.0 的假阳性。
     */
    static List<String> bigrams(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        if (text.length() == 1) {
            return List.of(text);
        }
        List<String> out = new ArrayList<>(text.length() - 1);
        for (int i = 0; i + 1 < text.length(); i++) {
            out.add(text.substring(i, i + 2));
        }
        return out;
    }

    /** 计算文本的 64 位 SimHash 指纹。 */
    public static long simHash(String text) {
        List<String> tokens = bigrams(text);
        if (tokens.isEmpty()) {
            return 0L;
        }
        // 词频加权——重复 token 反映文本侧重，应权重更高。
        Map<String, Integer> freq = new HashMap<>();
        for (String t : tokens) {
            freq.merge(t, 1, Integer::sum);
        }
        int[] votes = new int[64];
        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            long h = fnv1a64(e.getKey());
            int w = e.getValue();
            for (int i = 0; i < 64; i++) {
                if (((h >> i) & 1L) == 1L) {
                    votes[i] += w;
                } else {
                    votes[i] -= w;
                }
            }
        }
        long fp = 0L;
        for (int i = 0; i < 64; i++) {
            if (votes[i] > 0) {
                fp |= (1L << i);
            }
        }
        return fp;
    }

    /** 两个 64 位指纹的汉明距离。 */
    public static int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    /**
     * 两段文本的相似度 {@code [0,1]}：{@code 1 - hammingDistance/64}。1=完全相同。
     *
     * <p>{@code null} 入参返回 0（无文本无可比性）。两空串返回 1.0（皆空视为相同——SimHash 指纹同为 0）。
     */
    public static double similarity(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        long fa = simHash(a);
        long fb = simHash(b);
        return 1.0 - hammingDistance(fa, fb) / 64.0;
    }

    // ---- JSONB 扁平化 ----

    /**
     * 把三段 JSONB（{@code {sentences:[{idx,text}]}}）拼成纯文本——解析 sentences 数组、拼接各 {@code text}。
     * 取 hook+body+cta 全文（cross-task decision #3 选择 full-script-text，覆盖全文雷同、误判更低）。
     * 任一段为 null / 空 / 非法 JSON 跳过。
     */
    public static String flattenPlainText(String hookJson, String bodyJson, String ctaJson) {
        StringBuilder sb = new StringBuilder();
        sb.append(flattenSection(hookJson));
        sb.append(flattenSection(bodyJson));
        sb.append(flattenSection(ctaJson));
        return sb.toString();
    }

    /** 抽单段 JSONB 的 sentences[].text 拼接为纯文本。非法 / 空返回 ""。 */
    private static String flattenSection(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            JsonNode root = OM.readTree(json);
            JsonNode sentences = root.path("sentences");
            if (!sentences.isArray()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode s : sentences) {
                String t = s.path("text").asText("");
                if (!t.isEmpty()) {
                    sb.append(t);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ---- findSimilar（DB 读 + 本地比对）----

    /**
     * 在同用户历史成功稿（{@code review_state NOT IN ('generating','failed')}）中比对，返回<b>最相似</b>
     * 且 ≥ {@code threshold} 的稿件 id；无则 empty。
     *
     * <p>排除 {@code excludeScriptId}（刚创建稿自身）——cross-task decision #3 / #4：查重不应返回自身。
     * 传 -1 表示不排除。
     *
     * <p><b>纯读</b>——一次 SELECT 取历史稿（含 hook/body/cta），逐条本地 SimHash 比对。不写、不扣、不阻断。
     *
     * @param userId 当前用户
     * @param excludeScriptId 刚创建稿 id（排除自身）；-1 表示不排除
     * @param newPlainText 新稿全文纯文本（由 {@link #flattenPlainText} 产出）
     * @param threshold 相似度阈值（[0,1]，默认取 {@link #getDefaultThreshold()}）
     */
    public Optional<Long> findSimilar(
            long userId, long excludeScriptId, String newPlainText, double threshold) {
        if (newPlainText == null || newPlainText.isEmpty()) {
            return Optional.empty();
        }
        List<Script> historical = scriptMapper.findSuccessfulForDedup(userId);
        Long bestId = null;
        double bestSim = threshold; // 必须 ≥ threshold 才命中
        for (Script s : historical) {
            if (s.getId() != null && s.getId() == excludeScriptId) {
                continue;
            }
            String oldText = flattenPlainText(s.getHook(), s.getBody(), s.getCta());
            double sim = similarity(newPlainText, oldText);
            if (sim >= bestSim) {
                bestSim = sim;
                bestId = s.getId();
            }
        }
        return Optional.ofNullable(bestId);
    }
}
