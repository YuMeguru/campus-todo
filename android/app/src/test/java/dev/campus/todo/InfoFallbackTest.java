package dev.campus.todo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 本地兜底词表：该救的救回来，不该捞的别捞。 */
public class InfoFallbackTest {

    private static Model.Raw raw(String title, String body) {
        Model.Raw r = new Model.Raw();
        r.pkgLabel = "微信";
        r.title = title;
        r.body = body;
        return r;
    }

    private static Set<Integer> covered(Integer... indexes) {
        return new HashSet<>(Arrays.asList(indexes));
    }

    @Test
    public void classroomAnnouncementIsRescued() {
        // 这条就是用户遇到的那个消息：模型归了 ignored，兜底要把它捞回来。
        List<Model.Raw> batch = new ArrayList<>();
        batch.add(raw("26造价2班班级群",
                "@所有人 各位小伙伴，本周晚自习教室安排如下：26造价2班 至勤楼311"));
        assertEquals(Arrays.asList(1), InfoFallback.rescued(batch, covered()));
    }

    @Test
    public void alreadyCoveredItemsAreNotRescued() {
        List<Model.Raw> batch = new ArrayList<>();
        batch.add(raw("26造价2班班级群", "本周晚自习教室安排：至勤楼311"));
        // 模型已经给了条目，就不要再造一条重复的。
        assertTrue(InfoFallback.rescued(batch, covered(1)).isEmpty());
    }

    @Test
    public void chitChatIsNotRescued() {
        List<Model.Raw> batch = new ArrayList<>();
        batch.add(raw("室友", "晚上吃啥"));
        batch.add(raw("班长", "在吗"));
        assertTrue(InfoFallback.rescued(batch, covered()).isEmpty());
    }

    @Test
    public void singleCharactersDoNotOverreach() {
        // 刻意不用「楼」「课」这类单字：不然「楼下等你」也会被捞进来。
        assertFalse(InfoFallback.looksLikeInfo("室友", "楼下等你，快下来"));
        assertFalse(InfoFallback.looksLikeInfo("同学", "课上说的那个我忘了"));
    }

    @Test
    public void matchingIgnoresWidthAndSpaces() {
        assertTrue(InfoFallback.looksLikeInfo("群", "晚自习 教室 调整到至勤楼"));
        assertTrue(InfoFallback.looksLikeInfo("群", "调 课 通 知"));
        assertTrue(InfoFallback.looksLikeInfo("群", "ＴＯＮＧＺＨＩ 考试安排已发"));
    }

    @Test
    public void onlyUncoveredIndexesAreReturned() {
        List<Model.Raw> batch = new ArrayList<>();
        batch.add(raw("室友", "晚上吃啥"));                 // 1: 无关键词
        batch.add(raw("班群", "本周晚自习教室安排：311"));    // 2: 该救
        batch.add(raw("学委", "考试安排出来了"));            // 3: 该救，但模型已覆盖
        batch.add(raw("社团", "周五聚餐"));                  // 4: 无关键词
        assertEquals(Arrays.asList(2), InfoFallback.rescued(batch, covered(3)));
    }

    @Test
    public void nullAndEmptyInputsAreSafe() {
        assertTrue(InfoFallback.rescued(null, covered()).isEmpty());
        assertTrue(InfoFallback.rescued(new ArrayList<>(), null).isEmpty());
        assertFalse(InfoFallback.looksLikeInfo(null, null));
        assertFalse(InfoFallback.looksLikeInfo("", ""));
    }

    @Test
    public void keywordListIsUsable() {
        assertTrue(InfoFallback.KEYWORDS.length >= 10);
        for (String keyword : InfoFallback.KEYWORDS) {
            // 单字关键词太容易误伤，词表里不允许出现。
            assertTrue("关键词太短：" + keyword, keyword.length() >= 2);
        }
    }
}
