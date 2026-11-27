package app.xingluo.video.platform.cn;

import app.xingluo.video.model.MediaBundle;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Offline unit tests for Chinese platform extractors:
 * Bilibili (WBI signing, DASH HEVC/AVC/audio-only, multi-P, live),
 * Douyin (photo album 图文, playwm->play, _ROUTER_DATA, live),
 * Kuaishou (redirect photoId, __APOLLO_STATE__ $ref, INIT_STATE video + atlas, GraphQL),
 * Xiaohongshu (__INITIAL_STATE__ undefined handling, video, lossless imageList).
 */
public class CnExtractorsTest {

    // ======================== Bilibili ========================

    @Test
    public void bilibiliWbiSignatureMatchesKnownVector() {
        String imgUrl = "https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png";
        String subUrl = "https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png";
        String imgKey = BilibiliExtractor.extractKeyFromUrl(imgUrl);
        String subKey = BilibiliExtractor.extractKeyFromUrl(subUrl);

        assertEquals("7cd084941338484aae1ad9425b84077c", imgKey);
        assertEquals("4932caff0ff746eab6f01bf08b70ac45", subKey);
        assertEquals("ea1db124af3c7062474693fa704f4ff8", BilibiliExtractor.getMixinKey(imgKey, subKey));

        Map<String, String> params = new LinkedHashMap<>();
        params.put("foo", "114");
        params.put("bar", "514");
        params.put("baz", "1919810");

        String signed = BilibiliExtractor.signWbiParams(params, imgKey, subKey, 1702204169L);
        assertEquals("bar=514&baz=1919810&foo=114&wts=1702204169&w_rid=6149fdadf571698ca7e6a567265cd0ee", signed);
    }

    @Test
    public void bilibiliWbiStripsSpecialCharactersAndEncodesSpaces() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", "hello!'()* world");
        String signed = BilibiliExtractor.signWbiParams(
                params,
                "7cd084941338484aae1ad9425b84077c",
                "4932caff0ff746eab6f01bf08b70ac45",
                1702204169L
        );
        assertTrue(signed.startsWith("q=hello%20world&wts=1702204169&w_rid="));
    }

    @Test
    public void bilibiliSmartHevcVsAvcAndAudioOnly() throws Exception {
        JSONObject playJson = new JSONObject("""
            {
              "code": 0,
              "data": {
                "accept_quality": [120, 80, 64],
                "dash": {
                  "video": [
                    {"id": 80, "width": 1920, "height": 1080, "bandwidth": 1800000, "codecs": "hev1.1.6.L120.90", "baseUrl": "https://cdn.test/1080_hevc.m4s", "backupUrl": ["https://bk.test/1080_hevc.m4s"]},
                    {"id": 80, "width": 1920, "height": 1080, "bandwidth": 2200000, "codecs": "avc1.640032", "baseUrl": "https://cdn.test/1080_avc.m4s"},
                    {"id": 64, "width": 1280, "height": 720, "bandwidth": 900000, "codecs": "avc1.64001f", "baseUrl": "https://cdn.test/720_avc.m4s"}
                  ],
                  "audio": [
                    {"id": 30280, "codecs": "mp4a.40.2", "bandwidth": 192000, "baseUrl": "https://cdn.test/audio_hi.m4a", "backupUrl": ["https://bk.test/audio_hi.m4a"]},
                    {"id": 30216, "codecs": "mp4a.40.2", "bandwidth": 64000, "baseUrl": "https://cdn.test/audio_lo.m4a"}
                  ]
                }
              }
            }
            """);

        MediaBundle hevc = BilibiliExtractor.parseVideoPlayUrl(
                playJson, "测试视频", "UP主", List.of("科技"), "https://www.bilibili.com/video/BV1xx411c7mD/",
                1080, true, false);
        assertEquals(MediaBundle.Type.VIDEO, hevc.type);
        assertEquals("https://cdn.test/1080_hevc.m4s", hevc.videoUrls.get(0));
        assertEquals("https://bk.test/1080_hevc.m4s", hevc.videoUrls.get(1));
        assertEquals("https://cdn.test/audio_hi.m4a", hevc.audioUrls.get(0));
        assertTrue(hevc.watermarkFree);
        assertEquals("UP主", hevc.author);

        MediaBundle avc = BilibiliExtractor.parseVideoPlayUrl(
                playJson, "测试视频", "UP主", List.of("科技"), "https://www.bilibili.com/video/BV1xx411c7mD/",
                1080, false, false);
        assertEquals(MediaBundle.Type.VIDEO, avc.type);
        assertEquals("https://cdn.test/1080_avc.m4s", avc.videoUrls.get(0));

        MediaBundle audioOnly = BilibiliExtractor.parseVideoPlayUrl(
                playJson, "测试视频", "UP主", List.of("科技"), "https://www.bilibili.com/video/BV1xx411c7mD/",
                1080, true, true);
        assertEquals(MediaBundle.Type.AUDIO, audioOnly.type);
        assertTrue(audioOnly.videoUrls.isEmpty());
        assertEquals("https://cdn.test/audio_hi.m4a", audioOnly.audioUrls.get(0));
    }

    @Test
    public void bilibiliViewMultiPageAndMetadata() throws Exception {
        JSONObject viewJson = new JSONObject("""
            {
              "code": 0,
              "data": {
                "bvid": "BV1xx411c7mD",
                "aid": 123456,
                "title": "合集标题",
                "tname": "知识",
                "owner": {"mid": 99, "name": "星落导师"},
                "pages": [
                  {"page": 1, "cid": 111, "part": "第一讲"},
                  {"page": 2, "cid": 222, "part": "第二讲"}
                ]
              }
            }
            """);
        BilibiliExtractor.ViewMetadata meta = BilibiliExtractor.parseViewMetadata(viewJson, 2);
        assertEquals(222L, meta.cid);
        assertEquals("星落导师", meta.author);
        assertEquals(List.of("知识"), meta.tags);
        assertEquals("合集标题 · P2 第二讲", meta.title);
    }

    @Test
    public void bilibiliLivePlayInfoParsesHlsAndFlv() throws Exception {
        JSONObject liveJson = new JSONObject("""
            {
              "code": 0,
              "data": {
                "room_id": 2288,
                "live_status": 1,
                "playurl_info": {
                  "playurl": {
                    "stream": [
                      {
                        "protocol_name": "http_stream",
                        "format": [
                          {
                            "format_name": "flv",
                            "codec": [
                              {
                                "codec_name": "avc",
                                "base_url": "/live-bvc/1234/live_1.flv",
                                "url_info": [{"host": "https://d1.bilivideo.com", "extra": "?token=flv1"}]
                              }
                            ]
                          }
                        ]
                      },
                      {
                        "protocol_name": "http_hls",
                        "format": [
                          {
                            "format_name": "fmp4",
                            "codec": [
                              {
                                "codec_name": "hevc",
                                "base_url": "/live-bvc/1234/live_1.m3u8",
                                "url_info": [{"host": "https://d2.bilivideo.com", "extra": "?token=hls1"}]
                              }
                            ]
                          }
                        ]
                      }
                    ]
                  }
                }
              }
            }
            """);
        MediaBundle live = BilibiliExtractor.parseLivePlayInfo(
                liveJson, "星落直播间", "主播A", List.of("游戏"), "https://live.bilibili.com/2288", true);
        assertEquals(MediaBundle.Type.LIVE, live.type);
        assertEquals("https://d2.bilivideo.com/live-bvc/1234/live_1.m3u8?token=hls1", live.hlsUrl);
        assertEquals("https://d2.bilivideo.com/live-bvc/1234/live_1.m3u8?token=hls1", live.liveUrl);
        assertTrue(live.videoUrls.contains("https://d1.bilivideo.com/live-bvc/1234/live_1.flv?token=flv1"));
        assertEquals("主播A", live.author);
    }

    // ======================== Douyin ========================

    @Test
    public void douyinPhotoAlbumExtractsHdGalleryAndMusic() throws Exception {
        String html = """
            <script>window._ROUTER_DATA = {
              "loaderData": {
                "note_(id)/page": {
                  "videoInfoRes": {
                    "item_list": [{
                      "aweme_type": 68,
                      "desc": "今日份晚霞 #风景 #摄影",
                      "author": {"nickname": "小星摄影"},
                      "text_extra": [{"hashtag_name": "风景"}, {"hashtag_name": "摄影"}],
                      "music": {"play_url": {"url_list": ["https://sf.douyincdn.com/bgm.mp3"]}},
                      "images": [
                        {
                          "width": 1440,
                          "height": 1920,
                          "url_list": [
                            "https://p3.douyinpic.com/img1.webp",
                            "https://p3.douyinpic.com/img1.jpeg"
                          ]
                        },
                        {
                          "width": 1080,
                          "height": 1440,
                          "download_url_list": ["https://p3.douyinpic.com/img2_hd.png"],
                          "url_list": ["https://p3.douyinpic.com/img2.webp"]
                        }
                      ]
                    }]
                  }
                }
              }
            };</script>
            """;

        MediaBundle bundle = DouyinExtractor.parseHtml(html, "https://www.douyin.com/note/730011223344", 1080, true, false);
        assertEquals(MediaBundle.Type.GALLERY, bundle.type);
        assertTrue(bundle.watermarkFree);
        assertEquals("小星摄影", bundle.author);
        assertEquals(List.of("风景", "摄影"), bundle.tags);
        assertEquals(2, bundle.images.size());
        assertEquals("https://p3.douyinpic.com/img1.jpeg", bundle.images.get(0).url);
        assertEquals("https://p3.douyinpic.com/img2_hd.png", bundle.images.get(1).url);
        assertEquals(List.of("https://sf.douyincdn.com/bgm.mp3"), bundle.audioUrls);
    }

    @Test
    public void douyinVideoRewritesPlaywmToPlayAndRespectsQuality() throws Exception {
        JSONObject detail = new JSONObject("""
            {
              "aweme_detail": {
                "desc": "海风吹过",
                "author": {"nickname": "旅拍达人"},
                "text_extra": [{"hashtag_name": "旅行"}],
                "video": {
                  "bit_rate": [
                    {
                      "bit_rate": 4000000,
                      "is_h265": 0,
                      "play_addr": {
                        "width": 1080,
                        "height": 1920,
                        "url_list": ["https://aweme.snssdk.com/aweme/v1/playwm/?video_id=abc1080&watermark=1"]
                      }
                    },
                    {
                      "bit_rate": 1800000,
                      "is_h265": 0,
                      "play_addr": {
                        "width": 720,
                        "height": 1280,
                        "url_list": ["https://aweme.snssdk.com/aweme/v1/playwm/?video_id=abc720"]
                      }
                    }
                  ]
                }
              }
            }
            """);

        MediaBundle bundle720 = DouyinExtractor.parseAwemeJson(detail, "https://www.douyin.com/video/123", 720, true, false);
        assertEquals(MediaBundle.Type.VIDEO, bundle720.type);
        assertTrue(bundle720.watermarkFree);
        assertEquals("https://aweme.snssdk.com/aweme/v1/play/?video_id=abc720", bundle720.videoUrls.get(0));

        MediaBundle bundle1080 = DouyinExtractor.parseAwemeJson(detail, "https://www.douyin.com/video/123", 1080, true, false);
        assertEquals("https://aweme.snssdk.com/aweme/v1/play/?video_id=abc1080", bundle1080.videoUrls.get(0));
        assertEquals("旅拍达人", bundle1080.author);
        assertEquals(List.of("旅行"), bundle1080.tags);
    }

    @Test
    public void douyinLiveParsesHlsAndFlv() throws Exception {
        String liveJson = """
            {
              "data": {
                "data": [{
                  "title": "户外直播中",
                  "owner": {"nickname": "主播小明"},
                  "stream_url": {
                    "hls_pull_url_map": {"FULL_HD1": "https://pull.douyin.com/stream.m3u8"},
                    "flv_pull_url": {"FULL_HD1": "https://pull.douyin.com/stream.flv"}
                  }
                }]
              }
            }
            """;
        MediaBundle live = DouyinExtractor.parseLiveContent(liveJson, "https://live.douyin.com/8888", true);
        assertEquals(MediaBundle.Type.LIVE, live.type);
        assertEquals("https://pull.douyin.com/stream.m3u8", live.hlsUrl);
        assertEquals("主播小明", live.author);
        assertTrue(live.videoUrls.contains("https://pull.douyin.com/stream.flv"));
    }

    // ======================== Kuaishou ========================

    @Test
    public void kuaishouExtractsPhotoIdFromSupportedFormats() {
        assertEquals("3xabc123", KuaishouExtractor.extractPhotoId("https://www.kuaishou.com/short-video/3xabc123?foo=1"));
        assertEquals("3xfw999", KuaishouExtractor.extractPhotoId("https://www.kuaishou.com/fw/photo/3xfw999"));
        assertEquals("3xqry88", KuaishouExtractor.extractPhotoId("https://www.kuaishou.com/f/X123?photoId=3xqry88"));
    }

    @Test
    public void kuaishouApolloStateResolvesAuthorRefAndManifest() throws Exception {
        String html = """
            <script>window.__APOLLO_STATE__ = {
              "defaultClient": {
                "User:u100": {"id": "u100", "name": "快手创作者"},
                "VisionVideoDetailPhoto:3x999": {
                  "id": "3x999",
                  "caption": "雪山日出 #风景 #航拍",
                  "author": {"$ref": "User:u100"},
                  "photoUrl": "https://txmov2.a.kwimgs.com/fallback_720.mp4",
                  "manifest": {
                    "adaptationSet": [{
                      "representation": [
                        {"id": 1, "width": 720, "height": 1280, "bitrate": 1200000, "codecs": "avc1", "url": "https://txmov2.a.kwimgs.com/720.mp4"},
                        {"id": 2, "width": 1080, "height": 1920, "bitrate": 3200000, "codecs": "hev1", "url": "https://txmov2.a.kwimgs.com/1080_hevc.mp4", "backupUrl": ["https://alimov2.a.kwimgs.com/1080_hevc.mp4"]}
                      ]
                    }]
                  }
                }
              }
            };</script>
            """;
        MediaBundle bundle = KuaishouExtractor.parseHtml(html, "https://www.kuaishou.com/short-video/3x999", 1080, true, false);
        assertEquals(MediaBundle.Type.VIDEO, bundle.type);
        assertTrue(bundle.watermarkFree);
        assertEquals("快手创作者", bundle.author);
        assertEquals(List.of("风景", "航拍"), bundle.tags);
        assertEquals("https://txmov2.a.kwimgs.com/1080_hevc.mp4", bundle.videoUrls.get(0));
        assertTrue(bundle.videoUrls.contains("https://alimov2.a.kwimgs.com/1080_hevc.mp4"));
    }

    @Test
    public void kuaishouInitStateParsesVideoAndAtlasGallery() throws Exception {
        String videoHtml = """
            <script>window.INIT_STATE = {
              "result": 1,
              "photo": {
                "caption": "滑板少年",
                "userName": "街头拍客",
                "srcNoMark": "https://txmov2.a.kwimgs.com/nowm.mp4",
                "photoUrl": "https://txmov2.a.kwimgs.com/main.mp4"
              }
            };</script>
            """;
        MediaBundle video = KuaishouExtractor.parseHtml(videoHtml, "https://www.kuaishou.com/fw/photo/3x111", 1080, false, false);
        assertEquals(MediaBundle.Type.VIDEO, video.type);
        assertEquals("https://txmov2.a.kwimgs.com/nowm.mp4", video.videoUrls.get(0));
        assertEquals("街头拍客", video.author);

        String atlasHtml = """
            <script>window.INIT_STATE = {
              "photo": {
                "caption": "九宫格美食 #探店",
                "userName": "吃货日记",
                "ext_params": {
                  "atlas": {
                    "cdn": "tx2.a.kwimgs.com",
                    "cdnList": ["tx2.a.kwimgs.com", "alimov2.a.kwimgs.com"],
                    "list": ["/ufile/atlas/N1_1.jpg", "/ufile/atlas/N1_2.webp"]
                  }
                }
              }
            };</script>
            """;
        MediaBundle gallery = KuaishouExtractor.parseHtml(atlasHtml, "https://www.kuaishou.com/fw/photo/3x222", 1080, true, false);
        assertEquals(MediaBundle.Type.GALLERY, gallery.type);
        assertTrue(gallery.watermarkFree);
        assertEquals("吃货日记", gallery.author);
        assertEquals(2, gallery.images.size());
        assertEquals("https://tx2.a.kwimgs.com/ufile/atlas/N1_1.jpg", gallery.images.get(0).url);
        assertEquals(List.of("https://alimov2.a.kwimgs.com/ufile/atlas/N1_1.jpg"), gallery.images.get(0).backupUrls);
        assertEquals("https://tx2.a.kwimgs.com/ufile/atlas/N1_2.webp", gallery.images.get(1).url);
    }

    // ======================== Xiaohongshu ========================

    @Test
    public void xiaohongshuInitialStateVideoHandlesUndefinedLiterals() throws Exception {
        String html = """
            <script>window.__INITIAL_STATE__ = {
              "note": {
                "currentNoteId": "65a1b2c3d4e5f60708090a0b",
                "noteDetailMap": {
                  "undefined": undefined,
                  "65a1b2c3d4e5f60708090a0b": {
                    "note": {
                      "type": "video",
                      "title": "露营 Vlog",
                      "desc": "周末去山里",
                      "extra": undefined,
                      "user": {"nickname": "阿星露营"},
                      "tagList": [{"name": "露营"}, {"name": "户外"}],
                      "video": {
                        "media": {
                          "stream": {
                            "h264": [{"width": 1080, "height": 1920, "videoBitrate": 2000, "masterUrl": "https://sns-video-bd.xhscdn.com/h264_1080.mp4"}],
                            "h265": [{"width": 1080, "height": 1920, "videoBitrate": 1600, "masterUrl": "https://sns-video-bd.xhscdn.com/h265_1080.mp4", "backupUrls": ["https://sns-video-hw.xhscdn.com/h265_1080.mp4"]}]
                          }
                        }
                      }
                    }
                  }
                }
              }
            };</script>
            """;

        MediaBundle video = XiaohongshuExtractor.parseHtml(
                html,
                "https://www.xiaohongshu.com/explore/65a1b2c3d4e5f60708090a0b?xsec_token=tok&xsec_source=pc_feed",
                1080, true, false);
        assertEquals(MediaBundle.Type.VIDEO, video.type);
        assertTrue(video.watermarkFree);
        assertEquals("露营 Vlog", video.title);
        assertEquals("阿星露营", video.author);
        assertEquals(List.of("露营", "户外"), video.tags);
        assertEquals("https://sns-video-bd.xhscdn.com/h265_1080.mp4", video.videoUrls.get(0));
        assertTrue(video.videoUrls.contains("https://sns-video-hw.xhscdn.com/h265_1080.mp4"));
    }

    @Test
    public void xiaohongshuImageListBuildsLosslessWatermarkFreeUrls() throws Exception {
        String html = """
            <script>window.__INITIAL_STATE__ = {
              "note": {
                "noteDetailMap": {
                  "6600112233445566778899aa": {
                    "note": {
                      "type": "normal",
                      "title": "极简桌面搭配",
                      "user": {"nickname": "桌面美学"},
                      "tagList": [{"name": "桌搭"}],
                      "imageList": [
                        {
                          "traceId": "1040g00830abc111",
                          "width": 1440,
                          "height": 1920,
                          "urlDefault": "https://sns-webpic-qc.xhscdn.com/20261128/abc/1040g00830abc111!nd_dft_wlteh_webp_3?imageView2/2/w/1080"
                        },
                        {
                          "width": 1200,
                          "height": 1600,
                          "urlDefault": "https://sns-img-bd.xhscdn.com/1040g00830def222!nd_dft_wgjpeg_webp_3?imageView2/2/w/1080"
                        }
                      ]
                    }
                  }
                }
              }
            };</script>
            """;

        MediaBundle gallery = XiaohongshuExtractor.parseHtml(
                html,
                "https://www.xiaohongshu.com/explore/6600112233445566778899aa",
                1080, true, false);
        assertEquals(MediaBundle.Type.GALLERY, gallery.type);
        assertTrue(gallery.watermarkFree);
        assertEquals("桌面美学", gallery.author);
        assertEquals(2, gallery.images.size());
        assertEquals("https://sns-img-bd.xhscdn.com/1040g00830abc111", gallery.images.get(0).url);
        assertEquals("https://sns-img-bd.xhscdn.com/1040g00830def222", gallery.images.get(1).url);
    }

    @Test
    public void kuaishouGraphqlAndXhsPreserveXsecParams() throws Exception {
        JSONObject gql = new JSONObject("""
            {
              "data": {
                "visionVideoDetail": {
                  "author": {"id": "u1", "name": "GraphQL作者"},
                  "photo": {
                    "id": "3xgql",
                    "caption": "夜景延时",
                    "photoUrl": "https://txmov2.a.kwimgs.com/gql_h264.mp4",
                    "photoH265Url": "https://txmov2.a.kwimgs.com/gql_h265.mp4"
                  }
                }
              }
            }
            """);
        MediaBundle ks = KuaishouExtractor.parseGraphql(gql, "https://www.kuaishou.com/short-video/3xgql", 1080, true, false);
        assertEquals(MediaBundle.Type.VIDEO, ks.type);
        assertEquals("GraphQL作者", ks.author);
        assertEquals("https://txmov2.a.kwimgs.com/gql_h265.mp4", ks.videoUrls.get(0));

        String preserved = XiaohongshuExtractor.preserveXsecParams(
                "https://www.xiaohongshu.com/explore/abc?xsec_token=tok123&xsec_source=pc_share",
                "https://www.xiaohongshu.com/explore/abc"
        );
        assertTrue(preserved.contains("xsec_token=tok123"));
        assertTrue(preserved.contains("xsec_source=pc_share"));
    }
}
