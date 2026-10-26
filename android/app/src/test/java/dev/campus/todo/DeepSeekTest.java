package dev.campus.todo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 接口契约与退避策略的锁定测试：这些参数一旦被“顺手改一下”，这里会立刻失败。
 */
public class DeepSeekTest {

    // 单元测试也编译在 android.jar 上，那边的 org.json 把 JSONException 声明成受检异常，
    // 所以这里必须显式 throws（JVM 版实现里它是运行时异常，不影响）。
    @Test
    public void requestBodyMatchesTheOfficialContract() throws Exception {
        JSONObject body = new JSONObject(DeepSeek.buildRequestJson("测试"));

        assertEquals(DeepSeek.MODEL, body.getString("model"));
        assertEquals("deepseek-flash", body.getString("model"));

        // JSON Output 的两个必要条件：response_format + prompt 里出现 "json"。
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"));

        // 思考模式默认是开的，必须显式关掉。
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"));

        assertEquals(DeepSeek.MAX_OUTPUT_TOKENS, body.getInt("max_tokens"));
        assertEquals(false, body.getBoolean("stream"));

        assertEquals(2, body.getJSONArray("messages").length());
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"));
        assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("json"));
        assertEquals("user", body.getJSONArray("messages").getJSONObject(1).getString("role"));
    }

    @Test
    public void backoffGrowsThenCaps() {
        assertEquals(2_000L, DeepSeek.backoffMs(0));
        assertEquals(6_000L, DeepSeek.backoffMs(1));
        assertEquals(18_000L, DeepSeek.backoffMs(2));
        assertEquals(30_000L, DeepSeek.backoffMs(3));
        assertEquals(30_000L, DeepSeek.backoffMs(9));
    }

    @Test
    public void userMessageCarriesArrivalTimeAndNumberedItems() {
        long now = OffsetDateTime.parse("2026-10-08T12:00:00+08:00").toInstant().toEpochMilli();
        long arrived = now - 2L * 24 * 3600 * 1000;   // 两天前收到的通知

        List<Model.Raw> batch = new ArrayList<>();
        Model.Raw first = new Model.Raw();
        first.pkgLabel = "微信";
        first.title = "班群";
        first.body = "请在10月9日23:59前提交实验报告";
        first.postedAt = arrived;
        batch.add(first);

        Model.Raw second = new Model.Raw();
        second.pkgLabel = "钉钉";
        second.title = "辅导员";
        second.body = "明天下午两点开班会";
        second.postedAt = arrived;
        batch.add(second);

        String stamp = DeepSeek.messageTime(arrived);
        String message = DeepSeek.buildUserMessage(now, batch);

        assertTrue(message.contains("当前时间：2026-10-08T12:00:00+08:00"));
        // 每条通知都必须带上自己的到达时间：只给“当前时间”会让“明天”按整理时刻算错。
        assertTrue(message.contains("1) [微信 " + stamp + "] 班群：请在10月9日23:59前提交实验报告"));
        assertTrue(message.contains("2) [钉钉 " + stamp + "] 辅导员：明天下午两点开班会"));
        assertTrue(message.contains("相对时间请以它为准"));
    }

    @Test
    public void defaultBaseIsTheOfficialHttpsEndpoint() {
        assertEquals("https://api.deepseek.com", DeepSeek.DEFAULT_BASE);
        ZoneId.getAvailableZoneIds();   // keep the import honest: 接口不受时区影响
    }
}
