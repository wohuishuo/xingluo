(() => {
  'use strict';

  const ext = globalThis.browser || globalThis.chrome;

  const badgeEl = document.getElementById('platform-badge');
  const titleEl = document.getElementById('page-title');
  const urlEl = document.getElementById('page-url');
  const sendPrimaryBtn = document.getElementById('send-primary');
  const sendBatchBtn = document.getElementById('send-batch');
  const statusBanner = document.getElementById('status-banner');
  const mediaSection = document.getElementById('media-section');
  const mediaCountEl = document.getElementById('media-count');
  const mediaListEl = document.getElementById('media-list');
  const imagesSection = document.getElementById('images-section');
  const imagesCountEl = document.getElementById('images-count');
  const imagesGridEl = document.getElementById('images-grid');
  const emptyHintEl = document.getElementById('empty-hint');

  let currentPageUrl = '';
  let currentPageTitle = '';
  let discoveredEntries = [];

  function buildDownloadUri(url, title) {
    const params = new URLSearchParams();
    params.set('url', url);
    if (title) params.set('title', title);
    return `xingluo://download?${params.toString()}`;
  }

  function buildBatchUri(urls) {
    const params = new URLSearchParams();
    params.set('url', urls.join('\n'));
    return `xingluo://batch?${params.toString()}`;
  }

  function showStatus(message, isOk) {
    statusBanner.textContent = message;
    statusBanner.classList.toggle('ok', Boolean(isOk));
    statusBanner.hidden = false;
  }

  async function openDeepLink(deepLink, feedbackText) {
    try {
      await ext.tabs.create({ url: deepLink });
      showStatus(feedbackText || '已呼起星落 App ✦', true);
    } catch {
      try {
        window.location.href = deepLink;
        showStatus(feedbackText || '已呼起星落 App ✦', true);
      } catch (err) {
        showStatus(`无法呼起星落：${err?.message || '请确认已安装星落 App'}`, false);
      }
    }
  }

  function isSupportedWebOrMagnet(url) {
    return /^(?:https?:\/\/|magnet:\?xt=urn:btih:)/i.test(url || '');
  }

  function inferFallbackPlatform(url) {
    if (!url) return '网页媒体';
    if (/^magnet:\?xt=urn:btih:/i.test(url)) return '磁力 / 种子';
    try {
      const host = new URL(url).hostname.toLowerCase();
      if (host.includes('bilibili.com') || host.includes('b23.tv')) return '哔哩哔哩';
      if (host.includes('douyin.com')) return '抖音';
      if (host.includes('kuaishou.com')) return '快手';
      if (host.includes('xiaohongshu.com') || host.includes('xhslink.com')) return '小红书';
      if (host.includes('pixiv.net')) return 'Pixiv';
      if (host.includes('youtube.com') || host.includes('youtu.be')) return 'YouTube';
      if (host === 'x.com' || host.endsWith('.x.com') || host.includes('twitter.com')) return 'X / Twitter';
      if (host.includes('pan.quark.cn')) return '夸克网盘';
      if (host.includes('pan.baidu.com') || host.includes('yun.baidu.com')) return '百度网盘';
    } catch {
      return '网页媒体';
    }
    return '网页媒体';
  }

  function updateBatchButton() {
    const selected = discoveredEntries.filter((entry) => entry.checked);
    if (discoveredEntries.length === 0) {
      sendBatchBtn.hidden = true;
      return;
    }
    const targetCount = selected.length > 0 ? selected.length : discoveredEntries.length;
    sendBatchBtn.hidden = false;
    sendBatchBtn.textContent = selected.length > 0
      ? `批量发送到星落 (${targetCount})`
      : `批量发送全部 (${targetCount})`;
  }

  function renderEntries(entries) {
    mediaListEl.replaceChildren();
    discoveredEntries = entries.map((item) => ({ ...item, checked: true }));

    if (discoveredEntries.length === 0) {
      mediaSection.hidden = true;
      updateBatchButton();
      return;
    }

    mediaSection.hidden = false;
    mediaCountEl.textContent = `${discoveredEntries.length} 项`;

    for (const entry of discoveredEntries) {
      const row = document.createElement('div');
      row.className = 'item-row';

      const checkbox = document.createElement('input');
      checkbox.type = 'checkbox';
      checkbox.className = 'item-check';
      checkbox.checked = entry.checked;
      checkbox.setAttribute('aria-label', `选择 ${entry.label}`);
      checkbox.addEventListener('change', () => {
        entry.checked = checkbox.checked;
        updateBatchButton();
      });

      const info = document.createElement('div');
      info.className = 'item-info';

      const label = document.createElement('div');
      label.className = 'item-label';
      label.textContent = entry.label;

      const urlText = document.createElement('div');
      urlText.className = 'item-url';
      urlText.textContent = entry.url;

      info.append(label, urlText);

      const sendBtn = document.createElement('button');
      sendBtn.type = 'button';
      sendBtn.className = 'item-send';
      sendBtn.textContent = '下载';
      sendBtn.addEventListener('click', async () => {
        await openDeepLink(buildDownloadUri(entry.url, entry.title || currentPageTitle), '已发送该媒体到星落 ✦');
      });

      row.append(checkbox, info, sendBtn);
      mediaListEl.append(row);
    }

    updateBatchButton();
  }

  function renderImages(images) {
    imagesGridEl.replaceChildren();
    if (!Array.isArray(images) || images.length === 0) {
      imagesSection.hidden = true;
      return;
    }

    imagesSection.hidden = false;
    imagesCountEl.textContent = `${images.length} 张`;

    for (const imgItem of images) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'thumb-btn';
      btn.title = imgItem.alt || imgItem.url;

      const img = document.createElement('img');
      img.src = imgItem.url;
      img.alt = imgItem.alt || '发现的图片';
      img.loading = 'lazy';

      btn.addEventListener('click', async () => {
        await openDeepLink(buildDownloadUri(imgItem.url, imgItem.alt || currentPageTitle), '已发送图片到星落 ✦');
      });

      btn.append(img);
      imagesGridEl.append(btn);
    }
  }

  async function scanActiveTab() {
    const tabs = await ext.tabs.query({ active: true, currentWindow: true });
    const tab = tabs?.[0];
    if (!tab) {
      badgeEl.textContent = '未检测到标签页';
      titleEl.textContent = '当前没有活动网页';
      return;
    }

    currentPageUrl = tab.url || '';
    currentPageTitle = tab.title || '';

    let scanResult = null;
    if (tab.id != null && isSupportedWebOrMagnet(currentPageUrl)) {
      try {
        await ext.scripting.executeScript({
          target: { tabId: tab.id },
          files: ['content.js']
        });
        scanResult = await ext.tabs.sendMessage(tab.id, { type: 'XINGLUO_SCAN' });
      } catch {
        scanResult = null;
      }
    }

    if (scanResult?.pageUrl) {
      currentPageUrl = scanResult.pageUrl;
      currentPageTitle = scanResult.pageTitle || currentPageTitle;
    }

    badgeEl.textContent = scanResult?.platformName || inferFallbackPlatform(currentPageUrl);
    titleEl.textContent = currentPageTitle || '未命名网页';
    urlEl.textContent = currentPageUrl || '浏览器内部页面无法提取网页链接';

    const canSendPage = isSupportedWebOrMagnet(currentPageUrl);
    sendPrimaryBtn.disabled = !canSendPage;

    const merged = [];
    const seenUrls = new Set();
    for (const media of scanResult?.mediaItems || []) {
      if (!seenUrls.has(media.url)) {
        seenUrls.add(media.url);
        merged.push({
          url: media.url,
          label: `${media.kind === 'opengraph' ? 'OG 视频' : '媒体流'} · ${media.label}`,
          title: currentPageTitle
        });
      }
    }
    for (const link of scanResult?.links || []) {
      if (!seenUrls.has(link.url)) {
        seenUrls.add(link.url);
        merged.push({
          url: link.url,
          label: `${link.platformName} · ${link.label}`,
          title: link.label || currentPageTitle
        });
      }
    }

    renderEntries(merged);
    renderImages(scanResult?.images || []);

    if (!canSendPage) {
      emptyHintEl.textContent = '当前页面为浏览器受限页面，请在视频、图集或网盘页面点击星落图标。';
      emptyHintEl.hidden = false;
    } else if (merged.length === 0 && (scanResult?.images?.length || 0) === 0) {
      emptyHintEl.textContent = '未在 DOM 中发现额外媒体标签，可直接点击上方按钮将当前页面链接发送给星落解析。';
      emptyHintEl.hidden = false;
    } else {
      emptyHintEl.hidden = true;
    }
  }

  sendPrimaryBtn.addEventListener('click', async () => {
    if (!isSupportedWebOrMagnet(currentPageUrl)) return;
    await openDeepLink(buildDownloadUri(currentPageUrl, currentPageTitle), '已发送到星落下载 ✦');
  });

  sendBatchBtn.addEventListener('click', async () => {
    const selected = discoveredEntries.filter((entry) => entry.checked);
    const list = (selected.length > 0 ? selected : discoveredEntries).map((entry) => entry.url);
    if (list.length === 0) return;
    const targetUri = list.length === 1
      ? buildDownloadUri(list[0], currentPageTitle)
      : buildBatchUri(list);
    await openDeepLink(targetUri, `已批量发送 ${list.length} 个链接到星落 ✦`);
  });

  document.addEventListener('DOMContentLoaded', () => {
    scanActiveTab();
  });
})();
