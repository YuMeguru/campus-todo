package dev.campus.todo;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 采集规则管理：黑名单 / 白名单，可增可删。 */
public final class RulesActivity extends Activity {

    private LinearLayout denyBox;
    private LinearLayout allowBox;
    private TextView statusView;

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
        final int pad = Ui.dp(this, 18);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Ui.BG);
        box.setPadding(pad, pad, pad, pad);

        statusView = Ui.text(this, "", 12, Ui.MUTED, false);
        box.addView(statusView);

        box.addView(Ui.section(this, "黑名单（命中就不采集）"));
        denyBox = new LinearLayout(this);
        denyBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(denyBox);
        Button addDeny = Ui.button(this, "添加黑名单");
        addDeny.setOnClickListener(v -> prompt(Model.Rule.DENY));
        box.addView(addDeny);

        box.addView(Ui.section(this, "白名单（一旦有内容，就只采集命中的）"));
        allowBox = new LinearLayout(this);
        allowBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(allowBox);
        Button addAllow = Ui.button(this, "添加白名单");
        addAllow.setOnClickListener(v -> prompt(Model.Rule.ALLOW));
        box.addView(addAllow);

        box.addView(Ui.text(this,
                "匹配的是会话名（群名或联系人名），按「归一化后包含」判断：全角半角、空格差异都忽略，"
                        + "所以「计科 2201」和「计科2201」算同一个。\n"
                        + "命中黑名单的消息正文不入库，只累加一个计数。\n"
                        + "注意：微信群里通知的标题是**群名**而不是发言人，所以「只屏蔽群里的某个人」"
                        + "做不到，只能整个群屏蔽；私聊按联系人名屏蔽是正常的。",
                12, Ui.MUTED, false));

        box.addView(Ui.section(this, "本地兜底词表"));
        box.addView(Ui.text(this,
                "模型没提取、但正文命中下列词的通知，会直接保留进「班级通知」，不会被静默丢掉：\n"
                        + String.join("、", InfoFallback.KEYWORDS),
                12, Ui.MUTED, false));

        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(Ui.BG);
        scroller.addView(box, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroller;
    }

    private void reload() {
        List<Model.Rule> rules = Db.get(this).listRules();
        fill(denyBox, rules, Model.Rule.DENY);
        fill(allowBox, rules, Model.Rule.ALLOW);

        int blocked = Prefs.blockedCount(this);
        statusView.setText(blocked == 0
                ? "尚未拦下任何消息"
                : "已拦下 " + blocked + " 条，最近一次：" + Prefs.blockedLast(this));
    }

    private void fill(LinearLayout container, List<Model.Rule> rules, String mode) {
        container.removeAllViews();
        boolean any = false;
        for (Model.Rule rule : rules) {
            if (!mode.equals(rule.mode)) {
                continue;
            }
            any = true;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundColor(Ui.CARD);
            int p = Ui.dp(this, 12);
            row.setPadding(p, p, p, p);
            row.addView(Ui.text(this, rule.text, 15, Ui.TEXT, false),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button remove = Ui.button(this, "删除");
            remove.setOnClickListener(v -> {
                Db.get(this).deleteRule(rule.id);
                reload();
            });
            row.addView(remove);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, Ui.dp(this, 6), 0, 0);
            container.addView(row, lp);
        }
        if (!any) {
            container.addView(Ui.text(this, "（空）", 13, Ui.MUTED, false));
        }
    }

    private void prompt(final String mode) {
        final boolean deny = Model.Rule.DENY.equals(mode);

        EditText input = Ui.input(this, deny ? "例如：计科2201班群" : "例如：辅导员");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        int pad = Ui.dp(this, 18);
        input.setPadding(pad, Ui.dp(this, 8), pad, 0);

        new AlertDialog.Builder(this)
                .setTitle(deny ? "加入黑名单" : "加入白名单")
                .setView(input)
                .setPositiveButton("添加", (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (text.isEmpty()) {
                        return;
                    }
                    long id = Db.get(this).addRule(mode, text);
                    Toast.makeText(this, id < 0 ? "这条规则已经存在" : "已添加",
                            Toast.LENGTH_SHORT).show();
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
