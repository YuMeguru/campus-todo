package dev.campus.todo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongConsumer;

/** 前端主界面：状态 → 一键整理 → 待办列表 → 详情操作。 */
public final class MainActivity extends Activity {

    private static final int REQ_NOTIFICATIONS = 1001;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextView statusView;
    private TextView resultView;
    private Button organizeButton;
    private Button infoButton;
    private Button archiveButton;
    private TodoAdapter adapter;
    /** 详情弹窗。操作用自绘按钮行，所以需要留着引用才能在动作后关掉它。 */
    private AlertDialog detailDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        Reminder.ensureChannel(this);
        askNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }

    // ------------------------------------------------------------------ 界面

    private View buildUi() {
        final int pad = Ui.dp(this, 18);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(pad, pad, pad, Ui.dp(this, 10));
        header.addView(Ui.text(this, "校园待办", 24, Ui.TEXT, true));
        statusView = Ui.text(this, "", 13, Ui.MUTED, false);
        statusView.setPadding(0, Ui.dp(this, 4), 0, 0);
        header.addView(statusView);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        organizeButton = Ui.button(this, "一键整理待办");
        organizeButton.setTextSize(17);
        organizeButton.setOnClickListener(v -> onOrganizeClicked());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56));
        buttonParams.setMargins(pad, 0, pad, Ui.dp(this, 8));
        root.addView(organizeButton, buttonParams);

        resultView = Ui.text(this, "", 13, Ui.MUTED, false);
        resultView.setPadding(pad, 0, pad, Ui.dp(this, 8));
        root.addView(resultView);

        ListView list = new ListView(this);
        list.setBackgroundColor(Ui.BG);
        list.setDivider(null);
        list.setDividerHeight(Ui.dp(this, 8));
        list.setPadding(pad, 0, pad, Ui.dp(this, 8));
        list.setClipToPadding(false);
        adapter = new TodoAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) ->
                showDetail((Model.Todo) adapter.getItem(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            toggleDone((Model.Todo) adapter.getItem(position));
            return true;
        });

        TextView empty = Ui.text(this,
                "还没有待办。\n到设置里插入几条示例消息，或先开通知使用权，\n然后点上面的「一键整理待办」。",
                14, Ui.MUTED, false);
        empty.setGravity(Gravity.CENTER);
        list.setEmptyView(empty);

        FrameLayout listArea = new FrameLayout(this);
        listArea.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listArea.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(listArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(pad, 0, pad, pad);

        infoButton = Ui.button(this, "班级通知");
        infoButton.setTextSize(13);
        infoButton.setOnClickListener(v -> startActivity(new Intent(this, InfoActivity.class)));
        Button rawButton = Ui.button(this, "原始消息");
        rawButton.setTextSize(13);
        rawButton.setOnClickListener(v -> startActivity(new Intent(this, RawListActivity.class)));
        archiveButton = Ui.button(this, "归档");
        archiveButton.setTextSize(13);
        archiveButton.setOnClickListener(v -> startActivity(new Intent(this, ArchiveActivity.class)));
        Button settingsButton = Ui.button(this, "设置");
        settingsButton.setTextSize(13);
        settingsButton.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        final int gap = Ui.dp(this, 4);
        Button[] row = {infoButton, rawButton, archiveButton, settingsButton};
        for (int i = 0; i < row.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(i == 0 ? 0 : gap, 0, i == row.length - 1 ? 0 : gap, 0);
            bottom.addView(row[i], lp);
        }
        root.addView(bottom);

        return root;
    }

    private void refresh() {
        Db db = Db.get(this);
        // 每次回到列表都顺手归档一次：没有常驻后台服务，只能靠这种时机点。
        // 归档只是改状态，不会丢数据 —— 但会改变 listTodos 的结果，所以要放在查询前面。
        db.archiveDue(System.currentTimeMillis(), Db.ARCHIVE_WINDOW);
        List<Model.Todo> todos = db.listTodos();
        adapter.setData(todos);

        long[] counts = db.homeCounts();
        int waiting = (int) counts[0];
        int infoCount = (int) counts[1];
        long[] usage = db.todayUsage();
        int open = 0;
        for (Model.Todo t : todos) {
            if (!Model.STATUS_DONE.equals(t.status)) {
                open++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("未完成 ").append(open).append(" 条 · 待整理消息 ").append(waiting).append(" 条");
        sb.append(" · 今日调用 ").append(usage[0]).append('/').append(Organizer.DAILY_REQUESTS);
        if (!KeyVault.has(this)) {
            sb.append(" · 未设置 API Key");
        }
        sb.append(" · 自动整理 ").append(AutoOrganizer.label(Prefs.autoIntervalMinutes(this)));
        statusView.setText(sb.toString());

        infoButton.setText(infoCount > 0 ? "班级通知 " + infoCount : "班级通知");

        int archived = db.countArchived();
        archiveButton.setText(archived > 0 ? "归档 " + archived : "归档");
    }

    // ------------------------------------------------------- 一键整理（核心）

    private void onOrganizeClicked() {
        if (Prefs.confirmBeforeSend(this)) {
            String payload = Organizer.preview(this, System.currentTimeMillis());
            if (payload.isEmpty()) {
                Toast.makeText(this, "没有需要整理的新消息", Toast.LENGTH_SHORT).show();
                return;
            }
            ScrollView scroller = new ScrollView(this);
            TextView preview = Ui.text(this, payload, 12, Ui.MUTED, false);
            preview.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 12));
            scroller.addView(preview);
            new AlertDialog.Builder(this)
                    .setTitle("将要发送给 DeepSeek 的内容")
                    .setView(scroller)
                    .setPositiveButton("发送", (d, w) -> runOrganize())
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        runOrganize();
    }

    private void runOrganize() {
        organizeButton.setEnabled(false);
        organizeButton.setText("整理中…");
        resultView.setText("正在调用 DeepSeek 提炼待办…");

        final long now = System.currentTimeMillis();
        worker.execute(() -> {
            Organizer.Summary summary = Organizer.run(getApplicationContext(), now);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                organizeButton.setEnabled(true);
                organizeButton.setText("一键整理待办");
                resultView.setText(describe(summary));
                refresh();
            });
        });
    }

    private String describe(Organizer.Summary s) {
        if (s.error != null) {
            return "⚠ " + s.error;
        }
        if (s.collected == 0) {
            return "没有需要整理的新消息。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("已整理 ").append(s.collected).append(" 条消息：新增待办 ")
                .append(s.accepted).append(" 条");
        if (s.pending > 0) {
            sb.append("，待确认 ").append(s.pending).append(" 条");
        }
        if (s.info > 0) {
            sb.append("，班级通知 ").append(s.info).append(" 条");
            if (s.kept > 0) {
                sb.append("（其中 ").append(s.kept).append(" 条是本地兜底保留）");
            }
        }
        if (s.dropped > 0) {
            sb.append("，丢弃无原文依据 ").append(s.dropped).append(" 条");
        }
        if (s.skipped > 0) {
            sb.append("，重复跳过 ").append(s.skipped).append(" 条");
        }
        if (s.remaining > 0) {
            sb.append("；还有 ").append(s.remaining).append(" 条未整理");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- 详情操作

    private void showDetail(Model.Todo todo) {
        final int pad = Ui.dp(this, 18);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);

        box.addView(Ui.text(this, todo.title, 19, Ui.TEXT, true));
        box.addView(Ui.text(this,
                Ui.statusLabel(todo) + " · " + Ui.kindLabel(todo.kind)
                        + (todo.source.isEmpty() ? "" : " · 来源 " + todo.source),
                12, Ui.statusColor(todo), true));

        box.addView(Ui.section(this, "截止"));
        box.addView(Ui.text(this, Ui.dueLabel(todo)
                + (todo.dueText.isEmpty() ? "" : "（原文：“" + todo.dueText + "”）"), 15, Ui.TEXT, false));

        if (todo.snoozeUntil > System.currentTimeMillis()) {
            box.addView(Ui.text(this, "已设为 " + Ui.timeLabel(todo.snoozeUntil) + " 再提醒一次（截止时间不变）",
                    13, Ui.WARN, false));
        }

        if (!todo.course.isEmpty()) {
            box.addView(Ui.section(this, "课程"));
            box.addView(Ui.text(this, todo.course, 15, Ui.TEXT, false));
        }

        box.addView(Ui.section(this, "原文依据"));
        box.addView(Ui.text(this, todo.evidence, 13, Ui.MUTED, false));

        box.addView(Ui.section(this, "模型置信度"));
        box.addView(Ui.text(this, Math.round(todo.confidence * 100) + "%", 15, Ui.TEXT, false));

        if (todo.isPending()) {
            box.addView(Ui.section(this, "为什么是待确认"));
            box.addView(Ui.text(this,
                    "时间没推断出来，或者置信度低于 " + Math.round(Extract.MIN_CONFIDENCE * 100)
                            + "%。这种不会自动提醒。\n"
                            + "给它一个确定的时间，它就会变成正式待办并开始提醒。",
                    13, Ui.WARN, false));
        }

        // 操作用自绘按钮行，不用 AlertDialog 的三个按钮槽：
        // 待办需要「完成 / 编辑 / 稍后 / 删除」四个动作，三个槽位根本放不下。
        box.addView(Ui.section(this, "操作"));
        final boolean done = Model.STATUS_DONE.equals(todo.status);

        LinearLayout firstRow = actionRow();
        firstRow.addView(actionButton(done ? "标记未完成" : "完成", v -> {
            dismissDialog();
            toggleDone(todo);
        }));
        firstRow.addView(actionButton(todo.isPending() ? "确认并设定时间" : "编辑",
                v -> {
                    dismissDialog();
                    showEdit(todo);
                }));
        box.addView(firstRow);

        LinearLayout secondRow = actionRow();
        secondRow.addView(actionButton("稍后 1 小时", v -> {
            dismissDialog();
            snooze(todo);
        }));
        secondRow.addView(actionButton("删除", v -> {
            dismissDialog();
            remove(todo);
        }));
        box.addView(secondRow);

        ScrollView scroller = new ScrollView(this);
        scroller.addView(box, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        detailDialog = new AlertDialog.Builder(this)
                .setView(scroller)
                .setNegativeButton("关闭", null)
                .create();
        detailDialog.show();
    }

    private LinearLayout actionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, Ui.dp(this, 4));
        row.setLayoutParams(params);
        return row;
    }

    private Button actionButton(String label, View.OnClickListener listener) {
        Button button = Ui.button(this, label);
        button.setTextSize(14);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
        button.setLayoutParams(params);
        return button;
    }

    private void dismissDialog() {
        if (detailDialog != null && detailDialog.isShowing()) {
            detailDialog.dismiss();
        }
    }

    /**
     * 编辑待办。这也是「待确认」唯一的出口：给它一个确定时间，它就从待确认变成正式待办。
     */
    private void showEdit(final Model.Todo todo) {
        final int pad = Ui.dp(this, 18);
        final long[] due = { todo.dueAt };

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);

        if (todo.isPending()) {
            box.addView(Ui.text(this,
                    "这条现在是「待确认」。给它一个确定的时间，它就会变成正式待办并开始提醒；"
                            + "保持不设定时间则继续留在待确认。",
                    12, Ui.WARN, false));
        }

        box.addView(Ui.section(this, "标题"));
        final EditText titleField = Ui.input(this, "要做什么");
        titleField.setText(todo.title);
        box.addView(titleField);

        box.addView(Ui.section(this, "截止时间"));
        final TextView dueValue = Ui.text(this, dueValueLabel(due[0]), 14, Ui.TEXT, false);
        box.addView(dueValue);

        LinearLayout dueRow = actionRow();
        dueRow.addView(actionButton("选择时间", v -> pickDateTime(due[0], picked -> {
            due[0] = picked;
            dueValue.setText(dueValueLabel(picked));
        })));
        dueRow.addView(actionButton("清除时间", v -> {
            due[0] = 0;
            dueValue.setText(dueValueLabel(0));
        }));
        box.addView(dueRow);

        box.addView(Ui.section(this, "课程"));
        final EditText courseField = Ui.input(this, "可留空");
        courseField.setText(todo.course);
        box.addView(courseField);

        ScrollView scroller = new ScrollView(this);
        scroller.addView(box, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(todo.isPending() ? "确认这条待办" : "编辑待办")
                .setView(scroller)
                .setPositiveButton("保存", (d, w) -> saveEdit(todo,
                        titleField.getText().toString(),
                        courseField.getText().toString(),
                        due[0]))
                .setNegativeButton("取消", null)
                .show();
    }

    private String dueValueLabel(long millis) {
        return millis > 0 ? Ui.timeLabel(millis) : "未设定（会留在待确认）";
    }

    /** 先选日期再选时间；原来是待确认（没有时间）时，默认落到当天 23:59。 */
    private void pickDateTime(long current, LongConsumer onPicked) {
        final Calendar seed = Calendar.getInstance();
        if (current > 0) {
            seed.setTimeInMillis(current);
        }

        new DatePickerDialog(this, (view, year, month, day) -> {
            final Calendar picked = Calendar.getInstance();
            picked.set(year, month, day);
            if (current > 0) {
                picked.set(Calendar.HOUR_OF_DAY, seed.get(Calendar.HOUR_OF_DAY));
                picked.set(Calendar.MINUTE, seed.get(Calendar.MINUTE));
            } else {
                picked.set(Calendar.HOUR_OF_DAY, 23);
                picked.set(Calendar.MINUTE, 59);
            }
            new TimePickerDialog(this, (timeView, hour, minute) -> {
                picked.set(Calendar.HOUR_OF_DAY, hour);
                picked.set(Calendar.MINUTE, minute);
                picked.set(Calendar.SECOND, 0);
                picked.set(Calendar.MILLISECOND, 0);
                onPicked.accept(picked.getTimeInMillis());
            }, picked.get(Calendar.HOUR_OF_DAY), picked.get(Calendar.MINUTE), true).show();
        }, seed.get(Calendar.YEAR), seed.get(Calendar.MONTH), seed.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void saveEdit(Model.Todo todo, String rawTitle, String rawCourse, long dueAt) {
        String title = Filter.clean(rawTitle).trim();
        if (title.isEmpty()) {
            Toast.makeText(this, "标题不能为空，没保存", Toast.LENGTH_SHORT).show();
            return;
        }
        String course = Filter.clean(rawCourse).trim();
        if (course.length() > 20) {
            course = course.substring(0, 20);
        }

        // 唯一的升级路径：待确认 + 有了确定时间 = 正式待办。规则在 Model 里，有单测。
        String status = todo.statusAfterEdit(dueAt);

        Db.get(this).updateTodo(todo.id, title, course, dueAt, status);

        todo.title = title;
        todo.course = course;
        todo.dueAt = dueAt;
        todo.status = status;

        // 时间或状态变了，旧闹钟必须撤掉再按新值重排，否则会响在错误的时间点。
        Reminder.cancel(this, todo);
        if (Model.STATUS_ACTIVE.equals(status) && dueAt > 0) {
            Reminder.schedule(this, todo);
        }

        Toast.makeText(this,
                Model.STATUS_ACTIVE.equals(status) && dueAt > 0
                        ? "已保存，并排好提醒" : "已保存",
                Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void toggleDone(Model.Todo todo) {
        Db db = Db.get(this);
        boolean done = Model.STATUS_DONE.equals(todo.status);
        db.setTodoStatus(todo.id, done ? Model.STATUS_ACTIVE : Model.STATUS_DONE);
        todo.status = done ? Model.STATUS_ACTIVE : Model.STATUS_DONE;
        if (done) {
            Reminder.schedule(this, todo);
        } else {
            Reminder.cancel(this, todo);
        }
        refresh();
    }

    /** 只推迟提醒，绝不动截止时间——截止时间是消息里的事实，不该被一个按钮改掉。 */
    private void snooze(Model.Todo todo) {
        long until = System.currentTimeMillis() + 3600_000L;
        Db db = Db.get(this);
        db.setTodoSnooze(todo.id, until);
        db.setTodoStatus(todo.id, Model.STATUS_ACTIVE);
        todo.snoozeUntil = until;
        todo.status = Model.STATUS_ACTIVE;
        Reminder.schedule(this, todo);
        Toast.makeText(this, "1 小时后再提醒你一次（截止时间没变）", Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void remove(Model.Todo todo) {
        Reminder.cancel(this, todo);
        Db.get(this).deleteTodo(todo.id);
        refresh();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }
}
