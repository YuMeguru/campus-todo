package dev.campus.todo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FilterTest {

    @Test
    public void defaultsCoverTheEverydayApps() {
        assertTrue(Filter.DEFAULT_PACKAGES.contains("com.tencent.mm"));
        assertTrue(Filter.DEFAULT_PACKAGES.contains("com.tencent.mobileqq"));
        assertTrue(Filter.DEFAULT_PACKAGES.contains("com.alibaba.android.rimet"));
        assertTrue(Filter.DEFAULT_PACKAGES.contains("com.chaoxing.mobile"));
        assertFalse(Filter.DEFAULT_PACKAGES.contains("com.example.not.installed"));
        assertFalse(Filter.DEFAULT_PACKAGES.isEmpty());
    }

    @Test
    public void tooShortIsNoise() {
        assertTrue(Filter.isNoise("", "在吗"));
        assertFalse(Filter.isNoise("班群", "明天下午两点开班会，别忘了"));
    }

    @Test
    public void verificationCodeIsNoise() {
        assertTrue(Filter.isNoise("验证码", "您的验证码是 483920，5 分钟内有效"));
        assertTrue(Filter.isNoise("", "483920 是您的验证码"));
    }

    @Test
    public void paymentIsNoise() {
        assertTrue(Filter.isNoise("微信支付", "已收款 12.00 元"));
        assertTrue(Filter.isNoise("支付宝", "支付成功 ¥35.50"));
    }

    @Test
    public void transferWordingIsNotNoiseWhenItIsARealTask() {
        // 这是正经待办，不是收款通知。旧实现被“转账”二字直接过滤掉了。
        assertFalse(Filter.isNoise("班长", "明天18点前转账缴纳班费"));
        assertFalse(Filter.isNoise("生活委员", "班费每人50元，本周五前交"));
        // 聊天里出现支付词但没有金额/收据语义，也不能算噪声。
        assertFalse(Filter.isNoise("室友", "上次那个退款到账了没"));
    }

    @Test
    public void promoOnlyMatchesTitle() {
        assertTrue(Filter.isNoise("限时特价", "全场五折起"));
        // 正文里出现“优惠”不该误杀同学消息：漏掉真待办比多留一条广告更糟。
        assertFalse(Filter.isNoise("班长", "这周实验课有优惠券领吗"));
    }

    @Test
    public void normalizeFoldsFullWidthAndDropsAllWhitespace() {
        assertEquals("abc123", Filter.normalize("ＡＢＣ １２３"));
        assertEquals("提交报告", Filter.normalize(" 提交  报告 "));
        assertEquals("", Filter.normalize(null));
    }

    @Test
    public void hashIgnoresWhitespaceButNotContent() {
        String a = Filter.hash("com.tencent.mm", "班群", "明天交作业");
        String b = Filter.hash("com.tencent.mm", "班群", "明天 交作业");
        String c = Filter.hash("com.tencent.mm", "班群", "明天交论文");
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    public void cleanCollapsesWhitespaceAndTruncates() {
        assertEquals("a b c", Filter.clean("a\n\tb   c"));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < Filter.MAX_TEXT + 50; i++) {
            big.append('字');
        }
        assertEquals(Filter.MAX_TEXT, Filter.clean(big.toString()).length());
    }
}
