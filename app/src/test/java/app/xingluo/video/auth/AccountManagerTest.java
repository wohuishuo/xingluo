package app.xingluo.video.auth;

import app.xingluo.video.auth.AccountManager.Platform;
import org.junit.Test;
import static org.junit.Assert.*;

public class AccountManagerTest {
    @Test
    public void detectsAuthCookiesForAllEightPlatforms() {
        assertTrue(AccountManager.hasAuthCookie("buvid3=abc; SESSDATA=secret_val; bili_jct=xyz", Platform.BILIBILI));
        assertTrue(AccountManager.hasAuthCookie("ttwid=1%7Cabc; other=1", Platform.DOUYIN));
        assertTrue(AccountManager.hasAuthCookie("sessionid=dy_sess_99", Platform.DOUYIN));
        assertTrue(AccountManager.hasAuthCookie("a1=guest; web_session=xhs_token_123", Platform.XIAOHONGSHU));
        assertTrue(AccountManager.hasAuthCookie("kuaishou.server.web_st=ks_st_abc; did=web_123", Platform.KUAISHOU));
        assertTrue(AccountManager.hasAuthCookie("passToken=ks_pass_xyz", Platform.KUAISHOU));
        assertTrue(AccountManager.hasAuthCookie("device_token=abc; PHPSESSID=123456_abcdef", Platform.PIXIV));
        assertTrue(AccountManager.hasAuthCookie("__pus=quark_pus_1", Platform.QUARK));
        assertTrue(AccountManager.hasAuthCookie("__puus=quark_puus_2", Platform.QUARK));
        assertTrue(AccountManager.hasAuthCookie("BAIDUID=guest; BDUSS=bd_secret_token", Platform.BAIDU));
        assertTrue(AccountManager.hasAuthCookie("VISITOR_INFO1_LIVE=v; SAPISID=yt_sapi; SID=yt_sid", Platform.YOUTUBE));
        assertTrue(AccountManager.hasAuthCookie("__Secure-1PSID=yt_1psid", Platform.YOUTUBE));
    }

    @Test
    public void rejectsNullBlankGuestAndClearedCookies() {
        for (Platform p : Platform.values()) {
            assertFalse(AccountManager.hasAuthCookie(null, p));
            assertFalse(AccountManager.hasAuthCookie("", p));
            assertFalse(AccountManager.hasAuthCookie("   ", p));
        }
        assertFalse(AccountManager.hasAuthCookie("SESSDATA=abc", null));

        // Guest-only cookies must not report logged-in
        assertFalse(AccountManager.hasAuthCookie("buvid3=xyz; CURRENT_FNVAL=4048", Platform.BILIBILI));
        assertFalse(AccountManager.hasAuthCookie("a1=guest_only; webId=123", Platform.XIAOHONGSHU));
        assertFalse(AccountManager.hasAuthCookie("BAIDUID=guest123; BIDUPSID=abc", Platform.BAIDU));
        assertFalse(AccountManager.hasAuthCookie("VISITOR_INFO1_LIVE=xyz; YSC=abc", Platform.YOUTUBE));
        assertFalse(AccountManager.hasAuthCookie("device_token=only", Platform.PIXIV));

        // Cleared / deleted / empty values must not report logged-in
        assertFalse(AccountManager.hasAuthCookie("SESSDATA=", Platform.BILIBILI));
        assertFalse(AccountManager.hasAuthCookie("SESSDATA=\"\"", Platform.BILIBILI));
        assertFalse(AccountManager.hasAuthCookie("SESSDATA=deleted; bili_jct=1", Platform.BILIBILI));
        assertFalse(AccountManager.hasAuthCookie("BDUSS=null", Platform.BAIDU));
        assertFalse(AccountManager.hasAuthCookie("web_session=undefined", Platform.XIAOHONGSHU));
    }

    @Test
    public void isolatesPlatformsAndRejectsSubstringCollisions() {
        assertFalse(AccountManager.hasAuthCookie("SESSDATA=abc", Platform.PIXIV));
        assertFalse(AccountManager.hasAuthCookie("PHPSESSID=abc", Platform.BILIBILI));
        assertFalse(AccountManager.hasAuthCookie("MY_SESSDATA=abc; SESSDATA_FAKE=xyz", Platform.BILIBILI));
        assertFalse(AccountManager.hasAuthCookie("fake_web_session=abc", Platform.XIAOHONGSHU));
    }

    @Test
    public void platformMetadataAndMatchingAreValid() {
        assertEquals(8, Platform.values().length);
        for (Platform p : Platform.values()) {
            assertFalse(p.displayName().isBlank());
            assertTrue(p.loginUrl().startsWith("https://"));
            assertFalse(p.domains().isEmpty());
            assertFalse(p.authCookieKeys().isEmpty());
        }
        assertEquals(Platform.BILIBILI, AccountManager.matchPlatform("https://www.bilibili.com/video/BV1xx411c7mD"));
        assertEquals(Platform.QUARK, AccountManager.matchPlatform("https://pan.quark.cn/s/abcdef"));
        assertEquals(Platform.BAIDU, AccountManager.matchPlatform("https://pan.baidu.com/s/1abc"));
        assertNull(AccountManager.matchPlatform("https://bilibili.com.evil.test/login"));
    }
}
