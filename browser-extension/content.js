(() => {
  'use strict';
  if (globalThis.__xingluoBridgeLoaded) return;
  globalThis.__xingluoBridgeLoaded = true;

  const ext = globalThis.browser || globalThis.chrome;
  const MAX_SCAN_LINKS = 250;
  const MAX_MEDIA_ITEMS = 24;
  const MAX_IMAGES = 12;

  function hostIs(host, domain) {
    return Boolean(host && (host === domain || host.endsWith('.' + domain)));
  }

  function isWebUrl(url) {
    if (!url || typeof url !== 'string') return false;
    try {
      const parsed = new URL(url, location.href);
      return (parsed.protocol === 'https:' || parsed.protocol === 'http:') && Boolean(parsed.hostname);
    } catch {
      return false;
    }
  }

  function isMagnet(url) {
    return typeof url === 'string' && /^magnet:\?xt=urn:btih:[A-Za-z0-9]{32,40}/i.test(url.trim());
  }

  function normalizeUrl(raw) {
    if (!raw || typeof raw !== 'string') return '';
    const trimmed = raw.trim();
    if (isMagnet(trimmed)) return trimmed;
    try {
      const resolved = new URL(trimmed, location.href);
      if (resolved.protocol === 'https:' || resolved.protocol === 'http:') {
        return resolved.href;
      }
    } catch {
      return '';
    }
    return '';
  }

  function isPeerTubePage() {
    if (/\/(?:videos\/watch|w)\/[A-Za-z0-9_-]+/i.test(location.pathname)) return true;
    const ogPlatform = document.querySelector('meta[property="og:platform"]')?.content || '';
    const generator = document.querySelector('meta[name="generator"]')?.content || '';
    return /peertube/i.test(ogPlatform) || /peertube/i.test(generator);
  }

  function detectPlatform(url) {
    if (!url) return { id: 'web', name: '网页媒体' };
    if (isMagnet(url) || /\.torrent(?:[?#].*)?$/i.test(url)) {
      return { id: 'torrent', name: '磁力 / 种子' };
    }
    let parsed;
    try {
      parsed = new URL(url);
    } catch {
      return { id: 'web', name: '网页媒体' };
    }
    const h = parsed.hostname.toLowerCase();
    const path = parsed.pathname.toLowerCase();

    if (hostIs(h, 'bilibili.com') || hostIs(h, 'b23.tv')) return { id: 'bilibili', name: '哔哩哔哩' };
    if (hostIs(h, 'douyin.com') || hostIs(h, 'iesdouyin.com')) return { id: 'douyin', name: '抖音' };
    if (hostIs(h, 'kuaishou.com') || hostIs(h, 'chenzhongtech.com')) return { id: 'kuaishou', name: '快手' };
    if (hostIs(h, 'xiaohongshu.com') || hostIs(h, 'xhslink.com')) return { id: 'xiaohongshu', name: '小红书' };
    if (hostIs(h, 'pixiv.net') || hostIs(h, 'pximg.net')) return { id: 'pixiv', name: 'Pixiv' };
    if (hostIs(h, 'youtube.com') || hostIs(h, 'youtu.be')) return { id: 'youtube', name: 'YouTube' };
    if (hostIs(h, 'x.com') || hostIs(h, 'twitter.com') || hostIs(h, 'fxtwitter.com') || hostIs(h, 'vxtwitter.com')) {
      return { id: 'twitter', name: 'X / Twitter' };
    }
    if (hostIs(h, 'pan.quark.cn')) return { id: 'quark', name: '夸克网盘' };
    if (hostIs(h, 'pan.baidu.com') || hostIs(h, 'yun.baidu.com')) return { id: 'baidu', name: '百度网盘' };
    if (url === location.href && isPeerTubePage()) return { id: 'peertube', name: 'PeerTube' };
    if (/\/(?:videos\/watch|w)\/[A-Za-z0-9_-]+/i.test(path)) return { id: 'peertube', name: 'PeerTube' };
    return { id: 'web', name: '网页媒体' };
  }

  function isSupportedPlatformLink(url) {
    if (isMagnet(url)) return true;
    const p = detectPlatform(url);
    if (p.id === 'web') return false;
    try {
      const u = new URL(url);
      const path = u.pathname;
      switch (p.id) {
        case 'bilibili':
          return hostIs(u.hostname, 'b23.tv') || /\/video\/(?:BV[1-9A-HJ-NP-Za-km-z]{10}|av\d+)/i.test(path) || /\/bangumi\/play\//i.test(path);
        case 'douyin':
          return hostIs(u.hostname, 'iesdouyin.com') || /\/video\/\d+|\/note\/\d+|modal_id=\d+/i.test(u.href);
        case 'kuaishou':
          return /\/short-video\/|\/f\//i.test(path);
        case 'xiaohongshu':
          return hostIs(u.hostname, 'xhslink.com') || /\/(?:explore|discovery\/item)\/[A-Za-z0-9]+/i.test(path);
        case 'pixiv':
          return /\/(?:artworks|users)\/\d+/i.test(path);
        case 'youtube':
          return hostIs(u.hostname, 'youtu.be') || /\/watch|\/shorts\//i.test(path);
        case 'twitter':
          return /\/status\/\d+/i.test(path);
        case 'quark':
        case 'baidu':
          return /\/s\/[A-Za-z0-9_-]+/i.test(path);
        case 'peertube':
          return /\/(?:videos\/watch|w)\/[A-Za-z0-9_-]+/i.test(path);
        default:
          return true;
      }
    } catch {
      return false;
    }
  }

  function scanPage() {
    const pageUrl = location.href;
    const ogTitle = document.querySelector('meta[property="og:title"]')?.content?.trim();
    const pageTitle = ogTitle || document.title?.trim() || '未命名网页';
    const platform = detectPlatform(pageUrl);

    const mediaSeen = new Set();
    const mediaItems = [];
    const linkSeen = new Set();
    const links = [];
    const imageSeen = new Set();
    const images = [];

    function addMedia(url, kind, label) {
      const normalized = normalizeUrl(url);
      if (!normalized || mediaSeen.has(normalized) || mediaItems.length >= MAX_MEDIA_ITEMS) return;
      mediaSeen.add(normalized);
      mediaItems.push({ url: normalized, kind, label: label || kind });
    }

    function addLink(url, kind, label) {
      const normalized = normalizeUrl(url);
      if (!normalized || normalized === pageUrl || linkSeen.has(normalized) || links.length >= MAX_MEDIA_ITEMS) return;
      linkSeen.add(normalized);
      const info = detectPlatform(normalized);
      links.push({
        url: normalized,
        kind: kind || info.id,
        platformName: info.name,
        label: label || info.name
      });
    }

    function addImage(url, alt) {
      const normalized = normalizeUrl(url);
      if (!normalized || imageSeen.has(normalized) || images.length >= MAX_IMAGES) return;
      if (/^data:|\.svg(?:$|\?)/i.test(normalized)) return;
      imageSeen.add(normalized);
      images.push({ url: normalized, alt: (alt || '').trim().slice(0, 60) });
    }

    // 1. Scan <video> and child/standalone <source> elements
    const videos = document.querySelectorAll('video');
    for (const v of videos) {
      const label = v.getAttribute('aria-label') || v.getAttribute('title') || '网页视频流';
      for (const candidate of [v.currentSrc, v.src]) {
        if (candidate && isWebUrl(candidate)) addMedia(candidate, 'video', label);
      }
      for (const s of v.querySelectorAll('source')) {
        if (s.src && isWebUrl(s.src)) addMedia(s.src, 'video-source', s.type || label);
      }
      if (v.poster && isWebUrl(v.poster)) addImage(v.poster, '视频封面');
    }

    for (const s of document.querySelectorAll('source')) {
      if (s.closest('video')) continue;
      if (s.src && isWebUrl(s.src) && (/video|mp4|webm|m3u8/i.test(s.type || s.src))) {
        addMedia(s.src, 'source', s.type || '媒体源');
      }
    }

    // 2. Scan OpenGraph & Twitter meta tags
    const ogVideoSelectors = [
      'meta[property="og:video"]',
      'meta[property="og:video:url"]',
      'meta[property="og:video:secure_url"]',
      'meta[name="twitter:player:stream"]'
    ];
    for (const sel of ogVideoSelectors) {
      for (const meta of document.querySelectorAll(sel)) {
        const content = meta.getAttribute('content');
        if (content && isWebUrl(content)) {
          addMedia(content, 'opengraph', 'OpenGraph 视频');
        }
      }
    }

    const ogImage = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]')?.getAttribute('content');
    if (ogImage && isWebUrl(ogImage)) {
      addImage(ogImage, '页面封面');
    }

    // 3. Scan <a> elements for Pixiv artworks/artists, direct downloads, magnet, and supported platforms
    const anchors = document.querySelectorAll('a[href]');
    const anchorLimit = Math.min(anchors.length, MAX_SCAN_LINKS);
    for (let i = 0; i < anchorLimit; i++) {
      const a = anchors[i];
      const href = a.getAttribute('href') || '';
      const resolved = normalizeUrl(href);
      if (!resolved) continue;

      if (a.hasAttribute('download') && /\.(?:mp4|webm|mov|m4v|mkv|mp3|m4a)(?:[?#].*)?$/i.test(resolved)) {
        addMedia(resolved, 'download', a.textContent?.trim() || '直链下载');
        continue;
      }

      if (isMagnet(resolved)) {
        addLink(resolved, 'torrent', '磁力链接');
        continue;
      }

      if (/pixiv\.net\/(?:[a-z]+\/)?artworks\/\d+/i.test(resolved)) {
        addLink(resolved, 'pixiv-artwork', a.textContent?.trim() || 'Pixiv 作品');
        continue;
      }
      if (/pixiv\.net\/(?:[a-z]+\/)?users\/\d+/i.test(resolved)) {
        addLink(resolved, 'pixiv-artist', a.textContent?.trim() || 'Pixiv 画师主页');
        continue;
      }

      if (isSupportedPlatformLink(resolved)) {
        const info = detectPlatform(resolved);
        const text = (a.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 48);
        addLink(resolved, info.id, text || info.name);
      }
    }

    // 4. Scan visible body text (bounded) for bare magnet/cloud/platform URLs
    const bodyText = (document.body?.innerText || '').slice(0, 16000);
    if (bodyText) {
      const magnetMatches = bodyText.match(/magnet:\?xt=urn:btih:[A-Za-z0-9]{32,40}[^\s<>"'【】《》\u3000-\u303f\u4e00-\u9fff]*/gi) || [];
      for (const mag of magnetMatches.slice(0, 6)) {
        addLink(mag, 'torrent', '磁力链接');
      }
      const urlMatches = bodyText.match(/https?:\/\/[^\s<>"'【】《》\u3000-\u303f\u4e00-\u9fff]+/gi) || [];
      for (const rawUrl of urlMatches.slice(0, 30)) {
        const cleaned = rawUrl.replace(/[).,;!，。；！）\]}>]+$/, '');
        if (isSupportedPlatformLink(cleaned)) {
          const info = detectPlatform(cleaned);
          addLink(cleaned, info.id, info.name);
        }
      }
    }

    // 5. Scan sizable images (and Pixiv artwork images)
    const imgElements = document.querySelectorAll('img');
    const imgLimit = Math.min(imgElements.length, 80);
    for (let i = 0; i < imgLimit && images.length < MAX_IMAGES; i++) {
      const img = imgElements[i];
      const src = img.currentSrc || img.src || img.getAttribute('data-src') || '';
      if (!src || !isWebUrl(src)) continue;
      const w = img.naturalWidth || img.width || 0;
      const h = img.naturalHeight || img.height || 0;
      const isPixivImg = /pximg\.net/i.test(src);
      if (isPixivImg || (w >= 160 && h >= 160)) {
        addImage(src, img.alt || (isPixivImg ? 'Pixiv 插画' : '页面图片'));
      }
    }

    return {
      pageUrl,
      pageTitle,
      platformId: platform.id,
      platformName: platform.name,
      mediaItems,
      images,
      links
    };
  }

  if (ext?.runtime?.onMessage) {
    ext.runtime.onMessage.addListener((message, _sender, sendResponse) => {
      if (message?.type === 'XINGLUO_SCAN') {
        sendResponse(scanPage());
        return true;
      }
      return false;
    });
  }
})();
