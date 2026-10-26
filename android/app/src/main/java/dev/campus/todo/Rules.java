package dev.campus.todo;

import java.util.ArrayList;
import java.util.List;

/**
 * 采集规则判定。纯逻辑：不碰 Android API，可直接在 JVM 上单测（见 RulesTest）。
 *
 * 语义（顺序很重要）：
 *   1. 命中黑名单 → 拦掉。黑名单一票否决，白名单救不回来；
 *   2. 白名单非空且没命中 → 拦掉（白名单是「只收这些」的限定，不是「额外也收」）；
 *   3. 其余放行。
 *
 * 匹配方式：规则文本与会话名都先过 Filter.normalize（全角转半角、去掉所有空白、转小写）
 * 再做子串匹配。所以「计科 2201」与「计科2201」等价，规则写「班群」也能命中「计科2201班群」。
 */
public final class Rules {

    private Rules() {
    }

    /** 返回 null 表示放行；否则返回被拦的原因（展示给用户，也用于计数）。 */
    public static String decide(List<Model.Rule> rules, String title, String conversationTitle) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }

        List<Model.Rule> allow = new ArrayList<>();
        List<Model.Rule> deny = new ArrayList<>();
        for (Model.Rule rule : rules) {
            if (rule == null || rule.text == null || Filter.normalize(rule.text).isEmpty()) {
                continue;   // 空规则一律忽略，否则一条空白的黑名单会把所有消息干掉
            }
            if (Model.Rule.DENY.equals(rule.mode)) {
                deny.add(rule);
            } else {
                allow.add(rule);
            }
        }

        String denyHit = firstMatch(deny, title, conversationTitle);
        if (denyHit != null) {
            return "黑名单命中「" + denyHit + "」";
        }
        if (!allow.isEmpty() && firstMatch(allow, title, conversationTitle) == null) {
            return "不在白名单内";
        }
        return null;
    }

    /**
     * 标题与 conversationTitle 分别匹配，不拼成一个字符串再找：
     * 拼接会让规则跨字段命中（例如规则「群主」被「XX群」+「主」拼出来）。
     */
    static String firstMatch(List<Model.Rule> rules, String title, String conversationTitle) {
        String a = Filter.normalize(title);
        String b = Filter.normalize(conversationTitle);
        for (Model.Rule rule : rules) {
            String needle = Filter.normalize(rule.text);
            if (a.contains(needle) || (!b.isEmpty() && b.contains(needle))) {
                return rule.text;
            }
        }
        return null;
    }
}
