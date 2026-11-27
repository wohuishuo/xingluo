// ==UserScript==
// @name         星落 Xingluo Bridge
// @namespace    https://github.com/wohuishuo/xingluo
// @version      1.0.0
// @description  在支持的视频、图集与网盘网页右下角添加可拖动「✦ 星落」悬浮胶囊，一键呼起星落 App 下载（零遥测、无外部请求）。
// @author       Xingluo
// @match        *://*.bilibili.com/*
// @match        *://b23.tv/*
// @match        *://*.douyin.com/*
// @match        *://*.iesdouyin.com/*
// @match        *://*.kuaishou.com/*
// @match        *://*.chenzhongtech.com/*
// @match        *://*.xiaohongshu.com/*
// @match        *://xhslink.com/*
// @match        *://*.pixiv.net/*
// @match        *://*.youtube.com/*
// @match        *://youtu.be/*
// @match        *://*.x.com/*
// @match        *://*.twitter.com/*
// @match        *://pan.quark.cn/*
// @match        *://pan.baidu.com/*
// @match        *://yun.baidu.com/*
// @match        *://*/videos/watch/*
// @match        *://*/w/*
// @grant        none
// @run-at       document-idle
// @noframes
// ==/UserScript==

(() => {
  'use strict';

  const HOST_ID = 'xingluo-userscript-bridge-host';
  const POS_KEY = 'xingluo_pill_pos_v1';
  if (document.getElementById(HOST_ID)) return;

  function loadSavedPosition() {
    try {
      const raw = localStorage.getItem(POS_KEY);
      if (!raw) return null;
      const parsed = JSON.parse(raw);
      if (typeof parsed?.right === 'number' && typeof parsed?.bottom === 'number') {
        return parsed;
      }
    } catch {
      return null;
    }
    return null;
  }

  function savePosition(right, bottom) {
    try {
      localStorage.setItem(POS_KEY, JSON.stringify({ right, bottom }));
    } catch {
      // Ignore storage quota or private mode restrictions
    }
  }

  function buildDeepLink() {
    const params = new URLSearchParams();
    params.set('url', location.href);
    const ogTitle = document.querySelector('meta[property="og:title"]')?.content?.trim();
    const title = ogTitle || document.title?.trim() || '';
    if (title) params.set('title', title);
    return `xingluo://download?${params.toString()}`;
  }

  function triggerDeepLink(uri) {
    const link = document.createElement('a');
    link.href = uri;
    link.style.display = 'none';
    document.body.appendChild(link);
    link.click();
    link.remove();
  }

  const host = document.createElement('div');
  host.id = HOST_ID;
  host.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;inset:0;';

  const shadow = host.attachShadow({ mode: 'open' });

  const style = document.createElement('style');
  style.textContent = `
    .pill {
      position: fixed;
      right: 16px;
      bottom: 84px;
      pointer-events: auto;
      display: inline-flex;
      align-items: center;
      gap: 6px;
      height: 38px;
      padding: 0 14px;
      border-radius: 999px;
      border: 1px solid #EBE7EF;
      background: linear-gradient(135deg, #FAF9F6 0%, #EEE9F8 100%);
      color: #7E69BD;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
      font-size: 13px;
      font-weight: 700;
      letter-spacing: 0.02em;
      box-shadow: 0 6px 18px rgba(48, 43, 65, 0.16);
      cursor: pointer;
      user-select: none;
      -webkit-user-select: none;
      touch-action: none;
      transition: transform 0.14s ease, box-shadow 0.14s ease, background 0.14s ease, color 0.14s ease;
    }
    .pill:active {
      transform: scale(0.96);
    }
    .pill.sent {
      background: linear-gradient(135deg, #9480CD, #7B65B8);
      color: #FFFFFF;
      border-color: transparent;
    }
  `;

  const pill = document.createElement('button');
  pill.type = 'button';
  pill.className = 'pill';
  pill.setAttribute('aria-label', '发送到星落下载');
  pill.textContent = '✦ 星落';

  function clampPosition(right, bottom) {
    const maxRight = Math.max(8, window.innerWidth - pill.offsetWidth - 8);
    const maxBottom = Math.max(8, window.innerHeight - pill.offsetHeight - 8);
    return {
      right: Math.min(Math.max(8, right), maxRight),
      bottom: Math.min(Math.max(8, bottom), maxBottom)
    };
  }

  const saved = loadSavedPosition();
  if (saved) {
    pill.style.right = `${saved.right}px`;
    pill.style.bottom = `${saved.bottom}px`;
  }

  let pointerId = null;
  let startX = 0;
  let startY = 0;
  let startRight = 16;
  let startBottom = 84;
  let moved = false;

  pill.addEventListener('pointerdown', (e) => {
    if (e.button !== 0 && e.pointerType === 'mouse') return;
    pointerId = e.pointerId;
    startX = e.clientX;
    startY = e.clientY;
    startRight = parseFloat(getComputedStyle(pill).right) || 16;
    startBottom = parseFloat(getComputedStyle(pill).bottom) || 84;
    moved = false;
    try {
      pill.setPointerCapture(pointerId);
    } catch {
      // Ignore if pointer capture is unavailable
    }
  });

  pill.addEventListener('pointermove', (e) => {
    if (pointerId !== e.pointerId) return;
    const dx = e.clientX - startX;
    const dy = e.clientY - startY;
    if (!moved && Math.hypot(dx, dy) < 6) return;
    moved = true;
    const pos = clampPosition(startRight - dx, startBottom - dy);
    pill.style.right = `${pos.right}px`;
    pill.style.bottom = `${pos.bottom}px`;
  });

  function finishPointer(e) {
    if (pointerId !== e.pointerId) return;
    pointerId = null;
    try {
      pill.releasePointerCapture(e.pointerId);
    } catch {
      // Ignore
    }
    if (moved) {
      const right = parseFloat(pill.style.right) || 16;
      const bottom = parseFloat(pill.style.bottom) || 84;
      savePosition(right, bottom);
      return;
    }

    triggerDeepLink(buildDeepLink());
    pill.classList.add('sent');
    pill.textContent = '已发送 ✦';
    window.setTimeout(() => {
      pill.classList.remove('sent');
      pill.textContent = '✦ 星落';
    }, 1500);
  }

  pill.addEventListener('pointerup', finishPointer);
  pill.addEventListener('pointercancel', () => {
    pointerId = null;
  });

  shadow.append(style, pill);
  (document.body || document.documentElement).appendChild(host);
})();
