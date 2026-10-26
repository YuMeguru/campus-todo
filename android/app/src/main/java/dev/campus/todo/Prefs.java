package dev.campus.todo;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** 少量开关。放一个文件里，避免到处 new SharedPreferences。 */
public final class Prefs {

    private static final String FILE = "campus";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_PREVIEW = "confirm_before_send";
    private static final String KEY_AUTO_MINUTES = "auto_interval_minutes";
    private static final String KEY_BLOCKED_COUNT = "blocked_count";
    private static final String KEY_BLOCKED_LAST = "blocked_last";
    private static final String KEY_DEVICE = "ble_device_address";
    private static final String KEY_APPS = "enabled_apps";

    /**
     * 已启用采集的应用集合的内存副本。
     *
     * 通知回调可能一秒来好几条，每条都读一次 SharedPreferences 并新建 HashSet
     * 是白扔的性能。setEnabledApps 是唯一的写入点，写完同步刷这份副本。
     */
    private static volatile Set<String> CACHE;

    /**
     * 已启用的采集应用。
     *
     * 从没设置过时返回默认清单 —— 装好就该能用，不该先逼用户勾一遍微信。
     * 返回的集合是内部副本，调用方只读，别往里面加东西。
     */
    public static Set<String> enabledApps(Context ctx) {
        Set<String> cached = CACHE;
        if (cached != null) {
            return cached;
        }
        Set<String> stored = sp(ctx).getStringSet(KEY_APPS, null);
        Set<String> out = new HashSet<>(stored == null ? Filter.DEFAULT_PACKAGES : stored);
        CACHE = out;
        return out;
    }

    public static void setEnabledApps(Context ctx, Set<String> pkgs) {
        Set<String> copy = pkgs == null
                ? Collections.<String>emptySet()
                : new HashSet<>(pkgs);
        sp(ctx).edit().putStringSet(KEY_APPS, copy).apply();
        CACHE = copy;
    }

    public static boolean isAppEnabled(Context ctx, String pkg) {
        return pkg != null && enabledApps(ctx).contains(pkg);
    }

    /**
     * 上次同步成功的设备地址。空表示还没成功过，需要扫描。
     *
     * 存它是为了下次能直接连：手机系统一旦跟设备建立了连接，设备侧就会停止广播，
     * 这时再扫描永远扫不到 —— 而「已配对」恰恰意味着系统很可能正连着它。
     */
    public static String deviceAddress(Context ctx) {
        return sp(ctx).getString(KEY_DEVICE, "");
    }

    public static void setDeviceAddress(Context ctx, String address) {
        sp(ctx).edit().putString(KEY_DEVICE, address == null ? "" : address).apply();
    }

    private Prefs() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 打开后，“一键整理”会先把将要发送的正文弹出来让用户确认。 */
    public static boolean confirmBeforeSend(Context ctx) {
        return sp(ctx).getBoolean(KEY_PREVIEW, false);
    }

    public static void setConfirmBeforeSend(Context ctx, boolean value) {
        sp(ctx).edit().putBoolean(KEY_PREVIEW, value).apply();
    }

    /** 自动整理间隔（分钟）。0 表示关闭。JobScheduler 的硬下限是 15 分钟。 */
    public static int autoIntervalMinutes(Context ctx) {
        return sp(ctx).getInt(KEY_AUTO_MINUTES, 0);
    }

    public static void setAutoIntervalMinutes(Context ctx, int minutes) {
        sp(ctx).edit().putInt(KEY_AUTO_MINUTES, Math.max(0, minutes)).apply();
    }

    /**
     * 被采集规则拦掉的消息只记计数与最后一次命中的规则，**不存正文** ——
     * 用户把某个群拉黑，就是不想让它出现在库里。
     */
    public static void noteBlocked(Context ctx, String reason, String title) {
        SharedPreferences p = sp(ctx);
        p.edit()
                .putInt(KEY_BLOCKED_COUNT, p.getInt(KEY_BLOCKED_COUNT, 0) + 1)
                .putString(KEY_BLOCKED_LAST, reason + " · " + (title == null ? "" : title))
                .apply();
    }

    public static int blockedCount(Context ctx) {
        return sp(ctx).getInt(KEY_BLOCKED_COUNT, 0);
    }

    public static String blockedLast(Context ctx) {
        return sp(ctx).getString(KEY_BLOCKED_LAST, "");
    }
}
