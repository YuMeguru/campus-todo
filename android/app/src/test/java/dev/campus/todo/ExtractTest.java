package dev.campus.todo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Extract 的硬校验。这里测的不是“模型会不会做对”，而是“模型做错时我们会不会跟着错”。
 */
public class ExtractTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long NOW =
            OffsetDateTime.parse("2026-10-08T12:00:00+08:00").toInstant().toEpochMilli();

    private static final String SOURCE =
            "班群-计科2201：请在10月9日23:59前提交数据结构实验报告，迟交扣分\n"
                    + "辅导员：明天下午两点在A301开班会，请准时参加";

    /** 真实形态的群公告：一条通知里塞了好几个班的安排，而且没有截止时间。 */
    private static final String SOURCE_INFO =
            "@所有人 各位小伙伴，本周晚自习教室安排如下：\n"
                    + "26室内设计3班（单）至勤楼224\n"
                    + "26造价2班 至勤楼311\n"
                    + "26造价3班 至勤楼313\n"
                    + "26室内设计中外1班至勤楼320";

    @Test
    public void validItemIsAcceptedWithAbsoluteDue() {
        String json = """
                {"items":[{"src":1,"title":"提交数据结构实验报告","course":"数据结构",
                "kind":"homework","due_text":"本周五23:59前",
                "due_iso":"2026-10-09T23:59:00+08:00",
                "evidence":"请在10月9日23:59前提交数据结构实验报告","confidence":0.82}]}
                """;
        Extract.Result r = Extract.parse(json, SOURCE, NOW, ZONE);
        assertTrue(r.error, r.ok());
        assertEquals(1, r.accepted.size());
        assertEquals(0, r.pending.size());
        assertEquals("提交数据结构实验报告", r.accepted.get(0).title);
        assertEquals(OffsetDateTime.parse("2026-10-09T23:59:00+08:00").toInstant().toEpochMilli(),
                r.accepted.get(0).dueAt);
        assertEquals(Model.STATUS_ACTIVE, r.accepted.get(0).status);
    }

    @Test
    public void evidenceMissingFromSourceIsDropped() {
        // 这条是编的：原文里根本没有“明天交实验报告”这句话。
        String json = """
                {"items":[{"src":1,"title":"交实验报告","kind":"homework","due_text":"明天",
                "due_iso":"2026-10-09T08:00:00+08:00","evidence":"明天交实验报告","confidence":0.95}]}
                """;
        Extract.Result r = Extract.parse(json, SOURCE, NOW, ZONE);
        assertTrue(r.ok());
        assertEquals(0, r.total());
        assertEquals(1, r.dropped);
    }

    @Test
    public void evidenceMatchingIgnoresWhitespaceAndWidth() {
        String json = """
                {"items":[{"src":1,"title":"提交报告","kind":"homework","due_text":"10月9日23:59",
                "due_iso":"2026-10-09T23:59:00+08:00",
                "evidence":"请在 10月9日 23:59 前提交数据结构实验报告","confidence":0.9}]}
                """;
        Extract.Result r = Extract.parse(json, SOURCE, NOW, ZONE);
        assertTrue(r.error, r.ok());
        assertEquals(1, r.accepted.size());
    }

    @Test
    public void lowConfidenceGoesPendingNotActive() {
        String json = """
                {"items":[{"src":1,"title":"提交数据结构实验报告","kind":"homework","due_text":"10月9日",
                "due_iso":"2026-10-09T23:59:00+08:00",
                "evidence":"提交数据结构实验报告","confidence":0.3}]}
                """;
        Extract.Result r = Extract.parse(json, SOURCE, NOW, ZONE);
        assertEquals(0, r.accepted.size());
        assertEquals(1, r.pending.size());
        assertEquals(Model.STATUS_PENDING, r.pending.get(0).status);
    }

    @Test
    public void nullDueGoesPendingAndKeepsOriginalWording() {
        String json = """
                {"items":[{"src":2,"title":"开班会","kind":"notice","due_text":"明天下午两点",
                "due_iso":null,"evidence":"明天下午两点在A301开班会","confidence":0.9}]}
                """;
        Extract.Result r = Extract.parse(json, SOURCE, NOW, ZONE);
        assertEquals(1, r.pending.size());
        assertEquals(0, r.pending.get(0).dueAt);
        assertEquals("明天下午两点", r.pending.get(0).dueText);
    }

    @Test
    public void outOfRangeDueIsCleared() {
        String json = """
                {"items":[{"src":1,"title":"提交数据结构实验报告","kind":"homework","due_text":"",
                "due_iso":"2030-01-01T00:00:00+08:00",
                "evidence":"提交数据结构实验报告","confidence":0.9}]}
                """;
        Extract.Result r = Extract.parse(json, SOURCE, NOW, ZONE);
        assertEquals(0, r.accepted.size());
        assertEquals(1, r.pending.size());
        assertEquals(0, r.pending.get(0).dueAt);
    }

    @Test
    public void relativeWordingConflictClearsDue() {
        // 原文说“明天”，模型却推到了 10-15：冲突，降级为待确认。
        String json = """
                {"items":[{"src":2,"title":"开班会","kind":"notice","due_text":"明天下午两点",
                "due_iso":"2026-10-15T14:00:00+08:00",
                "evidence":"明天下午两点在A301开班会","confidence":0.95}]}
                """;
        Extract.Result r = Extract.parse(json, SOURCE, NOW, ZONE);
        assertEquals(0, r.accepted.size());
        assertEquals(1, r.pending.size());
    }

    @Test
    public void relativeWordingAgreementIsKept() {
        String json = """
                {"items":[{"src":2,"title":"开班会","kind":"notice","due_text":"明天下午两点",
                "due_iso":"2026-10-09T14:00:00+08:00",
                "evidence":"明天下午两点在A301开班会","confidence":0.95}]}
                """;
        Extract.Result r = Extract.parse(json, SOURCE, NOW, ZONE);
        assertEquals(1, r.accepted.size());
        assertEquals(0, r.pending.size());
    }

    @Test
    public void unknownKindFallsBackToOther() {
        String json = """
                {"items":[{"src":1,"title":"提交数据结构实验报告","kind":"闲聊","due_text":"",
                "due_iso":"2026-10-09T23:59:00+08:00",
                "evidence":"提交数据结构实验报告","confidence":0.9}]}
                """;
        Extract.Result r = Extract.parse(json, SOURCE, NOW, ZONE);
        assertEquals("other", r.accepted.get(0).kind);
    }

    @Test
    public void emptyContentIsAnErrorNotAnEmptyResult() {
        // 官方文档说 JSON Output 偶尔返回空 content —— 这必须被当成失败。
        Extract.Result r = Extract.parse("", SOURCE, NOW, ZONE);
        assertFalse(r.ok());
        assertNotNull(r.error);
        assertEquals(0, r.total());
    }

    @Test
    public void nonJsonIsAnError() {
        Extract.Result r = Extract.parse("好的，我整理好了", SOURCE, NOW, ZONE);
        assertFalse(r.ok());
    }

    @Test
    public void missingItemsArrayIsAnError() {
        Extract.Result r = Extract.parse("{\"ignored\":[]}", SOURCE, NOW, ZONE);
        assertFalse(r.ok());
    }

    @Test
    public void itemsBeyondLimitAreCountedNotSilentlyIgnored() {
        StringBuilder items = new StringBuilder();
        StringBuilder source = new StringBuilder();
        for (int i = 1; i <= 25; i++) {
            if (i > 1) {
                items.append(',');
            }
            items.append("{\"src\":1,\"title\":\"作业").append(i)
                    .append("提交\",\"kind\":\"homework\",\"due_text\":\"\",")
                    .append("\"due_iso\":\"2026-10-09T23:59:00+08:00\",")
                    .append("\"evidence\":\"作业").append(i)
                    .append("提交\",\"confidence\":0.9}");
            source.append("作业").append(i).append("提交 ");
        }
        Extract.Result r = Extract.parse("{\"items\":[" + items + "]}", source.toString(), NOW, ZONE);
        assertTrue(r.error, r.ok());
        assertEquals(Extract.MAX_ITEMS, r.total());
        assertEquals(5, r.overLimit);
    }

    @Test
    public void relativeDateIsResolvedAgainstArrivalTimeNotOrganizeTime() {
        // 10-06 收到“明天下午两点开班会”，10-08 才点整理：正确答案是 10-07。
        String source = "辅导员：明天下午两点在A301开班会";
        long arrived = OffsetDateTime.parse("2026-10-06T09:00:00+08:00").toInstant().toEpochMilli();
        String json = """
                {"items":[{"src":1,"title":"开班会","kind":"notice","due_text":"明天下午两点",
                "due_iso":"2026-10-07T14:00:00+08:00",
                "evidence":"明天下午两点在A301开班会","confidence":0.95}]}
                """;
        // 传通知到达时间 → 10-07 就是“明天”，接受。
        Extract.Result ok = Extract.parse(json, source, NOW, ZONE, new long[]{arrived});
        assertTrue(ok.error, ok.ok());
        assertEquals(1, ok.accepted.size());

        // 若仍按整理时刻（NOW = 10-08）判，10-07 会被误判成冲突而降级 —— 这正是修复前的行为。
        Extract.Result old = Extract.parse(json, source, NOW, ZONE, new long[]{NOW});
        assertEquals(0, old.accepted.size());
        assertEquals(1, old.pending.size());
    }

    @Test
    public void relativeDaySignalSurvivesEmptyDueText() {
        // 模型把 due_text 留空、日期给错：只信 due_text 的实现会直接放行，
        // 现在相对日信号同时看 evidence，而 evidence 必须逐字出现在原文里。
        String json = """
                {"items":[{"src":2,"title":"开班会","kind":"notice","due_text":"",
                "due_iso":"2026-10-20T14:00:00+08:00",
                "evidence":"明天下午两点在A301开班会","confidence":0.95}]}
                """;
        Extract.Result r = Extract.parse(json, SOURCE, NOW, ZONE);
        assertTrue(r.error, r.ok());
        assertEquals(0, r.accepted.size());
        assertEquals(1, r.pending.size());
    }

    @Test
    public void infoItemIsNeitherTodoNorPending() {
        // 这条就是「本周晚自习教室安排」那种消息：不是要做的事，也不是「待确认」。
        String json = """
                {"items":[{"src":1,"title":"本周晚自习教室：至勤楼311","kind":"info",
                "due_text":"本周","due_iso":null,
                "evidence":"26造价2班 至勤楼311","confidence":0.9}]}
                """;
        Extract.Result r = Extract.parse(json, SOURCE_INFO, NOW, ZONE);
        assertTrue(r.error, r.ok());
        assertEquals(0, r.accepted.size());
        assertEquals(0, r.pending.size());
        assertEquals(1, r.info.size());
        assertEquals(Model.STATUS_INFO, r.info.get(0).status);
        assertEquals(0, r.info.get(0).dueAt);
        assertEquals(1, r.total());
    }

    @Test
    public void infoSkipsTheConfidenceGate() {
        // 参考信息天生“不像待办”，置信度低也不该被降级或丢掉。
        String json = """
                {"items":[{"src":1,"title":"本周晚自习教室：至勤楼311","kind":"info",
                "due_text":"","due_iso":null,
                "evidence":"26造价2班 至勤楼311","confidence":0.1}]}
                """;
        Extract.Result r = Extract.parse(json, SOURCE_INFO, NOW, ZONE);
        assertEquals(1, r.info.size());
        assertEquals(0, r.pending.size());
        assertEquals(0, r.dropped);
    }

    @Test
    public void infoStillPassesTheEvidenceGate() {
        // 编造的 info 一样要丢：防幻觉闸门对参考信息同样生效。
        String json = """
                {"items":[{"src":1,"title":"本周晚自习教室：至勤楼999","kind":"info",
                "due_text":"","due_iso":null,
                "evidence":"26造价9班 至勤楼999","confidence":0.9}]}
                """;
        Extract.Result r = Extract.parse(json, SOURCE_INFO, NOW, ZONE);
        assertEquals(0, r.info.size());
        assertEquals(1, r.dropped);
        assertEquals(0, r.total());
    }

    @Test
    public void parseDueAcceptsAllThreeShapes() {
        assertEquals(OffsetDateTime.parse("2026-10-09T23:59:00+08:00").toInstant().toEpochMilli(),
                Extract.parseDue("2026-10-09T23:59:00+08:00", ZONE));
        assertEquals(OffsetDateTime.parse("2026-10-09T23:59:00+08:00").toInstant().toEpochMilli(),
                Extract.parseDue("2026-10-09T23:59:00", ZONE));
        assertEquals(OffsetDateTime.parse("2026-10-09T00:00:00+08:00").toInstant().toEpochMilli(),
                Extract.parseDue("2026-10-09", ZONE));
        assertEquals(0, Extract.parseDue("下周三前", ZONE));
        assertEquals(0, Extract.parseDue(null, ZONE));
    }
}
