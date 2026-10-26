package dev.campus.todo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * DeepSeek 官方接口的客户端。这是整个 App 唯一的出网点。
 *
 * 接口事实（照官方文档，别凭印象改）：
 *   - 模型名 deepseek-flash；思考模式**默认开启**，这里显式关掉；
 *   - JSON Output 需要 response_format={"type":"json_object"}，且 prompt 里要出现 "json"
 *     并给出格式样例；
 *   - 官方明确说明 JSON Output 偶尔会返回空 content —— 空内容按失败处理并重试一次，
 *     不能当成“没有待办”。
 *
 * 本文件不引用 Android API（只用 java.net 与 org.json），所以请求体的构造、
 * 退避策略这些逻辑可以在 JVM 上单测。
 */
public final class DeepSeek {

    private DeepSeek() {
    }

    public static final String DEFAULT_BASE = "https://api.deepseek.com";
    /** 唯一允许的出网主机。刻意不提供任何运行时改地址的入口。 */
    public static final String OFFICIAL_HOST = "api.deepseek.com";
    public static final String MODEL = "deepseek-flash";
    public static final int MAX_ATTEMPTS = 3;
    public static final int CONNECT_TIMEOUT_MS = 10_000;
    public static final int READ_TIMEOUT_MS = 60_000;
    public static final int MAX_OUTPUT_TOKENS = 2048;

    private static final long BACKOFF_BASE_MS = 2_000L;
    private static final long BACKOFF_CAP_MS = 30_000L;

    /** 逐字节固定：前缀稳定才能命中上下文缓存（输入价差 50 倍）。 */
    public static final String SYSTEM_PROMPT =
            "你是一个校园消息整理助手。用户给你若干条手机通知，每条前面标着它到达手机的时间，"
                    + "请提取其中需要执行的待办事项。\n"
                    + "\n"
                    + "只输出一个 json 对象，不要输出任何解释文字。格式示例：\n"
                    + "{\"items\":["
                    + "{\"src\":1,\"title\":\"提交数据结构实验报告\",\"course\":\"数据结构\","
                    + "\"kind\":\"homework\",\"due_text\":\"本周五23:59前\","
                    + "\"due_iso\":\"2026-10-09T23:59:00+08:00\","
                    + "\"evidence\":\"请在10月9日23:59前提交实验报告\",\"confidence\":0.82},"
                    + "{\"src\":2,\"title\":\"本周晚自习教室：至勤楼311\",\"kind\":\"info\","
                    + "\"due_text\":\"本周\",\"due_iso\":null,"
                    + "\"evidence\":\"26造价2班 至勤楼311\",\"confidence\":0.9}],"
                    + "\"ignored\":[{\"index\":3,\"reason\":\"闲聊\"}]}\n"
                    + "\n"
                    + "规则：\n"
                    + "1. src 是这条待办来自第几条通知，用上面给出的序号。\n"
                    + "2. kind 只能是 homework、exam、notice、info、other 之一。\n"
                    + "3. evidence 必须是原文里的原话片段，逐字复制，不要改写、不要翻译、不要补字。\n"
                    + "4. due_text 是原文中的时间表述，原样保留；due_iso 是你推断出的绝对时间，"
                    + "ISO 8601 带时区偏移。\n"
                    + "5. 换算相对时间（今天/明天/后天/本周五）时，一律以该条通知前面标的时间为基准，"
                    + "不要用“当前时间”——通知可能是几天前收到的。\n"
                    + "6. 无法确定具体时间时，due_iso 写 null，不要猜。\n"
                    + "7. confidence 是你判断“这条确实是一个待办”的把握，0 到 1 之间的小数。\n"
                    + "8. 参考信息类内容（班级通知、教室/课程安排、考试安排、调课、放假通知等）"
                    + "也要提取出来，用 kind=\"info\"，due_iso 一律写 null。这类不是待办，"
                    + "是给人回头查的资料，同样要给出 evidence。\n"
                    + "9. 只有真正无意义的闲聊、广告、验证码才放进 ignored，并给出 reason。\n"
                    + "10. items 最多 20 条；没有可提取的内容时 items 返回空数组。";

    public static final class Response {
        public int httpStatus;
        public String content = "";
        public String error;
        public boolean fatal;
        public int attempts;
        public int inTokens;
        public int outTokens;
        public int cacheTokens;
        public long durationMs;

        public boolean ok() {
            return error == null && content != null && !content.isEmpty();
        }
    }

    /** 只能打官方域名：出网地址不接受任何运行时配置。 */
    public static Response complete(String apiKey, String userText) {
        Response r = new Response();
        String requestBody = buildRequestJson(userText);
        long started = System.currentTimeMillis();

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            r.attempts = attempt + 1;
            if (attempt > 0) {
                long base = backoffMs(attempt - 1);
                if (!sleep(base + jitter(base))) {
                    r.error = "等待重试时被中断";
                    break;
                }
            }

            HttpURLConnection conn = null;
            try {
                conn = open(apiKey);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(requestBody.getBytes(StandardCharsets.UTF_8));
                }
                int status = conn.getResponseCode();
                r.httpStatus = status;
                String text = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());

                if (status == 200) {
                    if (parseSuccess(text, r)) {
                        r.error = null;
                        r.durationMs = System.currentTimeMillis() - started;
                        return r;
                    }
                    r.error = "200 但没有可用的 JSON 内容（JSON Output 已知会偶发空 content）";
                    continue;
                }
                if (status == 401 || status == 403) {
                    r.fatal = true;
                    r.error = "API Key 无效或没有权限（HTTP " + status + "）";
                    break;
                }
                if (status == 402) {
                    r.fatal = true;
                    r.error = "账户余额不足（HTTP 402），已暂停调用";
                    break;
                }
                r.error = "HTTP " + status + "：" + firstLine(text);
                if (status != 429 && status < 500) {
                    break;   // 400/404/422 重试没有意义
                }
            } catch (IOException e) {
                r.httpStatus = 0;
                r.error = "网络异常：" + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        r.durationMs = System.currentTimeMillis() - started;
        return r;
    }

    /** 指数退避的确定性部分：2s → 6s → 18s，封顶 30s。抖动由 jitter() 另外加。 */
    public static long backoffMs(int attempt) {
        long base = BACKOFF_BASE_MS;
        for (int i = 0; i < attempt; i++) {
            base *= 3;
            if (base >= BACKOFF_CAP_MS) {
                return BACKOFF_CAP_MS;
            }
        }
        return Math.min(base, BACKOFF_CAP_MS);
    }

    static long jitter(long base) {
        return (long) ((Math.random() * 2d - 1d) * base * 0.3d);
    }

    private static boolean sleep(long ms) {
        if (ms <= 0) {
            return true;
        }
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static String buildRequestJson(String userText) {
        // Android 的 org.json 把 JSONException 声明成受检异常（JVM 版是运行时异常），
        // 这里统一收口：只有我们自己写错常量才会走到 catch，属于编码错误。
        try {
            JSONObject root = new JSONObject();
            root.put("model", MODEL);
            // 思考模式默认开启；显式关掉，否则白付输出 token 且更慢。
            root.put("thinking", new JSONObject().put("type", "disabled"));
            root.put("response_format", new JSONObject().put("type", "json_object"));
            root.put("max_tokens", MAX_OUTPUT_TOKENS);
            root.put("stream", false);

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));
            messages.put(new JSONObject().put("role", "user").put("content", userText));
            root.put("messages", messages);

            return root.toString();
        } catch (JSONException e) {
            throw new IllegalStateException("构造请求体失败: " + e.getMessage(), e);
        }
    }

    /**
     * 每条通知都带上它**到达手机的时间**。只给「当前时间」是不够的：
     * 10-06 收到“明天开班会”、10-08 才整理时，按整理时刻推会得到 10-09，白错两天。
     */
    public static String buildUserMessage(long nowMillis, List<Model.Raw> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前时间：").append(localIso(nowMillis)).append('\n');
        sb.append("待整理通知（每条前面的时间是它到达手机的时间，相对时间请以它为准）：\n");
        for (int i = 0; i < batch.size(); i++) {
            Model.Raw raw = batch.get(i);
            sb.append(i + 1).append(") [")
                    .append(raw.pkgLabel == null ? "" : raw.pkgLabel).append(' ')
                    .append(messageTime(raw.postedAt > 0 ? raw.postedAt : nowMillis)).append("] ")
                    .append(Filter.clean(raw.display())).append('\n');
        }
        return sb.toString();
    }

    /** 通知到达时间，精确到分钟。 */
    public static String messageTime(long millis) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    public static String localIso(long millis) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
    }

    private static HttpURLConnection open(String apiKey) throws IOException {
        URL url = new URL(DEFAULT_BASE + "/chat/completions");
        // 方案要求业务请求只允许 DeepSeek 官方地址。API Key 与待办正文都在这一条请求里，
        // 只要允许改地址，就等于给「把 Key 和正文引到第三方域名」留了口子。
        if (!"https".equals(url.getProtocol()) || !OFFICIAL_HOST.equals(url.getHost())) {
            throw new IOException("只允许访问 https://" + OFFICIAL_HOST);
        }
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(false);   // 不接受被引到别的域
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        return conn;
    }

    private static boolean parseSuccess(String body, Response r) {
        try {
            JSONObject root = new JSONObject(body);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return false;
            }
            JSONObject message = choices.optJSONObject(0) == null
                    ? null : choices.optJSONObject(0).optJSONObject("message");
            String content = message == null ? "" : message.optString("content", "");
            if (content.trim().isEmpty()) {
                return false;
            }
            r.content = content;

            JSONObject usage = root.optJSONObject("usage");
            if (usage != null) {
                r.inTokens = usage.optInt("prompt_tokens", 0);
                r.outTokens = usage.optInt("completion_tokens", 0);
                r.cacheTokens = usage.optInt("prompt_cache_hit_tokens", 0);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = stream.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        String one = text.replace('\n', ' ').trim();
        return one.length() > 160 ? one.substring(0, 160) : one;
    }
}
