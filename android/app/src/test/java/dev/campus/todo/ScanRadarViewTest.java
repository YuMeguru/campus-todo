package dev.campus.todo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 雷达里唯一与绘制无关的逻辑：RSSI → 半径。
 *
 * 绘制本身在主机上测不了（要真机或 Robolectric），但「信号强就该靠圆心」和两端钳位
 * 是可以断言的 —— 而这恰好是最容易写反的地方（正负号一颠倒，雷达就完全在说反话）。
 */
public class ScanRadarViewTest {

    @Test
    public void strongerSignalSitsCloserToTheCenter() {
        float near = ScanRadarView.radiusFraction(-45);
        float mid = ScanRadarView.radiusFraction(-70);
        float far = ScanRadarView.radiusFraction(-95);
        assertTrue("信号越强半径应越小，实际 " + near + " vs " + mid, near < mid);
        assertTrue("信号越弱半径应越大，实际 " + mid + " vs " + far, mid < far);
    }

    @Test
    public void clampsAtBothEnds() {
        // 比「贴脸」还强：不许缩进圆心里面
        assertEquals(ScanRadarView.radiusFraction(-40), ScanRadarView.radiusFraction(-10), 1e-6);
        // 比最弱阈值还弱：不许跑出最外圈
        assertEquals(ScanRadarView.radiusFraction(-100), ScanRadarView.radiusFraction(-127), 1e-6);
    }

    @Test
    public void alwaysStaysInsideTheOuterRing() {
        for (int rssi = -127; rssi <= 0; rssi++) {
            float fraction = ScanRadarView.radiusFraction(rssi);
            assertTrue("rssi=" + rssi + " 落到圈外: " + fraction,
                    fraction > 0f && fraction <= 1f);
        }
    }

    @Test
    public void midpointIsHalfwayBetweenTheTwoRadii() {
        // -40..-100 的中点 -70 应落在 0.34..1.0 的中点
        assertEquals((0.34f + 1.0f) / 2f, ScanRadarView.radiusFraction(-70), 1e-4);
    }
}
