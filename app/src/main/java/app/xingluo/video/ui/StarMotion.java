package app.xingluo.video.ui;

import android.animation.*;
import android.content.Context;
import android.provider.Settings;
import android.view.*;
import android.view.animation.*;

/** Motion choreography helpers that honor system animator duration scale and provide tactile spring feedback. */
public final class StarMotion {
    private StarMotion() {}

    public static float scale(Context c) {
        try {
            return Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (Throwable ignored) {
            return 1f;
        }
    }

    public static boolean enabled(Context c) {
        return scale(c) > 0.01f;
    }

    public static void enter(View v) {
        if (!enabled(v.getContext())) {
            v.setAlpha(1f);
            v.setTranslationY(0f);
            return;
        }
        v.setAlpha(0f);
        v.setTranslationY(StarTheme.dp(v.getContext(), 10));
        v.animate().alpha(1f).translationY(0f)
                .setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
    }

    /** 35ms staggered cascade entrance for cards inside a LinearLayout container. */
    public static void cascadeEnter(ViewGroup group, int startIndex) {
        if (!enabled(group.getContext())) return;
        int delay = 0;
        for (int i = startIndex; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getHeight() < 4 && child instanceof View == true && child.getClass() == View.class) continue;
            child.setAlpha(0f);
            child.setTranslationY(StarTheme.dp(group.getContext(), 12));
            child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(Math.min(delay, 280))
                    .setDuration(240)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .start();
            delay += 35;
        }
    }

    /** Tactile spring-press feedback (0.97f squish on press, overshoot bounce on release) + haptic tap. */
    public static void attachSpringPress(View v, Runnable action) {
        v.setClickable(true);
        v.setFocusable(true);
        v.setOnTouchListener((view, event) -> {
            if (!enabled(view.getContext())) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN ->
                        view.animate().scaleX(0.965f).scaleY(0.965f).setDuration(90).setInterpolator(new DecelerateInterpolator()).start();
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(new OvershootInterpolator(1.6f)).start();
            }
            return false;
        });
        v.setOnClickListener(x -> {
            x.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (action != null) action.run();
        });
    }
}
