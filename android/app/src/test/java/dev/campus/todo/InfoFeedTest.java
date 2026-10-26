package dev.campus.todo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 班级通知的按天分组：跨零点、跨天顺序、空列表都要有确定行为。 */
public class InfoFeedTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long NOW =
            OffsetDateTime.parse("2026-10-08T12:00:00+08:00").toInstant().toEpochMilli();

    private static final long HOUR = 3600_000L;

    private static Model.Todo info(long createdAt, String title) {
        Model.Todo t = new Model.Todo();
        t.id = createdAt;
        t.title = title;
        t.status = Model.STATUS_INFO;
        t.createdAt = createdAt;
        return t;
    }

    @Test
    public void groupsByDayWithHeaders() {
        // listInfo() 按时间倒序给，分组要保持这个顺序。
        List<InfoFeed.Row> rows = InfoFeed.build(Arrays.asList(
                info(NOW, "今天下午"),
                info(NOW - 2 * HOUR, "今天上午"),
                info(NOW - 26 * HOUR, "昨天的事")), NOW, ZONE);

        assertEquals(5, rows.size());
        assertTrue(rows.get(0).header);
        assertEquals("今天", rows.get(0).label);
        assertFalse(rows.get(1).header);
        assertEquals("今天下午", rows.get(1).todo.title);
        assertFalse(rows.get(2).header);
        assertTrue(rows.get(3).header);
        assertEquals("昨天", rows.get(3).label);
        assertFalse(rows.get(4).header);
    }

    @Test
    public void sameDayAppearsOnlyOnce() {
        List<InfoFeed.Row> rows = InfoFeed.build(Arrays.asList(
                info(NOW, "a"), info(NOW - HOUR, "b"), info(NOW - 5 * HOUR, "c")),
                NOW, ZONE);
        int headers = 0;
        for (InfoFeed.Row row : rows) {
            if (row.header) {
                headers++;
            }
        }
        assertEquals(1, headers);
        assertEquals(4, rows.size());
    }

    @Test
    public void olderDaysUseMonthDay() {
        List<InfoFeed.Row> rows = InfoFeed.build(Arrays.asList(
                info(NOW - 3 * 24 * HOUR, "前天的")), NOW, ZONE);
        assertEquals("10-05", rows.get(0).label);
    }

    @Test
    public void boundariesAroundMidnight() {
        // 23:30 与次日 00:30 必须分成两天。
        long late = OffsetDateTime.parse("2026-10-07T23:30:00+08:00").toInstant().toEpochMilli();
        long early = OffsetDateTime.parse("2026-10-08T00:30:00+08:00").toInstant().toEpochMilli();
        List<InfoFeed.Row> rows = InfoFeed.build(Arrays.asList(
                info(early, "凌晨"), info(late, "昨晚")), NOW, ZONE);
        assertEquals(4, rows.size());
        assertEquals("今天", rows.get(0).label);
        assertEquals("昨天", rows.get(2).label);
    }

    @Test
    public void emptyAndNullInputsAreSafe() {
        assertTrue(InfoFeed.build(null, NOW, ZONE).isEmpty());
        assertTrue(InfoFeed.build(new ArrayList<>(), NOW, ZONE).isEmpty());

        List<Model.Todo> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(info(NOW, "只有这条"));
        List<InfoFeed.Row> rows = InfoFeed.build(withNull, NOW, ZONE);
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).header);
        assertEquals("只有这条", rows.get(1).todo.title);
    }

    @Test
    public void dayLabelHandlesTodayYesterdayAndBeyond() {
        LocalDate today = LocalDate.of(2026, 10, 8);
        assertEquals("今天", InfoFeed.dayLabel(today, today));
        assertEquals("昨天", InfoFeed.dayLabel(today.minusDays(1), today));
        assertEquals("明天", InfoFeed.dayLabel(today.plusDays(1), today));
        assertEquals("10-06", InfoFeed.dayLabel(today.minusDays(2), today));
    }
}
