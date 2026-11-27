package app.xingluo.video.ui;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** 16-week × 7-day GitHub / Anki style contribution heatmap rendered on Canvas with tap-to-inspect. */
public final class HeatmapView extends View {
    public interface OnCellTapListener { void onCellTapped(String dateLabel, int count); }

    private int[] counts = new int[112];
    private int selectedIndex = -1;
    private OnCellTapListener listener;
    private final Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int[] colors = {0xFFF0ECF7, 0xFFD9CDF2, 0xFFB8A2E4, 0xFF9378CE, 0xFF6B53AE};

    public HeatmapView(Context c) {
        super(c);
        selPaint.setStyle(Paint.Style.STROKE);
        selPaint.setStrokeWidth(StarTheme.dp(c, 1.5f));
        selPaint.setColor(StarTheme.INK);
    }

    public void setCounts(int[] days112) {
        if (days112 != null && days112.length == 112) {
            this.counts = days112.clone();
            invalidate();
        }
    }

    public void setOnCellTapListener(OnCellTapListener l) { this.listener = l; }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        float gap = StarTheme.dp(getContext(), 3.5f);
        float cell = Math.max(StarTheme.dp(getContext(), 8), (w - gap * 15f) / 16f);
        int h = (int) Math.ceil(cell * 7f + gap * 6f + StarTheme.dp(getContext(), 4));
        setMeasuredDimension(w, h);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        if (w <= 0) return;
        float gap = StarTheme.dp(getContext(), 3.5f);
        float cell = (w - gap * 15f) / 16f;
        float r = Math.min(StarTheme.dp(getContext(), 4), cell * 0.28f);

        for (int idx = 0; idx < 112; idx++) {
            int col = idx / 7;
            int row = idx % 7;
            float left = col * (cell + gap);
            float top = row * (cell + gap);
            int c = counts[idx];
            int level = c <= 0 ? 0 : c == 1 ? 1 : c <= 3 ? 2 : c <= 6 ? 3 : 4;
            cellPaint.setColor(colors[level]);
            rect.set(left, top, left + cell, top + cell);
            canvas.drawRoundRect(rect, r, r, cellPaint);
            if (idx == selectedIndex) canvas.drawRoundRect(rect, r, r, selPaint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float w = getWidth();
            float gap = StarTheme.dp(getContext(), 3.5f);
            float cell = (w - gap * 15f) / 16f;
            int col = Math.max(0, Math.min(15, (int) (event.getX() / (cell + gap))));
            int row = Math.max(0, Math.min(6, (int) (event.getY() / (cell + gap))));
            int idx = col * 7 + row;
            if (idx >= 0 && idx < 112) {
                selectedIndex = idx;
                invalidate();
                if (listener != null) {
                    Calendar cal = Calendar.getInstance();
                    cal.add(Calendar.DAY_OF_YEAR, -(111 - idx));
                    String label = new SimpleDateFormat("MM月dd日", Locale.CHINA).format(cal.getTime());
                    listener.onCellTapped(label, counts[idx]);
                }
            }
            return true;
        }
        return event.getAction() == MotionEvent.ACTION_DOWN || super.onTouchEvent(event);
    }
}
