package de.danoeh.antennapod.ui.statistics.editorial;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** 26-week × 7-day calendar heatmap. Intensity 0–4 mapped to a 5-step accent ramp. */
public class HeatmapView extends View {
    private static final String[] DAY_LABELS = {"S", "M", "T", "W", "T", "F", "S"};

    private int[][] data; // [26 weeks][7 days]
    private final Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cellRect = new RectF();

    // 5-step accent ramp: 0=no data, 1-4=increasing accent
    private static final int[] RAMP = {
            EditorialTheme.VERY_FAINT,
            EditorialTheme.ACCENT_TINT,
            EditorialTheme.ACCENT_SOFT,
            0xFFCA6B50,
            EditorialTheme.ACCENT,
    };

    public HeatmapView(Context context) { super(context); initLabel(); }
    public HeatmapView(Context context, AttributeSet a) { super(context, a); initLabel(); }
    public HeatmapView(Context context, AttributeSet a, int d) { super(context, a, d); initLabel(); }

    private void initLabel() {
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(EditorialTheme.INK_MUTE);
    }

    public void setData(int[][] heatmap) {
        this.data = heatmap;
        invalidate();
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int width = resolveSize(getResources().getDisplayMetrics().widthPixels, ws);
        float d = getResources().getDisplayMetrics().density;
        float labelColW = 16 * d;
        float cellSize = (width - 24 * d - labelColW - 16 * d) / 26f;
        int height = (int) (cellSize * 7 + 6 * 2 * d);
        setMeasuredDimension(width, Math.max(height, (int) (56 * d)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (data == null) return;
        float d = getResources().getDisplayMetrics().density;
        float labelColW = 16 * d;
        float gridLeft = 24 * d + labelColW;
        float available = getWidth() - gridLeft - 16 * d;
        int weeks = data.length;
        int days = data[0].length;
        float cellW = available / weeks;
        float gap = 2 * d;
        float cellSize = cellW - gap;
        float radius = 2 * d;

        labelPaint.setTextSize(8 * d);
        labelPaint.setTypeface(EditorialTheme.getMono(getContext()));

        for (int w = 0; w < weeks; w++) {
            for (int dw = 0; dw < days; dw++) {
                float x = gridLeft + w * cellW;
                float y = dw * (cellSize + gap);
                int intensity = data[w][dw];
                cellPaint.setColor(RAMP[Math.max(0, Math.min(4, intensity))]);
                cellRect.set(x, y, x + cellSize, y + cellSize);
                canvas.drawRoundRect(cellRect, radius, radius, cellPaint);
            }
        }

        // Day-of-week labels on the left
        float labelX = 24 * d + labelColW / 2f;
        for (int dw = 0; dw < days; dw++) {
            float cellTop = dw * (cellSize + gap);
            float labelY = cellTop + cellSize / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f;
            canvas.drawText(DAY_LABELS[dw], labelX, labelY, labelPaint);
        }
    }
}
