星落 Xingluo 1.1.0 是一款二次元风格的开源安卓视频下载器，支持 B站（bilibili）和抖音（Douyin）公开分享链接解析、下载与分享保存。Android 10+，MIT 开源。

## 下载与安装

展开本页下方 **Assets**，下载 **Xingluo-1.1.0.apk**；**Source code** 是源码，不是安装包。已有 1.0 正式版可直接覆盖升级。

[项目说明与常见问题](https://github.com/wohuishuo/xingluo#readme) · [English guide](https://github.com/wohuishuo/xingluo/blob/main/README.en.md) · [一分钟演示视频](https://github.com/wohuishuo/xingluo/releases/download/v1.1.0/Xingluo-60s.mp4)

## 1.1.0 功能

- 保存到系统相册、下载目录或系统授权的自选文件夹。
- 选择默认画质首选：1080p、720p、480p、360p。
- 失败时自动重新解析原链接，总共最多尝试 3 次，包含首次尝试。
- B站备用 CDN、音画合并和公开 MP4 回退。
- 二次元界面，接收其他应用分享后自动保存。

画质与可用性由平台提供的公开源决定。视频号微信卡片、加密内容、HLS/DRM 暂不支持。请保存有权使用的内容。

验证：21 项单元测试，12 项 Android 检查，Lint 0 错误；模拟器验证。没有宣称全部安卓版本与所有链接可用。

## English

Xingluo is an open-source Android video downloader for public Bilibili and Douyin links. This release adds selectable folders, preferred video quality and up to three total attempts with fresh-link resolution. Download the `.apk` asset to install on Android 10 or newer. Native WeChat cards, encrypted media, HLS, DRM and membership bypass are not supported.
