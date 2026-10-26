package dev.campus.todo;

import android.content.Context;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public final class TodoAdapter extends BaseAdapter {

    private static final class Holder {
        View bar;
        TextView title;
        TextView meta;
        TextView chip;
    }

    private final Context ctx;
    private final List<Model.Todo> items = new ArrayList<>();

    public TodoAdapter(Context ctx) {
        this.ctx = ctx;
    }

    public void setData(List<Model.Todo> data) {
        items.clear();
        if (data != null) {
            items.addAll(data);
        }
        notifyDataSetChanged();
    }

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
        LinearLayout row;
        Holder h;
        if (convertView instanceof LinearLayout) {
            row = (LinearLayout) convertView;
            h = (Holder) row.getTag();
        } else {
            row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundColor(Ui.CARD);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int pad = Ui.dp(ctx, 14);
            row.setPadding(pad, pad, pad, pad);

            h = new Holder();
            h.bar = new View(ctx);
            LinearLayout.LayoutParams barParams =
                    new LinearLayout.LayoutParams(Ui.dp(ctx, 4), Ui.dp(ctx, 46));
            row.addView(h.bar, barParams);

            LinearLayout column = new LinearLayout(ctx);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams columnParams =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            columnParams.setMargins(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 8), 0);
            row.addView(column, columnParams);

            h.title = Ui.text(ctx, "", 17, Ui.TEXT, true);
            column.addView(h.title);
            h.meta = Ui.text(ctx, "", 12, Ui.MUTED, false);
            h.meta.setPadding(0, Ui.dp(ctx, 3), 0, 0);
            column.addView(h.meta);

            h.chip = Ui.chip(ctx, "", Ui.MUTED);
            row.addView(h.chip);

            row.setTag(h);
        }

        Model.Todo t = items.get(position);
        h.bar.setBackgroundColor(Ui.statusColor(t));

        h.title.setText(t.title);
        int flags = h.title.getPaintFlags();
        h.title.setPaintFlags(Model.STATUS_DONE.equals(t.status)
                ? flags | Paint.STRIKE_THRU_TEXT_FLAG
                : flags & ~Paint.STRIKE_THRU_TEXT_FLAG);
        h.title.setTextColor(Model.STATUS_DONE.equals(t.status) ? Ui.DONE : Ui.TEXT);

        StringBuilder meta = new StringBuilder();
        meta.append("截止 ").append(Ui.dueLabel(t));
        if (t.source != null && !t.source.isEmpty()) {
            meta.append(" · ").append(t.source);
        }
        if (t.course != null && !t.course.isEmpty()) {
            meta.append(" · ").append(t.course);
        }
        meta.append(" · ").append(Ui.kindLabel(t.kind));
        if (t.snoozeUntil > System.currentTimeMillis()) {
            meta.append(" · 提醒 ").append(Ui.timeLabel(t.snoozeUntil));
        }
        h.meta.setText(meta.toString());

        h.chip.setText(Ui.statusLabel(t));
        h.chip.setTextColor(Ui.statusColor(t));

        return row;
    }
}
