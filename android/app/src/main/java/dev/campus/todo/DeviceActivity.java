package dev.campus.todo;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备同步页：扫描 → 连接 → 推送待办快照 → 接收设备上的完成/稍后操作。
 *
 * 这一版**不做自动同步**：设备侧固件还没实现 campus-sync，先把通路和排障信息做出来，
 * 由人按一下才知道发生了什么。等固件能跑了再谈「整理完自动推」。
 */
public final class DeviceActivity extends Activity implements BleClient.Listener {

    private static final int REQ_BLE = 2001;
    private static final int MAX_LOG_LINES = 300;

    private BleClient client;
    private TextView statusView;
    private TextView logView;
    private ScrollView logScroller;
    private ScanRadarView radar;

    private final StringBuilder logBuffer = new StringBuilder();
    private int logLines;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        client = new BleClient(this, this);
        setContentView(buildUi());
        requestBlePermissions();
        refreshReadiness();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        client.stop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refreshReadiness();
    }

    // ------------------------------------------------------------------ 界面

    private ViewGroup buildUi() {
        final int pad = Ui.dp(this, 14);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        statusView = Ui.text(this, "", 14, Ui.MUTED, false);
        statusView.setPadding(pad, pad, pad, Ui.dp(this, 8));
        root.addView(statusView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(pad, 0, pad, Ui.dp(this, 8));

        Button sync = Ui.button(this, "扫描并同步");
        sync.setOnClickListener(v -> startSync());
        buttons.addView(sync, weightParams(Ui.dp(this, 2), 0));

        Button stop = Ui.button(this, "停止");
        stop.setOnClickListener(v -> {
            client.stop();
            onStatus("已停止");
            appendLog("— 手动停止 —");
        });
        buttons.addView(stop, weightParams(0, Ui.dp(this, 2)));
        root.addView(buttons);

        Button bluetoothSettings = Ui.button(this, "打开蓝牙设置");
        bluetoothSettings.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        settingsParams.setMargins(pad, 0, pad, Ui.dp(this, 8));
        root.addView(bluetoothSettings, settingsParams);

        // 雷达放在按钮下面、日志上面：按钮保持在高处好按，雷达把扫描那段等待变成看得见的过程。
        radar = new ScanRadarView(this);
        LinearLayout.LayoutParams radarParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 190));
        radarParams.setMargins(pad, 0, pad, Ui.dp(this, 8));
        root.addView(radar, radarParams);

        logView = Ui.text(this, "", 11, Ui.MUTED, false);
        logView.setPadding(pad, pad, pad, pad);
        logScroller = new ScrollView(this);
        logScroller.setBackgroundColor(Ui.CARD);
        logScroller.addView(logView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        logParams.setMargins(pad, 0, pad, Ui.dp(this, 8));
        root.addView(logScroller, logParams);

        Button clear = Ui.button(this, "清空日志");
        clear.setOnClickListener(v -> {
            logBuffer.setLength(0);
            logLines = 0;
            logView.setText("");
        });
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clearParams.setMargins(pad, 0, pad, pad);
        root.addView(clear, clearParams);

        appendLog("协议：campus-sync/1，服务 " + BleFrame.SERVICE);
        appendLog("提示：设备侧需要先跑实现了该协议的固件，否则扫不到任何东西。");
        return root;
    }

    private LinearLayout.LayoutParams weightParams(int leftMargin, int rightMargin) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(leftMargin, 0, rightMargin, 0);
        return params;
    }

    private void refreshReadiness() {
        String blocked = client.describeReadiness();
        if (blocked == null) {
            statusView.setText("就绪。点「扫描并同步」开始。");
            statusView.setTextColor(Ui.MUTED);
        } else {
            statusView.setText(blocked);
            statusView.setTextColor(Ui.WARN);
        }
    }

    // ------------------------------------------------------------------ 动作

    private void startSync() {
        if (!client.hasPermissions()) {
            appendLog("缺少蓝牙权限，先授权");
            requestBlePermissions();
            return;
        }
        // 只推未完成的：设备屏只有 240×320，已完成的条目推过去也只是噪音；
        // 手机上标成完成的，会因为它不在快照里而从设备上消失 —— 这正是想要的界面结果。
        List<Model.Todo> open = new ArrayList<>();
        for (Model.Todo todo : Db.get(this).listTodos()) {
            if (!Model.STATUS_DONE.equals(todo.status)) {
                open.add(todo);
            }
        }
        appendLog("— 开始同步，待推送 " + open.size() + " 条 —");
        if (open.isEmpty()) {
            statusView.setText("没有未完成的待办，仍然会连一次设备。");
            statusView.setTextColor(Ui.MUTED);
        }
        client.sync(open);
    }

    private void requestBlePermissions() {
        if (client.hasPermissions()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLE);
        }
    }

    // ------------------------------------------------------- BleClient.Listener

    @Override
    public void onStatus(String message) {
        statusView.setText(message);
        statusView.setTextColor(Ui.MUTED);
    }

    @Override
    public void onLog(String line) {
        appendLog(line);
    }

    @Override
    public void onDeviceFound(String name, int rssi) {
        radar.addBlip(name, rssi);
    }

    @Override
    public void onScanning(boolean active) {
        radar.setScanning(active);
        if (active) {
            radar.clearBlips();
        }
    }

    @Override
    public void onDone(boolean ok, String message) {
        statusView.setText(message);
        statusView.setTextColor(ok ? Ui.OK : Ui.WARN);
        appendLog((ok ? "✔ " : "✘ ") + message);
    }

    private void appendLog(String line) {
        logBuffer.append(line).append('\n');
        logLines++;
        while (logLines > MAX_LOG_LINES) {
            int cut = logBuffer.indexOf("\n");
            if (cut < 0) {
                break;
            }
            logBuffer.delete(0, cut + 1);
            logLines--;
        }
        logView.setText(logBuffer.toString());
        logScroller.post(() -> logScroller.fullScroll(View.FOCUS_DOWN));
    }
}
