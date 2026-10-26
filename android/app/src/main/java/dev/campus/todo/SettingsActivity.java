package dev.campus.todo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 设置页：API Key、通知授权、用量、发送预览、示例数据。 */
public final class SettingsActivity extends Activity {

    private EditText keyField;
    private TextView keyState;
    private TextView autoState;
    private TextView listenerState;
    private TextView usageState;
    private CheckBox confirmBox;
    private TextView appsState;

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

        // ---------------------------------------------------------- API Key
        box.addView(Ui.section(this, "DeepSeek API Key"));
        keyState = Ui.text(this, "", 12, Ui.MUTED, false);
        box.addView(keyState);

        keyField = Ui.input(this, "sk-...");
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(keyField);

        Button saveKey = Ui.button(this, "保存 Key");
        saveKey.setOnClickListener(v -> saveKey());
        box.addView(saveKey);

        box.addView(Ui.text(this,
                "Key 存在 Android Keystore 加密后的私有文件里，不写进代码，也不上传到任何地方。"
                        + "清空输入框再保存即可删除。",
                12, Ui.MUTED, false));

        // ------------------------------------------------------- 通知使用权
        box.addView(Ui.section(this, "通知使用权"));
        listenerState = Ui.text(this, "", 13, Ui.TEXT, false);
        box.addView(listenerState);

        Button openListener = Ui.button(this, "去开启通知使用权");
        openListener.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        box.addView(openListener);

        box.addView(Ui.text(this,
                "采不到的：静音/免打扰群、开了“隐藏通知内容”的会话、装本应用之前的历史消息、"
                        + "以及图片语音类通知的正文。这些只能用分享/粘贴补录。",
                12, Ui.MUTED, false));

        // ------------------------------------------------------------ 用量
        box.addView(Ui.section(this, "今日用量"));
        usageState = Ui.text(this, "", 13, Ui.TEXT, false);
        box.addView(usageState);

        // -------------------------------------------------------- 发送预览
        box.addView(Ui.section(this, "发送前确认"));
        confirmBox = Ui.check(this, "整理前先把要发送的正文给我看一遍");
        confirmBox.setOnCheckedChangeListener((v, checked) ->
                Prefs.setConfirmBeforeSend(this, checked));
        box.addView(confirmBox);

        Button preview = Ui.button(this, "查看下次将要发送的内容");
        preview.setOnClickListener(v -> showPreview());
        box.addView(preview);

        // ------------------------------------------------------------ 出网
        box.addView(Ui.section(this, "出网范围"));
        box.addView(Ui.text(this,
                "只允许请求 https://" + DeepSeek.OFFICIAL_HOST + " 这一个域名，且地址不可配置——"
                        + "API Key 与待办正文都在这条请求里。",
                12, Ui.MUTED, false));

        // -------------------------------------------------------- 自动整理
        box.addView(Ui.section(this, "自动整理"));
        autoState = Ui.text(this, "", 13, Ui.TEXT, false);
        box.addView(autoState);
        Button autoPick = Ui.button(this, "选择整理间隔");
        autoPick.setOnClickListener(v -> pickAutoInterval());
        box.addView(autoPick);
        box.addView(Ui.text(this,
                "用系统自带的 JobScheduler 定时跑，没有常驻通知。只在联网时唤醒；没有新消息时不会发出请求，"
                        + "所以不会白花钱。15 分钟档在消息多的日子里可能先撞上每日请求上限。\n"
                        + "注意：在系统里把应用「强行停止」会让定时失效，重新打开一次应用即可恢复。",
                12, Ui.MUTED, false));

        // -------------------------------------------------------- 采集应用
        box.addView(Ui.section(this, "采集应用"));
        appsState = Ui.text(this, "", 12, Ui.MUTED, false);
        box.addView(appsState);
        Button apps = Ui.button(this, "选择要采集的应用");
        apps.setOnClickListener(v -> startActivity(new Intent(this, AppsActivity.class)));
        box.addView(apps);

        // -------------------------------------------------------- 采集规则
        box.addView(Ui.section(this, "采集规则"));
        box.addView(Ui.text(this,
                "黑名单里命中的会话不采集；白名单一旦有内容，就只采集命中的会话。",
                12, Ui.MUTED, false));
        Button rules = Ui.button(this, "管理白名单 / 黑名单");
        rules.setOnClickListener(v -> startActivity(new Intent(this, RulesActivity.class)));
        box.addView(rules);

        // -------------------------------------------------------- 设备同步
        box.addView(Ui.section(this, "设备同步"));
        box.addView(Ui.text(this,
                "通过蓝牙把未完成的待办推给 AI Passport，并接收设备上的完成 / 稍后操作。"
                        + "协议是 campus-sync/1（Nordic UART 布局的 GATT 服务 + 加密特征 + 整包 CRC）。\n"
                        + "设备侧需要先跑实现了该协议的固件，否则扫描会找不到设备。",
                12, Ui.MUTED, false));
        Button device = Ui.button(this, "打开设备同步页");
        device.setOnClickListener(v -> startActivity(new Intent(this, DeviceActivity.class)));
        box.addView(device);

        // -------------------------------------------------------- 演示数据
        box.addView(Ui.section(this, "演示"));
        Button sample = Ui.button(this, "插入示例消息");
        sample.setOnClickListener(v -> {
            int n = Sample.insert(this);
            Toast.makeText(this,
                    n > 0 ? "插入了 " + n + " 条示例消息，回首页点一键整理"
                            : "示例消息已经在库里了（指纹去重）",
                    Toast.LENGTH_LONG).show();
        });
        box.addView(sample);

        Button wipe = Ui.button(this, "清空所有数据");
        wipe.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("清空所有数据？")
                .setMessage("采集到的消息、待办、用量记录都会删除。API Key 也会一并清除。")
                .setPositiveButton("清空", (d, w) -> {
                    Db.get(this).wipe();
                    KeyVault.clear(this);
                    reload();
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show());
        box.addView(wipe);

        box.addView(Ui.text(this,
                "版本 " + BuildConfigLike.VERSION + " · 运行时依赖 0 个第三方库",
                12, Ui.MUTED, false));

        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(Ui.BG);
        scroller.addView(box, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroller;
    }

    private void reload() {
        boolean hasKey = KeyVault.has(this);
        keyState.setText(hasKey ? "已保存（Keystore 加密）" : "未设置 —— 没有 Key 无法整理");
        keyState.setTextColor(hasKey ? Ui.OK : Ui.WARN);
        keyField.setText("");

        boolean enabled = listenerEnabled();
        listenerState.setText(enabled
                ? "已开启，正在采集通知"
                : "未开启 —— 现在只能靠手动补录");
        listenerState.setTextColor(enabled ? Ui.OK : Ui.WARN);

        long[] usage = Db.get(this).todayUsage();
        usageState.setText("调用 " + usage[0] + "/" + Organizer.DAILY_REQUESTS
                + " 次 · 输入 " + usage[1] + " tokens · 输出 " + usage[2] + " tokens"
                + "\n上限：" + Organizer.DAILY_REQUESTS + " 次 / "
                + Organizer.DAILY_IN_TOKENS + " 输入 / " + Organizer.DAILY_OUT_TOKENS + " 输出");

        confirmBox.setChecked(Prefs.confirmBeforeSend(this));

        int minutes = Prefs.autoIntervalMinutes(this);
        boolean autoOn = minutes >= AutoOrganizer.MIN_MINUTES;
        autoState.setText("当前：" + AutoOrganizer.label(minutes)
                + (autoOn ? (AutoOrganizer.isScheduled(this)
                        ? "（系统已排期）" : "（未排期，重新选一次即可恢复）") : ""));
        autoState.setTextColor(autoOn ? Ui.OK : Ui.MUTED);

        int appCount = Prefs.enabledApps(this).size();
        appsState.setText(appCount == 0
                ? "当前开启 0 个应用 —— 通知一条都进不来"
                : "当前开启 " + appCount + " 个应用");
        appsState.setTextColor(appCount == 0 ? Ui.WARN : Ui.MUTED);
    }

    private void pickAutoInterval() {
        final int[] choices = AutoOrganizer.CHOICES;
        final String[] labels = new String[choices.length];
        int checked = 0;
        int current = Prefs.autoIntervalMinutes(this);
        for (int i = 0; i < choices.length; i++) {
            labels[i] = AutoOrganizer.label(choices[i]);
            if (choices[i] == current) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("自动整理间隔")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    AutoOrganizer.apply(this, choices[which]);
                    dialog.dismiss();
                    reload();
                    Toast.makeText(this, "已设为 " + labels[which], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveKey() {
        String value = keyField.getText().toString().trim();
        try {
            KeyVault.save(this, value);
            keyField.setText("");
            reload();
            Toast.makeText(this, value.isEmpty() ? "已删除 API Key" : "已保存",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showPreview() {
        String payload = Organizer.preview(this, System.currentTimeMillis());
        new AlertDialog.Builder(this)
                .setTitle("将要发送的内容")
                .setMessage(payload.isEmpty() ? "当前没有待整理的消息。" : payload)
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 不引 androidx，所以自己查系统设置里的已启用监听器列表。 */
    private boolean listenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }
}
