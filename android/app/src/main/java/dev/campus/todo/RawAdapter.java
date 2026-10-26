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
 * 原始消息列表：每条一张卡片。
 *
 * 第一行是「状态 + 来源 + 时间」，第二行是会话名，第三行才是正文预览（3 行封顶）。
 * 会话名单独一行是必要的：采集到的正文里常常没有它，而排障时第一眼要看的就是「哪个群」。
 */
public final class RawAdapter extends BaseAdapter {

    private static final class Holder {
        TextView state;
        TextView source;
        TextView time;
        TextView convo;
        TextView body;
    }

    private final Context ctx;
    private final List<Model.Raw> items = new ArrayList<>();

    public RawAdapter(Context ctx) {
        this.ctx = ctx;
    }

    public void setData(List<Model.Raw> data) {
        items.clear();
        if (data != null) {
            items.addAll(data);
        }
        notifyDataSetChanged();
    }

    public Model.Raw itemAt(int position) {
        return items.get(position);
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

            holder.state = Ui.chip(ctx, "", Ui.MUTED);
            top.addView(holder.state);

            holder.source = Ui.text(ctx, "", 13, Ui.TEXT, true);
            holder.source.setSingleLine(true);
            holder.source.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams sourceParams =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            sourceParams.setMargins(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8), 0);
            top.addView(holder.source, sourceParams);

            holder.time = Ui.text(ctx, "", 11, Ui.MUTED, false);
            top.addView(holder.time);
            card.addView(top);

            holder.convo = Ui.text(ctx, "", 14, Ui.TEXT, true);
            holder.convo.setMaxLines(1);
            holder.convo.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams convoParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            convoParams.setMargins(0, Ui.dp(ctx, 8), 0, 0);
            card.addView(holder.convo, convoParams);

            holder.body = Ui.text(ctx, "", 14, Ui.MUTED, false);
            holder.body.setMaxLines(3);
            holder.body.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bodyParams.setMargins(0, Ui.dp(ctx, 6), 0, 0);
            card.addView(holder.body, bodyParams);

            card.setTag(holder);
        }

        Model.Raw raw = items.get(position);

        holder.state.setText(Ui.rawStateLabel(raw.state));
        holder.state.setTextColor(Ui.rawStateColor(raw.state));
        holder.source.setText(raw.pkgLabel == null || raw.pkgLabel.isEmpty()
                ? "未知" : raw.pkgLabel);
        holder.time.setText(Ui.clockLabel(raw.postedAt));

        String convo = raw.title == null ? "" : raw.title.trim();
        if (convo.isEmpty()) {
            holder.convo.setVisibility(View.GONE);
        } else {
            holder.convo.setVisibility(View.VISIBLE);
            holder.convo.setText(convo);
        }

        holder.body.setText(raw.body == null || raw.body.isEmpty() ? "（无正文）" : raw.body);
        return card;
    }
}
