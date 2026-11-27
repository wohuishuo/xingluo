package app.xingluo.video;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import app.xingluo.video.auth.AccountManager;
import app.xingluo.video.engine.HttpClient;
import app.xingluo.video.ui.StarMotion;
import app.xingluo.video.ui.StarSheet;
import app.xingluo.video.ui.StarTheme;
import org.json.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import static app.xingluo.video.Ui.*;

public final class BrowserActivity extends Activity {
    private static final int MAX_SNIFFED = 50;
    private static final Pattern MEDIA_PATH = Pattern.compile("(?i)\\.(mp4|m3u8|flv)$");

    private record SniffedMedia(String url, String format, String host, String referer, String title) {}

    private WebView web;
    private TextView status;
    private TextView uaToggleBtn;
    private TextView sniffPill;
    private LinearLayout loginBanner;
    private ProgressBar progress;
    private Dialog activePopup;
    private WebView activePopupWeb;

    private String source = "";
    private String original = "";
    private volatile String currentPageUrl = "";
    private volatile String currentPageTitle = "";

    private boolean peanut = false;
    private boolean loginMode = false;
    private boolean desktopUa = false;
    private boolean filled = false;
    private boolean submitted = false;
    private boolean closed = false;
    private boolean loginBannerShown = false;
    private AccountManager.Platform targetPlatform;

    private final Map<String, SniffedMedia> sniffedMap = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        String platformExtra = getIntent().getStringExtra("platform");
        if (platformExtra != null && !platformExtra.isBlank()) {
            try { targetPlatform = AccountManager.Platform.valueOf(platformExtra); } catch (IllegalArgumentException ignored) {}
        }
        original = getIntent().getStringExtra("url");
        if (!LinkInput.isWebUrl(original) && targetPlatform != null) {
            original = targetPlatform.loginUrl();
        }
        if (!LinkInput.isWebUrl(original)) {
            finish();
            return;
        }
        peanut = getIntent().getBooleanExtra("peanut", false);
        loginMode = getIntent().getBooleanExtra("loginMode", false);
        if (targetPlatform == null) {
            targetPlatform = AccountManager.matchPlatform(original);
        }
        source = original;
        currentPageUrl = original;

        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        setContentView(root);

        // Top bar: Close + Title/Status
        LinearLayout bar = row(this);
        pad(bar, 10, 8, 14, 4);
        Icon back = new Icon(this, "close", PURPLE);
        pad(back, 10, 10, 10, 10);
        back.setContentDescription("关闭网页");
        click(back, this::finish);
        bar.addView(back, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));

        LinearLayout labels = column(this);
        pad(labels, 4, 0, 0, 0);
        String headerTitle = peanut ? "PeanutDL 备用解析"
                : loginMode ? (targetPlatform != null ? "登录 " + targetPlatform.displayName() : "平台网页登录")
                : "网页识别";
        labels.addView(text(this, headerTitle, 15, INK, true));
        String initHint = peanut ? "链接仅交给本页处理，识别后自动保存"
                : loginMode ? "登录状态仅保存在本机，支持扫码 / 弹窗验证"
                : "正常登录或播放，支持自动嗅探流媒体";
        status = text(this, initHint, 10, MUTED, false);
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        gap(labels, 3);
        labels.addView(status);
        bar.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar);

        // Toolbar controls: UA toggle + Refresh + Complete Login & Return
        LinearLayout toolbar = row(this);
        pad(toolbar, 14, 4, 14, 8);
        uaToggleBtn = toolbarPill("手机版 UA", StarTheme.LILAC_SOFT, StarTheme.PURPLE_DARK, this::toggleUa);
        toolbar.addView(uaToggleBtn);

        TextView refreshIconBtn = toolbarPill("刷新", StarTheme.LILAC_SOFT, StarTheme.INK, () -> {
            if (web != null) web.reload();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.leftMargin = dp(this, 8);
        toolbar.addView(refreshIconBtn, rlp);

        toolbar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));

        TextView doneTopBtn = toolbarPill("完成登录并返回", StarTheme.PURPLE, Color.WHITE, this::completeAndReturn);
        toolbar.addView(doneTopBtn);
        root.addView(toolbar);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(PURPLE));
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(this, 3)));

        // Warm lavender banner shown when loginMode detects valid auth cookies
        loginBanner = buildLoginBanner();
        loginBanner.setVisibility(View.GONE);
        root.addView(loginBanner, new LinearLayout.LayoutParams(-1, -2));

        // WebView container with floating bottom-right media sniffer pill
        FrameLayout webHost = new FrameLayout(this);
        web = new WebView(this);
        configureWebView(web);
        webHost.addView(web, new FrameLayout.LayoutParams(-1, -1));

        sniffPill = text(this, "嗅探到 0 个流媒体 · 点击接住", 12, Color.WHITE, true);
        pad(sniffPill, 14, 10, 14, 10);
        sniffPill.setBackground(StarTheme.ripple(StarTheme.gradient(0xFF9480D1, 0xFF7660B5, StarTheme.R_PILL, this), this));
        sniffPill.setElevation(dp(this, 6));
        sniffPill.setVisibility(View.GONE);
        StarMotion.attachSpringPress(sniffPill, this::showSniffedSheet);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        sp.rightMargin = dp(this, 14);
        sp.bottomMargin = dp(this, 14);
        webHost.addView(sniffPill, sp);

        root.addView(webHost, new LinearLayout.LayoutParams(-1, 0, 1));

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                progress.setProgress(p);
                progress.setVisibility(p < 100 ? View.VISIBLE : View.INVISIBLE);
            }

            @Override
            public void onReceivedTitle(WebView v, String title) {
                if (title != null && !title.isBlank()) currentPageTitle = title;
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                if (closed || resultMsg == null || !(resultMsg.obj instanceof WebView.WebViewTransport transport)) {
                    return false;
                }
                openPopupWindow(transport, resultMsg);
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) {
                closePopupWindow();
                flushCookies();
                checkLoginState();
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                flushCookies();
                String target = r.getUrl().toString();
                return !LinkInput.isWebUrl(target);
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                currentPageUrl = url;
                flushCookies();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                currentPageUrl = url;
                if (!peanut) source = url;
                flushCookies();
                checkLoginState();
                if (peanut && !filled && LinkInput.hostIs(Uri.parse(url).getHost(), "peanutdl.com")) {
                    filled = true;
                    fillReferenceForm();
                }
                if (!loginMode) {
                    inspect();
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (!closed && request != null && request.getUrl() != null) {
                    maybeCaptureMedia(request.getUrl().toString(), request.getRequestHeaders());
                }
                return null;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    status.setText("网页暂时打不开，可切换桌面版或刷新重试");
                }
            }
        });

        web.setDownloadListener((url, ua, disposition, mime, length) -> {
            if ((mime != null && mime.startsWith("video/")) || url.toLowerCase(Locale.ROOT).contains(".mp4")) {
                if (loginMode) {
                    captureMedia(url, "MP4", currentPageUrl);
                } else {
                    found(url, web.getTitle());
                }
            }
        });

        // Bottom footer
        LinearLayout footer = row(this);
        pad(footer, 14, 9, 14, 12);
        if (!loginMode) {
            TextView retry = button(this, "重新解析原链接", false, this::retry);
            footer.addView(retry, new LinearLayout.LayoutParams(0, -2, 1));
            TextView refresh = button(this, "刷新网页", false, () -> web.reload());
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1);
            rp.leftMargin = dp(this, 9);
            footer.addView(refresh, rp);
        } else {
            TextView refresh = button(this, "刷新网页", false, () -> web.reload());
            footer.addView(refresh, new LinearLayout.LayoutParams(0, -2, 1));
            TextView finishLogin = button(this, "完成登录并返回", true, this::completeAndReturn);
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, -2, 1);
            fp.leftMargin = dp(this, 9);
            footer.addView(finishLogin, fp);
        }
        root.addView(footer);

        String initialUrl = peanut
                ? "https://peanutdl.com/zh/" + (LinkInput.platform(original) == LinkInput.Platform.DOUYIN ? "douyin" : "bilibili")
                : original;
        web.loadUrl(initialUrl);
        handler.postDelayed(poll, 1400);
    }

    private TextView toolbarPill(String label, int bg, int fg, Runnable action) {
        TextView t = text(this, label, 11, fg, true);
        pad(t, 10, 6, 10, 6);
        t.setBackground(StarTheme.ripple(rounded(bg, StarTheme.R_PILL, this), this));
        StarMotion.attachSpringPress(t, action);
        return t;
    }

    private LinearLayout buildLoginBanner() {
        LinearLayout banner = row(this);
        pad(banner, 14, 10, 14, 10);
        banner.setBackgroundColor(StarTheme.LILAC_SOFT);
        TextView msg = text(this, "检测到已登录！现在可返回下载高清/网盘内容 ✦", 12, StarTheme.PURPLE_DARK, true);
        banner.addView(msg, new LinearLayout.LayoutParams(0, -2, 1));
        TextView btn = text(this, "立即返回", 11, Color.WHITE, true);
        pad(btn, 12, 7, 12, 7);
        btn.setBackground(StarTheme.ripple(rounded(StarTheme.PURPLE, StarTheme.R_PILL, this), this));
        StarMotion.attachSpringPress(btn, this::completeAndReturn);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.leftMargin = dp(this, 8);
        banner.addView(btn, bp);
        return banner;
    }

    private void configureWebView(WebView target) {
        WebSettings s = target.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setUserAgentString(desktopUa ? HttpClient.DESKTOP_UA : HttpClient.MOBILE_UA);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(target, true);
    }

    private void toggleUa() {
        desktopUa = !desktopUa;
        uaToggleBtn.setText(desktopUa ? "桌面版 UA" : "手机版 UA");
        if (web != null) {
            web.getSettings().setUserAgentString(desktopUa ? HttpClient.DESKTOP_UA : HttpClient.MOBILE_UA);
            web.reload();
        }
        status.setText(desktopUa ? "已切换为桌面版 UA，正在刷新…" : "已切换为手机版 UA，正在刷新…");
    }

    private void openPopupWindow(WebView.WebViewTransport transport, Message resultMsg) {
        closePopupWindow();
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = column(this);
        box.setBackground(rounded(StarTheme.SURFACE, 22, this));
        box.setClipToOutline(true);

        LinearLayout header = row(this);
        pad(header, 16, 12, 12, 10);
        header.setBackgroundColor(StarTheme.LILAC_SOFT);
        header.addView(text(this, "安全登录 / 授权弹窗", 14, StarTheme.INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView closeBtn = text(this, "✕", 15, StarTheme.MUTED, true);
        pad(closeBtn, 10, 4, 8, 4);
        StarMotion.attachSpringPress(closeBtn, this::closePopupWindow);
        header.addView(closeBtn);
        box.addView(header);

        WebView popupWeb = new WebView(this);
        configureWebView(popupWeb);
        popupWeb.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                flushCookies();
                return !LinkInput.isWebUrl(r.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                flushCookies();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                flushCookies();
                checkLoginState();
            }
        });
        popupWeb.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onCloseWindow(WebView window) {
                closePopupWindow();
                flushCookies();
                checkLoginState();
            }
        });

        box.addView(popupWeb, new LinearLayout.LayoutParams(-1, 0, 1));
        dialog.setContentView(box);
        dialog.setOnDismissListener(d -> {
            if (activePopupWeb != null) {
                activePopupWeb.stopLoading();
                activePopupWeb.destroy();
                activePopupWeb = null;
            }
            activePopup = null;
            flushCookies();
            checkLoginState();
        });

        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.78f);
            win.setLayout(w, h);
            win.setDimAmount(0.45f);
        }

        activePopup = dialog;
        activePopupWeb = popupWeb;
        dialog.show();
        transport.setWebView(popupWeb);
        resultMsg.sendToTarget();
    }

    private void closePopupWindow() {
        if (activePopup != null) {
            Dialog d = activePopup;
            activePopup = null;
            if (d.isShowing()) d.dismiss();
        }
        if (activePopupWeb != null) {
            activePopupWeb.stopLoading();
            activePopupWeb.destroy();
            activePopupWeb = null;
        }
    }

    private void flushCookies() {
        try {
            CookieManager.getInstance().flush();
        } catch (Throwable ignored) {}
    }

    private void completeAndReturn() {
        flushCookies();
        Intent result = new Intent();
        if (targetPlatform != null) {
            result.putExtra("platform", targetPlatform.name());
            result.putExtra("loggedIn", AccountManager.status(targetPlatform).loggedIn());
        }
        setResult(RESULT_OK, result);
        finish();
    }

    private void checkLoginState() {
        if (!loginMode || closed) return;
        AccountManager.Platform p = targetPlatform != null ? targetPlatform : AccountManager.matchPlatform(currentPageUrl);
        if (p == null) return;
        AccountManager.AccountStatus st = AccountManager.status(p);
        if (!st.loggedIn()) {
            AccountManager.Platform pagePlatform = AccountManager.matchPlatform(currentPageUrl);
            if (pagePlatform != null && pagePlatform != p) {
                st = AccountManager.status(pagePlatform);
            }
        }
        if (st.loggedIn() && !loginBannerShown) {
            loginBannerShown = true;
            loginBanner.setVisibility(View.VISIBLE);
            StarMotion.enter(loginBanner);
            status.setText(p.displayName() + " 已登录 · 可随时返回下载");
        }
    }

    private void maybeCaptureMedia(String url, Map<String, String> headers) {
        if (!LinkInput.isWebUrl(url)) return;
        int end = url.length();
        int q = url.indexOf('?');
        if (q >= 0) end = Math.min(end, q);
        int hash = url.indexOf('#');
        if (hash >= 0) end = Math.min(end, hash);
        String basePath = url.substring(0, end);
        Matcher m = MEDIA_PATH.matcher(basePath);
        if (!m.find()) return;
        String format = m.group(1).toUpperCase(Locale.ROOT);
        String ref = headers != null ? headers.get("Referer") : null;
        if (!LinkInput.isWebUrl(ref)) ref = currentPageUrl;
        captureMedia(url, format, ref);
    }

    private void captureMedia(String url, String format, String referer) {
        String host = "";
        try { host = Objects.toString(URI.create(url).getHost(), ""); } catch (Exception ignored) {}
        String title = currentPageTitle == null || currentPageTitle.isBlank() ? "网页嗅探视频" : currentPageTitle;
        boolean added;
        int size;
        synchronized (sniffedMap) {
            if (sniffedMap.containsKey(url) || sniffedMap.size() >= MAX_SNIFFED) return;
            sniffedMap.put(url, new SniffedMedia(url, format, host, referer, title));
            added = true;
            size = sniffedMap.size();
        }
        if (added) {
            handler.post(() -> {
                if (closed || sniffPill == null) return;
                boolean wasHidden = sniffPill.getVisibility() != View.VISIBLE;
                sniffPill.setText("嗅探到 " + size + " 个流媒体 · 点击接住");
                if (wasHidden) {
                    sniffPill.setVisibility(View.VISIBLE);
                    StarMotion.enter(sniffPill);
                }
            });
        }
    }

    private void showSniffedSheet() {
        List<SniffedMedia> items;
        synchronized (sniffedMap) {
            items = new ArrayList<>(sniffedMap.values());
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "暂未嗅探到流媒体，可先点击播放视频", Toast.LENGTH_SHORT).show();
            return;
        }
        Collections.reverse(items);
        StarSheet.show(this, "嗅探到的流媒体", "点击条目即可保存或复制地址", false, sheet -> {
            LinearLayout body = sheet.body();
            for (SniffedMedia item : items) {
                LinearLayout card = row(this);
                pad(card, 14, 12, 14, 12);
                card.setBackground(StarTheme.ripple(
                        StarTheme.border(StarTheme.SURFACE, StarTheme.LINE, StarTheme.R_CARD, this), this));

                int badgeBg = "MP4".equals(item.format) ? StarTheme.MINT_BG
                        : "FLV".equals(item.format) ? StarTheme.GOLD_BG : StarTheme.SKY_BG;
                int badgeFg = "MP4".equals(item.format) ? StarTheme.MINT_INK
                        : "FLV".equals(item.format) ? 0xFF8B6346 : StarTheme.SKY_INK;
                card.addView(StarTheme.pillBadge(this, item.format, badgeBg, badgeFg));

                LinearLayout info = column(this);
                pad(info, 10, 0, 8, 0);
                TextView hostLabel = text(this, item.host.isEmpty() ? item.title : item.host, 13, StarTheme.INK, true);
                hostLabel.setMaxLines(1);
                hostLabel.setEllipsize(TextUtils.TruncateAt.END);
                info.addView(hostLabel);
                gap(info, 3);
                TextView urlPreview = text(this, item.url, 10, StarTheme.MUTED, false);
                urlPreview.setMaxLines(2);
                urlPreview.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                info.addView(urlPreview);
                card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

                TextView actionTag = text(this, "接住", 12, StarTheme.PURPLE, true);
                card.addView(actionTag);

                StarMotion.attachSpringPress(card, () -> {
                    sheet.dismiss();
                    downloadSniffed(item);
                });
                body.addView(card);
                gap(body, 8);
            }
        });
    }

    private void downloadSniffed(SniffedMedia item) {
        if (closed) return;
        String ref = LinkInput.isWebUrl(item.referer) ? item.referer : source;
        try {
            MediaParser.checkedPlan(item.title, item.url, ref);
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage() == null ? "暂不支持直接保存此格式" : e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        flushCookies();
        String taskInput = LinkInput.isWebUrl(source) ? source : item.url;
        DownloadService.submit(this, taskInput, item.url, ref, item.title);
        Toast.makeText(this, "已加入收藏队列 ✦", Toast.LENGTH_LONG).show();
        if (!loginMode) {
            submitted = true;
            finish();
        }
    }

    private void fillReferenceForm() {
        String js = "(()=>{const e=[...document.querySelectorAll('input,textarea')].find(x=>(x.placeholder||'').includes('链接'));if(!e)return;const set=Object.getOwnPropertyDescriptor(e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value').set;set.call(e," + JSONObject.quote(original) + ");e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));setTimeout(()=>{const b=[...document.querySelectorAll('button')].find(x=>/^(获取|获取视频|解析|开始解析|获取链接)$/.test(x.innerText.trim()));if(b)b.click();},700);})()";
        web.evaluateJavascript(js, null);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (closed || submitted) return;
            if (loginMode) {
                checkLoginState();
            } else {
                inspect();
            }
            handler.postDelayed(this, 1600);
        }
    };

    private void inspect() {
        if (web == null || submitted || closed || loginMode) return;
        String js = "(()=>{let a=[...document.querySelectorAll('video')].sort((a,b)=>(b.paused?0:1)-(a.paused?0:1));let u='';for(const v of a){let x=v.currentSrc||v.src||(v.querySelector('source')||{}).src;if(x&&/^https?:/.test(x)){u=x;break;}}if(!u){const a=[...document.querySelectorAll('a[download]')].find(a=>/^https?:/.test(a.href)&&/\\.mp4(?:\\?|$)/i.test(a.href));if(a)u=a.href;}return JSON.stringify({url:u,title:document.title});})()";
        web.evaluateJavascript(js, result -> {
            if (result == null || result.equals("null")) return;
            try {
                Object decoded = new JSONTokener(result).nextValue();
                if (decoded instanceof String text) {
                    JSONObject o = new JSONObject(text);
                    String u = o.optString("url");
                    if (LinkInput.isWebUrl(u)) found(u, o.optString("title"));
                }
            } catch (Exception ignored) {}
        });
    }

    private void found(String url, String title) {
        if (submitted || closed) return;
        try {
            MediaParser.checkedPlan(title, url, source);
        } catch (Exception e) {
            status.setText(e.getMessage());
            return;
        }
        submitted = true;
        flushCookies();
        status.setText("已识别视频，正在交给小星保存…");
        String ref = LinkInput.platform(original) == LinkInput.Platform.BILIBILI
                ? "https://www.bilibili.com/"
                : currentPageUrl;
        DownloadService.submit(this, original, url, ref, title == null ? "网页视频" : title);
        Toast.makeText(this, "已识别，自动开始保存 ✦", Toast.LENGTH_LONG).show();
        finish();
    }

    private void retry() {
        if (submitted) return;
        status.setText("正在使用本机登录状态重试…");
        int quality = new Preferences(this).quality();
        worker.execute(() -> {
            try {
                Resolver.resolve(original, () -> closed, quality);
                runOnUiThread(() -> {
                    if (closed) return;
                    submitted = true;
                    DownloadService.submit(this, original);
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!closed) {
                        status.setText(e.getMessage() == null ? "暂时没有识别到视频，请播放后再试" : e.getMessage());
                    }
                });
            }
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        closePopupWindow();
        flushCookies();
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
