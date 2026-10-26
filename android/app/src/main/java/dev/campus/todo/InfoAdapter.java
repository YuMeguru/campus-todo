package dev.campus.todo;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 班级通知列表：日期小标题 + 通知卡片，两种视图类型。
 *
 * 卡片沿用待办列表的视觉语言（深色卡片 + 来源色条 + 次要信息浅灰），
 * 但语义不同 —— 没有状态色条、没有打勾，因为这一类东西的用法是「回来查」。
 */
public final class InfoAdapter extends BaseAdapter {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CARD = 1;

    private static final class Holder {
        TextView source;
        TextView convo;
        TextView time;
        TextView body;
        TextView dueTag;
    }

    private final Context ctx;
    private final List<InfoFeed.Row> rows = new ArrayList<>();

    public InfoAdapter(Context ctx) {
        this.ctx = ctx;
    }

    public void setRows(List<InfoFeed.Row> data) {
        rows.clear();
        if (data != null) {
            rows.addAll(data);
        }
        notifyDataSetChanged();
    }

    public InfoFeed.Row rowAt(int position) {
        return rows.get(position);
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public Object getItem(int position) {
        return rows.get(position);
    }

    @Override
    public long getItemId(int position) {
        InfoFeed.Row row = rows.get(position);
        return row.header ? -1L : row.todo.id;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).header ? TYPE_HEADER : TYPE_CARD;
    }

    /** 日期小标题不可点。 */
    @Override
    public boolean isEnabled(int position) {
        return !rows.get(position).header;
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        InfoFeed.Row row = rows.get(position);
        return row.header ? headerView(row, convertView) : cardView(row.todo, convertView);
    }

    private View headerView(InfoFeed.Row row, View convertView) {
        TextView view;
        if (convertView instanceof TextView) {
            view = (TextView) convertView;
        } else {
            view = Ui.text(ctx, "", 13, Ui.MUTED, true);
            view.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 14), 0, Ui.dp(ctx, 6));
        }
        view.setText(row.label);
        return view;
    }

    private View cardView(Model.Todo todo, View convertView) {
        LinearLayout card;
        Holder holder;

        if (convertView instanceof LinearLayout) {
            card = (LinearLayout) convertView;
            holder = (Holder) card.getTag();
        } else {
            card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(Ui.CARD);
            int pad = Ui.dp(ctx, 14);
            card.setPadding(pad, pad, pad, pad);

            holder = new Holder();

            LinearLayout top = new LinearLayout(ctx);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            holder.source = Ui.chip(ctx, "", Ui.MUTED);
            top.addView(holder.source);

            holder.convo = Ui.text(ctx, "", 13, Ui.TEXT, true);
            holder.convo.setSingleLine(true);
            holder.convo.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams convoParams =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            convoParams.setMargins(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8), 0);
            top.addView(holder.convo, convoParams);

            holder.time = Ui.text(ctx, "", 11, Ui.MUTED, false);
            top.addView(holder.time);

            card.addView(top);

            holder.body = Ui.text(ctx, "", 15, Ui.TEXT, false);
            holder.body.setMaxLines(4);
            holder.body.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bodyParams.setMargins(0, Ui.dp(ctx, 10), 0, 0);
            card.addView(holder.body, bodyParams);

            holder.dueTag = Ui.text(ctx, "", 11, Ui.WARN, false);
            holder.dueTag.setPadding(0, Ui.dp(ctx, 6), 0, 0);
            card.addView(holder.dueTag);

            card.setTag(holder);
        }

        holder.source.setText(todo.source == null || todo.source.isEmpty() ? "通知" : todo.source);
        holder.source.setTextColor(Ui.sourceColor(todo.source));
        holder.convo.setText(todo.convo == null || todo.convo.isEmpty()
                ? "未标注会话" : todo.convo);
        holder.time.setText(Ui.clockLabel(todo.createdAt));
        holder.body.setText(todo.title == null ? "" : todo.title);

        if (todo.dueText == null || todo.dueText.isEmpty()) {
            holder.dueTag.setVisibility(View.GONE);
        } else {
            holder.dueTag.setVisibility(View.VISIBLE);
            holder.dueTag.setText("原文时间：" + todo.dueText);
        }
        return card;
    }
}
