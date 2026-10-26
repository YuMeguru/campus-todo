package dev.campus.todo;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 应用目录：枚举手机里可选的 App、查应用显示名。
 *
 * 与 Filter 分家的理由：Filter 是纯逻辑（JVM 上可直接单测），这里全是 PackageManager 调用。
 */
public final class AppCatalog {

    private AppCatalog() {
    }

    /** 应用名缓存。列表滚动时每帧都在取名字，查一次就够。 */
    private static final Map<String, String> LABELS = new HashMap<>();

    /** 一条可选应用。enabled 是枚举那一刻的快照，界面自己维护勾选状态。 */
    public static final class Entry {
        public final String pkg;
        public final String label;
        public final boolean enabled;

        Entry(String pkg, String label, boolean enabled) {
            this.pkg = pkg;
            this.label = label;
            this.enabled = enabled;
        }
    }

    /**
     * 枚举可选应用，已启用的排在前面。
     *
     * 用 LAUNCHER intent 而不是 getInstalledApplications：后者会把几百个没有界面的
     * 系统组件一起列出来，用户根本认不出哪个是哪个。
     *
     * 已启用但不在启动器里的（某些厂商短信、隐藏了入口的 App）补在最后 ——
     * 开着却看不见、关不掉的开关是最烦人的。
     */
    public static List<Entry> installed(Context ctx, Set<String> enabled) {
        PackageManager pm = ctx.getPackageManager();
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String self = ctx.getPackageName();

        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        if (list != null) {
            for (ResolveInfo ri : list) {
                if (ri.activityInfo == null || ri.activityInfo.packageName == null) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(self) || !seen.add(pkg)) {
                    continue;
                }
                CharSequence label = ri.loadLabel(pm);
                String name = label == null ? pkg : label.toString().trim();
                out.add(new Entry(pkg, name.isEmpty() ? pkg : name,
                        enabled != null && enabled.contains(pkg)));
            }
        }
        if (enabled != null) {
            for (String pkg : enabled) {
                if (pkg == null || pkg.equals(self) || !seen.add(pkg)) {
                    continue;
                }
                out.add(new Entry(pkg, label(ctx, pkg), true));
            }
        }

        Collections.sort(out, (a, b) -> {
            if (a.enabled != b.enabled) {
                return a.enabled ? -1 : 1;      // 勾上的排前面，省得往下翻
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        return out;
    }

    /** 应用显示名。查不到就退回包名 —— 列表里一个空行比包名更难认。 */
    public static String label(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return "未知";
        }
        String cached = LABELS.get(pkg);
        if (cached != null) {
            return cached;
        }
        String name = pkg;
        try {
            PackageManager pm = ctx.getPackageManager();
            CharSequence cs = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            if (cs != null && cs.toString().trim().length() > 0) {
                name = cs.toString().trim();
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // 应用已卸载：保留包名，历史消息还看得出是谁发的。
        }
        LABELS.put(pkg, name);
        return name;
    }
}
