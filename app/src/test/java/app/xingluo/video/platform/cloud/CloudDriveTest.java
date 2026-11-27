package app.xingluo.video.platform.cloud;

import app.xingluo.video.LinkInput;
import app.xingluo.video.engine.HttpClient;
import okhttp3.Headers;
import org.junit.After;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class CloudDriveTest {

    @After
    public void tearDown() {
        HttpClient.setCookieBridgeForTest(null);
    }

    // --- Quark Cloud Drive ---

    @Test
    public void quarkExtractsPwdIdAndPasscode() {
        String shareText = "我用夸克网盘给你分享了「教程.mp4」，点击链接即可保存。链接：https://pan.quark.cn/s/ab12CD34ef56?entry=fb 提取码：Q9k2";
        String url = LinkInput.extract(shareText);
        assertEquals(LinkInput.Platform.QUARK, LinkInput.platform(url));
        assertEquals("ab12CD34ef56", QuarkExtractor.extractPwdId(url));
        assertEquals("Q9k2", LinkInput.extractPasscode(shareText));
    }

    @Test
    public void quarkParsesStokenAndAuthFailure() {
        String okJson = """
                {
                  "status": 200,
                  "code": 0,
                  "data": {
                    "stoken": "st_token_xyz_123",
                    "title": "设计素材合集"
                  }
                }
                """;
        QuarkExtractor.ShareTokenResult token = QuarkExtractor.parseStoken(okJson);
        assertEquals("st_token_xyz_123", token.stoken);
        assertEquals("设计素材合集", token.title);

        String authFailJson = """
                {
                  "status": 401,
                  "code": 41001,
                  "message": "need login"
                }
                """;
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> QuarkExtractor.parseStoken(authFailJson));
        assertTrue(ex.getMessage().contains("请点网页识别登录夸克网盘账号后再加速下载"));
    }

    @Test
    public void quarkParsesDetailDownloadAndSaveToSelfFallback() {
        String detailJson = """
                {
                  "status": 200,
                  "code": 0,
                  "data": {
                    "list": [
                      {
                        "fid": "dir_001",
                        "file_name": "子目录",
                        "size": 0,
                        "dir": true,
                        "file_type": 0,
                        "share_fid_token": "tok_dir"
                      },
                      {
                        "fid": "fid_video_1",
                        "file_name": "demo_1080p.mp4",
                        "size": 52428800,
                        "dir": false,
                        "file_type": 1,
                        "share_fid_token": "tok_vid_1"
                      }
                    ]
                  }
                }
                """;
        List<QuarkExtractor.QuarkFile> files = QuarkExtractor.parseShareFiles(detailJson);
        assertEquals(2, files.size());
        assertTrue(files.get(0).dir);
        assertFalse(files.get(1).dir);
        assertEquals("fid_video_1", files.get(1).fid);
        assertEquals("demo_1080p.mp4", files.get(1).name);
        assertEquals(52428800L, files.get(1).size);
        assertEquals("tok_vid_1", files.get(1).shareFidToken);

        String saveJson = """
                {
                  "status": 200,
                  "code": 0,
                  "data": {
                    "task_id": "task_save_9981"
                  }
                }
                """;
        assertEquals("task_save_9981", QuarkExtractor.parseSaveTaskId(saveJson));

        String taskPollJson = """
                {
                  "status": 200,
                  "code": 0,
                  "data": {
                    "status": 2,
                    "save_as": {
                      "save_as_top_fids": ["saved_fid_777"]
                    }
                  }
                }
                """;
        List<String> savedFids = QuarkExtractor.parseTaskSavedFids(taskPollJson);
        assertEquals(List.of("saved_fid_777"), savedFids);

        String downloadJson = """
                {
                  "status": 200,
                  "code": 0,
                  "data": [
                    {
                      "fid": "saved_fid_777",
                      "file_name": "demo_1080p.mp4",
                      "size": 52428800,
                      "download_url": "https://dl-pc.quark.cn/file/demo_1080p.mp4?auth=xyz"
                    }
                  ]
                }
                """;
        List<QuarkExtractor.DownloadEntry> entries = QuarkExtractor.parseDownloadUrls(downloadJson);
        assertEquals(1, entries.size());
        assertEquals("https://dl-pc.quark.cn/file/demo_1080p.mp4?auth=xyz", entries.get(0).downloadUrl);
        assertEquals(52428800L, entries.get(0).size);
    }

    @Test
    public void quarkMergesCookiesAcrossPanAndDriveDomains() {
        Map<String, String> store = new HashMap<>();
        store.put("https://pan.quark.cn/", "__pus=abc123; cna=xyz");
        store.put("https://drive-pc.quark.cn/", "__puus=rotated_token; __pvi=1");
        HttpClient.setCookieBridgeForTest(new HttpClient.CookieStoreBridge() {
            @Override public String getCookie(String url) { return store.get(url); }
            @Override public void setCookie(String url, String setCookieHeader) {}
        });

        String merged = QuarkExtractor.mergedQuarkCookies();
        assertTrue(merged.contains("__pus=abc123"));
        assertTrue(merged.contains("__puus=rotated_token"));
        assertTrue(merged.contains("cna=xyz"));
    }

    // --- Baidu Netdisk ---

    @Test
    public void baiduExtractsSurlPasscodeAndVerifiesBdclndCookie() {
        String shareText = "链接: https://pan.baidu.com/s/1AbCdEfGhIjK 提取码: 9x2m";
        String url = LinkInput.extract(shareText);
        assertEquals(LinkInput.Platform.BAIDU_PAN, LinkInput.platform(url));
        String surl = BaiduNetdiskExtractor.extractSurl(url);
        assertEquals("1AbCdEfGhIjK", surl);
        assertEquals("AbCdEfGhIjK", BaiduNetdiskExtractor.stripLeadingOne(surl));
        assertEquals("9x2m", LinkInput.extractPasscode(shareText));
        assertEquals("1xyz987", BaiduNetdiskExtractor.extractSurl("https://pan.baidu.com/share/init?surl=xyz987"));

        String verifyJson = """
                {"errno":0,"err_msg":"","randsk":"randsk_token_value%3D"}
                """;
        Headers headers = new Headers.Builder()
                .add("Set-Cookie", "BDCLND=randsk_token_value%3D; Path=/")
                .build();
        assertEquals("randsk_token_value%3D", BaiduNetdiskExtractor.parseVerifyRandsk(verifyJson, headers));
    }

    @Test
    public void baiduParsesShareHtmlListAndDownloadJson() {
        String shareHtml = """
                <html>
                <head><title>学习资料.zip_免费高速下载|百度网盘-分享无限制</title></head>
                <script>
                locals.mset({"uk":20481024,"shareid":9988776655,"sign":"ab12cd34ef567890","timestamp":1730000123});
                </script>
                </html>
                """;
        BaiduNetdiskExtractor.ShareMeta meta = BaiduNetdiskExtractor.parseShareHtmlMeta(shareHtml);
        assertTrue(meta.isComplete());
        assertEquals("20481024", meta.uk);
        assertEquals("9988776655", meta.shareId);
        assertEquals("ab12cd34ef567890", meta.sign);
        assertEquals("1730000123", meta.timestamp);
        assertEquals("学习资料.zip", meta.title);

        String listJson = """
                {
                  "errno": 0,
                  "list": [
                    {
                      "fs_id": 112233445566,
                      "server_filename": "学习资料.zip",
                      "size": 104857600,
                      "isdir": 0,
                      "path": "/学习资料.zip"
                    },
                    {
                      "fs_id": 223344556677,
                      "server_filename": "子文件夹",
                      "size": 0,
                      "isdir": 1,
                      "path": "/子文件夹"
                    }
                  ]
                }
                """;
        List<BaiduNetdiskExtractor.BaiduFile> files = BaiduNetdiskExtractor.parseShareList(listJson);
        assertEquals(2, files.size());
        assertEquals(112233445566L, files.get(0).fsId);
        assertEquals("学习资料.zip", files.get(0).name);
        assertEquals(104857600L, files.get(0).size);
        assertFalse(files.get(0).dir);
        assertTrue(files.get(1).dir);

        String dlJson = """
                {
                  "errno": 0,
                  "list": [
                    {
                      "fs_id": 112233445566,
                      "dlink": "https://d.pcs.baidu.com/file/abcdef?fid=112233445566&dstime=1730000200"
                    }
                  ]
                }
                """;
        List<BaiduNetdiskExtractor.BaiduDownload> dls = BaiduNetdiskExtractor.parseDownloadLinks(dlJson);
        assertEquals(1, dls.size());
        assertEquals(112233445566L, dls.get(0).fsId);
        assertTrue(dls.get(0).dlink.startsWith("https://d.pcs.baidu.com/"));
    }

    // --- Lanzou Cloud ---

    @Test
    public void lanzouExtractsIframeAndAjaxmParamsWithCommentsAndVars() {
        String shareHtml = """
                <html>
                <head><title>星落安装包.apk - 蓝奏云</title></head>
                <body>
                <iframe class="ifr2" name="1234" src="/fn?VmFRZV01U2g=&amp;t=1" frameborder="0"></iframe>
                </body>
                </html>
                """;
        assertEquals("/fn?VmFRZV01U2g=&t=1", LanzouExtractor.extractIframeSrc(shareHtml));

        String iframeHtml = """
                <script type="text/javascript">
                // https://example.com/comment-url-must-not-break
                var ajaxdata = 'dyn_signs_value';
                var ws_sign = 'ws_123456';
                /* block comment
                   var ajaxdata = 'fake_commented_out';
                */
                $.ajax({
                    type : 'post',
                    url : '/ajaxm.php?file=889900',
                    data : {
                        'action':'downprocess',
                        'signs':ajaxdata,
                        'sign':'V2RSYVUxBmg=',
                        'websign':ws_sign,
                        'websignkey':'wk_99',
                        'ves':1
                    }
                });
                </script>
                """;
        String stripped = LanzouExtractor.stripJsComments(iframeHtml);
        assertFalse(stripped.contains("fake_commented_out"));

        LanzouExtractor.AjaxRequestSpec spec = LanzouExtractor.parseAjaxmRequest(iframeHtml, "");
        assertEquals("/ajaxm.php?file=889900", spec.endpointPath);
        assertEquals("downprocess", spec.params.get("action"));
        assertEquals("dyn_signs_value", spec.params.get("signs"));
        assertEquals("V2RSYVUxBmg=", spec.params.get("sign"));
        assertEquals("ws_123456", spec.params.get("websign"));
        assertEquals("wk_99", spec.params.get("websignkey"));
        assertEquals("1", spec.params.get("ves"));
    }

    @Test
    public void lanzouParsesAjaxmResponseAndFolderList() {
        String ajaxmJson = """
                {
                  "zt": 1,
                  "dom": "https://developer.lanzoug.com",
                  "url": "?UxUCMg1vBzRTYgFj",
                  "inf": "星落安装包.apk"
                }
                """;
        LanzouExtractor.AjaxmResponse resp = LanzouExtractor.parseAjaxmResponse(ajaxmJson);
        assertEquals("https://developer.lanzoug.com/file/?UxUCMg1vBzRTYgFj", resp.redirectUrl);
        assertEquals("星落安装包.apk", resp.inf);

        String folderHtml = """
                <script>
                var t_tok = '1730000555';
                var k_tok = 'k_token_abc';
                $.ajax({
                    type : 'post',
                    url : '/filemoreajax.php',
                    data : { 'lx':2, 'fid':12345, 'uid':'67890', 'pg':1, 'rep':'0', 't':t_tok, 'k':k_tok, 'up':1, 'ls':1, 'pwd':'' }
                });
                </script>
                """;
        assertTrue(LanzouExtractor.isFolderShare(folderHtml));
        LanzouExtractor.AjaxRequestSpec folderSpec = LanzouExtractor.parseFolderRequest(folderHtml, "ab12");
        assertEquals("/filemoreajax.php", folderSpec.endpointPath);
        assertEquals("12345", folderSpec.params.get("fid"));
        assertEquals("1730000555", folderSpec.params.get("t"));
        assertEquals("k_token_abc", folderSpec.params.get("k"));
        assertEquals("ab12", folderSpec.params.get("pwd"));

        String folderJson = """
                {
                  "zt": 1,
                  "text": [
                    {"id": "iAbc123", "name_all": "tool.zip", "size": "15.2 M"},
                    {"id": "iDef456", "name_all": "readme.txt", "size": "4.0 K"}
                  ]
                }
                """;
        List<LanzouExtractor.FolderFileEntry> entries = LanzouExtractor.parseFolderFiles(folderJson);
        assertEquals(2, entries.size());
        assertEquals("iAbc123", entries.get(0).id);
        assertEquals("tool.zip", entries.get(0).name);
    }
}
