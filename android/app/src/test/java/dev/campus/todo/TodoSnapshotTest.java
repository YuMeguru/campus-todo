package dev.campus.todo;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 快照与设备操作的文本编解码。重点在「字段里的分隔符不能把记录拆散」。 */
public class TodoSnapshotTest {

    private static Model.Todo todo(long id, String title) {
        Model.Todo t = new Model.Todo();
        t.id = id;
        t.title = title;
        t.status = Model.STATUS_ACTIVE;
        t.source = "微信";
        t.convo = "26造价2班班级群";
        t.dueAt = 1_760_000_000_000L;
        return t;
    }

    @Test
    public void goldenSnapshotBytes() {
        // 与固件端 tests/test_campus_logic.c 的 test_golden_snapshot_parses 是同一串字节：
        // 安卓端编码、固件端解析，两边各自单测都可能通过而实际上对不上话，这串是共同锚点。
        Model.Todo t = new Model.Todo();
        t.id = 42;
        t.dueAt = 1760000000000L;
        t.snoozeUntil = 0;
        t.status = Model.STATUS_ACTIVE;
        t.source = "微信";
        t.convo = "班级群";
        t.title = "作业";

        byte[] actual = TodoSnapshot.encode(List.of(t)).getBytes(StandardCharsets.UTF_8);
        byte[] expected = ("campus-sync/1\n"
                + "T\t42\t1760000000000\t0\tactive\t微信\t班级群\t作业\n")
                .getBytes(StandardCharsets.UTF_8);

        assertEquals(66, expected.length);
        assertEquals(0x3438, BleFrame.crc16(expected));
        assertArrayEquals(expected, actual);
    }

    @Test
    public void roundTripKeepsEveryField() {
        Model.Todo t = todo(42, "提交数据结构实验报告");
        t.status = Model.STATUS_PENDING;
        t.snoozeUntil = 1_760_000_500_000L;

        TodoSnapshot.Decoded decoded = TodoSnapshot.decode(TodoSnapshot.encode(List.of(t)));
        assertTrue(decoded.error, decoded.ok());
        assertEquals(1, decoded.todos.size());

        Model.Todo back = decoded.todos.get(0);
        assertEquals(42, back.id);
        assertEquals("提交数据结构实验报告", back.title);
        assertEquals(Model.STATUS_PENDING, back.status);
        assertEquals("微信", back.source);
        assertEquals("26造价2班班级群", back.convo);
        assertEquals(1_760_000_000_000L, back.dueAt);
        assertEquals(1_760_000_500_000L, back.snoozeUntil);
    }

    @Test
    public void tabsAndNewlinesInsideFieldsCannotSplitARecord() {
        // 这是这个文本格式唯一的硬约束：公告里带换行/制表符很常见。
        Model.Todo t = todo(7, "第一行\n第二行\t带制表符");
        String wire = TodoSnapshot.encode(List.of(t));

        TodoSnapshot.Decoded decoded = TodoSnapshot.decode(wire);
        assertTrue(decoded.ok());
        assertEquals(1, decoded.todos.size());
        assertEquals(0, decoded.skipped);
        assertEquals("第一行 第二行 带制表符", decoded.todos.get(0).title);
    }

    @Test
    public void missingHeaderIsAnError() {
        TodoSnapshot.Decoded decoded = TodoSnapshot.decode("T\t1\t0\t0\tactive\t微信\t群\t标题\n");
        assertFalse(decoded.ok());
        assertNotNull(decoded.error);
        assertEquals(0, decoded.todos.size());
    }

    @Test
    public void emptyInputIsAnError() {
        assertFalse(TodoSnapshot.decode("").ok());
        assertFalse(TodoSnapshot.decode(null).ok());
    }

    @Test
    public void malformedLinesAreCountedNotSilentlySwallowed() {
        String wire = TodoSnapshot.HEADER + "\n"
                + "T\t1\t0\t0\tactive\t微信\t群\t好的\n"
                + "T\t不是数字\t0\t0\tactive\t微信\t群\t坏的\n"
                + "乱七八糟\n"
                + "T\t2\t0\t0\tactive\t钉钉\t群\t也好\n";
        TodoSnapshot.Decoded decoded = TodoSnapshot.decode(wire);
        assertTrue(decoded.ok());
        assertEquals(2, decoded.todos.size());
        assertEquals(2, decoded.skipped);
    }

    @Test
    public void emptyFieldsSurviveTheSplit() {
        // 用 split(..., -1) 才能保住尾部空字段；否则「标题为空」会被当成字段不够而丢掉。
        Model.Todo t = new Model.Todo();
        t.id = 9;
        String wire = TodoSnapshot.encode(List.of(t));
        TodoSnapshot.Decoded decoded = TodoSnapshot.decode(wire);
        assertTrue(decoded.ok());
        assertEquals(1, decoded.todos.size());
        assertEquals(9, decoded.todos.get(0).id);
        assertEquals("", decoded.todos.get(0).title);
    }

    @Test
    public void longTitlesAreClippedForTheDeviceScreen() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            big.append('字');
        }
        TodoSnapshot.Decoded decoded =
                TodoSnapshot.decode(TodoSnapshot.encode(List.of(todo(1, big.toString()))));
        assertEquals(120, decoded.todos.get(0).title.length());
    }

    @Test
    public void emptySnapshotIsStillAValidHeader() {
        TodoSnapshot.Decoded decoded = TodoSnapshot.decode(TodoSnapshot.encode(new ArrayList<>()));
        assertTrue(decoded.ok());
        assertEquals(0, decoded.todos.size());
        assertEquals(0, decoded.skipped);
    }

    @Test
    public void opRoundTrip() {
        String wire = TodoSnapshot.encodeOp(42, Model.DeviceOp.SNOOZE, 30, 7);
        Model.DeviceOp op = TodoSnapshot.decodeOp(wire);
        assertNotNull(op);
        assertEquals(42, op.todoId);
        assertEquals(Model.DeviceOp.SNOOZE, op.action);
        assertEquals(30, op.snoozeMinutes);
        assertEquals(7, op.devSeq);
    }

    @Test
    public void badOpsReturnNullInsteadOfThrowing() {
        assertNull(TodoSnapshot.decodeOp(null));
        assertNull(TodoSnapshot.decodeOp(""));
        assertNull(TodoSnapshot.decodeOp("42\t完成\t三十\t7"));   // 稍后分钟数不是数字
        assertNull(TodoSnapshot.decodeOp("42\t完成\t30"));       // 字段不够
        assertNull(TodoSnapshot.decodeOp("42\t\t30\t7"));       // 动作为空
    }

    @Test
    public void nullTodosAreSkippedWhenEncoding() {
        List<Model.Todo> list = new ArrayList<>();
        list.add(null);
        list.add(todo(1, "只有这条"));
        TodoSnapshot.Decoded decoded = TodoSnapshot.decode(TodoSnapshot.encode(list));
        assertEquals(1, decoded.todos.size());
    }
}
