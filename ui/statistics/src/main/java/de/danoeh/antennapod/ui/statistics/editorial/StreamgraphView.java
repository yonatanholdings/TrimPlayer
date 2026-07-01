package de.danoeh.antennapod.ui.statistics.editorial;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import de.danoeh.antennapod.storage.database.DBReader;
import java.util.List;

/**
 * Multi-year streamgraph: smooth Catmull-Rom area + 1.6dp accent stroke,
 * hollow circles at each year point (peak = larger), serif numerals, PEAK/YTD labels.
 */
public class StreamgraphView extends View {
    private List<DBReader.EditorialStats.YearItem> yearly;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint circleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint yearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curvePath = new Path();
    private final Path fillPath = new Path();

    public StreamgraphView(Context context) { super(context); init(); }

    public StreamgraphView(Context context, AttributeSet a) { super(context, a); init(); }

    public StreamgraphView(Context context, AttributeSet a, int d) { super(context, a, d); init(); }

    private void init() {
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setColor(EditorialTheme.vermilion(getContext()));
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setColor(EditorialTheme.vermilion(getContext()));
        circleFill.setStyle(Paint.Style.FILL);
        circleFill.setColor(EditorialTheme.paper(getContext()));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(EditorialTheme.ink(getContext()));
        yearPaint.setTextAlign(Paint.Align.CENTER);
        yearPaint.setColor(EditorialTheme.inkMuted(getContext()));
        tickPaint.setTextAlign(Paint.Align.CENTER);
        tickPaint.setColor(EditorialTheme.vermilion(getContext()));
        fillPaint.setStyle(Paint.Style.FILL);
    }

    public void setData(List<DBReader.EditorialStats.YearItem> yearly) {
        this.yearly = yearly;
        invalidate();
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = resolveSize(getResources().getDisplayMetrics().widthPixels, ws);
        int h = (int) (160 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float labelH = 24 * getResources().getDisplayMetrics().density;
        fillPaint.setShader(new LinearGradient(0, 0, 0, h - labelH,
                Color.argb(87, Color.red(EditorialTheme.vermilion(getContext())), Color.green(EditorialTheme.vermilion(getContext())), Color.blue(EditorialTheme.vermilion(getContext()))),
                Color.argb(5, Color.red(EditorialTheme.vermilion(getContext())), Color.green(EditorialTheme.vermilion(getContext())), Color.blue(EditorialTheme.vermilion(getContext()))),
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (yearly == null || yearly.size() < 2) {
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        int n = yearly.size();

        float labelH = 18 * d;
        float numH = 20 * d;
        float tickH = 16 * d;
        float padL = 24 * d, padR = 24 * d;
        float chartTop = tickH;
        float chartBottom = getHeight() - labelH - numH;
        float chartH = chartBottom - chartTop;

        float maxHrs = 0.01f;
        int peakIdx = 0;
        for (int i = 0; i < n; i++) {
            if (yearly.get(i).hrs > maxHrs) { maxHrs = yearly.get(i).hrs; peakIdx = i; }
        }

        strokePaint.setStrokeWidth(1.6f * d);
        circlePaint.setStrokeWidth(1.5f * d);
        labelPaint.setTextSize(14 * d);
        labelPaint.setTypeface(EditorialTheme.getSerif(getContext()));
        yearPaint.setTextSize(8 * d);
        yearPaint.setTypeface(EditorialTheme.getMono(getContext()));
        tickPaint.setTextSize(7 * d);
        tickPaint.setTypeface(EditorialTheme.getMono(getContext()));

        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = padL + i * (getWidth() - padL - padR) / (n - 1);
            ys[i] = chartBottom - (yearly.get(i).hrs / maxHrs) * chartH;
        }

        // Fill area
        fillPath.reset();
        fillPath.moveTo(xs[0], ys[0]);
        buildCurve(fillPath, xs, ys);
        fillPath.lineTo(xs[n - 1], chartBottom);
        fillPath.lineTo(xs[0], chartBottom);
        fillPath.close();
        canvas.drawPath(fillPath, fillPaint);

        // Stroke curve
        curvePath.reset();
        curvePath.moveTo(xs[0], ys[0]);
        buildCurve(curvePath, xs, ys);
        canvas.drawPath(curvePath, strokePaint);

        // Points, labels, year axis
        for (int i = 0; i < n; i++) {
            boolean isPeak = i == peakIdx;
            boolean isLast = i == n - 1;
            float dotR = isPeak ? 6 * d : 4 * d;

            canvas.drawCircle(xs[i], ys[i], dotR, circleFill);
            canvas.drawCircle(xs[i], ys[i], dotR, circlePaint);

            // Numeral above point — show days (consistent with year rows)
            float hrs = yearly.get(i).hrs;
            long days = (long) (hrs / 24);
            String hrsStr = days > 0 ? days + "d" : Math.round(hrs) + "h";
            canvas.drawText(hrsStr, xs[i], ys[i] - dotR - 3 * d, labelPaint);

            // Year label below chart
            String yr = "'" + String.valueOf(yearly.get(i).year).substring(2);
            canvas.drawText(yr, xs[i], getHeight() - 2 * d, yearPaint);

            // PEAK / YTD tick
            if (isPeak) {
                tickPaint.setColor(EditorialTheme.vermilion(getContext()));
                canvas.drawText("PEAK", xs[i], tickH - 3 * d, tickPaint);
            } else if (isLast) {
                tickPaint.setColor(EditorialTheme.inkMuted(getContext()));
                canvas.drawText("YTD", xs[i], tickH - 3 * d, tickPaint);
            }
        }
    }

    private static void buildCurve(Path path, float[] xs, float[] ys) {
        int n = xs.length;
        for (int i = 1; i < n; i++) {
            float x0 = i > 1 ? xs[i - 2] : xs[0];
            float y0 = i > 1 ? ys[i - 2] : ys[0];
            float x1 = xs[i - 1], y1 = ys[i - 1];
            float x2 = xs[i], y2 = ys[i];
            float x3 = i < n - 1 ? xs[i + 1] : xs[n - 1];
            float y3 = i < n - 1 ? ys[i + 1] : ys[n - 1];
            path.cubicTo(
                    x1 + (x2 - x0) / 6f, y1 + (y2 - y0) / 6f,
                    x2 - (x3 - x1) / 6f, y2 - (y3 - y1) / 6f,
                    x2, y2);
        }
    }
}
