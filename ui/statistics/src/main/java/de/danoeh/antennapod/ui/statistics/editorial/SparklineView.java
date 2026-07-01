package de.danoeh.antennapod.ui.statistics.editorial;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** Small area sparkline + line + endpoint dots for weekly listening data. */
public class SparklineView extends View {
    private float[] data; // weekly hours, length 12 (index 0 = oldest)
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    public SparklineView(Context context) { super(context); init(); }
    public SparklineView(Context context, AttributeSet a) { super(context, a); init(); }
    public SparklineView(Context context, AttributeSet a, int d) { super(context, a, d); init(); }

    private void init() {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(1.5f);
        linePaint.setColor(EditorialTheme.vermilion(getContext()));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(EditorialTheme.vermilion(getContext()));
        fillPaint.setStyle(Paint.Style.FILL);
    }

    public void setData(float[] weeklyHours) {
        this.data = weeklyHours;
        invalidate();
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = resolveSize((int) (200 * getResources().getDisplayMetrics().density), ws);
        int h = resolveSize((int) (48 * getResources().getDisplayMetrics().density), hs);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        fillPaint.setShader(new LinearGradient(0, 0, 0, h,
                EditorialTheme.vermilionTint(getContext()), 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (data == null || data.length < 2) {
            return;
        }
        int n = data.length;
        float maxV = 0.01f;
        for (float v : data) if (v > maxV) maxV = v;

        float w = getWidth(), h = getHeight();
        float d = getResources().getDisplayMetrics().density;
        float dotR = 3 * d;
        float padTop = dotR + 2 * d;
        float chartH = h - padTop;

        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = i * (w - 1) / (n - 1);
            ys[i] = padTop + chartH - (data[i] / maxV) * chartH;
        }

        // Fill area
        fillPath.reset();
        fillPath.moveTo(xs[0], ys[0]);
        buildCurve(fillPath, xs, ys);
        fillPath.lineTo(xs[n - 1], h);
        fillPath.lineTo(xs[0], h);
        fillPath.close();
        canvas.drawPath(fillPath, fillPaint);

        // Line
        linePath.reset();
        linePath.moveTo(xs[0], ys[0]);
        buildCurve(linePath, xs, ys);
        canvas.drawPath(linePath, linePaint);

        // Endpoint dots (first + last)
        dotPaint.setColor(EditorialTheme.paper(getContext()));
        canvas.drawCircle(xs[0], ys[0], dotR, dotPaint);
        canvas.drawCircle(xs[n - 1], ys[n - 1], dotR, dotPaint);
        dotPaint.setColor(EditorialTheme.vermilion(getContext()));
        canvas.drawCircle(xs[0], ys[0], dotR - d, dotPaint);
        canvas.drawCircle(xs[n - 1], ys[n - 1], dotR - d, dotPaint);
    }

    /** Catmull-Rom spline approximated with cubic Béziers. */
    private static void buildCurve(Path path, float[] xs, float[] ys) {
        int n = xs.length;
        for (int i = 1; i < n; i++) {
            float x0 = i > 1 ? xs[i - 2] : xs[0];
            float y0 = i > 1 ? ys[i - 2] : ys[0];
            float x1 = xs[i - 1], y1 = ys[i - 1];
            float x2 = xs[i], y2 = ys[i];
            float x3 = i < n - 1 ? xs[i + 1] : xs[n - 1];
            float y3 = i < n - 1 ? ys[i + 1] : ys[n - 1];
            float cp1x = x1 + (x2 - x0) / 6f;
            float cp1y = y1 + (y2 - y0) / 6f;
            float cp2x = x2 - (x3 - x1) / 6f;
            float cp2y = y2 - (y3 - y1) / 6f;
            path.cubicTo(cp1x, cp1y, cp2x, cp2y, x2, y2);
        }
    }
}
