package dev.campus.todo;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.ZoneId;
import java.util.List;

/**
 * 班级通知：和待办分开的参考信息分区。
 *
 * 和待办的差别不只是排版：这里的条目没有截止时间、不排提醒、也不能「完成」——
 * 教室安排、调课、考试范围这类东西的用法是「以后回来查」。
 */
public final class InfoActivity extends Activity {

    private InfoAdapter adapter;
    private TextView summary;
    private ListView list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private ViewGroup buildUi() {
        final int pad = Ui.dp(this, 14);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        summary = Ui.text(this, "", 13, Ui.MUTED, false);
        summary.setPadding(pad, pad, pad, Ui.dp(this, 8));
        root.addView(summary);

        adapter = new InfoAdapter(this);
        list = new ListView(this);
        list.setBackgroundColor(Ui.BG);
        list.setDivider(null);
        list.setDividerHeight(Ui.dp(this, 8));
        list.setPadding(pad, 0, pad, Ui.dp(this, 8));
        list.setClipToPadding(false);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) ->
                showDetail(adapter.rowAt(position).todo));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmDelete(adapter.rowAt(position).todo);
            return true;
        });

        TextView empty = Ui.text(this,
                "还没有收到班级通知。\n\n教室安排、调课、考试范围这类内容会被放到这里，"
                        + "和待办分开：不排提醒，也不能打勾，只供你回来查。\n\n"
                        + "到设置里点「插入示例消息」可以看到效果。",
                14, Ui.MUTED, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(pad * 2, 0, pad * 2, 0);
        list.setEmptyView(empty);

        FrameLayout area = new FrameLayout(this);
        area.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        area.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(area, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button clear = Ui.button(this, "清空全部通知");
        clear.setOnClickListener(v -> confirmClear());
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clearParams.setMargins(pad, 0, pad, pad);
        root.addView(clear, clearParams);

        return root;
    }

    private void reload() {
        List<Model.Todo> items = Db.get(this).listInfo();
        adapter.setRows(InfoFeed.build(items, System.currentTimeMillis(),
                ZoneId.systemDefault()));

        summary.setText(items.isEmpty()
                ? "参考信息分区：不排提醒，也不能打勾。"
                : "共 " + items.size() + " 条 · 点开看全文，长按删除\n"
                        + "参考信息：不排提醒，也不能打勾。");
    }

    private void showDetail(final Model.Todo todo) {
        final int pad = Ui.dp(this, 18);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);

        box.addView(Ui.text(this, todo.title, 16, Ui.TEXT, false));

        box.addView(Ui.section(this, "来自"));
        box.addView(Ui.text(this,
                (todo.source == null || todo.source.isEmpty() ? "未知" : todo.source)
                        + (todo.convo == null || todo.convo.isEmpty() ? "" : " · " + todo.convo),
                13, Ui.MUTED, false));

        box.addView(Ui.section(this, "收到时间"));
        box.addView(Ui.text(this, Ui.timeLabel(todo.createdAt), 14, Ui.TEXT, false));

        if (todo.dueText != null && !todo.dueText.isEmpty()) {
            box.addView(Ui.section(this, "原文里的时间表述"));
            box.addView(Ui.text(this, todo.dueText, 14, Ui.TEXT, false));
        }

        if (todo.evidence != null && !todo.evidence.isEmpty()
                && !todo.evidence.equals(todo.title)) {
            box.addView(Ui.section(this, "原文片段"));
            box.addView(Ui.text(this, todo.evidence, 13, Ui.MUTED, false));
        }

        ScrollView scroller = new ScrollView(this);
        scroller.addView(box, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setView(scroller)
                .setPositiveButton("删除", (d, w) -> {
                    Db.get(this).deleteTodo(todo.id);
                    reload();
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void confirmDelete(final Model.Todo todo) {
        new AlertDialog.Builder(this)
                .setTitle("删除这条通知？")
                .setMessage(todo.title)
                .setPositiveButton("删除", (d, w) -> {
                    Db.get(this).deleteTodo(todo.id);
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清空全部班级通知？")
                .setMessage("只清空这个分区，待办不受影响。")
                .setPositiveButton("清空", (d, w) -> {
                    Db.get(this).clearInfo();
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
