package app.xingluo.video.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import app.xingluo.video.Preferences;
import app.xingluo.video.R;
import java.util.*;

/**
 * Living interactive XiaoXing mascot companion:
 * - Natural randomized blinking (`mascot_blink`) & speaking (`mascot_open`)
 * - Gaze & leaning reaction toward user's link input ("让她看")
 * - Edge-peeking mode that ducks on scroll & pops back up
 * - Tap (bouncy quote), Long-press ("摸摸头" + hearts), Triple-tap (secret collector card)
 */
public final class XiaoXingCompanionView extends FrameLayout {
    public enum Mode { HERO, PEEK }
    public enum EventType { IDLE, CLIPBOARD_FOUND, DOWNLOADING_FAST, SUCCESS, ERROR }

    public interface Listener {
        void onSpeech(String message, boolean highlight);
        void onTripleTapEasterEgg();
        void onErrorTap();
    }

    private final ImageView avatar;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rng = new Random();
    private final Mode mode;
    private Listener listener;
    private EventType currentEvent = EventType.IDLE;
    private boolean peekingDucked = false;
    private int tapCount = 0;
    private long lastTapMs = 0L;
    private boolean expressionLocked = false;
    private android.animation.ObjectAnimator bobAnim;
    private final Runnable popBackRunnable;

    private final Runnable blinkLoop = new Runnable() {
        @Override public void run() {
            if (!isAttachedToWindow()) return;
            if (!expressionLocked && currentEvent != EventType.DOWNLOADING_FAST) {
                avatar.setImageResource(R.drawable.mascot_blink);
                handler.postDelayed(() -> {
                    if (isAttachedToWindow() && !expressionLocked) {
                        avatar.setImageResource(currentEvent == EventType.SUCCESS ? R.drawable.mascot_open : R.drawable.mascot_neutral);
                    }
                }, 150);
            }
            handler.postDelayed(this, 3600 + rng.nextInt(2600));
        }
    };

    public XiaoXingCompanionView(Context context, Mode mode) {
        super(context);
        this.mode = mode;
        setClipChildren(false);
        setClipToPadding(false);
        avatar = new ImageView(context);
        popBackRunnable = () -> {
            if (peekingDucked && isAttachedToWindow()) {
                peekingDucked = false;
                avatar.animate().translationY(0).scaleY(1f).setInterpolator(new OvershootInterpolator(1.5f)).setDuration(220).start();
            }
        };
        avatar.setImageResource(R.drawable.mascot_neutral);
        avatar.setScaleType(ImageView.ScaleType.FIT_CENTER);
        avatar.setContentDescription("小星正在陪你收藏喜欢的内容");
        addView(avatar, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        avatar.setOnClickListener(v -> handleTap());
        avatar.setOnLongClickListener(v -> {
            handleHeadPat();
            return true;
        });
    }

    public void setListener(Listener l) { this.listener = l; }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(blinkLoop, 1800);
        if (StarMotion.enabled(getContext())) {
            float amp = StarTheme.dp(getContext(), mode == Mode.HERO ? 3.5f : 2.0f);
            bobAnim = android.animation.ObjectAnimator.ofFloat(this, "translationY", -amp, amp);
            bobAnim.setDuration(1900);
            bobAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            bobAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            bobAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            bobAnim.start();
        }
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        if (bobAnim != null) {
            bobAnim.cancel();
            bobAnim = null;
        }
        super.onDetachedFromWindow();
    }

    /** Makes XiaoXing lean and look curiously toward the link input box with contextual hints. */
    public void lookAtInput(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) {
            avatar.animate().rotation(0f).translationX(0f).scaleX(1f).scaleY(1f).setDuration(220).start();
            return;
        }
        avatar.animate().rotation(-5.5f).translationX(-StarTheme.dp(getContext(), 6)).setDuration(200).start();
        flashOpen(500);
        if (listener == null) return;
        String low = s.toLowerCase(Locale.ROOT);
        if (low.contains("pixiv.net")) {
            listener.onSpeech("咦，是 Pixiv！要帮画师作品建 Tag 文件夹吗？", true);
        } else if (low.contains("bilibili.com") || low.contains("b23.tv") || low.matches("(?i).*\\bbv[1-9a-z]{10}.*")) {
            listener.onSpeech("让我看看 B站链接～ 准备好高清合并啦！", true);
        } else if (low.contains("douyin.com") || low.contains("xiaohongshu") || low.contains("xhslink") || low.contains("kuaishou")) {
            listener.onSpeech("短视频/图文已锁定，马上无水印接住！", true);
        } else if (low.contains("quark.cn") || low.contains("pan.baidu.com") || low.contains("lanzou")) {
            listener.onSpeech("检测到网盘分享，多线程加速引擎待命中！", true);
        } else if (low.startsWith("magnet:") || low.contains(".torrent")) {
            listener.onSpeech("磁力种子来啦！准备解析文件列表～", true);
        } else if (low.contains("live.")) {
            listener.onSpeech("是直播间呢，随时可以开始录制并保存！", true);
        } else {
            listener.onSpeech("小星正在盯着这段链接看… 点下面就能开始！", false);
        }
    }

    public void reactToEvent(EventType event, String customMessage) {
        this.currentEvent = event;
        switch (event) {
            case CLIPBOARD_FOUND -> {
                flashOpen(900);
                avatar.animate().translationY(-StarTheme.dp(getContext(), 6)).rotation(-4f).setDuration(180)
                        .withEndAction(() -> avatar.animate().translationY(0).rotation(0).setDuration(220).start()).start();
                if (listener != null && customMessage != null) listener.onSpeech(customMessage, true);
            }
            case DOWNLOADING_FAST -> {
                avatar.setImageResource(R.drawable.mascot_open);
                avatar.animate().rotation(2f).scaleX(1.03f).scaleY(1.03f).setDuration(220).start();
            }
            case SUCCESS -> {
                flashOpen(1200);
                avatar.animate().translationY(-StarTheme.dp(getContext(), 10)).scaleX(1.06f).scaleY(1.06f).setDuration(180)
                        .withEndAction(() -> avatar.animate().translationY(0).scaleX(1f).scaleY(1f)
                                .setInterpolator(new OvershootInterpolator(1.8f)).setDuration(300).start()).start();
                if (listener != null) listener.onSpeech(customMessage != null ? customMessage : "接住啦！已经放进收藏袋 ✦", true);
            }
            case ERROR -> {
                avatar.setImageResource(R.drawable.mascot_neutral);
                avatar.animate().rotation(7f).setDuration(200).start();
                if (listener != null) listener.onSpeech("咦？网路好像打了个盹，点这里让小星诊断一下？", true);
            }
            case IDLE -> {
                avatar.setImageResource(R.drawable.mascot_neutral);
                avatar.animate().rotation(0f).scaleX(1f).scaleY(1f).setDuration(200).start();
            }
        }
    }

    /** Edge-peeking response to vertical scroll in Collection Bag or Drawer. */
    public void onScrollDelta(int dy) {
        if (mode != Mode.PEEK || !StarMotion.enabled(getContext())) return;
        if (dy > 8 && !peekingDucked) {
            peekingDucked = true;
            avatar.animate().translationY(StarTheme.dp(getContext(), 16)).scaleY(0.92f).setDuration(150).start();
        } else if (dy < -6 && peekingDucked) {
            peekingDucked = false;
            avatar.animate().translationY(0).scaleY(1f).setInterpolator(new OvershootInterpolator(1.4f)).setDuration(200).start();
        }
        handler.removeCallbacks(popBackRunnable);
        handler.postDelayed(popBackRunnable, 500);
    }

    private void flashOpen(long millis) {
        expressionLocked = true;
        avatar.setImageResource(R.drawable.mascot_open);
        handler.postDelayed(() -> {
            expressionLocked = false;
            if (isAttachedToWindow()) avatar.setImageResource(R.drawable.mascot_neutral);
        }, millis);
    }

    private void handleTap() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        long now = System.currentTimeMillis();
        if (now - lastTapMs < 480) tapCount++; else tapCount = 1;
        lastTapMs = now;
        if (tapCount >= 3) {
            tapCount = 0;
            avatar.animate().rotationBy(360f).setDuration(460).setInterpolator(new OvershootInterpolator()).start();
            if (listener != null) listener.onTripleTapEasterEgg();
            return;
        }
        if (currentEvent == EventType.ERROR && listener != null) {
            listener.onErrorTap();
            return;
        }
        flashOpen(550);
        avatar.animate().rotation(-6f).translationY(-StarTheme.dp(getContext(), 7)).setDuration(140)
                .withEndAction(() -> avatar.animate().rotation(0f).translationY(0)
                        .setInterpolator(new OvershootInterpolator(1.6f)).setDuration(240).start()).start();
        if (listener != null) {
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            String[] pool = {
                    hour >= 23 || hour < 5 ? "夜深啦，小星陪你把最后几个视频收好 ✦" : "今天也要把喜欢的瞬间收藏好呀 ✦",
                    "长按我可以摸摸头哦～",
                    "试试点左上角菜单，能看网络诊断和 16 周热力图！",
                    "遇到不能下载的网页，可以在内置浏览器登录再试试！"
            };
            listener.onSpeech(pool[rng.nextInt(pool.length)], false);
        }
    }

    private void handleHeadPat() {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        expressionLocked = true;
        avatar.setImageResource(R.drawable.mascot_blink);
        avatar.animate().scaleX(1.10f).scaleY(0.86f).setDuration(140)
                .withEndAction(() -> avatar.animate().scaleX(1f).scaleY(1f)
                        .setInterpolator(new OvershootInterpolator(2.0f)).setDuration(300).start()).start();
        int aff = new Preferences(getContext()).bumpMascotAffection();
        if (listener != null) {
            listener.onSpeech("呜哇～被摸头了好开心 ♥（小星好感度 " + aff + "）", true);
        }
        handler.postDelayed(() -> {
            expressionLocked = false;
            if (isAttachedToWindow()) avatar.setImageResource(R.drawable.mascot_neutral);
        }, 1200);
    }
}
