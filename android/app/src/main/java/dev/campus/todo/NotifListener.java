package dev.campus.todo;

import android.app.Notification;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * 后端的第一段：通知采集。
 *
 * 采集范围由用户在「采集应用」页里勾选，默认是一份主流 IM / 校园 / 短信清单。
 *
 * 边界（UI 里也要如实告诉用户，不要装作能拿到）：
 *   - 只认用户勾选过的应用；
 *   - 常驻通知（播放器、下载、省电守护这些）直接丢 —— 它们不是消息；
 *   - 静音/免打扰群、开了“隐藏通知内容”的会话、以及任何没弹出通知的消息，这里都不会收到；
 *   - 图片、语音、文件类通知只有标题文案，拿不到内容。
 */
public final class NotifListener extends NotificationListenerService {

    private static final String TAG = "NotifListener";

    @Override
    public void onListenerConnected() {
        Log.i(TAG, "通知使用权已连接");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            return;
        }
        // 常驻通知不是消息：音乐播放器、下载进度、输入法、省电守护全在这个类里。
        // 放开采集范围之后它们一天能来几十条，在最前面挡掉最省事。
        if (sbn.isOngoing()) {
            return;
        }
        String pkg = sbn.getPackageName();
        if (!Prefs.isAppEnabled(this, pkg)) {
            return;
        }

        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;
        String title = text(extras.getCharSequence(Notification.EXTRA_TITLE));
        // 聊天类通知常用 conversationTitle 存会话名；标题和它都要参与规则匹配。
        String conversationTitle =
                text(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        String body = text(extras.getCharSequence(Notification.EXTRA_TEXT));

        // 群聊的完整内容常常在 bigText 或 textLines 里，取最长的那份。
        String big = text(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (big.length() > body.length()) {
            body = big;
        }
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null && lines.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (CharSequence line : lines) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(text(line));
            }
            String joined = sb.toString().trim();
            if (joined.length() > body.length()) {
                body = joined;
            }
        }

        // MessagingStyle（多数聊天应用的直接回复通知）把每条消息放进 EXTRA_MESSAGES 的
        // Bundle 数组，只看 EXTRA_TEXT 会拿到被截断的那一句。
        // 公开 API 没有解析器（extractMessagingStyleFromNotification 是 SystemApi），
        // 所以直接读 Bundle：这些键是通知跨进程传输的线上格式，稳定。
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null && messages.length > 0) {
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, messages.length - 3);   // 最近 3 条足够拼出上下文
            for (int i = from; i < messages.length; i++) {
                if (!(messages[i] instanceof Bundle)) {
                    continue;
                }
                CharSequence line = ((Bundle) messages[i]).getCharSequence("text");
                if (line == null || line.length() == 0) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(line);
            }
            String joined = sb.toString().trim();
            if (joined.length() > body.length()) {
                body = joined;
            }
        }

        // 采集规则：命中的消息正文直接不入库，只累加一个计数 ——
        // 用户把某个群拉黑，就是不想让它出现在库里。
        // 每次通知查一次规则表是个几行的小查询，先不过度优化；规则量大了再加缓存。
        String blocked = Rules.decide(Db.get(this).listRules(), title, conversationTitle);
        if (blocked != null) {
            Prefs.noteBlocked(this, blocked, title);
            Log.i(TAG, "规则拦截：" + blocked);
            return;
        }

        Model.Raw raw = new Model.Raw();
        raw.pkg = pkg;
        raw.pkgLabel = AppCatalog.label(this, pkg);
        raw.title = Filter.clean(title);
        raw.body = Filter.clean(body);
        raw.postedAt = sbn.getPostTime() > 0 ? sbn.getPostTime() : System.currentTimeMillis();
        raw.ingest = "auto";
        raw.state = Filter.isNoise(raw.title, raw.body) ? "skip" : "new";

        if (raw.state.equals("new") && raw.title.isEmpty() && raw.body.isEmpty()) {
            return;
        }

        long id = Db.get(this).insertRaw(raw);
        if (id < 0) {
            return;   // 指纹重复：同一条通知被系统重复回调
        }
        Log.i(TAG, "采集 " + raw.pkgLabel + " state=" + raw.state + " id=" + id);
    }

    private static String text(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }
}
