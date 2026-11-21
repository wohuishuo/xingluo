package app.xingluo.video;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MediaParserTest {
    @Test public void bilibiliMp4FallbackKeeps1080Preference() {
        for(String request:Resolver.biliFormats(80,true)) {
            assertTrue(request.startsWith("fnval=0&qn=80&"));
        }
    }

    @Test public void prefersResolutionToBitrateForDouyin() throws Exception {
        var response = new JSONObject("""
            {"video":{"bit_rate":[
              {"bit_rate":4000000,"play_addr":{"width":480,"height":854,"url_list":["https://cdn.test/480"]}},
              {"bit_rate":1800000,"play_addr":{"width":720,"height":1280,"url_list":["https://cdn.test/720"]}},
              {"bit_rate":5000000,"play_addr":{"width":1080,"height":1920,"url_list":["https://cdn.test/1080"]}}
            ]}}
            """);
        assertEquals("https://cdn.test/720", MediaParser.douyin(response,"https://www.douyin.com/",720).videoUrl);
    }

    @Test public void includesHigherResolutionMainDouyinAddressInSelection() throws Exception {
        var response = new JSONObject("""
            {"video":{"width":1080,"height":1920,"play_addr":{"url_list":["https://cdn.test/main"]},
              "bit_rate":[{"bit_rate":4000000,"play_addr":{"width":480,"height":854,"url_list":["https://cdn.test/480"]}}]}}
            """);
        assertEquals("https://cdn.test/main", MediaParser.douyin(response,"https://www.douyin.com/",1080).videoUrl);
        assertEquals("https://cdn.test/480", MediaParser.douyin(response,"https://www.douyin.com/",720).videoUrl);
    }

    @Test public void selectsVerticalBilibiliTrackByShortSide() throws Exception {
        var response = new JSONObject("""
            {"data":{"dash":{"video":[
              {"width":720,"height":1280,"codecs":"avc1","bandwidth":1700000,"baseUrl":"https://cdn.test/720"},
              {"width":360,"height":640,"codecs":"avc1","bandwidth":800000,"baseUrl":"https://cdn.test/360"}
            ],"audio":[{"codecs":"mp4a","baseUrl":"https://cdn.test/audio"}]}}}
            """);
        assertEquals("https://cdn.test/720", MediaParser.bilibili(response,"t","https://www.bilibili.com/",720).videoUrl);
    }

    @Test public void qualityPreferenceCapsBiliVideoTrack() throws Exception {
        JSONObject response=new JSONObject("{\"data\":{\"dash\":{\"video\":[{\"height\":1080,\"codecs\":\"avc1\",\"baseUrl\":\"https://cdn.test/1080\"},{\"height\":480,\"codecs\":\"avc1\",\"baseUrl\":\"https://cdn.test/480\"}],\"audio\":[{\"codecs\":\"mp4a\",\"baseUrl\":\"https://cdn.test/audio\"}]}}}");
        assertEquals("https://cdn.test/480",MediaParser.bilibili(response,"t","https://www.bilibili.com/",480).videoUrl);
        assertEquals("https://cdn.test/1080",MediaParser.bilibili(response,"t","https://www.bilibili.com/",1080).videoUrl);
    }
    @Test public void verticalDouyinQualityUsesShorterEdge() throws Exception {
        JSONObject obj=new JSONObject("{\"desc\":\"t\",\"video\":{\"bit_rate\":[{\"bit_rate\":2000,\"play_addr\":{\"width\":1080,\"height\":1920,\"url_list\":[\"https://cdn.test/1080\"]}},{\"bit_rate\":1000,\"play_addr\":{\"width\":720,\"height\":1280,\"url_list\":[\"https://cdn.test/720\"]}}]}}");
        assertEquals("https://cdn.test/720",MediaParser.douyin(obj,"https://www.douyin.com/",720).videoUrl);
    }
    @Test public void retainsPlatformBackupCdnsForBlockedPrimary() throws Exception {
        JSONObject response=new JSONObject("{\"code\":0,\"data\":{\"durl\":[{\"url\":\"https://primary.test/a.mp4\",\"backup_url\":[\"https://backup.test/a.mp4\"]}]}}");
        MediaPlan p=MediaParser.bilibili(response,"t","https://www.bilibili.com/");
        assertEquals(java.util.List.of("https://primary.test/a.mp4","https://backup.test/a.mp4"),p.videoUrls);
    }
    @Test public void selectsAvcAndAudioInsteadOfSilentOrHevcVideo() throws Exception {
        JSONObject response = new JSONObject("{\"code\":0,\"data\":{\"dash\":{\"video\":[{\"id\":80,\"height\":1080,\"bandwidth\":900,\"codecs\":\"hev1\",\"baseUrl\":\"https://cdn.test/hevc\"},{\"id\":64,\"height\":720,\"bandwidth\":500,\"codecs\":\"avc1.64001f\",\"baseUrl\":\"https://cdn.test/video\"}],\"audio\":[{\"codecs\":\"mp4a.40.2\",\"bandwidth\":128000,\"baseUrl\":\"https://cdn.test/audio\"}]}}}");
        MediaPlan p = MediaParser.bilibili(response, "我的视频", "https://www.bilibili.com/");
        assertEquals("https://cdn.test/video", p.videoUrl); assertEquals("https://cdn.test/audio", p.audioUrl);
    }
    @Test public void rejectsPreviewInsteadOfClaimingWholeDownload() throws Exception {
        JSONObject response = new JSONObject("{\"code\":0,\"data\":{\"is_preview\":1,\"durl\":[{\"url\":\"https://cdn.test/a.mp4\"}]}}");
        assertThrows(IllegalArgumentException.class, () -> MediaParser.bilibili(response,"t","https://www.bilibili.com/"));
    }
    @Test public void rejectsPlatformError() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> MediaParser.bilibili(new JSONObject("{\"code\":-403,\"message\":\"访问受限\"}"),"t","https://www.bilibili.com/"));
    }
    @Test public void findsDouyinRouterDataWithoutPickingMusicUrl() throws Exception {
        String html="<script>window._ROUTER_DATA = {\"loaderData\":{\"video_(id)/page\":{\"videoInfoRes\":{\"item_list\":[{\"desc\":\"海边\",\"music\":{\"play_url\":{\"uri\":\"https://cdn.test/music.mp3\"}},\"video\":{\"play_addr\":{\"url_list\":[\"https://cdn.test/movie.mp4?x=1\"]}}}]}}}};</script>";
        MediaPlan p=MediaParser.webpage(html,"https://www.iesdouyin.com/share/video/123");
        assertEquals("https://cdn.test/movie.mp4?x=1",p.videoUrl); assertEquals("海边",p.title);
    }
    @Test public void findsHtmlVideoAndDecodesEntities() throws Exception {
        MediaPlan p=MediaParser.webpage("<title>夏日 &amp; 风</title><meta content=\"https://cdn.test/v.mp4?x=1&amp;y=2\" property=\"og:video\">", "https://channels.weixin.qq.com/test");
        assertEquals("https://cdn.test/v.mp4?x=1&y=2",p.videoUrl); assertEquals("夏日 & 风",p.title);
    }
    @Test public void noVideoIsActionableFailure() {
        assertThrows(IllegalArgumentException.class, () -> MediaParser.webpage("<html>请在微信内打开</html>","https://channels.weixin.qq.com/abc"));
    }
    @Test public void hlsIsNotTreatedAsMp4() {
        assertThrows(IllegalArgumentException.class, () -> MediaParser.webpage("<video src=\"https://cdn.test/master.m3u8\"></video>","https://example.com"));
    }
}
