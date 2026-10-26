package dev.campus.todo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 采集规则的判定顺序与匹配方式。
 *
 * 这里钉住三件事：黑名单一票否决、白名单是「只收这些」而不是「额外也收」、
 * 归一化后子串匹配（全角半角与空格差异不该影响命中）。
 */
public class RulesTest {

    private static Model.Rule rule(String mode, String text) {
        Model.Rule r = new Model.Rule();
        r.mode = mode;
        r.text = text;
        return r;
    }

    private static List<Model.Rule> rules(Model.Rule... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    @Test
    public void noRulesMeansEverythingPasses() {
        assertNull(Rules.decide(null, "计科2201班群", null));
        assertNull(Rules.decide(new ArrayList<>(), "计科2201班群", null));
    }

    @Test
    public void denyMatchesBySubstring() {
        List<Model.Rule> r = rules(rule(Model.Rule.DENY, "班群"));
        assertNotNull(Rules.decide(r, "计科2201班群", null));
        assertNull(Rules.decide(r, "辅导员", null));
    }

    @Test
    public void denyIgnoresWidthAndSpaces() {
        List<Model.Rule> r = rules(rule(Model.Rule.DENY, "计科 2201"));
        // 规则里带空格、通知里不带；全角数字也要能对上。
        assertNotNull(Rules.decide(r, "计科2201班群", null));
        assertNotNull(Rules.decide(r, "计科２２０１班群", null));
    }

    @Test
    public void denyMatchesConversationTitleToo() {
        List<Model.Rule> r = rules(rule(Model.Rule.DENY, "辅导员"));
        // 标题是群名、会话名才是联系人：两个字段都要参与匹配。
        assertNotNull(Rules.decide(r, "计科2201班群", "辅导员"));
        assertNull(Rules.decide(r, "计科2201班群", "班长"));
    }

    @Test
    public void denyNeverMatchesAcrossTheTwoFields() {
        // 拼接后再匹配会让「XX群」+「主」拼出「群主」，这里必须不命中。
        List<Model.Rule> r = rules(rule(Model.Rule.DENY, "群主"));
        assertNull(Rules.decide(r, "计科班", "群"));
    }

    @Test
    public void nonEmptyAllowListRestrictsToMatches() {
        List<Model.Rule> r = rules(rule(Model.Rule.ALLOW, "辅导员"));
        assertNull(Rules.decide(r, "辅导员", null));
        assertNotNull(Rules.decide(r, "计科2201班群", null));
    }

    @Test
    public void denyWinsOverAllow() {
        // 两个列表都命中时，黑名单说话。
        List<Model.Rule> r = rules(
                rule(Model.Rule.ALLOW, "班群"),
                rule(Model.Rule.DENY, "计科2201班群"));
        String reason = Rules.decide(r, "计科2201班群", null);
        assertNotNull(reason);
        assertEquals(true, reason.startsWith("黑名单"));
    }

    @Test
    public void blankRulesAreIgnored() {
        // 一条空白的黑名单不能把所有消息都干掉。
        List<Model.Rule> r = rules(rule(Model.Rule.DENY, "   "));
        assertNull(Rules.decide(r, "计科2201班群", null));
        // 空白的白名单同样不能把「只收这些」变成「什么都不收」。
        List<Model.Rule> r2 = rules(rule(Model.Rule.ALLOW, ""));
        assertNull(Rules.decide(r2, "计科2201班群", null));
    }

    @Test
    public void reasonNamesTheRuleThatFired() {
        List<Model.Rule> r = rules(rule(Model.Rule.DENY, "班群"));
        String reason = Rules.decide(r, "计科2201班群", null);
        assertNotNull(reason);
        assertEquals(true, reason.contains("班群"));
    }
}
