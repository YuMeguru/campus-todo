package dev.campus.todo;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 采集应用选择页：勾上谁就采谁。
 *
 * 默认给的是主流清单，不是「全选」—— 全量采集会把系统通知、广告、验证码一起吞进来，
 * 先把熟的跑顺，需要时再一个个加。
 *
 * 勾选即时落盘，没有「保存」按钮：多一步确认只会让人以为没生效。
 */
public final class AppsActivity extends Activity {

    private final List<AppCatalog.Entry> items = new ArrayList<>();
    private final Set<String> enabled = new HashSet<>();
    private AppsAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enabled.addAll(Prefs.enabledApps(this));
        items.addAll(AppCatalog.installed(this, enabled));
        setContentView(buildUi());
    }

    private ViewGroup buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, Ui.dp(this, 12), pad, 0);

        root.addView(Ui.text(this, "采集哪些应用", 20, Ui.TEXT, true));

        TextView tip = Ui.text(this,
                "勾上的应用，通知会被采集并送去提炼。默认只开了主流聊天、校园与短信类；"
                        + "勾得越多，噪音和 AI 花费越高。常驻通知（播放器、下载进度）一律不采。",
                13, Ui.MUTED, false);
        tip.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        root.addView(tip);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button all = Ui.button(this, "全选");
        Button none = Ui.button(this, "全不选");
        Button def = Ui.button(this, "恢复默认");
        all.setOnClickListener(v -> applyAll(true));
        none.setOnClickListener(v -> applyAll(false));
        def.setOnClickListener(v -> {
            enabled.clear();
            enabled.addAll(Filter.DEFAULT_PACKAGES);
            persist();
            adapter.notifyDataSetChanged();
        });
        bar.addView(all);
        bar.addView(none);
        bar.addView(def);
        root.addView(bar);

        ListView list = new ListView(this);
        list.setBackgroundColor(Ui.BG);
        adapter = new AppsAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    /** 全选只作用于列表里真实存在的应用：默认清单里写的别家短信，勾了也收不到。 */
    private void applyAll(boolean on) {
        enabled.clear();
        if (on) {
            for (AppCatalog.Entry e : items) {
                enabled.add(e.pkg);
            }
        }
        persist();
        adapter.notifyDataSetChanged();
    }

    private void persist() {
        Prefs.setEnabledApps(this, enabled);
    }

    private final class AppsAdapter extends BaseAdapter {

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
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            CheckBox box;
            if (convertView instanceof CheckBox) {
                box = (CheckBox) convertView;
            } else {
                box = Ui.check(AppsActivity.this, "");
                box.setBackgroundColor(Ui.BG);
                int p = Ui.dp(AppsActivity.this, 10);
                box.setPadding(p, p, p, p);
            }
            AppCatalog.Entry e = items.get(position);
            // 先摘掉监听器再 setChecked：复用的行若留着旧回调，会把相邻项的勾选改掉。
            box.setOnCheckedChangeListener(null);
            box.setText(e.label + "\n" + e.pkg);
            box.setTextSize(13);
            box.setChecked(enabled.contains(e.pkg));
            box.setOnCheckedChangeListener((b, checked) -> {
                if (checked) {
                    enabled.add(e.pkg);
                } else {
                    enabled.remove(e.pkg);
                }
                persist();
            });
            return box;
        }
    }
}
