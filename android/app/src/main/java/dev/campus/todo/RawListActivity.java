package dev.campus.todo;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 采集到的原始消息。排障用：能看到通知到底有没有进来、哪些被本地规则过滤成了 skip、
 * 指纹去重有没有生效。
 *
 * 列表走 RawAdapter 懒加载。之前这里是把最多 200 条先拼成一个字符串数组再交给
 * ArrayAdapter —— 一进页面就在主线程把活全干完，现在每一行只在真的要显示时才组装。
 */
public final class RawListActivity extends Activity {

    private RawAdapter adapter;
    private TextView summary;

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

        adapter = new RawAdapter(this);
        ListView list = new ListView(this);
        list.setBackgroundColor(Ui.BG);
        list.setDivider(null);
        list.setDividerHeight(Ui.dp(this, 8));
        list.setPadding(pad, 0, pad, pad);
        list.setClipToPadding(false);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) ->
                showDetail(adapter.itemAt(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmDelete(adapter.itemAt(position));
            return true;
        });

        TextView empty = Ui.text(this,
                "还没有采集到任何消息。\n\n"
                        + "只采集微信 / 钉钉 / 学习通的通知。到设置里开启通知使用权后，"
                        + "这三个应用弹出的通知会出现在这里。\n\n"
                        + "也可以先到设置里点「插入示例消息」看效果。",
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

        return root;
    }

    private void reload() {
        Db db = Db.get(this);
        List<Model.Raw> raws = db.recentRaw(200);
        adapter.setData(raws);

        long[] counts = db.rawCounts();
        summary.setText("待整理 " + counts[0] + " · 已过滤 " + counts[1]
                + " · 已整理 " + counts[2]
                + "\n点开看全文，长按删除。「已过滤」是被本地规则挡下的：验证码、支付收据、"
                + "营销标题、过短、重复通知。");
    }

    private void showDetail(final Model.Raw raw) {
        final int pad = Ui.dp(this, 18);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);

        box.addView(Ui.text(this, Ui.rawStateLabel(raw.state), 12,
                Ui.rawStateColor(raw.state), true));

        box.addView(Ui.section(this, "正文"));
        box.addView(Ui.text(this,
                raw.body == null || raw.body.isEmpty() ? "（无正文）" : raw.body,
                15, Ui.TEXT, false));

        if (raw.title != null && !raw.title.isEmpty()) {
            box.addView(Ui.section(this, "会话"));
            box.addView(Ui.text(this, raw.title, 14, Ui.TEXT, false));
        }

        box.addView(Ui.section(this, "来源与时间"));
        box.addView(Ui.text(this,
                (raw.pkgLabel == null || raw.pkgLabel.isEmpty() ? "未知" : raw.pkgLabel)
                        + " · " + Ui.timeLabel(raw.postedAt)
                        + "\n采集方式：" + ingestLabel(raw.ingest),
                13, Ui.MUTED, false));

        ScrollView scroller = new ScrollView(this);
        scroller.addView(box, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setView(scroller)
                .setPositiveButton("删除", (d, w) -> {
                    Db.get(this).deleteRaw(raw.id);
                    reload();
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void confirmDelete(final Model.Raw raw) {
        new AlertDialog.Builder(this)
                .setTitle("删除这条原始消息？")
                .setMessage(raw.display())
                .setPositiveButton("删除", (d, w) -> {
                    Db.get(this).deleteRaw(raw.id);
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String ingestLabel(String ingest) {
        if (ingest == null) {
            return "未知";
        }
        switch (ingest) {
            case "auto":
                return "通知自动采集";
            case "share":
                return "分享补录";
            case "paste":
                return "粘贴补录";
            case "sample":
                return "示例数据";
            default:
                return ingest;
        }
    }
}
