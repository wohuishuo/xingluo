package app.xingluo.video.ui;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import app.xingluo.video.DownloadService;
import app.xingluo.video.stats.ActivityStats;
import java.util.List;
import static app.xingluo.video.ui.StarTheme.*;

/**
 * Expandable "星落足迹 & 画像" card combining the 16-week Anki/GitHub contribution heatmap,
 * 24-hour active rhythm histogram, platform distribution, favorite creators, and keyword tags.
 */
public final class TasteCardView extends LinearLayout {
    private boolean expanded = false;

    public TasteCardView(Context context, ActivityStats.Summary summary, Runnable onFilterByKeyword) {
        super(context);
        setOrientation(VERTICAL);
        setBackground(border(SURFACE, LINE, R_CARD, context));
        pad(this, 16, 15, 16, 15);

        LinearLayout header = row(context);
        LinearLayout left = column(context);
        LinearLayout titleRow = row(context);
        titleRow.addView(text(context, "星落足迹 · 收藏热力图", 14, INK, true));
        hGap(titleRow, 8);
        titleRow.addView(pillBadge(context, summary.personaTag, LILAC, PURPLE_DARK));
        left.addView(titleRow);
        gap(left, 4);
        String meta = "近16周共收藏 " + summary.totalDownloads + " 次 · "
                + DownloadService.bytes(summary.totalBytes)
                + " · 连续 " + summary.currentStreakDays + " 天（最高 " + summary.bestStreakDays + " 天）";
        left.addView(text(context, meta, 10, MUTED, false));
        header.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

        TextView toggle = pillBadge(context, "展开画像 ▾", LILAC_SOFT, PURPLE);
        header.addView(toggle);
        addView(header);
        gap(this, 12);

        HeatmapView heatmap = new HeatmapView(context);
        heatmap.setCounts(summary.heatmap112Days);
        addView(heatmap, new LayoutParams(-1, -2));

        gap(this, 8);
        LinearLayout legendRow = row(context);
        TextView tipText = text(context, "点击小方块查看当日记录 · " + summary.peakHourLabel, 10, MUTED, false);
        legendRow.addView(tipText, new LinearLayout.LayoutParams(0, -2, 1));
        legendRow.addView(text(context, "少 ", 9, MUTED, false));
        for (int c : new int[]{0xFFF0ECF7, 0xFFD9CDF2, 0xFFB8A2E4, 0xFF9378CE, 0xFF6B53AE}) {
            View dot = new View(context);
            dot.setBackground(rounded(c, 3, context));
            LinearLayout.LayoutParams dpLp = new LinearLayout.LayoutParams(dp(context, 10), dp(context, 10));
            dpLp.leftMargin = dp(context, 2);
            legendRow.addView(dot, dpLp);
        }
        legendRow.addView(text(context, " 多", 9, MUTED, false));
        addView(legendRow);

        heatmap.setOnCellTapListener((dateLabel, count) ->
                tipText.setText(count > 0 ? dateLabel + " 收藏了 " + count + " 个心动瞬间 ✦" : dateLabel + " 暂未下载"));

        LinearLayout detailPanel = column(context);
        detailPanel.setVisibility(GONE);
        gap(detailPanel, 14);
        View sep = new View(context);
        sep.setBackgroundColor(LINE);
        detailPanel.addView(sep, new LayoutParams(-1, dp(context, 1)));
        gap(detailPanel, 12);

        // 24h Histogram
        detailPanel.addView(text(context, "24 小时活跃时段", 12, INK, true));
        gap(detailPanel, 8);
        detailPanel.addView(new HourlyChartView(context, summary.hourlyBuckets), new LayoutParams(-1, dp(context, 56)));
        gap(detailPanel, 12);

        // Platforms
        if (!summary.topPlatforms.isEmpty()) {
            detailPanel.addView(text(context, "常逛平台分布", 12, INK, true));
            gap(detailPanel, 6);
            for (ActivityStats.PlatformStat ps : summary.topPlatforms) {
                LinearLayout pr = row(context);
                pr.addView(text(context, ps.name, 11, INK, true), new LinearLayout.LayoutParams(dp(context, 78), -2));
                LinearLayout track = row(context);
                track.setBackground(rounded(LILAC_SOFT, R_PILL, context));
                View fill = new View(context);
                fill.setBackground(rounded(PURPLE, R_PILL, context));
                int wPct = Math.max(8, (int) Math.round(ps.ratio * 100));
                track.addView(fill, new LayoutParams(0, dp(context, 8), wPct));
                if (100 - wPct > 0) track.addView(new View(context), new LayoutParams(0, dp(context, 8), 100 - wPct));
                pr.addView(track, new LayoutParams(0, dp(context, 8), 1));
                hGap(pr, 8);
                pr.addView(text(context, ps.count + "次", 10, MUTED, false));
                detailPanel.addView(pr);
                gap(detailPanel, 5);
            }
            gap(detailPanel, 6);
        }

        // Top Creators
        if (!summary.topAuthors.isEmpty()) {
            detailPanel.addView(text(context, "最常收藏的 UP 主 / 画师", 12, INK, true));
            gap(detailPanel, 6);
            FlowWrapView authorsWrap = new FlowWrapView(context);
            for (ActivityStats.NamedCount nc : summary.topAuthors) {
                authorsWrap.addChip(pillBadge(context, "✦ " + nc.name + " ×" + nc.count, LILAC_SOFT, PURPLE_DARK));
            }
            detailPanel.addView(authorsWrap);
            gap(detailPanel, 10);
        }

        // Top Tags
        if (!summary.topKeywords.isEmpty()) {
            detailPanel.addView(text(context, "常见灵感标签", 12, INK, true));
            gap(detailPanel, 6);
            FlowWrapView tagsWrap = new FlowWrapView(context);
            for (ActivityStats.NamedCount nc : summary.topKeywords) {
                tagsWrap.addChip(pillBadge(context, "#" + nc.name + " (" + nc.count + ")", SKY_BG, SKY_INK));
            }
            detailPanel.addView(tagsWrap);
        }

        if (summary.totalDownloads == 0) {
            detailPanel.addView(text(context, "再收藏几个视频或画师作品，小星就会画出你的专属画像啦 ✦", 11, MUTED, false));
        }

        addView(detailPanel);

        StarMotion.attachSpringPress(toggle, () -> {
            expanded = !expanded;
            detailPanel.setVisibility(expanded ? VISIBLE : GONE);
            toggle.setText(expanded ? "收起画像 ▴" : "展开画像 ▾");
            if (expanded && StarMotion.enabled(context)) {
                StarMotion.enter(detailPanel);
            }
        });
    }

    private static final class HourlyChartView extends View {
        private final int[] buckets;
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        HourlyChartView(Context c, int[] buckets) {
            super(c);
            this.buckets = buckets != null && buckets.length == 24 ? buckets : new int[24];
            labelPaint.setColor(MUTED);
            labelPaint.setTextSize(dp(c, 9));
            labelPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float labelH = dp(getContext(), 14);
            float chartH = h - labelH;
            int max = 1;
            for (int v : buckets) if (v > max) max = v;
            float gap = dp(getContext(), 2.5f);
            float bw = (w - gap * 23f) / 24f;
            for (int i = 0; i < 24; i++) {
                float x = i * (bw + gap);
                float ratio = buckets[i] / (float) max;
                float bh = Math.max(dp(getContext(), 4), ratio * (chartH - dp(getContext(), 4)));
                barPaint.setColor(buckets[i] == 0 ? 0xFFEDE8F5 : (buckets[i] == max ? PURPLE : 0xFFB8A2E4));
                rect.set(x, chartH - bh, x + bw, chartH);
                canvas.drawRoundRect(rect, bw / 2f, bw / 2f, barPaint);
                if (i == 0 || i == 6 || i == 12 || i == 18 || i == 23) {
                    canvas.drawText(i + "h", x + bw / 2f, h - dp(getContext(), 2), labelPaint);
                }
            }
        }
    }

    /** Simple wrap container for badges/chips without needing FlexboxLayout library. */
    public static final class FlowWrapView extends ViewGroup {
        private final int spacing;
        public FlowWrapView(Context c) {
            super(c);
            this.spacing = dp(c, 6);
        }
        public void addChip(View v) { addView(v); }
        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int maxW = MeasureSpec.getSize(widthMeasureSpec);
            int x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                measureChild(child, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), heightMeasureSpec);
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();
                if (x + cw > maxW && x > 0) {
                    x = 0;
                    y += lineH + spacing;
                    lineH = 0;
                }
                x += cw + spacing;
                lineH = Math.max(lineH, ch);
            }
            setMeasuredDimension(maxW, y + lineH);
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int maxW = r - l;
            int x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                int cw = child.getMeasuredWidth();
                int ch = child.getMeasuredHeight();
                if (x + cw > maxW && x > 0) {
                    x = 0;
                    y += lineH + spacing;
                    lineH = 0;
                }
                child.layout(x, y, x + cw, y + ch);
                x += cw + spacing;
                lineH = Math.max(lineH, ch);
            }
        }
    }
}
