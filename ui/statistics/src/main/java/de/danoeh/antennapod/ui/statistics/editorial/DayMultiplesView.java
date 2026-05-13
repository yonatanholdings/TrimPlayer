package de.danoeh.antennapod.ui.statistics.editorial;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** 7 small bar multiples for day-of-week listening (Sun–Sat). Peak day in vermilion. */
public class DayMultiplesView extends View {
    private long[] byDay; // length 7, Sun=0
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private static final String[] LABELS = {"S", "M", "T", "W", "T", "F", "S"};

    public DayMultiplesView(Context context) { super(context); init(); }
    public DayMultiplesView(Context context, AttributeSet a) { super(context, a); init(); }
    public DayMultiplesView(Context context, AttributeSet a, int d) { super(context, a, d); init(); }

    private void init() {
        labelPaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setData(long[] byDay) {
        this.byDay = byDay;
        invalidate();
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = resolveSize(getResources().getDisplayMetrics().widthPixels, ws);
        int h = (int) (96 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (byDay == null || byDay.length < 7) return;
        float d = getResources().getDisplayMetrics().density;
        float textH = 12 * d;
        float valueH = 14 * d;
        float chartH = getHeight() - textH - valueH - 4 * d;

        long max = 1;
        int peakDay = 0;
        for (int i = 0; i < 7; i++) if (byDay[i] > max) { max = byDay[i]; peakDay = i; }

        float cellW = (getWidth() - 48 * d) / 7f;
        float bw = cellW * 0.55f;
        float left = 24 * d;

        labelPaint.setTextSize(9 * d);
        labelPaint.setTypeface(EditorialTheme.getMono(getContext()));
        valuePaint.setTextSize(10 * d);
        valuePaint.setTypeface(EditorialTheme.getSerif(getContext()));

        for (int i = 0; i < 7; i++) {
            float cx = left + i * cellW + cellW / 2;
            float barH = byDay[i] > 0 ? Math.max(2 * d, (byDay[i] * chartH) / max) : 2 * d;
            float top = valueH + chartH - barH;

            barPaint.setColor(i == peakDay ? EditorialTheme.ACCENT : EditorialTheme.INK_SOFT);
            barPaint.setAlpha(i == peakDay ? 255 : 160);
            barRect.set(cx - bw / 2, top, cx + bw / 2, valueH + chartH);
            canvas.drawRect(barRect, barPaint);

            // Serif minute label above bar
            valuePaint.setColor(i == peakDay ? EditorialTheme.ACCENT : EditorialTheme.INK_MUTE);
            String minLabel = byDay[i] >= 60 ? Math.round(byDay[i] / 60f) + "h" : byDay[i] + "m";
            canvas.drawText(minLabel, cx, top - 3 * d, valuePaint);

            // Day label below
            labelPaint.setColor(EditorialTheme.INK_MUTE);
            canvas.drawText(LABELS[i], cx, getHeight(), labelPaint);
        }
    }
}
