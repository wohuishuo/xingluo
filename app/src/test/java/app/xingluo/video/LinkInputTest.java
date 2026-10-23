package app.xingluo.video;

import org.junit.Test;
import static org.junit.Assert.*;

public class LinkInputTest {
    @Test public void extractsUsersMixedShareText() {
        assertEquals("https://b23.tv/y7xiWGg", LinkInput.extract("【🥒若不是睦突然闯进我生活 & 睦睦的酒🥒【bilibilionly同人扶持计划】-哔哩哔哩】 https://b23.tv/y7xiWGg 。"));
    }
    @Test public void trimsClosingPunctuationButKeepsQuery() {
        assertEquals("https://v.douyin.com/abc/?x=1&y=2", LinkInput.extract("分享 (https://v.douyin.com/abc/?x=1&y=2)。"));
    }
    @Test public void bareBvBecomesCanonicalUrl() {
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", LinkInput.extract("看 BV1xx411c7mD"));
    }
    @Test public void domainsCannotImpersonatePlatforms() {
        assertEquals(LinkInput.Platform.WEB, LinkInput.platform("https://bilibili.com.evil.test/video/x"));
        assertEquals(LinkInput.Platform.BILIBILI, LinkInput.platform("https://m.bilibili.com/video/BV1xx411c7mD"));
    }
    @Test public void rejectsNonHttpAndMissingLinks() {
        assertThrows(IllegalArgumentException.class, () -> LinkInput.extract("javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> LinkInput.extract("微信视频号卡片"));
        assertFalse(LinkInput.isWebUrl("https://user:secret@example.com/video.mp4"));
    }
    @Test public void safeFilenameCannotEscapeFolder() {
        String value = LinkInput.safeName("../坏:名/称\\测试?*\n.mp4");
        assertFalse(value.contains("/")); assertFalse(value.contains("\\")); assertFalse(value.startsWith("."));
        assertTrue(value.length() <= 90);
    }
    @Test public void keepsBiliPage() { assertEquals(3, LinkInput.page("https://www.bilibili.com/video/BV1xx411c7mD?p=3&x=1")); }
}
