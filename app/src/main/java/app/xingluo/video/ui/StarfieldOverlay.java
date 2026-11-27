package app.xingluo.video.ui;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.*;

/** Lightweight non-blocking overlay for pastel starlight confetti bursts & mascot affection hearts. */
public final class StarfieldOverlay extends View {
    private static final class Particle {
        float x, y, vx, vy, size, alpha, rot, vrot;
        int color;
        boolean heart;
    }

    private final List<Particle> particles = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rng = new Random();
    private final int[] palette = {StarTheme.PURPLE, StarTheme.GOLD, 0xFFB799D4, 0xFFF29BB2, 0xFF7BB897};

    public StarfieldOverlay(Context c) {
        super(c);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void burstCenter() {
        if (!StarMotion.enabled(getContext())) return;
        float cx = getWidth() > 0 ? getWidth() * 0.5f : StarTheme.dp(getContext(), 180);
        float cy = getHeight() > 0 ? getHeight() * 0.38f : StarTheme.dp(getContext(), 280);
        spawn(cx, cy, 24, false);
    }

    public void heartsAt(float x, float y) {
        if (!StarMotion.enabled(getContext())) return;
        spawn(x, y, 10, true);
    }

    private void spawn(float cx, float cy, int count, boolean heart) {
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < count; i++) {
            Particle p = new Particle();
            double angle = heart ? (-Math.PI / 2 + (rng.nextDouble() - 0.5) * 1.3) : (rng.nextDouble() * Math.PI * 2);
            float speed = (heart ? 2.5f : 4f) + rng.nextFloat() * 7f;
            p.x = cx + (rng.nextFloat() - 0.5f) * 32 * density;
            p.y = cy + (rng.nextFloat() - 0.5f) * 18 * density;
            p.vx = (float) Math.cos(angle) * speed * density;
            p.vy = (float) Math.sin(angle) * speed * density - (heart ? 3 * density : 2 * density);
            p.size = (heart ? 8f : 6f) + rng.nextFloat() * 5f;
            p.size *= density;
            p.alpha = 1f;
            p.rot = rng.nextFloat() * 360f;
            p.vrot = (rng.nextFloat() - 0.5f) * 10f;
            p.color = heart ? (i % 2 == 0 ? 0xFFE886A4 : StarTheme.GOLD) : palette[rng.nextInt(palette.length)];
            p.heart = heart;
            particles.add(p);
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (particles.isEmpty()) return;
        float density = getResources().getDisplayMetrics().density;
        Iterator<Particle> it = particles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            p.x += p.vx;
            p.y += p.vy;
            p.vy += (p.heart ? 0.08f : 0.28f) * density;
            p.vx *= 0.97f;
            p.rot += p.vrot;
            p.alpha -= p.heart ? 0.022f : 0.026f;
            if (p.alpha <= 0.02f) {
                it.remove();
                continue;
            }
            paint.setColor(p.color);
            paint.setAlpha((int) (Math.max(0f, Math.min(1f, p.alpha)) * 255));
            canvas.save();
            canvas.translate(p.x, p.y);
            canvas.rotate(p.rot);
            if (p.heart) {
                drawStarOrHeart(canvas, p.size, true);
            } else {
                drawStarOrHeart(canvas, p.size, false);
            }
            canvas.restore();
        }
        if (!particles.isEmpty()) postInvalidateDelayed(16);
    }

    private void drawStarOrHeart(Canvas canvas, float r, boolean heart) {
        Path path = new Path();
        if (heart) {
            path.moveTo(0, r * 0.35f);
            path.cubicTo(-r, -r * 0.35f, -r * 0.45f, -r, 0, -r * 0.35f);
            path.cubicTo(r * 0.45f, -r, r, -r * 0.35f, 0, r * 0.35f);
        } else {
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4.0;
                float rad = (i % 2 == 0) ? r : r * 0.38f;
                float px = (float) Math.cos(a) * rad;
                float py = (float) Math.sin(a) * rad;
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            path.close();
        }
        canvas.drawPath(path, paint);
    }
}
