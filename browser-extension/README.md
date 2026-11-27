# 星落 Xingluo Bridge — 浏览器扩展与油猴脚本指南 / Browser Extension & Userscript Guide

将手机和桌面浏览器中发现的视频、图集、网盘与磁力链接，一键发送到 **星落 (Xingluo)** Android App 下载保存。  
Send discovered videos, artworks, cloud drive links, and magnet URIs from mobile or desktop browsers straight to the **Xingluo** Android app in one tap.

---

## 目录内容 / Files Included

- `manifest.json` — Manifest V3 配置（最小权限：`activeTab`、`scripting`、`contextMenus`，兼容 Firefox Android / Kiwi / Lemur / Edge / Chrome）。
- `content.js` — 扫描当前网页的 `<video>`、`<source>`、OpenGraph 视频标签、Pixiv 作品/画师页链接，以及 B 站 / 抖音 / 小红书 / 快手 / YouTube / X / 夸克 / 百度网盘 / 磁力链接。
- `popup.html` / `popup.js` — 星落奶油紫主题弹窗，支持单条下载 (`xingluo://download?url=...&title=...`) 与批量发送 (`xingluo://batch?url=...`)。
- `background.js` — 轻量 Service Worker，在桌面端及 Kiwi 等支持的浏览器注册右键菜单「使用星落下载」。
- `xingluo-bridge.user.js` — 零遥测油猴脚本，在支持平台右下角显示可拖动的「✦ 星落」悬浮胶囊。

---

## 一、浏览器扩展安装指南 / Extension Installation

> **重要提示 / Important:** 请先在手机上安装 **星落 (Xingluo)** App，浏览器才能呼起 `xingluo://` 深链接。  
> Make sure the **Xingluo** Android app is installed first so `xingluo://` deep links can launch it.

### 1. Firefox for Android（火狐手机版）

1. 将 `browser-extension/` 目录内的文件打包为 `.zip`（确保 `manifest.json` 位于压缩包根目录，而非套在子文件夹里）。  
   Zip the contents of `browser-extension/` so `manifest.json` is at the archive root.
2. **方法 A（开发者临时加载 / Temporary Add-on via USB）：**
   - 在手机 Firefox **设置 → 关于 Firefox** 连续点击版本号 5 次开启调试选项，打开 **通过 USB 进行远程调试**。
   - 电脑打开 Firefox 访问 `about:debugging#/runtime/this-firefox` 或连接设备后点击 **临时载入附加组件 (Load Temporary Add-on)**，选择 `manifest.json`（或打包好的 `.zip`）。
3. **方法 B（Firefox Nightly / 自签 XPI 安装）：**
   - 使用 Mozilla `web-ext` 签名或打包为 `.xpi`，在 Firefox Android 的附加组件调试页面或自定义合集（Collection）中安装。

### 2. Kiwi Browser（Android）

1. 将 `browser-extension/` 打包为 `.zip`（或将整个 `browser-extension` 文件夹复制到手机存储）。  
   Zip `browser-extension/` (or copy the folder to your phone's storage).
2. 打开 Kiwi Browser，点击右上角菜单 **⋮ → 扩展程序 (Extensions)**。
3. 打开右上角 **开发者模式 (Developer mode)**。
4. 点击 **+ (from .zip/.crx/.user.js)** 选择 `.zip` 压缩包（或点击 **Load** 选择 `browser-extension` 文件夹）。
5. 浏览网页时，点击右上角菜单底部即可打开 **星落 Xingluo Bridge** 弹窗，或长按网页链接选择 **使用星落下载**。

### 3. Lemur Browser（狐猴浏览器，Android）

1. 将 `browser-extension/` 打包为 `.zip` 并发送到手机。  
   Package `browser-extension/` as a `.zip` file on your phone.
2. 打开狐猴浏览器，点击底部功能栏 **扩展商店 / 扩展管理 (Extensions)**。
3. 开启 **开发者模式 (Developer mode)**。
4. 点击 **加载已解压的扩展程序 / 导入 CRX/ZIP**，选中压缩包完成安装。

### 4. Desktop Chrome / Edge（及 Edge Mobile 支持扩展版本）

1. 打开 `chrome://extensions`（Chrome）或 `edge://extensions`（Edge）。
2. 打开右上角 **开发者模式 (Developer mode)**。
3. 点击 **加载已解压的扩展程序 (Load unpacked)**，选择本仓库的 `browser-extension` 文件夹。
4. 在网页空白处、视频或链接上右键即可看到 **「使用星落下载」**，或点击工具栏扩展图标打开弹窗。

---

## 二、油猴脚本安装指南（Via / XBrowser / Tampermonkey）/ Userscript Guide

如果您的手机浏览器不支持 MV3 扩展（如 **Via 浏览器**、**X 浏览器**），或更喜欢悬浮按钮，可以直接安装 `xingluo-bridge.user.js`：  
If your mobile browser does not support MV3 extensions (such as **Via** or **XBrowser**), or you prefer a floating pill button, install `xingluo-bridge.user.js`:

### 1. Via Browser（Via 浏览器）

1. 打开 Via 浏览器，进入 **设置 → 脚本 (Scripts)**。
2. 点击右上角 **+** → **添加脚本 (Add script)**（或通过文件管理器直接打开 `xingluo-bridge.user.js`）。
3. 将 `xingluo-bridge.user.js` 的完整代码粘贴并保存（名称可填 `星落 Xingluo Bridge`）。
4. 访问 B 站、抖音、小红书、快手、Pixiv、YouTube、X、夸克网盘、百度网盘或 PeerTube 页面时，右下角会出现可拖动的 **「✦ 星落」** 悬浮胶囊，轻点即可呼起星落 App 下载。

### 2. XBrowser（X 浏览器）

1. 打开 X 浏览器，进入 **设置 → 脚本设置 / 油猴脚本 (User Scripts)**。
2. 选择 **导入脚本 / 添加自定义脚本**，导入 `xingluo-bridge.user.js`（或粘贴脚本源码并启用）。
3. 在支持的网页轻点 **「✦ 星落」** 胶囊即可直接发送当前链接给星落；长按拖动可自由调整胶囊位置（自动记忆）。

### 3. Tampermonkey / Violentmonkey / Greasemonkey

1. 点击浏览器扩展栏的 **Tampermonkey / Violentmonkey / Greasemonkey** 图标 → **添加新脚本 (Create a new script)**。
2. 全选替换编辑器内容为 `xingluo-bridge.user.js` 源码并按下 `Ctrl + S` 保存。

---

## 三、深链接协议与隐私说明 / Deep Link Protocol & Privacy

- **单条下载 / Single download:**  
  `xingluo://download?url=<URL-encoded link>&title=<URL-encoded title>`
- **批量下载 / Batch download:**  
  `xingluo://batch?url=<URL-encoded newline-separated links>`
- **隐私与零遥测 / Zero Telemetry & Minimal Permissions:**  
  - 扩展仅申请 `activeTab`、`scripting`、`contextMenus` 权限，不申请 `<all_urls>` 后台全站读取权限。
  - 仅在您点击扩展图标或右键菜单时扫描当前标签页 DOM。
  - 扩展与油猴脚本均**不发起任何网络请求**、**不收集任何统计或个人数据**。
