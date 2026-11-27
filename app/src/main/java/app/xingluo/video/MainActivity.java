package app.xingluo.video;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import app.xingluo.video.auth.AccountManager;
import app.xingluo.video.engine.torrent.TorrentMeta;
import app.xingluo.video.stats.ActivityStats;
import app.xingluo.video.ui.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import static app.xingluo.video.ui.StarTheme.*;

public final class MainActivity extends Activity {
    private static final String APP_VERSION = "2.0.0";

    private FrameLayout rootFrame;
    private LinearLayout shell, body, bottomBar, taskList, navRow;
    private View navPill;
    private EditText input, searchInput;
    private View clearInputBtn;
    private TextView primarySubmitBtn, pasteAltBtn, speechBubble, clipboardBannerTitle, bagBadge;
    private LinearLayout clipboardBanner;
    private XiaoXingCompanionView heroMascot, peekMascot;
    private StarDrawer drawer;
    private StarfieldOverlay starfield;

    private TaskStore store;
    private int tab = 0;
    private String bagFilter = "全部", searchQuery = "";
    private String lastSubmitted = "", draft = "";
    private String pendingClipboardUrl = "", pendingClipboardRaw = "";
    private String lastCompletedId = "";
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refreshTasks(); }
    };

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new TaskStore(this);
        if (saved != null) {
            tab = saved.getInt("tab");
            lastSubmitted = saved.getString("last", "");
            draft = saved.getString("draft", "");
            bagFilter = saved.getString("bagFilter", "全部");
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, new IntentFilter(TaskStore.CHANGED), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, new IntentFilter(TaskStore.CHANGED), "app.xingluo.video.INTERNAL", null);
        }
        buildShell();
        if (saved == null) consume(getIntent());
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        lastSubmitted = "";
        consume(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshTasks();
        checkClipboardSoon();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) checkClipboardSoon();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("tab", tab);
        out.putString("last", lastSubmitted);
        out.putString("bagFilter", bagFilter);
        if (input != null) out.putString("draft", input.getText().toString());
        super.onSaveInstanceState(out);
    }

    @Override public void onBackPressed() {
        if (drawer != null && drawer.isOpen()) {
            drawer.close();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        unregisterReceiver(receiver);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void consume(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri stream = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                : intent.getParcelableExtra(Intent.EXTRA_STREAM);

        if (Intent.ACTION_SEND.equals(action) && stream != null && "content".equals(stream.getScheme())) {
            String mime = intent.getType() == null ? "" : intent.getType();
            if (mime.startsWith("video/") || mime.startsWith("image/")) {
                if (!lastSubmitted.equals(stream.toString())) {
                    lastSubmitted = stream.toString();
                    requestStorageAndNotifications();
                    DownloadService.submit(this, "分享的视频", stream.toString(), "", displayName(stream));
                    showTasksAfterSubmit();
                }
                return;
            }
            if (mime.contains("bittorrent") || displayName(stream).toLowerCase(Locale.ROOT).endsWith(".torrent")) {
                handleTorrentContentUri(stream);
                return;
            }
        }

        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            Uri data = intent.getData();
            String scheme = data.getScheme() == null ? "" : data.getScheme().toLowerCase(Locale.ROOT);
            if ("xingluo".equals(scheme)) {
                String url = data.getQueryParameter("url");
                if (url != null && !url.isBlank() && !url.equals(lastSubmitted)) {
                    lastSubmitted = url;
                    submit(url);
                }
                return;
            }
            if ("magnet".equals(scheme)) {
                String mag = data.toString();
                if (!mag.equals(lastSubmitted)) {
                    lastSubmitted = mag;
                    submit(mag);
                }
                return;
            }
            if ("content".equals(scheme) || "file".equals(scheme)) {
                handleTorrentContentUri(data);
                return;
            }
        }

        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null && Intent.ACTION_PROCESS_TEXT.equals(action)) {
            CharSequence proc = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            if (proc != null) text = proc.toString();
        }
        if (text == null && Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<String> list = intent.getStringArrayListExtra(Intent.EXTRA_TEXT);
            if (list != null) text = String.join("\n", list);
        }
        if (text != null && !text.isBlank() && !text.equals(lastSubmitted)) {
            lastSubmitted = text;
            submit(text);
        }
    }

    private void handleTorrentContentUri(Uri uri) {
        try {
            File localTorrent = new File(getCacheDir(), "import-" + System.currentTimeMillis() + ".torrent");
            try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(localTorrent)) {
                if (in == null) throw new IOException("无法读取种子文件");
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            TorrentMeta meta = TorrentMeta.fromTorrentFile(localTorrent);
            if (!meta.webSeeds.isEmpty()) {
                DownloadService.submit(this, meta.webSeeds.get(0), meta.webSeeds.get(0), "", meta.name);
            } else if (!meta.trackers.isEmpty()) {
                String magnet = "magnet:?xt=urn:btih:" + meta.infoHashHex + "&dn=" + Uri.encode(meta.name) + "&tr=" + Uri.encode(meta.trackers.get(0));
                DownloadService.submit(this, magnet, "", "", meta.name);
            } else {
                toast("该 .torrent 未包含可用的 Tracker 或 WebSeed");
                return;
            }
            showTasksAfterSubmit();
        } catch (Exception e) {
            toast("解析种子失败：" + e.getMessage());
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0).replaceAll("(?i)\\.mp4$", "");
        } catch (Exception ignored) {}
        return "分享的视频";
    }

    private void buildShell() {
        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(BG);

        shell = column(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOnScrollChangeListener((v, sx, sy, oldX, oldY) -> {
            if (peekMascot != null) peekMascot.onScrollDelta(sy - oldY);
        });

        body = column(this);
        pad(body, 20, 10, 20, 24);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        buildBottomBar();
        shell.addView(bottomBar, new LinearLayout.LayoutParams(-1, dp(this, 74)));
        rootFrame.addView(shell, new FrameLayout.LayoutParams(-1, -1));

        drawer = new StarDrawer(this, this::handleDrawerAction);
        rootFrame.addView(drawer.view(), new FrameLayout.LayoutParams(-1, -1));

        starfield = new StarfieldOverlay(this);
        rootFrame.addView(starfield, new FrameLayout.LayoutParams(-1, -1));

        setContentView(rootFrame);
        renderTab();
    }

    private void buildBottomBar() {
        bottomBar = column(this);
        bottomBar.setBackground(border(0xFFFDFCFA, LINE, 0, this));
        FrameLayout navContainer = new FrameLayout(this);
        pad(navContainer, 14, 7, 14, 8);

        navPill = new View(this);
        navPill.setBackground(rounded(LILAC, 18, this));
        navContainer.addView(navPill, new FrameLayout.LayoutParams(dp(this, 100), -1));

        navRow = row(this);
        String[] icons = {"home", "bag", "settings"};
        String[] labels = {"接住心动", "我的收藏", "小小偏好"};
        for (int i = 0; i < 3; i++) {
            final int index = i;
            LinearLayout item = column(this);
            item.setGravity(Gravity.CENTER);
            FrameLayout iconWrap = new FrameLayout(this);
            Ui.Icon icon = new Ui.Icon(this, icons[i], tab == i ? PURPLE : MUTED);
            iconWrap.addView(icon, new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER));
            if (i == 1) {
                bagBadge = text(this, "", 8, Color.WHITE, true);
                bagBadge.setGravity(Gravity.CENTER);
                bagBadge.setBackground(rounded(PURPLE, R_PILL, this));
                bagBadge.setPadding(dp(this, 4), dp(this, 1), dp(this, 4), dp(this, 1));
                bagBadge.setVisibility(View.GONE);
                FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
                bp.rightMargin = dp(this, 18);
                iconWrap.addView(bagBadge, bp);
            }
            item.addView(iconWrap, new LinearLayout.LayoutParams(-1, dp(this, 25)));
            gap(item, 4);
            TextView label = text(this, labels[i], 10, tab == i ? PURPLE : MUTED, tab == i);
            label.setGravity(Gravity.CENTER);
            item.addView(label);
            StarMotion.attachSpringPress(item, () -> {
                if (tab != index) {
                    tab = index;
                    renderTab();
                }
            });
            navRow.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        }
        navContainer.addView(navRow, new FrameLayout.LayoutParams(-1, -1));
        bottomBar.addView(navContainer, new LinearLayout.LayoutParams(-1, -1));
    }

    private void syncBottomBar() {
        for (int i = 0; i < 3; i++) {
            LinearLayout item = (LinearLayout) navRow.getChildAt(i);
            FrameLayout iconWrap = (FrameLayout) item.getChildAt(0);
            Ui.Icon oldIcon = (Ui.Icon) iconWrap.getChildAt(0);
            String name = new String[]{"home", "bag", "settings"}[i];
            iconWrap.removeViewAt(0);
            iconWrap.addView(new Ui.Icon(this, name, tab == i ? PURPLE : MUTED), 0,
                    new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER));
            TextView label = (TextView) item.getChildAt(2);
            label.setTextColor(tab == i ? PURPLE : MUTED);
            label.setTypeface(tab == i ? android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) : android.graphics.Typeface.DEFAULT);
        }
        navRow.post(() -> {
            int w = navRow.getWidth() / 3;
            if (w > 0) {
                ViewGroup.LayoutParams lp = navPill.getLayoutParams();
                lp.width = w - dp(this, 4);
                navPill.setLayoutParams(lp);
                float targetX = tab * w + dp(this, 2);
                if (StarMotion.enabled(this)) {
                    navPill.animate().translationX(targetX).setDuration(240).setInterpolator(new OvershootInterpolator(0.9f)).start();
                } else {
                    navPill.setTranslationX(targetX);
                }
            }
        });
    }

    private void renderTab() {
        if (input != null) {
            draft = input.getText().toString();
            input = null;
        }
        searchInput = null;
        taskList = null;
        heroMascot = null;
        peekMascot = null;
        body.removeAllViews();
        syncBottomBar();

        if (tab == 0) home();
        else if (tab == 1) bag();
        else settings();

        StarMotion.enter(body);
    }

    private void topBar(String title, String subtitle, boolean showPeekMascot) {
        LinearLayout bar = row(this);
        bar.setPadding(0, dp(this, 4), 0, dp(this, 14));

        Ui.Icon menuBtn = new Ui.Icon(this, "menu", PURPLE);
        menuBtn.setBackground(StarTheme.ripple(rounded(LILAC_SOFT, 14, this), this));
        pad(menuBtn, 9, 9, 9, 9);
        menuBtn.setContentDescription("打开菜单");
        StarMotion.attachSpringPress(menuBtn, () -> drawer.open(this));
        bar.addView(menuBtn, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));

        LinearLayout words = column(this);
        pad(words, 12, 0, 8, 0);
        words.addView(text(this, title, 21, INK, true));
        gap(words, 2);
        TextView sub = text(this, subtitle, 9, MUTED, false);
        sub.setLetterSpacing(0.09f);
        words.addView(sub);
        bar.addView(words, new LinearLayout.LayoutParams(0, -2, 1));

        if (showPeekMascot) {
            peekMascot = new XiaoXingCompanionView(this, XiaoXingCompanionView.Mode.PEEK);
            peekMascot.setListener(new XiaoXingCompanionView.Listener() {
                @Override public void onSpeech(String msg, boolean hi) { toast(msg); }
                @Override public void onTripleTapEasterEgg() { showMascotProfileSheet(); }
                @Override public void onErrorTap() { NetworkDoctorSheet.show(MainActivity.this, "", "", ""); }
            });
            bar.addView(peekMascot, new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
            hGap(bar, 6);
        }

        LinearLayout docPill = row(this);
        docPill.setBackground(StarTheme.ripple(rounded(LILAC_SOFT, R_PILL, this), this));
        pad(docPill, 10, 8, 11, 8);
        docPill.addView(new Ui.Icon(this, "pulse", PURPLE), new LinearLayout.LayoutParams(dp(this, 15), dp(this, 15)));
        hGap(docPill, 5);
        docPill.addView(text(this, "诊断", 11, PURPLE_DARK, true));
        StarMotion.attachSpringPress(docPill, () -> NetworkDoctorSheet.show(this, "", "", ""));
        bar.addView(docPill);

        body.addView(bar);
    }

    private void home() {
        topBar("星落", "A LITTLE VIDEO POCKET · v" + APP_VERSION, false);

        // Hero card with Living XiaoXing
        FrameLayout hero = new FrameLayout(this);
        hero.setBackground(gradient(0xFFECE6F7, 0xFFF4EFF6, R_HERO, this));
        hero.setClipToOutline(true);
        body.addView(hero, new LinearLayout.LayoutParams(-1, dp(this, 212)));

        TextView tag = pillBadge(this, "✦  把喜欢，留在身边", 0xB3FFFFFF, PURPLE_DARK);
        frame(hero, tag, -2, -2, 16, 16, Gravity.TOP | Gravity.LEFT);

        TextView title = text(this, "每一份心动\n都有处可藏。", 22, INK, true);
        title.setLineSpacing(dp(this, 5), 1f);
        frame(hero, title, 180, -2, 18, 54, Gravity.TOP | Gravity.LEFT);

        speechBubble = text(this, "长按小星可以摸摸头，输入链接我会盯紧哦～", 9.5f, PURPLE_DARK, true);
        speechBubble.setBackground(rounded(0xCCFFFFFF, 14, this));
        pad(speechBubble, 9, 7, 9, 7);
        speechBubble.setLineSpacing(dp(this, 2), 1f);
        frame(hero, speechBubble, 148, -2, 14, 130, Gravity.TOP | Gravity.LEFT);

        heroMascot = new XiaoXingCompanionView(this, XiaoXingCompanionView.Mode.HERO);
        heroMascot.setListener(new XiaoXingCompanionView.Listener() {
            @Override public void onSpeech(String msg, boolean hi) {
                if (speechBubble != null) {
                    speechBubble.setText(msg);
                    speechBubble.setBackground(rounded(hi ? 0xFFF8F1FF : 0xCCFFFFFF, 14, MainActivity.this));
                }
                if (msg.contains("摸头") && starfield != null && heroMascot != null) {
                    int[] loc = new int[2];
                    heroMascot.getLocationInWindow(loc);
                    starfield.heartsAt(loc[0] + heroMascot.getWidth() * 0.5f, loc[1] + heroMascot.getHeight() * 0.25f);
                }
            }
            @Override public void onTripleTapEasterEgg() {
                if (starfield != null) starfield.burstCenter();
                showMascotProfileSheet();
            }
            @Override public void onErrorTap() {
                NetworkDoctorSheet.show(MainActivity.this, "", "", "");
            }
        });
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(dp(this, 152), dp(this, 174), Gravity.END | Gravity.BOTTOM);
        mp.rightMargin = dp(this, -2);
        mp.bottomMargin = dp(this, 6);
        hero.addView(heroMascot, mp);

        gap(body, 14);

        // Auto Clipboard Banner (hidden until valid link found)
        clipboardBanner = row(this);
        clipboardBanner.setBackground(border(LILAC_SOFT, PURPLE, R_CARD, this));
        pad(clipboardBanner, 13, 11, 12, 11);
        clipboardBanner.setVisibility(View.GONE);
        LinearLayout cbWords = column(this);
        cbWords.addView(text(this, "✦ 小星从剪贴板发现了链接", 11, PURPLE_DARK, true));
        gap(cbWords, 2);
        clipboardBannerTitle = text(this, "", 10, MUTED, false);
        clipboardBannerTitle.setMaxLines(1);
        clipboardBannerTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        cbWords.addView(clipboardBannerTitle);
        clipboardBanner.addView(cbWords, new LinearLayout.LayoutParams(0, -2, 1));
        TextView cbGo = pillBadge(this, "立即接住", PURPLE, Color.WHITE);
        StarMotion.attachSpringPress(cbGo, () -> {
            if (!pendingClipboardRaw.isEmpty()) {
                new Preferences(this).lastClipboardHash(String.valueOf(pendingClipboardRaw.hashCode()));
                clipboardBanner.setVisibility(View.GONE);
                if (input != null) input.setText(pendingClipboardRaw);
                submit(pendingClipboardRaw);
            }
        });
        clipboardBanner.addView(cbGo);
        hGap(clipboardBanner, 6);
        TextView cbDismiss = pillBadge(this, "忽略", SURFACE, MUTED);
        StarMotion.attachSpringPress(cbDismiss, () -> {
            new Preferences(this).lastClipboardHash(String.valueOf(pendingClipboardRaw.hashCode()));
            clipboardBanner.setVisibility(View.GONE);
        });
        clipboardBanner.addView(cbDismiss);
        body.addView(clipboardBanner);
        gap(body, 10);

        // Input & Paste Card (Fixes Issue #1 item 4!)
        LinearLayout section = row(this);
        section.addView(text(this, "接住一个视频或图集", 15, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        Preferences prefs = new Preferences(this);
        TextView enginePill = pillBadge(this, "⚡ " + prefs.threadsPerTask() + "线程 · " + prefs.maxConcurrentTasks() + "并发", LILAC_SOFT, PURPLE_DARK);
        StarMotion.attachSpringPress(enginePill, this::showEngineSheet);
        section.addView(enginePill);
        body.addView(section);
        gap(body, 10);

        LinearLayout card = column(this);
        card.setBackground(border(SURFACE, LINE, 22, this));
        pad(card, 14, 12, 14, 14);
        body.addView(card);

        LinearLayout inputRow = row(this);
        inputRow.setGravity(Gravity.TOP);
        input = new EditText(this);
        input.setText(draft);
        input.setTextSize(13);
        input.setTextColor(INK);
        input.setHintTextColor(0xFFAAA3B7);
        input.setHint("粘贴链接、分享文案、Pixiv画师、网盘或 magnet: 磁力…");
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setMinLines(2);
        input.setMaxLines(4);
        input.setGravity(Gravity.TOP);
        input.setPadding(dp(this, 2), dp(this, 2), dp(this, 2), dp(this, 4));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setContentDescription("视频链接或分享文案");
        inputRow.addView(input, new LinearLayout.LayoutParams(0, dp(this, 62), 1));

        clearInputBtn = text(this, "✕", 14, MUTED, true);
        pad(clearInputBtn, 8, 4, 4, 8);
        clearInputBtn.setVisibility(draft.isEmpty() ? View.GONE : View.VISIBLE);
        StarMotion.attachSpringPress(clearInputBtn, () -> {
            if (input != null) {
                input.setText("");
                draft = "";
            }
        });
        inputRow.addView(clearInputBtn);
        card.addView(inputRow);

        View line = new View(this);
        line.setBackgroundColor(LINE);
        card.addView(line, new LinearLayout.LayoutParams(-1, dp(this, 1)));
        gap(card, 12);

        LinearLayout btnRow = row(this);
        primarySubmitBtn = primaryButton(this, draft.isBlank() ? "粘贴并下载" : "开始下载", this::handlePrimarySubmit);
        btnRow.addView(primarySubmitBtn, new LinearLayout.LayoutParams(0, dp(this, 46), 1));

        pasteAltBtn = secondaryButton(this, "粘贴新链接", this::pasteFreshFromClipboard);
        pasteAltBtn.setVisibility(draft.isBlank() ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams altLp = new LinearLayout.LayoutParams(-2, dp(this, 46));
        altLp.leftMargin = dp(this, 8);
        btnRow.addView(pasteAltBtn, altLp);
        card.addView(btnRow);

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                String txt = s.toString();
                boolean has = !txt.isBlank();
                if (clearInputBtn != null) clearInputBtn.setVisibility(has ? View.VISIBLE : View.GONE);
                if (primarySubmitBtn != null) primarySubmitBtn.setText(has ? "开始下载" : "粘贴并下载");
                if (pasteAltBtn != null) pasteAltBtn.setVisibility(has ? View.VISIBLE : View.GONE);
                if (heroMascot != null) heroMascot.lookAtInput(txt);
            }
        });

        gap(body, 12);
        HorizontalScrollView chipsScroll = new HorizontalScrollView(this);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = row(this);
        addPlatformChip(chips, "哔哩哔哩", 0xFFE2EFF8, 0xFF50788D, "支持 BV/b23/分P/4K 与 B站直播录制");
        addPlatformChip(chips, "抖音·图文", 0xFFEEFAF6, 0xFF427A66, "支持无水印视频、抖音图文相册与直播");
        addPlatformChip(chips, "小红书", 0xFFFDEEEF, 0xFFB04F5B, "支持无水印视频笔记与无损原图集");
        addPlatformChip(chips, "快手", 0xFFFFF3E4, 0xFF9C6024, "支持快手短视频与图集无水印保存");
        addPlatformChip(chips, "Pixiv", 0xFFE6F3FD, 0xFF3E6F9E, "支持作品/画师主页批量抓取，自动建 Tag 文件夹并过滤杂图");
        addPlatformChip(chips, "网盘加速", 0xFFEFEBFB, 0xFF6B53AE, "支持夸克网盘、百度网盘（含提取码）、蓝奏云多线程加速");
        addPlatformChip(chips, "YouTube·X", 0xFFF3EFFB, 0xFF705E92, "支持 YouTube/X/TikTok/Reddit/PeerTube/Vimeo/AI 分享");
        addPlatformChip(chips, "磁力·直播", 0xFFF7F0E6, 0xFF846442, "支持 HLS/FLV 直播录制、magnet 磁力与 WebSeed");
        chipsScroll.addView(chips);
        body.addView(chipsScroll);

        gap(body, 18);
        LinearLayout recent = row(this);
        recent.addView(text(this, "最近的心动", 15, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView all = text(this, "收藏袋 & 热力图  ›", 11, PURPLE, true);
        StarMotion.attachSpringPress(all, () -> { tab = 1; renderTab(); });
        recent.addView(all);
        body.addView(recent);
        gap(body, 10);

        taskList = column(this);
        body.addView(taskList);
        refreshTasks();
    }

    private void addPlatformChip(LinearLayout group, String label, int bg, int fg, String tip) {
        TextView t = pillBadge(this, "• " + label, bg, fg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMargins(0, 0, dp(this, 6), 0);
        StarMotion.attachSpringPress(t, () -> {
            if (heroMascot != null) heroMascot.reactToEvent(XiaoXingCompanionView.EventType.CLIPBOARD_FOUND, tip);
            else toast(tip);
        });
        group.addView(t, p);
    }

    private void frame(FrameLayout parent, View v, int width, int height, int x, int y, int gravity) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(width < 0 ? width : dp(this, width), height < 0 ? height : dp(this, height), gravity);
        p.leftMargin = dp(this, x);
        p.topMargin = dp(this, y);
        parent.addView(v, p);
    }

    private void bag() {
        topBar("我的收藏袋", "YOUR LITTLE COLLECTION & INSIGHTS", true);

        // Search input
        LinearLayout searchBox = row(this);
        searchBox.setBackground(border(SURFACE, LINE, R_CARD, this));
        pad(searchBox, 14, 6, 10, 6);
        searchInput = new EditText(this);
        searchInput.setText(searchQuery);
        searchInput.setHint("搜索标题、UP主、画师或平台…");
        searchInput.setTextSize(12);
        searchInput.setTextColor(INK);
        searchInput.setHintTextColor(0xFFAAA3B7);
        searchInput.setBackgroundColor(Color.TRANSPARENT);
        searchInput.setSingleLine(true);
        searchBox.addView(searchInput, new LinearLayout.LayoutParams(0, dp(this, 36), 1));
        if (!searchQuery.isEmpty()) {
            TextView clr = text(this, "✕", 13, MUTED, true);
            pad(clr, 8, 4, 6, 4);
            StarMotion.attachSpringPress(clr, () -> { searchQuery = ""; renderTab(); });
            searchBox.addView(clr);
        }
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                searchQuery = s.toString();
                refreshTasks();
            }
        });
        body.addView(searchBox);
        gap(body, 10);

        // Filter pills
        HorizontalScrollView filterScroll = new HorizontalScrollView(this);
        filterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout filters = row(this);
        for (String value : new String[]{"全部", "下载中", "已收藏", "图集", "失败"}) {
            boolean active = bagFilter.equals(value);
            TextView f = pillBadge(this, value, active ? PURPLE : LILAC_SOFT, active ? Color.WHITE : MUTED);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(this, 7);
            StarMotion.attachSpringPress(f, () -> { bagFilter = value; renderTab(); });
            filters.addView(f, lp);
        }
        filterScroll.addView(filters);
        body.addView(filterScroll);
        gap(body, 12);

        // Heatmap + Expandable Taste Card
        ActivityStats.Summary summary = new ActivityStats(this).summarize();
        body.addView(new TasteCardView(this, summary, null));
        gap(body, 14);

        taskList = column(this);
        body.addView(taskList);
        refreshTasks();
    }

    private void refreshTasks() {
        List<TaskStore.Task> allTasks = store.all();
        long activeCount = allTasks.stream().filter(TaskStore.Task::active).count();
        if (bagBadge != null) {
            if (activeCount > 0) {
                bagBadge.setText(String.valueOf(activeCount));
                bagBadge.setVisibility(View.VISIBLE);
            } else {
                bagBadge.setVisibility(View.GONE);
            }
        }
        if (heroMascot != null && !allTasks.isEmpty()) {
            TaskStore.Task top = allTasks.get(0);
            if (top.active()) {
                heroMascot.reactToEvent(XiaoXingCompanionView.EventType.DOWNLOADING_FAST, null);
            } else if ("done".equals(top.status) && !top.id.equals(lastCompletedId)) {
                lastCompletedId = top.id;
                heroMascot.reactToEvent(XiaoXingCompanionView.EventType.SUCCESS, "「" + top.title + "」已收藏进袋子 ✦");
                if (starfield != null) starfield.burstCenter();
            } else if ("failed".equals(top.status)) {
                heroMascot.reactToEvent(XiaoXingCompanionView.EventType.ERROR, null);
            }
        }

        if (taskList == null || tab == 2) return;
        List<TaskStore.Task> filtered = tab == 1 ? store.search(searchQuery, bagFilter) : allTasks;
        taskList.removeAllViews();
        int shown = 0;
        for (TaskStore.Task t : filtered) {
            if (tab == 0 && shown >= 3) break;
            taskList.addView(taskCard(t));
            gap(taskList, 10);
            shown++;
        }
        if (shown == 0) {
            LinearLayout empty = column(this);
            empty.setGravity(Gravity.CENTER);
            pad(empty, 14, 22, 14, 22);
            empty.setBackground(border(0xFFFEFDFB, LINE, R_CARD, this));
            empty.addView(new Ui.Icon(this, "bag", 0xFFBDB2D2), new LinearLayout.LayoutParams(dp(this, 30), dp(this, 30)));
            gap(empty, 10);
            empty.addView(text(this, tab == 0 ? "还没有收藏，先接住一个喜欢的瞬间吧" : "这里暂时没有匹配的记录", 11, MUTED, false));
            taskList.addView(empty);
        }
    }

    private View taskCard(TaskStore.Task task) {
        LinearLayout card = column(this);
        pad(card, 14, 13, 14, 13);
        card.setBackground(border(SURFACE, LINE, R_CARD, this));

        LinearLayout top = row(this);
        String icon = "done".equals(task.status) ? "check"
                : "failed".equals(task.status) ? "globe"
                : "GALLERY".equals(task.mediaType) ? "image"
                : "download";
        int tone = "done".equals(task.status) ? MINT_INK
                : "failed".equals(task.status) ? ROSE_INK
                : PURPLE;
        Ui.Icon symbol = new Ui.Icon(this, icon, tone);
        symbol.setBackground(rounded("done".equals(task.status) ? MINT_BG : "failed".equals(task.status) ? ROSE_BG : LILAC_SOFT, 13, this));
        pad(symbol, 10, 10, 10, 10);
        top.addView(symbol, new LinearLayout.LayoutParams(dp(this, 42), dp(this, 42)));

        LinearLayout words = column(this);
        pad(words, 11, 0, 0, 0);
        TextView title = text(this, task.title, 13, INK, true);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(title);
        gap(words, 4);

        String pName = task.platform.isEmpty()
                ? (task.input.equals("分享的视频") ? "分享导入" : LinkInput.platformName(task.input))
                : task.platform;
        String meta = pName
                + (task.author.isEmpty() ? "" : " · " + task.author)
                + " · " + new SimpleDateFormat("MM/dd HH:mm", Locale.CHINA).format(new Date(task.created));
        words.addView(text(this, meta, 9, MUTED, false));
        top.addView(words, new LinearLayout.LayoutParams(0, -2, 1));

        if (!task.active()) {
            Ui.Icon del = new Ui.Icon(this, "trash", MUTED);
            pad(del, 7, 7, 7, 7);
            del.setContentDescription("删除记录");
            StarMotion.attachSpringPress(del, () -> confirmDeleteTask(task));
            top.addView(del, new LinearLayout.LayoutParams(dp(this, 30), dp(this, 30)));
        }
        card.addView(top);

        // Badges row
        if (task.watermarkFree || "GALLERY".equals(task.mediaType) || task.resumable || task.threadCount > 1) {
            gap(card, 7);
            LinearLayout badges = row(this);
            if (task.watermarkFree) {
                badges.addView(pillBadge(this, "无水印", MINT_BG, MINT_INK));
                hGap(badges, 5);
            }
            if ("GALLERY".equals(task.mediaType) && task.itemCount > 1) {
                badges.addView(pillBadge(this, "图集 " + task.itemCount + "P", SKY_BG, SKY_INK));
                hGap(badges, 5);
            }
            if (task.resumable) {
                badges.addView(pillBadge(this, "可续传", GOLD_BG, 0xFF8C5D18));
                hGap(badges, 5);
            }
            if (task.active() && task.threadCount > 1) {
                badges.addView(pillBadge(this, task.threadCount + "线程加速", LILAC_SOFT, PURPLE_DARK));
            }
            card.addView(badges);
        }

        gap(card, 8);
        TextView detail = text(this, task.detail, 11, "failed".equals(task.status) ? ROSE_INK : MUTED, false);
        detail.setLineSpacing(dp(this, 3), 1f);
        card.addView(detail);

        if (task.active()) {
            gap(card, 9);
            StarProgressBar p = new StarProgressBar(this);
            p.setProgressValue(task.percent);
            card.addView(p, new LinearLayout.LayoutParams(-1, dp(this, 6)));
        }

        gap(card, 10);
        LinearLayout actions = row(this);
        actions.setGravity(Gravity.END);
        if ("recording".equals(task.status)) {
            smallAction(actions, "停止并保存", () -> DownloadService.stopLiveAndSave(this, task.id));
            smallAction(actions, "取消", () -> DownloadService.cancel(this, task.id));
        } else if (task.active()) {
            smallAction(actions, "暂停", () -> DownloadService.pause(this, task.id));
            smallAction(actions, "取消", () -> DownloadService.cancel(this, task.id));
        } else if ("paused".equals(task.status)) {
            smallAction(actions, "继续下载", () -> retry(task));
            smallAction(actions, "取消", () -> DownloadService.cancel(this, task.id));
        } else if ("done".equals(task.status)) {
            smallAction(actions, "分享", () -> shareMedia(task));
            smallAction(actions, "打开 ↗", () -> openMedia(task));
        } else {
            smallAction(actions, task.resumable ? "断点续传" : "重试", () -> retry(task));
            smallAction(actions, "诊断原因", () -> NetworkDoctorSheet.show(this, task.errorCause.isEmpty() ? task.detail : task.errorCause, task.errorCode, task.input));
            if (!task.input.equals("分享的视频") && LinkInput.isWebUrl(task.input)) {
                smallAction(actions, "网页识别", () -> openBrowser(task.input, false));
            }
        }
        card.addView(actions);
        return card;
    }

    private void smallAction(LinearLayout row, String label, Runnable action) {
        TextView t = text(this, label, 11, PURPLE_DARK, true);
        pad(t, 10, 7, 10, 7);
        t.setBackground(StarTheme.ripple(rounded(LILAC_SOFT, 11, this), this));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.leftMargin = dp(this, 6);
        StarMotion.attachSpringPress(t, action);
        row.addView(t, p);
    }

    private void confirmDeleteTask(TaskStore.Task task) {
        StarSheet.show(this, "删除这条收藏记录？", task.title, false, b -> {
            b.option("仅移除收藏袋记录", "手机相册 / 下载目录里的文件会保留", true, () -> {
                store.delete(task.id);
                refreshTasks();
                toast("已从列表移除");
            });
            if ("done".equals(task.status) && !task.uri.isEmpty()) {
                b.option("同时删除本地文件", "移除记录并删除已保存的文件", false, () -> {
                    MediaFiles.deletePublished(this, task.uri);
                    store.delete(task.id);
                    refreshTasks();
                    toast("已删除记录与本地文件");
                });
            }
        });
    }

    private void handlePrimarySubmit() {
        String value = input != null ? input.getText().toString().strip() : "";
        if (value.isEmpty()) {
            pasteFreshFromClipboard();
        } else {
            submit(value);
        }
    }

    private void pasteFreshFromClipboard() {
        String clipText = readClipboardText();
        if (clipText.isBlank()) {
            toast("剪贴板里还没有链接，先去复制一段分享文案吧");
            return;
        }
        if (input != null) input.setText(clipText);
        submit(clipText);
    }

    private String readClipboardText() {
        try {
            ClipboardManager clip = getSystemService(ClipboardManager.class);
            if (clip != null && clip.hasPrimaryClip() && clip.getPrimaryClip() != null && clip.getPrimaryClip().getItemCount() > 0) {
                CharSequence c = clip.getPrimaryClip().getItemAt(0).coerceToText(this);
                return c == null ? "" : c.toString().strip();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void checkClipboardSoon() {
        if (tab != 0 || clipboardBanner == null || !new Preferences(this).autoClipboard()) return;
        handler.postDelayed(() -> {
            if (tab != 0 || clipboardBanner == null) return;
            String raw = readClipboardText();
            if (raw.isEmpty()) return;
            String hash = String.valueOf(raw.hashCode());
            if (hash.equals(new Preferences(this).lastClipboardHash())) return;
            String extracted = LinkInput.tryExtract(raw);
            if (extracted == null) return;
            pendingClipboardRaw = raw;
            pendingClipboardUrl = extracted;
            clipboardBannerTitle.setText(LinkInput.platformName(extracted) + " · " + extracted);
            clipboardBanner.setVisibility(View.VISIBLE);
            if (heroMascot != null) {
                heroMascot.reactToEvent(XiaoXingCompanionView.EventType.CLIPBOARD_FOUND,
                        "在剪贴板看到 " + LinkInput.platformName(extracted) + " 链接！点下方直接接住～");
            }
        }, 260);
    }

    private void submit(String raw) {
        try {
            String url = LinkInput.extract(raw);
            requestStorageAndNotifications();
            DownloadService.submit(this, raw.contains("提取码") || raw.contains("pwd") ? raw : url);
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null && rootFrame != null) imm.hideSoftInputFromWindow(rootFrame.getWindowToken(), 0);
            if (input != null) input.clearFocus();
            showTasksAfterSubmit();
        } catch (Exception e) {
            toast(e.getMessage() == null ? "暂时无法开始下载" : e.getMessage());
        }
    }

    private void requestStorageAndNotifications() {
        List<String> req = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(0).getBoolean("notifAsked", false)) {
            getPreferences(0).edit().putBoolean("notifAsked", true).apply();
            req.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT == 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            req.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!req.isEmpty()) requestPermissions(req.toArray(new String[0]), 10);
    }

    private void showTasksAfterSubmit() {
        tab = 1;
        renderTab();
        toast("收到，交给小星啦 ✦");
        handler.postDelayed(this::refreshTasks, 350);
    }

    private void retry(TaskStore.Task t) {
        if (!t.direct.isEmpty()) DownloadService.submit(this, t.input, t.direct, t.referer, t.title);
        else submit(t.input);
    }

    private void openBrowser(String raw, boolean peanut) {
        try {
            String url = LinkInput.extract(raw);
            startActivity(new Intent(this, BrowserActivity.class).putExtra("url", url).putExtra("peanut", peanut));
        } catch (Exception e) { toast(e.getMessage()); }
    }

    private void openMedia(TaskStore.Task task) {
        try {
            String mime = "GALLERY".equals(task.mediaType) ? "image/*" : "AUDIO".equals(task.mediaType) ? "audio/*" : "video/mp4";
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(task.uri), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception e) { toast("没有找到文件，可能已被移动或删除"); }
    }

    private void shareMedia(TaskStore.Task task) {
        try {
            String mime = "GALLERY".equals(task.mediaType) ? "image/*" : "AUDIO".equals(task.mediaType) ? "audio/*" : "video/mp4";
            Intent i = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, Uri.parse(task.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "分享这个心动瞬间"));
        } catch (Exception e) { toast("文件可能已被移动或删除"); }
    }

    private void settings() {
        topBar("小小偏好", "KEEP IT SIMPLE, KEEP IT YOURS", false);
        Preferences prefs = new Preferences(this);

        preference("画质与编码偏好", prefs.quality() + "p · " + (prefs.preferHevc() ? "HEVC 优先" : "H.264 兼容") + (prefs.audioOnly() ? " · 仅音频" : ""), "play", this::chooseQualitySheet);
        preference("保存位置", prefs.locationLabel(), "bag", this::chooseDestinationSheet);
        preference("多引擎速度与并发", "同时 " + prefs.maxConcurrentTasks() + " 个任务 · 单任务 " + prefs.threadsPerTask() + " 线程加速" + (prefs.autoResume() ? " · 断点续传" : ""), "bolt", this::showEngineSheet);
        preference("剪贴板自动识别", prefs.autoClipboard() ? "回到首页时自动探测新链接" : "已关闭自动探测", "paste", () -> {
            prefs.autoClipboard(!prefs.autoClipboard());
            renderTab();
            toast(prefs.autoClipboard() ? "已开启剪贴板自动识别" : "已关闭剪贴板自动识别");
        });
        preference("Pixiv 与图集过滤", "最小 " + prefs.pixivMinPx() + "px · 单次上限 " + prefs.pixivMaxBatch() + " 张" + (prefs.pixivSkipAi() ? " · 过滤AI" : ""), "image", this::showPixivFilterSheet);
        preference("账号通行证中心", "管理 B站 / 抖音 / 小红书 / Pixiv / 网盘登录态", "globe", () -> AccountCenterSheet.show(this, this::renderTab));
        preference("浏览器插件联动", "Firefox / Kiwi / Via / 油猴脚本与网页选中直传", "share", () -> BrowserBridgeSheet.show(this, null));
        preference("网络与 VPN 诊断", "一键排查国内外访问阻断、403 或节点超时", "pulse", () -> NetworkDoctorSheet.show(this, "", "", ""));
        preference("加速检查更新", "当前 v" + APP_VERSION + " · 支持官方 API 与镜像探测", "star", () -> UpdateSheet.show(this, APP_VERSION));
        preference("国产手机安装指南", "华为鸿蒙 / 小米 / OPPO / vivo 防拦截说明", "link", this::showOemGuideSheet);
        preference("整理收藏记录", "清除失败任务或已结束记录（保留本地文件）", "trash", this::showCleanupSheet);

        gap(body, 14);
        TextView note = text(this, "星落 " + APP_VERSION + " · Android 9+ 原生轻量全能下载器\nMIT 开源协议 · 所有解析与 Cookie 均仅保存在手机本地。", 10, MUTED, false);
        note.setLineSpacing(dp(this, 4), 1f);
        pad(note, 4, 0, 4, 0);
        body.addView(note);
    }

    private void handleDrawerAction(StarDrawer.Action action) {
        switch (action) {
            case ACCOUNTS -> AccountCenterSheet.show(this, this::renderTab);
            case DOCTOR -> NetworkDoctorSheet.show(this, "", "", "");
            case BROWSER_BRIDGE -> BrowserBridgeSheet.show(this, null);
            case ENGINE -> showEngineSheet();
            case TORRENT_INPUT -> {
                tab = 0;
                renderTab();
                if (input != null) input.setHint("粘贴 magnet:?xt=urn:btih:... 磁力链接或 .torrent 地址…");
                toast("已切换到磁力 / 种子输入模式");
            }
            case UPDATE -> UpdateSheet.show(this, APP_VERSION);
            case GITHUB -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/wohuishuo/xingluo")));
        }
    }

    private void chooseQualitySheet() {
        Preferences prefs = new Preferences(this);
        StarSheet.show(this, "画质与编码偏好", "多档源时优先选不超过首选的最高清晰度", true, b -> {
            int[] values = {2160, 1080, 720, 480, 360};
            String[] labels = {"4K · 2160p 超清优先（需平台支持）", "1080p · 高清优先（推荐）", "720p · 均衡流畅", "480p · 省流量", "360p · 极速缓存"};
            for (int i = 0; i < values.length; i++) {
                final int v = values[i];
                b.option(labels[i], "", prefs.quality() == v, () -> {
                    prefs.quality(v);
                    renderTab();
                    toast("画质首选已设为 " + v + "p");
                });
            }
            b.option("HEVC (H.265) 优先：" + (prefs.preferHevc() ? "已开启" : "已关闭"),
                    "同画质体积更小，老设备播放异常时可关闭", false, () -> {
                        prefs.preferHevc(!prefs.preferHevc());
                        renderTab();
                    });
            b.option("仅提取音频：" + (prefs.audioOnly() ? "已开启" : "已关闭"),
                    "直接保存 M4A / AAC 音轨，适合音乐与播客", false, () -> {
                        prefs.audioOnly(!prefs.audioOnly());
                        renderTab();
                    });
        });
    }

    private void chooseDestinationSheet() {
        Preferences prefs = new Preferences(this);
        StarSheet.show(this, "选择默认保存位置", "支持 Android 9+ 相册与自定义文件夹", false, b -> {
            b.option("系统相册 · Movies / 星落", "图集自动存入 Pictures / 星落", "gallery".equals(prefs.destination()), () -> {
                prefs.destination("gallery");
                renderTab();
                toast("已设为系统相册");
            });
            b.option("下载目录 · Download / 星落", "网盘文件与种子自动存入 Download / 星落", "downloads".equals(prefs.destination()), () -> {
                prefs.destination("downloads");
                renderTab();
                toast("已设为下载目录");
            });
            b.option("自选文件夹（系统授权）", "支持 Pixiv 画师按 Tag 自动创建子目录", "custom".equals(prefs.destination()), () -> {
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(picker, 910);
            });
        });
    }

    private void showEngineSheet() {
        Preferences prefs = new Preferences(this);
        StarSheet.show(this, "多引擎加速控制台", "调节并发队列与 Range 分段线程数", true, b -> {
            for (int c : new int[]{1, 2, 3, 5}) {
                b.option("同时下载 " + c + " 个任务", c >= 3 ? "多任务并发队列" : "节省电量与带宽", prefs.maxConcurrentTasks() == c, () -> {
                    prefs.maxConcurrentTasks(c);
                    renderTab();
                    toast("已设为最多 " + c + " 个任务并发（后续服务生效）");
                });
            }
            for (int t : new int[]{1, 2, 4, 8}) {
                b.option("单任务 " + t + " 线程分段", t >= 4 ? "适合高速视频、夸克网盘与大文件" : "适合限流节点", prefs.threadsPerTask() == t, () -> {
                    prefs.threadsPerTask(t);
                    renderTab();
                    toast("已设为单任务 " + t + " 线程");
                });
            }
            b.option("长视频断点续传：" + (prefs.autoResume() ? "已开启" : "已关闭"),
                    "失败后保留已下载分片，重试或重新解析后继续", false, () -> {
                        prefs.autoResume(!prefs.autoResume());
                        renderTab();
                    });
        });
    }

    private void showPixivFilterSheet() {
        Preferences prefs = new Preferences(this);
        StarSheet.show(this, "Pixiv 与图集过滤", "自动排除背景图、头像和过小素材", true, b -> {
            b.option("最小分辨率过滤：" + prefs.pixivMinPx() + "px", "点此循环切换 0 / 400 / 800 px", false, () -> {
                int next = prefs.pixivMinPx() == 0 ? 400 : prefs.pixivMinPx() == 400 ? 800 : 0;
                prefs.pixivMinPx(next);
                renderTab();
                toast("最小边长过滤已设为 " + next + "px");
            });
            b.option("自动过滤网页背景/UI小图：" + (prefs.pixivSkipBg() ? "已开启" : "已关闭"), "跳过头像、横幅与网站图标", false, () -> {
                prefs.pixivSkipBg(!prefs.pixivSkipBg());
                renderTab();
            });
            b.option("过滤 AI 标记作品：" + (prefs.pixivSkipAi() ? "已开启" : "已关闭"), "根据 Pixiv aiType 元数据跳过 AI 生成图", false, () -> {
                prefs.pixivSkipAi(!prefs.pixivSkipAi());
                renderTab();
            });
            b.option("画师主页单次抓包上限：" + prefs.pixivMaxBatch() + " 作品", "点此切换 15 / 30 / 60", false, () -> {
                int next = prefs.pixivMaxBatch() <= 15 ? 30 : prefs.pixivMaxBatch() <= 30 ? 60 : 15;
                prefs.pixivMaxBatch(next);
                renderTab();
            });
        });
    }

    private void showOemGuideSheet() {
        StarSheet.show(this, "国产手机安装与兼容指南", "Android 9+ 原生签名 · 零多余敏感权限", true, b -> {
            b.option("华为 HarmonyOS（纯净模式）", "如遇纯净模式拦截，请在系统设置中关闭「增强防护/纯净模式」或点击「了解风险并继续安装」", false, () -> {});
            b.option("小米 HyperOS / MIUI", "安装页选择「继续安装」并验证锁屏密码即可，星落已使用正式 RSA-3072 v1+v2+v3 签名", false, () -> {});
            b.option("OPPO ColorOS / vivo OriginOS", "首次侧载 GitHub APK 请允许来源应用安装外部应用，如遇安全扫描提示请选「单次授权安装」", false, () -> {});
            b.option("Android 9 (API 28) 兼容说明", "仅 Android 9 会申请写入存储权限用于保存至公共相册；Android 10–16 全程零存储权限", false, () -> {});
        });
    }

    private void showCleanupSheet() {
        StarSheet.show(this, "整理收藏袋记录", "仅清理列表记录，已保存到相册的视频会安全保留", false, b -> {
            b.option("仅清空失败 / 已取消记录", "保留所有已成功下载的记录", false, () -> {
                int n = store.clearFailed();
                renderTab();
                toast("已清理 " + n + " 条失败/取消记录");
            });
            b.option("清空全部已结束记录", "进行中的任务会继续保留", false, () -> {
                store.clearFinished();
                renderTab();
                toast("收藏袋记录已整理好");
            });
        });
    }

    private void showMascotProfileSheet() {
        Preferences p = new Preferences(this);
        ActivityStats.Summary s = new ActivityStats(this).summarize();
        int aff = p.mascotAffection();
        String rank = aff >= 20 ? "星落灵魂契约者 ✦" : aff >= 8 ? "小星最信赖的收藏家" : aff >= 3 ? "心意相通的小伙伴" : "初遇星落的旅人";
        StarSheet.show(this, "✦ 小星守护档案", rank, true, b -> {
            b.option("小星好感度：" + aff, "长按 Hero 区小星摸摸头可提升好感", false, () -> {});
            b.option("累计守护瞬间：" + s.totalDownloads + " 次", "活跃画像：" + s.personaTag + " · " + s.peakHourLabel, false, () -> {});
            b.option("连续收藏记录：" + s.currentStreakDays + " 天", "历史最佳连续：" + s.bestStreakDays + " 天", false, () -> {});
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 910 || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            String label = DocumentsContract.getTreeDocumentId(uri);
            if (label.contains(":")) label = label.substring(label.indexOf(':') + 1);
            new Preferences(this).folder(uri, label.isEmpty() ? "已选择文件夹" : label);
            renderTab();
            toast("自选文件夹已保存");
        } catch (Exception e) {
            toast("没有获得文件夹写入权限，请重新选择");
        }
    }

    private void preference(String title, String desc, String symbol, Runnable action) {
        LinearLayout row = row(this);
        pad(row, 14, 15, 11, 15);
        row.setBackground(StarTheme.ripple(border(SURFACE, LINE, 19, this), this));
        Ui.Icon icon = new Ui.Icon(this, symbol, PURPLE);
        icon.setBackground(rounded(LILAC_SOFT, 12, this));
        pad(icon, 7, 7, 7, 7);
        row.addView(icon, new LinearLayout.LayoutParams(dp(this, 36), dp(this, 36)));
        LinearLayout words = column(this);
        pad(words, 12, 0, 0, 0);
        words.addView(text(this, title, 13, INK, true));
        gap(words, 4);
        words.addView(text(this, desc, 10, MUTED, false));
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(new Ui.Icon(this, "chevron", 0xFFB3A9C2), new LinearLayout.LayoutParams(dp(this, 16), dp(this, 16)));
        StarMotion.attachSpringPress(row, action);
        body.addView(row);
        gap(body, 9);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
