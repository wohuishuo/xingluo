'use strict';

const ext = globalThis.browser || globalThis.chrome;
const MENU_ID = 'xingluo-download';

function buildDownloadUri(url, title) {
  const params = new URLSearchParams();
  params.set('url', url);
  if (title) params.set('title', title);
  return `xingluo://download?${params.toString()}`;
}

function resolveTargetUrl(info, tab) {
  const candidates = [
    info?.linkUrl,
    info?.srcUrl,
    info?.selectionText?.trim(),
    info?.pageUrl,
    tab?.url
  ];
  for (const candidate of candidates) {
    if (!candidate) continue;
    if (/^(?:https?:\/\/|magnet:\?xt=urn:btih:)/i.test(candidate)) {
      return candidate;
    }
  }
  return '';
}

async function showFeedbackToast(tabId, message) {
  if (tabId == null || !ext.scripting?.executeScript) return;
  try {
    await ext.scripting.executeScript({
      target: { tabId },
      func: (text) => {
        const existing = document.getElementById('xingluo-ext-toast');
        if (existing) existing.remove();
        const toast = document.createElement('div');
        toast.id = 'xingluo-ext-toast';
        toast.textContent = text;
        Object.assign(toast.style, {
          position: 'fixed',
          bottom: '24px',
          left: '50%',
          transform: 'translateX(-50%)',
          zIndex: '2147483647',
          background: 'linear-gradient(135deg, #9480CD, #7B65B8)',
          color: '#FFFFFF',
          padding: '9px 16px',
          borderRadius: '999px',
          fontSize: '13px',
          fontWeight: '600',
          boxShadow: '0 8px 20px rgba(48, 43, 65, 0.22)',
          fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
          pointerEvents: 'none',
          transition: 'opacity 0.25s ease'
        });
        document.documentElement.appendChild(toast);
        setTimeout(() => {
          toast.style.opacity = '0';
          setTimeout(() => toast.remove(), 260);
        }, 2000);
      },
      args: [message]
    });
  } catch {
    // Ignore on restricted pages or browsers without tab scripting access
  }
}

function registerContextMenu() {
  if (!ext.contextMenus?.create) return;
  try {
    ext.contextMenus.removeAll(() => {
      void ext.runtime?.lastError;
      try {
        ext.contextMenus.create({
          id: MENU_ID,
          title: '使用星落下载',
          contexts: ['page', 'link', 'video', 'image', 'selection']
        }, () => {
          void ext.runtime?.lastError;
        });
      } catch {
        // Ignore duplicate or unsupported context menu errors
      }
    });
  } catch {
    // Ignore on platforms where contextMenus is partially unsupported
  }
}

if (ext.runtime?.onInstalled) {
  ext.runtime.onInstalled.addListener(() => {
    registerContextMenu();
  });
}

if (ext.contextMenus?.onClicked) {
  ext.contextMenus.onClicked.addListener(async (info, tab) => {
    if (info.menuItemId !== MENU_ID) return;
    const targetUrl = resolveTargetUrl(info, tab);
    if (!targetUrl) {
      await showFeedbackToast(tab?.id, '未检测到可用的网页或磁力链接');
      return;
    }

    const deepLink = buildDownloadUri(targetUrl, tab?.title || '');
    try {
      await ext.tabs.create({ url: deepLink });
      await showFeedbackToast(tab?.id, '已呼起星落 App 下载 ✦');
    } catch {
      await showFeedbackToast(tab?.id, '请确认手机或设备已安装星落 App');
    }
  });
}
