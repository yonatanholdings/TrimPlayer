package de.danoeh.antennapod.ui.statistics.editorial;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** 26-week × 7-day calendar heatmap. Intensity 0–4 mapped to a 5-step accent ramp.
 *  Tappable: dispatches {@link OnCellClickListener#onCellClick(int, int)} with
 *  (weekIdx, dayIdx). Cell geometry mirrors {@link #onDraw} exactly. */
public class HeatmapView extends View {
    private static final String[] DAY_LABELS = {"S", "M", "T", "W", "T", "F", "S"};

    public interface OnCellClickListener {
        void onCellClick(int weekIdx, int dayIdx);
    }

    private int[][] data; // [26 weeks][7 days]
    private final Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cellRect = new RectF();
    /** 5-step accent ramp resolved at construction so it follows the system theme. */
    private final int[] ramp;
    private OnCellClickListener cellClickListener;
    /** Track down-event coordinates so up-event ignored if the user dragged. */
    private float downX = -1, downY = -1;

    public HeatmapView(Context context) { super(context); ramp = buildRamp(context); initLabel(); }
    public HeatmapView(Context context, AttributeSet a) { super(context, a); ramp = buildRamp(context); initLabel(); }
    public HeatmapView(Context context, AttributeSet a, int d) { super(context, a, d); ramp = buildRamp(context); initLabel(); }

    private static int[] buildRamp(Context c) {
        return new int[] {
                EditorialTheme.ruleVeryFaint(c),
                EditorialTheme.vermilionTint(c),
                EditorialTheme.vermilionSoft(c),
                0xFFCA6B50,
                EditorialTheme.vermilion(c),
        };
    }

    private void initLabel() {
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(EditorialTheme.inkMuted(getContext()));
    }

    public void setData(int[][] heatmap) {
        this.data = heatmap;
        invalidate();
    }

    public void setOnCellClickListener(OnCellClickListener listener) {
        this.cellClickListener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (cellClickListener == null || data == null) return super.onTouchEvent(e);
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (downX >= 0 && Math.hypot(e.getX() - downX, e.getY() - downY) < dp(12)) {
                    int[] cell = hitTest(e.getX(), e.getY());
                    if (cell != null) {
                        cellClickListener.onCellClick(cell[0], cell[1]);
                        performClick();
                    }
                }
                downX = downY = -1;
                return true;
            case MotionEvent.ACTION_CANCEL:
                downX = downY = -1;
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /** Returns [weekIdx, dayIdx] for a touch coordinate, or null if outside grid. */
    private int[] hitTest(float x, float y) {
        if (data == null || data.length == 0) return null;
        float d = getResources().getDisplayMetrics().density;
        float labelColW = 16 * d;
        float gridLeft = 24 * d + labelColW;
        float available = getWidth() - gridLeft - 16 * d;
        int weeks = data.length;
        int days = data[0].length;
        float cellW = available / weeks;
        float gap = 2 * d;
        float cellSize = cellW - gap;

        if (x < gridLeft) return null;
        int w = (int) ((x - gridLeft) / cellW);
        int dw = (int) (y / (cellSize + gap));
        if (w < 0 || w >= weeks || dw < 0 || dw >= days) return null;
        return new int[] {w, dw};
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
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
                cellPaint.setColor(ramp[Math.max(0, Math.min(4, intensity))]);
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
