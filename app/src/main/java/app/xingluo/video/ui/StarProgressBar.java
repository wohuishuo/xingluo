package app.xingluo.video.ui;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Smooth rounded gradient progress bar with subtle animated shimmer and a starlight tip. */
public final class StarProgressBar extends View {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int percent = -1;
    private float phase = 0f;
    private boolean animating = false;

    public StarProgressBar(Context c) {
        super(c);
        trackPaint.setColor(StarTheme.LILAC);
        starPaint.setColor(Color.WHITE);
        shimmerPaint.setColor(0x45FFFFFF);
    }

    public void setProgressValue(int pct) {
        this.percent = pct;
        if (!animating && StarMotion.enabled(getContext())) {
            animating = true;
            postInvalidateOnAnimation();
        } else {
            invalidate();
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (StarMotion.enabled(getContext())) {
            animating = true;
            postInvalidateOnAnimation();
        }
    }

    @Override protected void onDetachedFromWindow() {
        animating = false;
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float r = h / 2f;
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, trackPaint);

        float startX = 0f;
        float endX;
        if (percent < 0) {
            float span = w * 0.36f;
            float t = (float) ((Math.sin(phase * Math.PI * 2) + 1.0) * 0.5);
            startX = t * (w - span);
            endX = startX + span;
        } else {
            float clamped = Math.max(0.04f, Math.min(1f, percent / 100f));
            endX = w * clamped;
        }

        fillPaint.setShader(new LinearGradient(startX, 0, Math.max(startX + 1, endX), 0,
                0xFFA18EE0, StarTheme.PURPLE, Shader.TileMode.CLAMP));
        rect.set(startX, 0, endX, h);
        canvas.drawRoundRect(rect, r, r, fillPaint);

        float shimmerX = startX + ((phase * 1.4f) % 1f) * Math.max(1f, endX - startX);
        canvas.save();
        Path clip = new Path();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.drawCircle(shimmerX, h / 2f, h * 1.6f, shimmerPaint);
        canvas.restore();

        if (endX > h * 1.4f) {
            canvas.drawCircle(endX - r, h / 2f, Math.max(2f, r * 0.48f), starPaint);
        }

        if (animating && isShown()) {
            phase = (phase + 0.018f) % 1f;
            postInvalidateDelayed(32);
        }
    }
}
