package de.danoeh.antennapod.ui.statistics.editorial;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** 7 small bar multiples for day-of-week listening (Sun–Sat). Peak day in vermilion.
 *  Tappable: dispatches {@link OnDayClickListener#onDayClick(int)} with day
 *  index (0=Sun … 6=Sat). */
public class DayMultiplesView extends View {
    public interface OnDayClickListener {
        void onDayClick(int day);
    }

    private long[] byDay; // length 7, Sun=0
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private static final String[] LABELS = {"S", "M", "T", "W", "T", "F", "S"};
    private OnDayClickListener dayClickListener;
    private float downX = -1, downY = -1;

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

    public void setOnDayClickListener(OnDayClickListener listener) {
        this.dayClickListener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (dayClickListener == null) return super.onTouchEvent(e);
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (downX >= 0 && Math.hypot(e.getX() - downX, e.getY() - downY) < dp(12)) {
                    int day = hitTestDay(e.getX());
                    if (day >= 0) {
                        dayClickListener.onDayClick(day);
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

    /** Returns day index 0–6 for an x coordinate, or -1 outside the chart. */
    private int hitTestDay(float x) {
        float d = getResources().getDisplayMetrics().density;
        float left = 24 * d;
        float cellW = (getWidth() - 48 * d) / 7f;
        if (x < left) return -1;
        int i = (int) ((x - left) / cellW);
        return (i < 0 || i > 6) ? -1 : i;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
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

            barPaint.setColor(i == peakDay ? EditorialTheme.vermilion(getContext()) : EditorialTheme.inkSoft(getContext()));
            barPaint.setAlpha(i == peakDay ? 255 : 160);
            barRect.set(cx - bw / 2, top, cx + bw / 2, valueH + chartH);
            canvas.drawRect(barRect, barPaint);

            // Serif minute label above bar
            valuePaint.setColor(i == peakDay ? EditorialTheme.vermilion(getContext()) : EditorialTheme.inkMuted(getContext()));
            String minLabel = byDay[i] >= 60 ? Math.round(byDay[i] / 60f) + "h" : byDay[i] + "m";
            canvas.drawText(minLabel, cx, top - 3 * d, valuePaint);

            // Day label below
            labelPaint.setColor(EditorialTheme.inkMuted(getContext()));
            canvas.drawText(LABELS[i], cx, getHeight(), labelPaint);
        }
    }
}
