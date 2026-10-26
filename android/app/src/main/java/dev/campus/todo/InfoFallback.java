package dev.campus.todo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 本地兜底：模型没给出任何条目的通知里，只要看起来是教务/后勤类通知，就直接保留成 info。
 *
 * 为什么需要它：把「教室改到 313」这种事交给一次模型判断，就是给了它被静默丢掉的机会。
 * 这份词表把这一类消息从「大概率保留」变成「一定保留」。
 *
 * 代价的不对称是它值得做的理由：误判最坏是多一条 info（不提醒、不进待办列表、可删），
 * 漏判是走错教室。
 *
 * 天花板也说清楚：它靠的是一份**小而稳定**的词表，覆盖的是班级后勤通知这类固定话术；
 * 全新形状的消息它救不了，那仍然是模型判断的地盘。
 *
 * 纯逻辑：不碰 Android API，可直接在 JVM 上单测（见 InfoFallbackTest）。
 */
public final class InfoFallback {

    private InfoFallback() {
    }

    /**
     * 命中即保留。刻意选「教室 / 自习 / 调课」这类词而不是「楼」「课」这种单字：
     * 单字会把「楼下等你」「课上说的」一起捞进来。
     */
    public static final String[] KEYWORDS = {
            // 上课与教室
            "教室", "自习", "调课", "停课", "补课", "课表", "上课时间", "上课地点", "机房", "实验楼",
            // 考试
            "考场", "考试安排", "考试时间", "监考", "补考", "重修",
            // 校历与事务
            "放假", "开学", "报到", "选课", "缴费", "校园卡", "饭卡",
            // 生活
            "宿舍", "寝室", "快递点", "值班",
    };

    public static boolean looksLikeInfo(String title, String body) {
        String haystack = Filter.normalize((title == null ? "" : title)
                + " " + (body == null ? "" : body));
        if (haystack.isEmpty()) {
            return false;
        }
        for (String keyword : KEYWORDS) {
            String needle = Filter.normalize(keyword);
            if (!needle.isEmpty() && haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回本批中「模型没给出任何条目、但看着像教务/后勤通知」的序号（1 起，与 src 对齐）。
     *
     * @param covered 模型已经给出条目的 src 序号集合，可以是 null。
     */
    public static List<Integer> rescued(List<Model.Raw> batch, Set<Integer> covered) {
        List<Integer> out = new ArrayList<>();
        if (batch == null) {
            return out;
        }
        for (int i = 0; i < batch.size(); i++) {
            int index = i + 1;
            if (covered != null && covered.contains(index)) {
                continue;   // 模型已经处理过这条，交给它
            }
            Model.Raw raw = batch.get(i);
            if (raw != null && looksLikeInfo(raw.title, raw.body)) {
                out.add(index);
            }
        }
        return out;
    }
}
