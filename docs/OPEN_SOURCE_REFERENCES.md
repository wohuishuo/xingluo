# 开源架构参考与洁净室致谢 (Open-Source References)

星落 Xingluo v2.0.0 坚持 **轻量纯 Java + 单一核心网络库 (`OkHttp 4.12`)** 的路线，未直接捆绑 GPL/Python/FFmpeg 二进制，而是采用**洁净室设计方式**借鉴了优秀开源项目在协议、工程与交互上的实践经验：

| 模块与能力 | 参考思路与开源生态灵感 | 星落 v2.0.0 原生实现 |
| :--- | :--- | :--- |
| **WBI 签名与流优先级解析** | Bilibili 开源生态（`yt-dlp`、`SocialSisterYi/bilibili-API-collect` 文档规范） | `BilibiliExtractor.java`（含 `wbi` 参数重排与 MD5 混洗、`try_look=1`、Live URL 与 HEVC/AVC 选择） |
| **HTTP Range 并发断点续传** | Aria2 / 1DM / DownloadManager2 分片调度模型 | `SegmentedDownloader.java`（动态 1–8 线程切片、`.part` 持久化与单线程稳妥回退） |
| **m3u8 解析 / AES-128 / 无 FFmpeg 封装** | `m3u8-parser`、Android `MediaMuxer` 轻量封装思路（Seal / ExoPlayer） | `HlsDownloader.java` + `TsToMp4Muxer.java` + `LiveRecorder.java` |
| **纯 Java BitTorrent / WebSeed** | Bencode 规范、BEP-0003 & BEP-0019 HTTP/HTTPS WebSeeding | `engine/torrent/{Bencode,TorrentMeta,TorrentDownloader}.java` |
| **Pixiv 画师批量抓图与智能滤图** | `gallery-dl`、`PixEz-flutter` 的原图 Referer 与 Tag 归档体验 | `PixivExtractor.java` + `ImageFilter.java`（过滤头像/背景/分辨率门槛/可选 AI 标签） |
| **抖音图文与各平台公开接口** | `Evil0ctal/Douyin_TikTok_Download_API`、`cobalt`、PeerTube 公开 REST API | `DouyinExtractor`（含 `aweme_type=68` 图集）、`KuaishouExtractor`、`XiaohongshuExtractor`、`GlobalMediaExtractor` |
| **WebView 隔离与多窗口 OAuth** | Android CustomTabs / Isolated CookieManager 最佳安全实践 | `AccountManager.java` + `BrowserActivity.java`（启用三方 Cookie、弹出式认证窗口支持，零外传） |
| **贡献热力图与记忆曲线打卡** | GitHub Contribution Graph / Anki 连续打卡视觉反馈 | `stats/ActivityStats.java` + `ui/HeatmapView.java` + `ui/TasteCardView.java` |
| **手机浏览器一键传链** | WebExtensions MV3、Tampermonkey / Violentmonkey Android 规范 | `browser-extension/`（MV3 + `xingluo-bridge.user.js`） |
