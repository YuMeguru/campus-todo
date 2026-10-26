package dev.campus.todo;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 采集白名单、噪声过滤与文本归一化。
 *
 * 纯逻辑：不碰 Android API，可直接在 JVM 上单测（见 FilterTest）。
 */
public final class Filter {

    private Filter() {
    }

    /**
     * 默认开启的采集范围：主流 IM + 校园 + 短信。
     *
     * 这只是默认值 —— 用户在「采集应用」页里勾成什么，就以什么为准（见 Prefs.enabledApps）。
     * 各厂商的短信包名都列上了：没装的不会出现在可选列表里，写多了也不坏事。
     */
    public static final Set<String> DEFAULT_PACKAGES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "com.tencent.mm",                     // 微信
                    "com.tencent.mobileqq",               // QQ
                    "com.tencent.tim",                    // TIM
                    "com.alibaba.android.rimet",          // 钉钉
                    "com.tencent.wework",                 // 企业微信
                    "com.ss.android.lark",                // 飞书
                    "com.chaoxing.mobile",                // 学习通
                    "com.tencent.androidqqmail",          // QQ 邮箱
                    "com.netease.mail",                   // 网易邮箱
                    "com.android.mms",                    // 短信（AOSP）
                    "com.google.android.apps.messaging",  // 短信（Google）
                    "com.coloros.sms",                    // 短信（OPPO / 一加）
                    "com.oplus.sms",                      // 短信（OPPO 新）
                    "com.oneplus.sms",                    // 短信（一加）
                    "com.miui.smsextra",                  // 短信（小米）
                    "com.huawei.mms",                     // 短信（华为）
                    "com.sec.android.app.mms"             // 短信（三星）
            )));

    public static final int MIN_TEXT = 4;
    public static final int MAX_TEXT = 2000;

    private static final Pattern VERIFY_CODE = Pattern.compile(
            "(验证码|动态码|校验码|verification code)\\D{0,6}\\d{4,8}"
                    // 数字在前时中间常夹着“是您的”之类的字，所以用 \D{0,4} 而不是只允许空白。
                    + "|\\d{4,8}\\D{0,4}(验证码|动态码|校验码)");

    /**
     * 支付类收据的“强”词：这些出现在普通聊天里的概率极低。
     * 单独的“转账”“红包”不算强词 —— 「明天18点前转账缴纳班费」是正经待办，
     * 被它误杀就丢了一条真任务。
     */
    private static final Pattern PAYMENT_RECEIPT = Pattern.compile(
            "已收款|收款到账|支付成功|付款成功|退款成功|扣款|账单|还款|余额变动");

    /** 金额：¥12、￥12.5、12.00元。系统收据一定带金额，聊天里的“转账”不一定。 */
    private static final Pattern AMOUNT = Pattern.compile("[¥￥]\\s*\\d|\\d+(?:\\.\\d{1,2})?\\s*元");

    /** 只在标题里判定：标题是“微信支付/支付宝”这类才是系统收据，而不是同学在说转账。 */
    private static final Pattern PAYMENT_TITLE = Pattern.compile(
            "微信支付|支付宝|转账|红包|收款");

    /**
     * 营销词只匹配标题。放在正文上会把同学聊天里的“优惠/福利”一起误杀，
     * 而漏掉真待办比多留一条广告更糟。
     */
    private static final Pattern PROMO_TITLE = Pattern.compile(
            "优惠|促销|领券|折扣|特价|限时购|满减|秒杀|抽奖|福利|邀请有奖|推荐有奖");

    /** 返回 true 表示这条通知不值得进入待分析队列。 */
    public static boolean isNoise(String title, String body) {
        String joined = ((title == null ? "" : title) + " " + (body == null ? "" : body)).trim();
        if (joined.length() < MIN_TEXT) {
            return true;
        }
        if (VERIFY_CODE.matcher(joined).find()) {
            return true;
        }
        // 支付噪声要「强收据词 + 金额」同时成立。只看“转账/红包”会把
        // 「明天18点前转账缴纳班费」这种真待办误杀 —— 漏掉真待办比多留一条收款通知糟得多。
        if (PAYMENT_RECEIPT.matcher(joined).find() && AMOUNT.matcher(joined).find()) {
            return true;
        }
        if (title == null) {
            return false;
        }
        return PAYMENT_TITLE.matcher(title).find() || PROMO_TITLE.matcher(title).find();
    }

    /**
     * 归一化：全角转半角、去掉所有空白与控制字符、转小写。
     *
     * “去掉所有空白”是刻意为两件事共用的：一是去重哈希（同一通知重发时
     * 空白可能不同），二是校验模型给的 evidence 是否真的出现在原文里
     * （模型经常在不该断句的地方插空格）。
     */
    public static String normalize(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) {
                c = (char) (c - 0xFEE0);
            } else if (c == 0x3000) {
                c = ' ';
            }
            if (c == 0x200B || c == 0xFEFF || c == 0x200E || c == 0x200F) {
                continue;
            }
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                continue;
            }
            if (Character.isISOControl(c)) {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** 用于展示与入库：把换行/制表压成单个空格，去掉控制字符，并限长。 */
    public static String clean(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean pendingSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0x200B || c == 0xFEFF) {
                continue;
            }
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)) {
                pendingSpace = sb.length() > 0;
                continue;
            }
            if (pendingSpace) {
                sb.append(' ');
                pendingSpace = false;
            }
            sb.append(c);
        }
        String out = sb.toString();
        return out.length() > MAX_TEXT ? out.substring(0, MAX_TEXT) : out;
    }

    /**
     * 内容指纹（FNV-1a 64）。自己实现而不引 MessageDigest，是为了让它成为
     * 一个确定性、可单测的纯函数；这里只需要去重，不需要密码学强度。
     */
    public static String hash(String pkg, String title, String body) {
        String material = normalize(pkg) + '\u0001' + normalize(title) + '\u0001' + normalize(body);
        byte[] bytes = material.getBytes(StandardCharsets.UTF_8);
        long h = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            h ^= (b & 0xFFL);
            h *= 0x100000001b3L;
        }
        return String.format("%016x", h);
    }
}
