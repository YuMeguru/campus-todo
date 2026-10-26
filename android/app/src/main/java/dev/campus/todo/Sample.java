package dev.campus.todo;

import android.content.Context;

/**
 * 演示用的示例消息。真机上装完应用不一定马上有微信/钉钉通知，
 * 用它把“采集 → 一键整理 → 待办 → 提醒”整条链路跑起来。
 */
public final class Sample {

    private Sample() {
    }

    private static final String[][] MESSAGES = {
            {"com.tencent.mm", "班群-计科2201", "请在10月9日23:59前提交数据结构实验报告，迟交扣分"},
            {"com.alibaba.android.rimet", "辅导员", "明天下午两点在A301开班会，请准时参加"},
            {"com.chaoxing.mobile", "学习通", "《大学英语》第三单元测试将于本周日23:00截止，请及时完成"},
            {"com.tencent.mm", "室友", "晚上吃啥"},
            // 参考信息：会被放进「班级通知」分区，而不是待办列表。
            {"com.tencent.mm", "26造价2班班级群",
                    "@所有人 各位小伙伴，本周晚自习教室安排如下：26造价2班 至勤楼311，26造价3班 至勤楼313"},
    };

    public static int insert(Context ctx) {
        Db db = Db.get(ctx);
        int inserted = 0;
        long now = System.currentTimeMillis();
        for (int i = 0; i < MESSAGES.length; i++) {
            Model.Raw raw = new Model.Raw();
            raw.pkg = MESSAGES[i][0];
            raw.pkgLabel = AppCatalog.label(ctx, MESSAGES[i][0]);
            raw.title = MESSAGES[i][1];
            raw.body = MESSAGES[i][2];
            raw.postedAt = now - (MESSAGES.length - i) * 60_000L;
            raw.ingest = "sample";
            raw.state = Filter.isNoise(raw.title, raw.body) ? "skip" : "new";
            if (db.insertRaw(raw) >= 0) {
                inserted++;
            }
        }
        return inserted;
    }
}
