package dev.campus.todo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 编辑之后的状态迁移。
 *
 * 这是「待确认」唯一的出口，所以它的规则必须被钉住 —— 一旦被写错，
 * 用户就再也无法把模型不敢确定的条目变成正式待办，而且不会有任何报错。
 */
public class TodoStateTest {

    private static final long SOME_TIME = 1_760_000_000_000L;

    private static Model.Todo withStatus(String status, long dueAt) {
        Model.Todo t = new Model.Todo();
        t.status = status;
        t.dueAt = dueAt;
        return t;
    }

    @Test
    public void pendingBecomesActiveOnceItHasATime() {
        assertEquals(Model.STATUS_ACTIVE,
                withStatus(Model.STATUS_PENDING, 0).statusAfterEdit(SOME_TIME));
    }

    @Test
    public void pendingWithoutTimeStaysPending() {
        // 只改了标题、没给时间：仍然留在待确认，不该偷偷变成正式待办去提醒。
        assertEquals(Model.STATUS_PENDING,
                withStatus(Model.STATUS_PENDING, 0).statusAfterEdit(0));
    }

    @Test
    public void pendingKeepsExistingTimeWhenReEdited() {
        assertEquals(Model.STATUS_ACTIVE,
                withStatus(Model.STATUS_PENDING, SOME_TIME).statusAfterEdit(SOME_TIME));
        // 但把时间清掉，就该退回待确认 —— 否则它会带着 0 时间留在 active，永远不提醒也不再被看见。
        assertEquals(Model.STATUS_PENDING,
                withStatus(Model.STATUS_PENDING, SOME_TIME).statusAfterEdit(0));
    }

    @Test
    public void activeStaysActiveWhenEdited() {
        assertEquals(Model.STATUS_ACTIVE,
                withStatus(Model.STATUS_ACTIVE, SOME_TIME).statusAfterEdit(SOME_TIME + 1));
        // 改标题顺手清掉时间也不该把它打成待确认：它是用户自己确认过的事。
        assertEquals(Model.STATUS_ACTIVE,
                withStatus(Model.STATUS_ACTIVE, SOME_TIME).statusAfterEdit(0));
    }

    @Test
    public void doneIsNotResurrectedByEditing() {
        assertEquals(Model.STATUS_DONE,
                withStatus(Model.STATUS_DONE, SOME_TIME).statusAfterEdit(SOME_TIME));
    }

    @Test
    public void infoNeverTurnsIntoATodo() {
        // 班级通知区没有编辑入口，但规则本身也不能把它变成待办。
        assertEquals(Model.STATUS_INFO,
                withStatus(Model.STATUS_INFO, 0).statusAfterEdit(SOME_TIME));
    }

    @Test
    public void predicatesMatchTheStatuses() {
        assertEquals(true, withStatus(Model.STATUS_PENDING, 0).isPending());
        assertEquals(true, withStatus(Model.STATUS_INFO, 0).isInfo());
        assertEquals(true, withStatus(Model.STATUS_ACTIVE, 0).isOpen());
        assertEquals(false, withStatus(Model.STATUS_DONE, 0).isOpen());
    }
}
