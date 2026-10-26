package dev.campus.todo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 扫描雷达：同心圆 + 旋转扫掠，扫到的设备按信号强度放在对应半径上。
 *
 * 为什么自己画：这个工程运行时依赖是 0 个第三方库，而雷达就是几段圆弧加几个点，
 * 用 Canvas 画比引一个库划算。
 *
 * 一个前提要说清：扫描是按服务 UUID 过滤的，正常只会扫到一台设备，所以雷达上通常只有
 * 一个点。它的价值不在「热闹」，而在于把最长 15 秒的扫描等待变成看得见的过程 ——
 * 顺带用半径表达一个诚实的信息：信号强说明离得近。
 */
public final class ScanRadarView extends View {

    /** 扫到的设备。角度在加入时分配一次并固定，否则每帧重算会让点乱跳。 */
    private static final class Blip {
        final String label;
        final float radiusFraction;
        final float angleDeg;
        float presence;

        Blip(String label, float radiusFraction, float angleDeg) {
            this.label = label;
            this.radiusFraction = radiusFraction;
            this.angleDeg = angleDeg;
        }
    }

    private static final int RING_COUNT = 3;
    private static final float SWEEP_DEG_PER_SEC = 160f;
    private static final float TAIL_DEG = 72f;
    private static final int TAIL_STEPS = 18;
    private static final float BLIP_FADE_PER_SEC = 4.5f;

    /** 强到这个值就当作贴着设备，放在最小半径上。 */
    private static final int RSSI_NEAR = -40;
    /** 弱到这个值就贴外圈，再弱也不出圈（RSSI 本来就不是距离，不装作能测更远）。 */
    private static final int RSSI_FAR = -100;
    private static final float RADIUS_NEAR = 0.34f;
    private static final float RADIUS_FAR = 1.0f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Blip> blips = new ArrayList<>();
    private final RectF arcBox = new RectF();

    private boolean scanning;
    private float sweepDeg;
    private long lastFrameNanos;

    public ScanRadarView(Context context) {
        this(context, null);
    }

    public ScanRadarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(Ui.CARD);
    }

    /**
     * RSSI → 半径比例（0 是圆心，1 是最外圈）。
     *
     * 单独抽成静态纯函数是为了能测：画图测不了，但「信号强就该靠圆心」这件事可以断言。
     */
    public static float radiusFraction(int rssi) {
        if (rssi >= RSSI_NEAR) {
            return RADIUS_NEAR;
        }
        if (rssi <= RSSI_FAR) {
            return RADIUS_FAR;
        }
        float t = (RSSI_NEAR - rssi) / (float) (RSSI_NEAR - RSSI_FAR);
        return RADIUS_NEAR + (RADIUS_FAR - RADIUS_NEAR) * t;
    }

    // ------------------------------------------------------------------ 对外

    public void setScanning(boolean value) {
        if (scanning == value) {
            return;
        }
        scanning = value;
        lastFrameNanos = 0L;
        invalidate();
    }

    public void clearBlips() {
        blips.clear();
        invalidate();
    }

    /** 扫到一台设备。rssi 决定它落在哪个半径上。 */
    public void addBlip(String label, int rssi) {
        for (Blip existing : blips) {
            if (existing.label.equals(label)) {
                return;  // 同一台设备反复上报（扫描回调很密），只留一个点
            }
        }
        // 角度按已有点数散开，避免重叠；加点抖动以免每次都落在同一条线上。
        float angle = (blips.size() * 137.5f + 24f) % 360f;
        blips.add(new Blip(label, radiusFraction(rssi), angle));
        lastFrameNanos = 0L;
        invalidate();
    }

    public boolean hasBlip() {
        return !blips.isEmpty();
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float width = getWidth();
        float height = getHeight();
        float centerX = width / 2f;
        float centerY = height / 2f;
        float maxRadius = Math.min(width, height) / 2f - Ui.dp(getContext(), 16);
        if (maxRadius <= 0f) {
            return;
        }

        boolean animating = advance(maxRadius);
        drawRings(canvas, centerX, centerY, maxRadius);
        if (scanning) {
            drawSweep(canvas, centerX, centerY, maxRadius);
        }
        drawBlips(canvas, centerX, centerY, maxRadius, width);
        drawCenter(canvas, centerX, centerY);

        // 只在需要动的时候排下一帧：静止时不停重绘，省电。
        if (animating) {
            postInvalidateOnAnimation();
        }
    }

    /** 按真实时间推进动画（不按帧数，否则不同刷新率下速度不一样）。返回是否还需要下一帧。 */
    private boolean advance(float maxRadius) {
        long now = System.nanoTime();
        float deltaSec = lastFrameNanos == 0L ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        if (deltaSec > 0.25f) {
            deltaSec = 0.25f;  // 后台切回来时别一帧跳很远
        }

        boolean animating = scanning;
        if (scanning) {
            sweepDeg = (sweepDeg + SWEEP_DEG_PER_SEC * deltaSec) % 360f;
        }
        for (Blip blip : blips) {
            if (blip.presence < 1f) {
                blip.presence = Math.min(1f, blip.presence + BLIP_FADE_PER_SEC * deltaSec);
                animating = true;
            }
        }
        // maxRadius 目前不参与推进，留着是因为尾巴长度以后可能要按尺寸缩放。
        if (maxRadius <= 0f) {
            return animating;
        }
        return animating;
    }

    private void drawRings(Canvas canvas, float cx, float cy, float maxRadius) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(getContext(), 1));
        paint.setColor(Ui.MUTED);
        paint.setAlpha(70);
        for (int i = 1; i <= RING_COUNT; i++) {
            canvas.drawCircle(cx, cy, maxRadius * i / RING_COUNT, paint);
        }
        paint.setAlpha(40);
        canvas.drawLine(cx - maxRadius, cy, cx + maxRadius, cy, paint);
        canvas.drawLine(cx, cy - maxRadius, cx, cy + maxRadius, paint);
        paint.setAlpha(255);
    }

    private void drawSweep(Canvas canvas, float cx, float cy, float maxRadius) {
        arcBox.set(cx - maxRadius, cy - maxRadius, cx + maxRadius, cy + maxRadius);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(getContext(), 12));
        paint.setColor(Ui.ACCENT);

        // 用一串逐渐变亮的弧拼出尾巴，避免为渐变去算 SweepGradient 的旋转矩阵 ——
        // 逻辑简单到能一眼看懂，代价只是每帧多十几次 drawArc。
        float step = TAIL_DEG / TAIL_STEPS;
        for (int i = 0; i < TAIL_STEPS; i++) {
            float lead = 1f - (float) i / TAIL_STEPS;
            paint.setAlpha((int) (10 + 150 * lead * lead));
            float start = sweepDeg - (i + 1) * step;
            canvas.drawArc(arcBox, start, step + 0.5f, false, paint);
        }
        paint.setAlpha(255);
    }

    private void drawBlips(Canvas canvas, float cx, float cy, float maxRadius, float width) {
        float dotRadius = Ui.dp(getContext(), 5);
        for (Blip blip : blips) {
            double rad = Math.toRadians(blip.angleDeg);
            float x = cx + (float) Math.cos(rad) * maxRadius * blip.radiusFraction;
            float y = cy + (float) Math.sin(rad) * maxRadius * blip.radiusFraction;
            int alpha = (int) (255 * blip.presence * blip.presence);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.OK);
            paint.setAlpha(alpha / 3);
            canvas.drawCircle(x, y, dotRadius * 2.6f, paint);
            paint.setAlpha(alpha);
            canvas.drawCircle(x, y, dotRadius, paint);

            paint.setTextSize(Ui.dp(getContext(), 12));
            paint.setColor(Ui.TEXT);
            paint.setAlpha(alpha);
            float textWidth = paint.measureText(blip.label);
            float textX = Math.min(x + dotRadius * 2.6f + Ui.dp(getContext(), 4),
                    width - textWidth - Ui.dp(getContext(), 4));
            canvas.drawText(blip.label, Math.max(Ui.dp(getContext(), 4), textX),
                    y - dotRadius - Ui.dp(getContext(), 2), paint);
            paint.setAlpha(255);
        }
    }

    private void drawCenter(Canvas canvas, float cx, float cy) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.ACCENT);
        paint.setAlpha(scanning ? 255 : 120);
        canvas.drawCircle(cx, cy, Ui.dp(getContext(), 4), paint);
        paint.setAlpha(255);
    }

    @Override
    protected void onDetachedFromWindow() {
        // 视图没了就停掉动画循环，否则 invalidate 会一直排下去。
        scanning = false;
        super.onDetachedFromWindow();
    }
}
