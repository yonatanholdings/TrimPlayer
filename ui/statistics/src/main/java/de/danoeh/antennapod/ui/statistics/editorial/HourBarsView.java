package de.danoeh.antennapod.ui.statistics.editorial;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** 24 vertical bars, one per hour-of-day. Peak bar is painted vermilion.
 *  Tappable: dispatches {@link OnHourClickListener#onHourClick(int)} with the
 *  hour index (0–23) the user tapped. */
public class HourBarsView extends View {
    public interface OnHourClickListener {
        void onHourClick(int hour);
    }

    private long[] byHour; // length 24, minutes per hour
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private OnHourClickListener hourClickListener;
    private float downX = -1, downY = -1;

    public HourBarsView(Context context) { super(context); init(); }
    public HourBarsView(Context context, AttributeSet a) { super(context, a); init(); }
    public HourBarsView(Context context, AttributeSet a, int d) { super(context, a, d); init(); }

    private void init() {
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(EditorialTheme.inkMuted(getContext()));
        tickPaint.setColor(EditorialTheme.vermilion(getContext()));
        tickPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setData(long[] byHour) {
        this.byHour = byHour;
        invalidate();
    }

    public void setOnHourClickListener(OnHourClickListener listener) {
        this.hourClickListener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (hourClickListener == null) return super.onTouchEvent(e);
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (downX >= 0 && Math.hypot(e.getX() - downX, e.getY() - downY) < dp(12)) {
                    int hour = hitTestHour(e.getX());
                    if (hour >= 0) {
                        hourClickListener.onHourClick(hour);
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

    /** Returns hour index 0–23 for an x coordinate, or -1 outside the chart. */
    private int hitTestHour(float x) {
        float d = getResources().getDisplayMetrics().density;
        float left = 24 * d;
        float barW = (getWidth() - 48 * d) / 24f;
        if (x < left) return -1;
        int h = (int) ((x - left) / barW);
        return (h < 0 || h > 23) ? -1 : h;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
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

            barPaint.setColor(h == peakHour ? EditorialTheme.vermilion(getContext()) : EditorialTheme.inkSoft(getContext()));
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
