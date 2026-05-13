package de.danoeh.antennapod.ui.statistics.editorial;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import de.danoeh.antennapod.storage.database.DBReader;
import java.util.List;

/**
 * Full-circle donut chart. Segmented arc, 1.2dp gaps, butt caps, double hairline ring.
 * Center text slot filled via superimposed TextViews in the layout.
 */
public class DonutView extends View {
    private List<DBReader.EditorialStats.ShowItem> shows;
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();
    private final RectF innerRing = new RectF();
    private final RectF outerRing = new RectF();

    public DonutView(Context context) { super(context); init(); }
    public DonutView(Context context, AttributeSet a) { super(context, a); init(); }
    public DonutView(Context context, AttributeSet a, int d) { super(context, a, d); init(); }

    private void init() {
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.BUTT);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(0.75f * getResources().getDisplayMetrics().density);
        ringPaint.setColor(EditorialTheme.FAINT);
    }

    public void setData(List<DBReader.EditorialStats.ShowItem> shows) {
        this.shows = shows;
        invalidate();
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int size = resolveSize(getResources().getDisplayMetrics().widthPixels / 2, ws);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (shows == null || shows.isEmpty()) return;
        float d = getResources().getDisplayMetrics().density;
        float stroke = 22 * d;
        float gap = 1.2f * d;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - stroke / 2 - 4 * d;

        arcPaint.setStrokeWidth(stroke);
        arcBounds.set(cx - r, cy - r, cx + r, cy + r);

        // Compute total
        float total = 0;
        for (DBReader.EditorialStats.ShowItem s : shows) total += s.hrs;
        if (total == 0) return;

        float gapAngle = (float) Math.toDegrees(gap / r);
        float start = -90f;

        for (DBReader.EditorialStats.ShowItem s : shows) {
            float sweep = 360f * (s.hrs / total) - gapAngle;
            if (sweep < 0.5f) { start += sweep + gapAngle; continue; }
            arcPaint.setColor(s.color | 0xFF000000);
            canvas.drawArc(arcBounds, start + gapAngle / 2, sweep, false, arcPaint);
            start += sweep + gapAngle;
        }

        // Double hairline ring
        float outerR = r + stroke / 2 + 2 * d;
        float innerR = r - stroke / 2 - 2 * d;
        outerRing.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR);
        innerRing.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR);
        ringPaint.setStrokeWidth(0.75f * d);
        canvas.drawOval(outerRing, ringPaint);
        canvas.drawOval(innerRing, ringPaint);
    }
}
