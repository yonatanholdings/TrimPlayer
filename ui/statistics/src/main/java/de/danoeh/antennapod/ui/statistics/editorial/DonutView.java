package de.danoeh.antennapod.ui.statistics.editorial;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import de.danoeh.antennapod.storage.database.DBReader;
import java.util.List;

/**
 * Full-circle donut chart. Segmented arc, 1.2dp gaps, butt caps, double hairline ring.
 * Center text slot filled via superimposed TextViews in the layout.
 *
 * Tappable: dispatches {@link OnSegmentClickListener#onSegmentClick(int)} with
 * the segment index the user tapped. External callers can also drive selection
 * via {@link #setSelectedIndex(int)} (e.g. from a sibling list row tap).
 */
public class DonutView extends View {
    public interface OnSegmentClickListener {
        void onSegmentClick(int index);
    }

    private List<DBReader.EditorialStats.ShowItem> shows;
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();
    private final RectF innerRing = new RectF();
    private final RectF outerRing = new RectF();
    private int selectedIndex = -1;
    private OnSegmentClickListener segmentClickListener;
    private float downX = -1, downY = -1;

    public DonutView(Context context) { super(context); init(); }
    public DonutView(Context context, AttributeSet a) { super(context, a); init(); }
    public DonutView(Context context, AttributeSet a, int d) { super(context, a, d); init(); }

    private void init() {
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.BUTT);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(0.75f * getResources().getDisplayMetrics().density);
        ringPaint.setColor(EditorialTheme.ruleFaint(getContext()));
    }

    public void setData(List<DBReader.EditorialStats.ShowItem> shows) {
        this.shows = shows;
        // Kick off per-show palette extraction so the donut becomes recognizable
        // at a glance after a brief async tick. Cached results paint immediately
        // in onDraw via FeedColorCache.peek().
        if (shows != null) {
            for (DBReader.EditorialStats.ShowItem s : shows) {
                if (s.feedId != -1 && s.imageUrl != null && FeedColorCache.peek(s.feedId) == null) {
                    FeedColorCache.apply(getContext(), s.feedId, s.imageUrl, s.color,
                            color -> invalidate());
                }
            }
        }
        invalidate();
    }

    public void setOnSegmentClickListener(OnSegmentClickListener listener) {
        this.segmentClickListener = listener;
    }

    public void setSelectedIndex(int index) {
        if (this.selectedIndex == index) {
            return;
        }
        this.selectedIndex = index;
        invalidate();
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (shows == null || shows.isEmpty()) {
            return super.onTouchEvent(e);
        }
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (downX >= 0 && Math.hypot(e.getX() - downX, e.getY() - downY) < 12 * getResources().getDisplayMetrics().density) {
                    int idx = hitTestSegment(e.getX(), e.getY());
                    if (idx >= 0) {
                        // Toggle: tapping the already-selected segment deselects.
                        setSelectedIndex(idx == selectedIndex ? -1 : idx);
                        if (segmentClickListener != null) {
                            segmentClickListener.onSegmentClick(idx);
                        }
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

    /** Returns the segment index for a touch coordinate, or -1 if outside the
     *  ring annulus. Uses the same geometry constants as onDraw, with the gap
     *  swept into the leading segment so taps near gaps still register. */
    private int hitTestSegment(float x, float y) {
        if (shows == null || shows.isEmpty()) {
            return -1;
        }
        float d = getResources().getDisplayMetrics().density;
        float stroke = 22 * d;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - stroke / 2 - 4 * d;
        float dx = x - cx, dy = y - cy;
        float dist = (float) Math.hypot(dx, dy);
        if (dist < r - stroke / 2 || dist > r + stroke / 2) {
            return -1;
        }

        // Angle clockwise from top (12 o'clock = 0, increasing clockwise).
        double ang = Math.atan2(dy, dx) + Math.PI / 2;
        if (ang < 0) {
            ang += 2 * Math.PI;
        }
        double angleDeg = Math.toDegrees(ang);

        float total = 0;
        for (DBReader.EditorialStats.ShowItem s : shows) {
            total += s.hrs;
        }
        if (total == 0) {
            return -1;
        }

        float cumulative = 0;
        for (int i = 0; i < shows.size(); i++) {
            float allocation = 360f * (shows.get(i).hrs / total);
            if (angleDeg >= cumulative && angleDeg < cumulative + allocation) {
                return i;
            }
            cumulative += allocation;
        }
        return -1;
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int size = resolveSize(getResources().getDisplayMetrics().widthPixels / 2, ws);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (shows == null || shows.isEmpty()) {
            return;
        }
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
        for (DBReader.EditorialStats.ShowItem s : shows) {
            total += s.hrs;
        }
        if (total == 0) {
            return;
        }

        float gapAngle = (float) Math.toDegrees(gap / r);
        float start = -90f;

        for (int i = 0; i < shows.size(); i++) {
            DBReader.EditorialStats.ShowItem s = shows.get(i);
            float sweep = 360f * (s.hrs / total) - gapAngle;
            if (sweep < 0.5f) { start += sweep + gapAngle; continue; }
            Integer extracted = s.feedId != -1 ? FeedColorCache.peek(s.feedId) : null;
            int color = (extracted != null ? extracted : s.color) | 0xFF000000;
            arcPaint.setColor(color);
            // When a segment is selected, dim the others so the selected pops.
            arcPaint.setAlpha(selectedIndex < 0 || selectedIndex == i ? 255 : 80);
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
