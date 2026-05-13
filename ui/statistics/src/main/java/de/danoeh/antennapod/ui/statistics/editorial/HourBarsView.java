package de.danoeh.antennapod.ui.statistics.editorial;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** 24 vertical bars, one per hour-of-day. Peak bar is painted vermilion. */
public class HourBarsView extends View {
    private long[] byHour; // length 24, minutes per hour
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();

    public HourBarsView(Context context) { super(context); init(); }
    public HourBarsView(Context context, AttributeSet a) { super(context, a); init(); }
    public HourBarsView(Context context, AttributeSet a, int d) { super(context, a, d); init(); }

    private void init() {
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(EditorialTheme.INK_MUTE);
        tickPaint.setColor(EditorialTheme.ACCENT);
        tickPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setData(long[] byHour) {
        this.byHour = byHour;
        invalidate();
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = resolveSize(getResources().getDisplayMetrics().widthPixels, ws);
        int h = (int) (120 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (byHour == null || byHour.length < 24) return;
        float d = getResources().getDisplayMetrics().density;
        float labelH = 14 * d;
        float tickH = 16 * d;
        float chartH = getHeight() - labelH - tickH;

        long max = 1;
        int peakHour = 0;
        for (int i = 0; i < 24; i++) {
            if (byHour[i] > max) { max = byHour[i]; peakHour = i; }
        }

        float totalW = getWidth();
        float barW = (totalW - 48 * d) / 24f;
        float gap = Math.max(1 * d, barW * 0.15f);
        float bw = barW - gap;
        float left = 24 * d;

        labelPaint.setTextSize(8 * d);
        tickPaint.setTextSize(7 * d);
        tickPaint.setTypeface(EditorialTheme.getMono(getContext()));
        labelPaint.setTypeface(EditorialTheme.getMono(getContext()));

        for (int h = 0; h < 24; h++) {
            float barH = byHour[h] > 0 ? Math.max(2 * d, (byHour[h] * chartH) / max) : 2 * d;
            float x = left + h * barW;
            float top = tickH + chartH - barH;

            barPaint.setColor(h == peakHour ? EditorialTheme.ACCENT : EditorialTheme.INK_SOFT);
            barPaint.setAlpha(h == peakHour ? 255 : 180);
            barRect.set(x, top, x + bw, tickH + chartH);
            canvas.drawRect(barRect, barPaint);

            // Axis labels at 00 06 12 18 23
            if (h == 0 || h == 6 || h == 12 || h == 18 || h == 23) {
                String lbl = h == 0 ? "00" : h == 23 ? "23" : String.valueOf(h);
                canvas.drawText(lbl, x + bw / 2, getHeight(), labelPaint);
            }
        }

        // PEAK tick above peak bar
        float peakX = left + peakHour * barW + bw / 2;
        float peakBarH = (byHour[peakHour] * chartH) / max;
        float peakTop = tickH + chartH - peakBarH;
        canvas.drawText("PEAK · " + byHour[peakHour] + "m", peakX, peakTop - 4 * d, tickPaint);
    }
}
