package dev.campus.todo;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 归档区：完成或过期满一天的待办会收到这里。
 *
 * 归档**不是删除** —— 这一页每一条都能原样恢复回主列表。
 * 只有点「清空归档」再确认，数据才真没了。
 */
public final class ArchiveActivity extends Activity {

    private final List<Model.Todo> items = new ArrayList<>();
    private ArchiveAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        reload();
    }

    private View buildUi() {
        int pad = Ui.dp(this, 16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(pad, Ui.dp(this, 12), pad, 0);

        root.addView(Ui.text(this, "归档", 20, Ui.TEXT, true));

        TextView tip = Ui.text(this,
                "完成或过期满一天的待办会自动收到这里。归档不删数据，点「恢复」就回到主列表。",
                13, Ui.MUTED, false);
        tip.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        root.addView(tip);

        Button purge = Ui.button(this, "清空归档");
        purge.setOnClickListener(v -> confirmPurge());
        root.addView(purge);

        ListView list = new ListView(this);
        list.setBackgroundColor(Ui.BG);
        list.setDivider(null);
        list.setDividerHeight(Ui.dp(this, 8));
        list.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        list.setClipToPadding(false);
        adapter = new ArchiveAdapter();
        list.setAdapter(adapter);

        TextView empty = Ui.text(this, "归档区是空的", 14, Ui.MUTED, false);
        empty.setGravity(Gravity.CENTER);
        list.setEmptyView(empty);

        FrameLayout area = new FrameLayout(this);
        area.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        area.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(area, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void reload() {
        items.clear();
        items.addAll(Db.get(this).listArchived());
        adapter.notifyDataSetChanged();
    }

    /** 恢复：状态按归档前的样子还原，该排的提醒重新排上。 */
    private void restore(Model.Todo todo) {
        Db db = Db.get(this);
        db.restoreTodo(todo.id);
        Model.Todo back = db.getTodo(todo.id);
        if (back != null && Model.STATUS_ACTIVE.equals(back.status)) {
            Reminder.schedule(this, back);
        }
        Toast.makeText(this, "已恢复到待办列表", Toast.LENGTH_SHORT).show();
        reload();
    }

    private void removeOne(Model.Todo todo) {
        new AlertDialog.Builder(this)
                .setTitle("删除这一条？")
                .setMessage("删掉就找不回来了。只是想让它别占地方的话，留在归档区就够了。")
                .setPositiveButton("删除", (d, w) -> {
                    Db.get(this).deleteTodo(todo.id);
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmPurge() {
        final int n = items.size();
        if (n == 0) {
            Toast.makeText(this, "归档区是空的", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空 " + n + " 条归档？")
                .setMessage("这是真删除，清完找不回来。\n"
                        + "只是不想让主列表变长的话不用清 —— 归档本来就不占主列表。")
                .setPositiveButton("清空", (d, w) -> {
                    Db.get(this).purgeArchived();
                    reload();
                    Toast.makeText(this, "已清空 " + n + " 条", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static final class Holder {
        TextView title;
        TextView meta;
        Button restore;
        Button remove;
    }

    private final class ArchiveAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout card;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof Holder) {
                card = (LinearLayout) convertView;
            } else {
                card = buildCard();
            }
            bind(card, items.get(position));
            return card;
        }
    }

    private LinearLayout buildCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Ui.CARD);
        int p = Ui.dp(this, 14);
        card.setPadding(p, p, p, p);

        Holder h = new Holder();

        h.title = Ui.text(this, "", 15, Ui.TEXT, true);
        h.title.setMaxLines(2);
        h.title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(h.title);

        h.meta = Ui.text(this, "", 12, Ui.MUTED, false);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaParams.setMargins(0, Ui.dp(this, 6), 0, 0);
        card.addView(h.meta, metaParams);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, Ui.dp(this, 10), 0, 0);
        row.setLayoutParams(rowParams);

        h.restore = Ui.button(this, "恢复");
        h.restore.setTextSize(13);
        h.remove = Ui.button(this, "删除");
        h.remove.setTextSize(13);

        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(h.restore, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(h.remove, half2);
        card.addView(row);

        card.setTag(h);
        return card;
    }

    private void bind(LinearLayout card, Model.Todo t) {
        Holder h = (Holder) card.getTag();

        h.title.setText(t.title == null || t.title.isEmpty() ? "（无标题）" : t.title);

        StringBuilder meta = new StringBuilder();
        meta.append(t.source == null || t.source.isEmpty() ? "未知" : t.source);
        // 有完成时间的是做完的，没有的是过期没做的 —— 这是区分两类删除的唯一依据。
        meta.append(" · ").append(t.completedAt > 0 ? "已完成" : "已过期");
        if (t.archivedAt > 0) {
            meta.append(" · 归档于 ").append(Ui.timeLabel(t.archivedAt));
        }
        if (t.convo != null && !t.convo.isEmpty()) {
            meta.append('\n').append(t.convo);
        }
        h.meta.setText(meta.toString());

        h.restore.setOnClickListener(v -> restore(t));
        h.remove.setOnClickListener(v -> removeOne(t));
    }
}
