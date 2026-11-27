package app.xingluo.video.platform.global;

import app.xingluo.video.model.MediaBundle;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class GlobalAndPixivTest {

    // =========================================================================
    // 1. ImageFilter Tests
    // =========================================================================

    @Test
    public void imageFilterRejectsChromeAvatarsTinyAiR18AndDeduplicates() {
        List<MediaBundle.ImageItem> input = List.of(
                new MediaBundle.ImageItem("https://i.pximg.net/user-profile/img/avatar_50.jpg", "avatar.jpg", 800, 800),
                new MediaBundle.ImageItem("https://i.pximg.net/custom-thumb/img/123.jpg", "thumb.jpg", 800, 800),
                new MediaBundle.ImageItem("https://example.com/common/bg_header.png", "bg.png", 1200, 800),
                new MediaBundle.ImageItem("https://example.com/assets/logo.svg", "logo.svg", 1000, 1000),
                new MediaBundle.ImageItem("https://example.com/track/1x1.gif", "pixel.gif", 600, 600),
                new MediaBundle.ImageItem("https://i.pximg.net/img-original/img/2025/01/01/00/00/00/111_p0.png", "tiny.png", 250, 500),
                new MediaBundle.ImageItem("https://i.pximg.net/img-original/img/2025/01/01/00/00/00/222_p0.png", "banner_ratio.png", 2500, 420),
                new MediaBundle.ImageItem("https://i.pximg.net/img-original/img/2025/01/01/00/00/00/333_p0.png",
                        List.of(), "ai.png", 1024, 1024, true, false, List.of("AI")),
                new MediaBundle.ImageItem("https://i.pximg.net/img-original/img/2025/01/01/00/00/00/444_p0.png",
                        List.of(), "r18.png", 1024, 1024, false, true, List.of("R-18")),
                new MediaBundle.ImageItem("https://i.pximg.net/img-original/img/2025/01/01/00/00/00/555_p0.png",
                        List.of(), "valid.png", 1200, 1600, false, false, List.of("landscape")),
                new MediaBundle.ImageItem("http://I.PXIMG.NET/img-original/img/2025/01/01/00/00/00/555_p0.png#frag",
                        List.of(), "dup.png", 1200, 1600, false, false, List.of("landscape")),
                new MediaBundle.ImageItem("https://i.pximg.net/img-original/img/2025/01/01/00/00/00/666_p0.jpg",
                        List.of(), "unknown_dims.jpg", 0, 0, false, false, List.of())
        );

        List<MediaBundle.ImageItem> filtered = ImageFilter.filter(input, 400, true, true, true);
        assertEquals(2, filtered.size());
        assertEquals("valid.png", filtered.get(0).filename);
        assertEquals("unknown_dims.jpg", filtered.get(1).filename);

        // When skipBg=false, extreme aspect ratio is allowed (as long as minPx is satisfied)
        List<MediaBundle.ImageItem> keepRatio = ImageFilter.filter(input, 400, false, true, true);
        assertEquals(3, keepRatio.size());
        assertEquals("banner_ratio.png", keepRatio.get(0).filename);
    }

    // =========================================================================
    // 2. PixivExtractor Tests
    // =========================================================================

    @Test
    public void pixivUrlParsingCoversArtworkLegacyAndArtistRoutes() {
        var art = PixivExtractor.parseUrl("https://www.pixiv.net/en/artworks/123456789");
        assertEquals(PixivExtractor.TargetType.ARTWORK, art.type());
        assertEquals("123456789", art.id());

        var legacy = PixivExtractor.parseUrl("https://www.pixiv.net/member_illust.php?mode=medium&illust_id=987654");
        assertEquals(PixivExtractor.TargetType.ARTWORK, legacy.type());
        assertEquals("987654", legacy.id());

        var userAll = PixivExtractor.parseUrl("https://www.pixiv.net/users/4242");
        assertEquals(PixivExtractor.TargetType.USER_ALL, userAll.type());
        assertEquals("4242", userAll.id());

        var userIllusts = PixivExtractor.parseUrl("https://www.pixiv.net/en/users/4242/illustrations");
        assertEquals(PixivExtractor.TargetType.USER_ILLUSTS, userIllusts.type());

        var userManga = PixivExtractor.parseUrl("https://www.pixiv.net/users/4242/manga");
        assertEquals(PixivExtractor.TargetType.USER_MANGA, userManga.type());
    }

    @Test
    public void pixivSingleAndMultiPageParsingGeneratesCleanNamesAndReferer() throws Exception {
        JSONObject illustJson = new JSONObject("""
            {
              "error": false,
              "message": "",
              "body": {
                "illustId": "11223344",
                "illustTitle": "Starry Sky",
                "userId": "7788",
                "userName": "Hoshino/Art",
                "illustType": 0,
                "aiType": 1,
                "xRestrict": 0,
                "pageCount": 2,
                "width": 1920,
                "height": 1080,
                "urls": {"original": "https://i.pximg.net/img-original/img/2025/05/01/00/00/00/11223344_p0.png"},
                "tags": {"tags": [{"tag": "landscape"}, {"tag": "night sky"}, {"tag": "stars"}]}
              }
            }
            """);
        JSONObject pagesJson = new JSONObject("""
            {
              "error": false,
              "body": [
                {"urls": {"original": "https://i.pximg.net/img-original/img/2025/05/01/00/00/00/11223344_p0.png"}, "width": 1920, "height": 1080},
                {"urls": {"original": "https://i.pximg.net/img-original/img/2025/05/01/00/00/00/11223344_p1.jpg"}, "width": 1600, "height": 1200}
              ]
            }
            """);

        MediaBundle bundle = PixivExtractor.parseSingleArtwork(illustJson, pagesJson, ImageFilter.Config.defaults());
        assertEquals(MediaBundle.Type.GALLERY, bundle.type);
        assertEquals("Pixiv", bundle.platform);
        assertEquals("https://www.pixiv.net/", bundle.referer);
        assertEquals("https://www.pixiv.net/", bundle.headers.get("Referer"));
        assertEquals("Pixiv/Hoshino_Art_7788", bundle.subfolder);
        assertEquals(2, bundle.images.size());
        assertEquals("[Hoshino_Art][11223344][landscape_nightsky]_p0.png", bundle.images.get(0).filename);
        assertEquals("[Hoshino_Art][11223344][landscape_nightsky]_p1.jpg", bundle.images.get(1).filename);
    }

    @Test
    public void pixivArtistProfileAllTopAndBatchParsingFiltersAiAndUgoira() throws Exception {
        JSONObject allJson = new JSONObject("""
            {
              "error": false,
              "body": {
                "illusts": {"1001": null, "1002": null, "1003": null},
                "manga": []
              }
            }
            """);
        JSONObject topJson = new JSONObject("""
            {
              "error": false,
              "body": {
                "illusts": {
                  "1003": {
                    "id": "1003", "userName": "Alice", "illustType": 0, "aiType": 1, "xRestrict": 0,
                    "width": 1400, "height": 1800, "tags": ["original", "blue"],
                    "url": "https://i.pximg.net/img-master/img/2025/01/01/1003_p0_master1200.jpg"
                  },
                  "1002": {
                    "id": "1002", "userName": "Alice", "illustType": 0, "aiType": 2, "xRestrict": 0,
                    "width": 1200, "height": 1200, "tags": ["AI"],
                    "url": "https://i.pximg.net/img-master/img/2025/01/01/1002_p0_master1200.jpg"
                  }
                },
                "manga": {}
              }
            }
            """);
        JSONObject batchJson = new JSONObject("""
            {
              "error": false,
              "body": {
                "works": {
                  "1001": {
                    "id": "1001", "userName": "Alice", "illustType": 2, "aiType": 1, "xRestrict": 0,
                    "width": 800, "height": 800, "tags": ["ugoira"],
                    "url": "https://i.pximg.net/img-master/img/2025/01/01/1001_ugoira0.jpg"
                  }
                }
              }
            }
            """);

        MediaBundle bundle = PixivExtractor.parseArtistCatalog(
                "900", PixivExtractor.TargetType.USER_ILLUSTS,
                allJson, topJson, List.of(batchJson),
                new ImageFilter.Config(400, true, true, false), 10);

        assertEquals(MediaBundle.Type.GALLERY, bundle.type);
        assertEquals("Pixiv/Alice_900", bundle.subfolder);
        assertEquals("https://www.pixiv.net/", bundle.referer);
        assertEquals(1, bundle.images.size());
        assertEquals("[Alice][1003][original_blue]_p0.jpg", bundle.images.get(0).filename);
    }

    // =========================================================================
    // 3. Twitter / Reddit / YouTube Tests
    // =========================================================================

    @Test
    public void twitterFxTwitterVideoAndPhotosAndSyndicationFallback() throws Exception {
        JSONObject fxVideo = new JSONObject("""
            {
              "code": 200,
              "tweet": {
                "text": "Rocket launch",
                "author": {"name": "SpaceNews", "screen_name": "spacenews"},
                "media": {
                  "videos": [{
                    "variants": [
                      {"content_type": "application/x-mpegURL", "url": "https://video.twimg.com/ext/1.m3u8"},
                      {"content_type": "video/mp4", "bitrate": 832000, "width": 640, "height": 360, "url": "https://video.twimg.com/vid/640x360/low.mp4"},
                      {"content_type": "video/mp4", "bitrate": 2176000, "width": 1280, "height": 720, "url": "https://video.twimg.com/vid/1280x720/hd.mp4"}
                    ]
                  }]
                }
              }
            }
            """);
        MediaBundle videoBundle = GlobalMediaExtractor.parseFxTwitter(fxVideo, "https://x.com/i/status/1", 1080);
        assertEquals(MediaBundle.Type.VIDEO, videoBundle.type);
        assertEquals("https://video.twimg.com/vid/1280x720/hd.mp4", videoBundle.videoUrls.get(0));
        assertEquals(2, videoBundle.videoUrls.size());

        JSONObject fxPhotos = new JSONObject("""
            {
              "code": 200,
              "tweet": {
                "text": "Sunset photos",
                "author": {"name": "PhotoUser"},
                "media": {
                  "photos": [
                    {"url": "https://pbs.twimg.com/media/ABC123.jpg", "width": 2048, "height": 1536}
                  ]
                }
              }
            }
            """);
        MediaBundle photoBundle = GlobalMediaExtractor.parseFxTwitter(fxPhotos, "https://x.com/i/status/2", 1080);
        assertEquals(MediaBundle.Type.GALLERY, photoBundle.type);
        assertEquals("https://pbs.twimg.com/media/ABC123.jpg:orig", photoBundle.images.get(0).url);

        String token = GlobalMediaExtractor.twitterSyndicationToken("1800000000000000000");
        assertFalse(token.isEmpty());
        assertFalse(token.contains("0"));
        assertFalse(token.contains("."));

        JSONObject syndication = new JSONObject("""
            {
              "text": "Backup video",
              "user": {"name": "SynUser"},
              "mediaDetails": [{
                "type": "video",
                "video_info": {
                  "variants": [
                    {"content_type": "video/mp4", "bitrate": 950000, "url": "https://video.twimg.com/vid/720.mp4"}
                  ]
                }
              }]
            }
            """);
        MediaBundle synBundle = GlobalMediaExtractor.parseTwitterSyndication(syndication, "https://x.com/i/status/3", 720);
        assertEquals("https://video.twimg.com/vid/720.mp4", synBundle.videoUrls.get(0));
    }

    @Test
    public void redditJsonParsesVideoPlusAudioAndGallery() {
        String videoListing = """
            [{
              "data": {
                "children": [{
                  "data": {
                    "title": "Cool &amp; Fast",
                    "author": "op_user",
                    "subreddit_name_prefixed": "r/videos",
                    "secure_media": {
                      "reddit_video": {
                        "fallback_url": "https://v.redd.it/abc123xyz/DASH_1080.mp4?source=fallback",
                        "hls_url": "https://v.redd.it/abc123xyz/HLSPlaylist.m3u8?a=1&amp;b=2",
                        "is_gif": false,
                        "has_audio": true
                      }
                    }
                  }
                }]
              }
            }]
            """;
        MediaBundle vid = GlobalMediaExtractor.parseRedditJson(videoListing, "https://www.reddit.com/r/videos/comments/1/");
        assertEquals(MediaBundle.Type.VIDEO, vid.type);
        assertEquals("Cool & Fast", vid.title);
        assertEquals("https://v.redd.it/abc123xyz/DASH_1080.mp4?source=fallback", vid.videoUrls.get(0));
        assertEquals("https://v.redd.it/abc123xyz/DASH_AUDIO_128.mp4", vid.audioUrls.get(0));
        assertEquals("https://v.redd.it/abc123xyz/HLSPlaylist.m3u8?a=1&b=2", vid.hlsUrl);

        String galleryListing = """
            [{
              "data": {
                "children": [{
                  "data": {
                    "title": "Art Album",
                    "author": "artist",
                    "gallery_data": {"items": [{"media_id": "m1"}, {"media_id": "m2"}]},
                    "media_metadata": {
                      "m1": {"s": {"u": "https://preview.redd.it/img1.png?width=1080&amp;format=png", "x": 1080, "y": 1440}},
                      "m2": {"s": {"u": "https://preview.redd.it/img2.jpg?width=1200&amp;format=jpg", "x": 1200, "y": 900}}
                    }
                  }
                }]
              }
            }]
            """;
        MediaBundle gal = GlobalMediaExtractor.parseRedditJson(galleryListing, "https://www.reddit.com/r/art/comments/2/");
        assertEquals(MediaBundle.Type.GALLERY, gal.type);
        assertEquals(2, gal.images.size());
        assertEquals("https://preview.redd.it/img1.png?width=1080&format=png", gal.images.get(0).url);
    }

    @Test
    public void youtubeInnertubeStreamingDataSupportsDashProgressiveAudioAndPoTokenError() throws Exception {
        JSONObject dashResponse = new JSONObject("""
            {
              "playabilityStatus": {"status": "OK"},
              "videoDetails": {"title": "YouTube Demo", "author": "Creator"},
              "streamingData": {
                "formats": [
                  {"itag": 18, "mimeType": "video/mp4; codecs=\\"avc1.42001E, mp4a.40.2\\"", "width": 640, "height": 360, "bitrate": 500000, "url": "https://rr.googlevideo.com/prog360.mp4"}
                ],
                "adaptiveFormats": [
                  {"itag": 137, "mimeType": "video/mp4; codecs=\\"avc1.640028\\"", "width": 1920, "height": 1080, "bitrate": 4000000, "contentLength": "10485760", "url": "https://rr.googlevideo.com/vid1080.mp4"},
                  {"itag": 140, "mimeType": "audio/mp4; codecs=\\"mp4a.40.2\\"", "bitrate": 128000, "contentLength": "1048576", "url": "https://rr.googlevideo.com/aud128.m4a"}
                ]
              }
            }
            """);

        MediaBundle dash = GlobalMediaExtractor.parseYouTubePlayer(dashResponse, "dQw4w9WgXcQ", 1080, false, false);
        assertEquals(MediaBundle.Type.VIDEO, dash.type);
        assertEquals("https://rr.googlevideo.com/vid1080.mp4", dash.videoUrls.get(0));
        assertEquals("https://rr.googlevideo.com/aud128.m4a", dash.audioUrls.get(0));
        assertEquals(10485760L + 1048576L, dash.estimatedBytes);

        MediaBundle prog = GlobalMediaExtractor.parseYouTubePlayer(dashResponse, "dQw4w9WgXcQ", 360, false, false);
        assertEquals("https://rr.googlevideo.com/prog360.mp4", prog.videoUrls.get(0));
        assertTrue(prog.audioUrls.isEmpty());

        MediaBundle audioOnly = GlobalMediaExtractor.parseYouTubePlayer(dashResponse, "dQw4w9WgXcQ", 1080, true, false);
        assertEquals(MediaBundle.Type.AUDIO, audioOnly.type);
        assertEquals("https://rr.googlevideo.com/aud128.m4a", audioOnly.audioUrls.get(0));

        JSONObject gated = new JSONObject("""
            {
              "playabilityStatus": {
                "status": "LOGIN_REQUIRED",
                "reason": "Sign in to confirm you're not a bot"
              }
            }
            """);
        var err = assertThrows(IllegalArgumentException.class,
                () -> GlobalMediaExtractor.parseYouTubePlayer(gated, "dQw4w9WgXcQ", 1080, false, false));
        assertTrue(err.getMessage().contains("PO Token") || err.getMessage().contains("登录"));
    }

    // =========================================================================
    // 4. TikTok / Vimeo / Weibo / AcFun / PeerTube / Generic Tests
    // =========================================================================

    @Test
    public void otherGlobalPlatformsAndPeerTubeHlsFallbackWork() throws Exception {
        String tiktokHtml = """
            <html><script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
            {"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":{
              "desc":"Dance Clip","author":{"nickname":"TTAuthor"},
              "video":{"downloadAddr":"https://v16.tiktokcdn.com/nowm.mp4","playAddr":"https://v16.tiktokcdn.com/play.mp4"}
            }}}}}
            </script></html>
            """;
        MediaBundle tt = GlobalMediaExtractor.parseTikTokHtml(tiktokHtml, "https://www.tiktok.com/@a/video/1", 1080);
        assertEquals("https://v16.tiktokcdn.com/nowm.mp4", tt.videoUrls.get(0));
        assertTrue(tt.watermarkFree);

        JSONObject vimeo = new JSONObject("""
            {
              "video": {"title": "Vimeo Short", "owner": {"name": "Director"}},
              "request": {"files": {"progressive": [
                {"width": 1280, "height": 720, "bitrate": 1500, "url": "https://vod.vimeo.com/720.mp4"},
                {"width": 1920, "height": 1080, "bitrate": 4000, "url": "https://vod.vimeo.com/1080.mp4"}
              ]}}
            }
            """);
        assertEquals("https://vod.vimeo.com/1080.mp4",
                GlobalMediaExtractor.parseVimeoConfig(vimeo, "https://vimeo.com/123", 1080).videoUrls.get(0));

        JSONObject weibo = new JSONObject("""
            {"data":{"user":{"screen_name":"WBUser"},"status_title":"微博测试",
              "page_info":{"media_info":{"mp4_720p_mp4":"https://f.video.weibocdn.com/720.mp4","mp4_ld_mp4":"https://f.video.weibocdn.com/ld.mp4"}}}}
            """);
        assertEquals("https://f.video.weibocdn.com/720.mp4",
                GlobalMediaExtractor.parseWeiboStatus(weibo, "https://m.weibo.cn/detail/1").videoUrls.get(0));

        String acfunHtml = """
            <script>window.videoInfo = {"title":"AcFun Demo","user":{"name":"AcUP"},"currentVideoInfo":{"ksPlayJson":"{\\"adaptationSet\\":[{\\"representation\\":[{\\"width\\":1920,\\"height\\":1080,\\"avgBitrate\\":3000,\\"url\\":\\"https://tx.acfun.cn/1080.m3u8\\"}]}]}"}};</script>
            """;
        MediaBundle ac = GlobalMediaExtractor.parseAcFunHtml(acfunHtml, "https://www.acfun.cn/v/ac123", 1080);
        assertEquals("https://tx.acfun.cn/1080.m3u8", ac.hlsUrl);

        JSONObject peerTubeHlsOnly = new JSONObject("""
            {
              "uuid": "d8099a47-2f56-46c1-99d3-a64fa1934651",
              "name": "PeerTube HLS",
              "downloadEnabled": true,
              "isLive": false,
              "files": [],
              "streamingPlaylists": [
                {"playlistUrl": "https://tube.test/hls/master.m3u8", "files": [{"fileUrl": "https://tube.test/hls/1080-frag.mp4"}]}
              ]
            }
            """);
        MediaBundle pt = GlobalMediaExtractor.parsePeerTube(peerTubeHlsOnly, "https://tube.test/w/1", 1080);
        assertEquals("https://tube.test/hls/master.m3u8", pt.hlsUrl);

        MediaBundle genericM3u8 = GlobalMediaExtractor.parseGenericHtml(
                "<html><title>Stream</title><video src=\"https://cdn.example.com/live/index.m3u8\"></video></html>",
                "https://example.com/watch", 720);
        assertEquals("https://cdn.example.com/live/index.m3u8", genericM3u8.hlsUrl);
    }

    // =========================================================================
    // 5. AiMediaExtractor Tests (Civitai / Runway / Luma / Jimeng)
    // =========================================================================

    @Test
    public void civitaiAndAiShareExtractionWorksAndStripsWatermarks() throws Exception {
        JSONObject civitaiImages = new JSONObject("""
            {
              "items": [{
                "id": 9988,
                "url": "https://image.civitai.com/xG1nkqKTMzGDvpLrqFT7WA/uuid/original=true/9988.png",
                "width": 1024,
                "height": 1536,
                "type": "image",
                "nsfw": false,
                "username": "CivitCreator",
                "meta": {"Model": "FluxDev", "prompt": "cyberpunk starfield"}
              }]
            }
            """);
        MediaBundle civ = AiMediaExtractor.parseCivitaiImages(civitaiImages, "https://civitai.com/posts/123", null);
        assertEquals(MediaBundle.Type.GALLERY, civ.type);
        assertEquals("Civitai", civ.platform);
        assertEquals(1, civ.images.size());
        assertTrue(civ.images.get(0).aiGenerated);
        assertTrue(civ.extraInfo.contains("FluxDev"));

        String runwayHtml = """
            <html><head><title>Runway Gen</title>
            <meta property="og:video:secure_url" content="https://cdn.runwayml.com/gen3/clip.mp4">
            </head></html>
            """;
        MediaBundle rw = AiMediaExtractor.parseSsrSharePage(runwayHtml, "https://runwayml.com/share/abc", "Runway");
        assertEquals("https://cdn.runwayml.com/gen3/clip.mp4", rw.videoUrls.get(0));

        String jimengHtml = """
            <script>window.__INITIAL_STATE__ = {"work":{"video_url":"https://v.jimeng.cdn/clip.mp4?watermark=1&sign=abc123&x-oss-process=video/watermark"}};</script>
            """;
        MediaBundle jm = AiMediaExtractor.parseCnAiShareHtml(jimengHtml, "https://jimeng.jianying.com/s/1", "即梦 AI");
        assertEquals("https://v.jimeng.cdn/clip.mp4?sign=abc123", jm.videoUrls.get(0));

        var gatedErr = assertThrows(IllegalArgumentException.class,
                () -> AiMediaExtractor.parseCnAiShareHtml("<html><body>请登录可灵账号</body></html>",
                        "https://klingai.kuaishou.com/s/1", "可灵 AI"));
        assertTrue(gatedErr.getMessage().contains("登录"));
    }
}
