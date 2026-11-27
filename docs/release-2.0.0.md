# 星落 Xingluo v2.0.0 发布说明

> **Android 9+（API 28+）原生轻量全能下载器 · 仅 4.45 MB（4.25 MiB）· 纯 Java + OkHttp · 同签名密钥可直接从 v1.x 覆盖升级**

---

## 🎀 这一版更新了什么

### 1. 社区反馈重点修复（回应 [Issue #1](https://github.com/wohuishuo/xingluo/issues/1)）
- **输入框有字时无法覆盖粘贴**：输入框新增一键清空 `✕`，当输入框非空时主按钮智能切换，并新增 **「粘贴新链接」** 与 **回到首页自动识别剪贴板链接**。
- **收藏袋单条删除 & 搜索筛选**：支持在收藏袋与最近列表删除单条记录（可选 **「仅移除记录」** 或 **「连同本地文件一起删除」**），并支持关键字搜索和全部/下载中/已收藏/图集/失败筛选。
- **展示文件大小与真实分辨率**：每张收藏卡片标出准确体积及真实像素宽高（如 `5.7 MB · 实际 852×480`、`3.9 MB · 实际 720×1280`）。
- **抖音图文（Photo Album）解析**：支持 `aweme_type=68` / `images[]` 图文作品完整解析并保存到系统相册。
- **快手 / 小红书 / AI 分享页**：支持快手无水印、小红书视频与无损图集、常见 AI 生成平台公开页媒体保存。
- **可调节并发队列与提速引擎**：提供 `1–5` 任务并发调度 + 单任务 `1–8` 线程 HTTP Range 分段下载与 `.part` 断点续传，拒绝大文件龟速与中断重来。
- **网络与 VPN 诊断**：遇到海外或节点超时/403，卡片上点击 **「诊断原因」**（或首页右上角 **「诊断」**），并行测速国内/海外节点并给出清晰排查建议（是否断网、是否需开启 VPN、或需登录 Cookie）。

### 2. 新增能力与全平台扩展
- **Pixiv 专精批抓与智能过滤**：支持 Pixiv 单作品多 P 与 `/users/<uid>` 画师主页批量原图抓取，自动归档至 `Pixiv/<作者名>_<UID>/` 且文件名保留 Tag；内置 `ImageFilter` 自动过滤网页头像、背景、低清缩略图及可选 AI 标签作品。
- **HLS / AES-128 / 直播录制**：内置 `m3u8` 与 HTTP-FLV 录制器，借助 Android 原生 `MediaMuxer` 纯 Java 封装为标准 MP4，直播过程中可随时点击 **「停止并保存」**。
- **纯 Java BitTorrent / `.torrent` / Magnet（支持 BEP-0019 WebSeed）**：免除体积膨胀的原生 C++ SO 依赖，直接解析并下载公开 WebSeed / 种子资源。
- **网盘与全球站点**：支持夸克、百度网盘（含分享提取码识别）、蓝奏云，以及 YouTube、X/Twitter、TikTok、Reddit、Vimeo、微博、AcFun、PeerTube（MP4 + HLS）解析。
- **手机浏览器插件联动**：新增 [browser-extension](https://github.com/wohuishuo/xingluo/tree/main/browser-extension)（Firefox/Kiwi MV3 扩展 + Via/Tampermonkey 油猴脚本 `xingluo-bridge.user.js`），网页一键传链与选中文本唤起星落。
- **账号通行证中心 & 内置网页登录修复**：启用第三方 Cookie 支持与 OAuth 多窗口弹窗，彻底修复 B站 / Pixiv / 抖音 / 小红书 / 网盘登录态无法持久的问题，Cookie 仅保留在手机本地。
- **加速检查 GitHub 更新**：融合 GitHub 官方 API、Latest 跳转探测与多镜像竞速测温。

### 3. 交互细节与陪伴仪式感
- **活体小星（XiaoXing）**：会眨眼、输入链接时歪头盯紧输入框并给出气泡提醒；轻点随机语录，**长按摸摸头**飘出爱心并累加好感度，**快速三连击**打开收藏者档案，页面下滑时顶部萌宠会自动躲藏。
- **16 周收藏足迹热力图 & 趣味画像**：类 Anki/GitHub 的每日格子热力图，点击「展开画像」可见 24h 活跃时段、常逛平台比例与高频 UP 主 / 画师统计。

---

## 📦 Release 附件清单
- `Xingluo-2.0.0.apk`：已签名 Release APK（Android 9+ / API 28+，V3 签名 SHA-256 `a1c38981a0945d9e0904b37d314797750d6237c450a19087ed4587bdf6c64d67`）
- `Xingluo-2.0.0-browser-extension.zip`：Firefox / Kiwi 扩展与 Via 油猴脚本完整打包
- `Xingluo-2.0.0-screenshots.zip`：v2.0.0 全套真机 UI 展示截图
- `Xingluo-2.0.0-demo-1-overview.mp4` ~ `demo-4-pro-toolkit.mp4`：4 支不同主题的竖屏宣传演示视频（旗舰总览 / 短视频图文 / Pixiv 二次元 / 极客工具箱）
- `Xingluo-2.0.0-SHA256SUMS.txt`：所有发布文件的 SHA-256 校验值

---

## 🧪 验证说明
- **Android 16 模拟器 (`emulator-5560`) 实测**：v1.2.0 覆盖升级成功；`SmokeInstrumentation` 16/16 项全过；抖音真实视频实下（`4,076,253 B`，`720×1280 h264+aac`，`ffmpeg` 完整解码无错）；B站短链实下（`5,930,435 B`，`852×480 hevc+aac`，`ffmpeg` 完整解码无错）；网络/VPN 诊断实测响应正常。
- **单元测试**：`84 / 84` 全部通过（基于本地 `MiniHttpServer` 与真实报文夹具覆盖全部下载引擎、解析器、滤图、诊断、更新、账号与统计模块）。
