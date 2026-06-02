package de.danoeh.antennapod.ui.screen.playback.audio;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import androidx.annotation.Nullable;

/**
 * Zoomed, draggable segment-boundary editor — the Android port of the design's
 * {@code BoundaryEditor} (report.jsx). Renders a synthetic waveform across a
 * zoomed window, a tinted selected region, two draggable edge handles, and an
 * optional playhead. Tapping/dragging outside the handles scrubs.
 *
 * <p>All times are in <b>seconds</b> (floats), matching {@code TrimClient.Segment}.
 * The waveform is synthetic (we have no client-side amplitude data) but
 * deterministic: the same window always renders the same bars, so a segment
 * looks stable across re-opens.
 */
public class BoundaryEditor extends View {
    private static final int BARS = 60;
    private static final float MIN_GAP_SEC = 1f;
    /** Dragging a handle within this many dp of an edge auto-scrolls the window. */
    private static final float EDGE_PAN_MARGIN_DP = 28f;
    private static final long PAN_TICK_MS = 16L;

    /** Window (zoomed view range) and current selection, in seconds. */
    private float winStart = 0f;
    private float winEnd = 1f;
    private float boundStart = 0f;
    private float boundEnd = 1f;
    private float playhead = Float.NaN;
    private String type = "ad";

    /** Hard episode bounds the window/handles can never cross (pan stops here). */
    private float limitStart = 0f;
    private float limitEnd = Float.MAX_VALUE;

    private float density;
    private float[] amps = new float[BARS];

    private final Paint paintBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintRegion = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintHandle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintHandleTick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintPlayhead = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintEdgeHint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();
    private final Path tmpPath = new Path();

    private boolean dark;
    private int colorOutlineVar;
    private int colorOnSurf;

    /** Which element the active pointer grabbed. */
    private enum Drag { NONE, START, END, SCRUB }
    private Drag drag = Drag.NONE;

    /** Auto-pan loop: +1 pans the window right, -1 left, 0 idle. */
    private final Handler panHandler = new Handler(Looper.getMainLooper());
    private int panDir = 0;
    /** Last touch x while a handle is held, so the auto-pan keeps the dragged
     *  handle pinned under the (stationary) finger as the window slides. */
    private float panTouchX = 0f;
    private final Runnable panRunnable = this::panTick;

    public interface OnBoundsChangeListener {
        void onBoundsChange(float start, float end);
    }

    public interface OnScrubListener {
        void onScrub(float seconds);
    }

    @Nullable private OnBoundsChangeListener boundsListener;
    @Nullable private OnScrubListener scrubListener;

    public BoundaryEditor(Context context) {
        super(context);
        init(context);
    }

    public BoundaryEditor(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public BoundaryEditor(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        dark = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        // Neutral outline + on-surface colors approximated from the design palette
        // (kept local so this view has no dependency on a segment-color resource).
        colorOutlineVar = dark ? 0xFF3A3F3B : 0xFFC8CCC8;
        colorOnSurf = dark ? 0xFFE3E4E0 : 0xFF1A1C1A;
        paintPlayhead.setColor(colorOnSurf);
        paintHandleTick.setColor(0xD9FFFFFF);
        paintEdgeHint.setColor(colorOnSurf);
    }

    public void setOnBoundsChangeListener(@Nullable OnBoundsChangeListener l) {
        this.boundsListener = l;
    }

    public void setOnScrubListener(@Nullable OnScrubListener l) {
        this.scrubListener = l;
    }

    /** Set the zoomed window. Regenerates the synthetic waveform deterministically. */
    public void setWindow(float startSec, float endSec) {
        this.winStart = startSec;
        this.winEnd = Math.max(startSec + MIN_GAP_SEC, endSec);
        regenWave();
        invalidate();
    }

    /** Hard episode bounds. Handles clamp here (not to the window), and the
     *  auto-pan stops here, so a boundary can be dragged anywhere in the episode
     *  even though only a zoomed slice is visible. */
    public void setLimits(float minSec, float maxSec) {
        this.limitStart = minSec;
        this.limitEnd = Math.max(minSec + MIN_GAP_SEC, maxSec);
        invalidate();
    }

    public void setBounds(float startSec, float endSec) {
        this.boundStart = startSec;
        this.boundEnd = endSec;
        invalidate();
    }

    public void setType(String type) {
        this.type = type != null ? type.toLowerCase() : "ad";
        invalidate();
    }

    /** Playhead position in seconds, or {@code NaN} to hide it. */
    public void setPlayhead(float seconds) {
        this.playhead = seconds;
        invalidate();
    }

    public float getBoundStart() {
        return boundStart;
    }

    public float getBoundEnd() {
        return boundEnd;
    }

    /** Nudge the start edge by {@code deltaSec}, clamped to the episode and min
     *  gap. Pans the window if the new edge falls outside the visible slice. */
    public void nudgeStart(float deltaSec) {
        float v = clampToLimits(boundStart + deltaSec);
        boundStart = Math.min(v, boundEnd - MIN_GAP_SEC);
        panWindowToContain(boundStart);
        emitBounds();
        invalidate();
    }

    public void nudgeEnd(float deltaSec) {
        float v = clampToLimits(boundEnd + deltaSec);
        boundEnd = Math.max(v, boundStart + MIN_GAP_SEC);
        panWindowToContain(boundEnd);
        emitBounds();
        invalidate();
    }

    private float span() {
        return winEnd - winStart;
    }

    private float clampToWindow(float v) {
        return Math.max(winStart, Math.min(winEnd, v));
    }

    private float clampToLimits(float v) {
        return Math.max(limitStart, Math.min(limitEnd, v));
    }

    /** Shift the visible window (keeping its span) so {@code t} sits just inside
     *  the nearer edge, clamped to the episode limits. No-op if already visible. */
    private void panWindowToContain(float t) {
        float sp = span();
        float edge = sp * 0.1f;
        float newStart = winStart;
        if (t < winStart + edge) {
            newStart = t - edge;
        } else if (t > winEnd - edge) {
            newStart = t + edge - sp;
        } else {
            return;
        }
        newStart = Math.max(limitStart, newStart);
        float newEnd = newStart + sp;
        if (newEnd > limitEnd) {
            newEnd = limitEnd;
            newStart = Math.max(limitStart, newEnd - sp);
        }
        if (newStart != winStart || newEnd != winEnd) {
            winStart = newStart;
            winEnd = newEnd;
            regenWave();
        }
    }

    /** Seconds → x pixel within the content box. */
    private float timeToX(float t) {
        return ((t - winStart) / span()) * contentWidth() + getPaddingLeft();
    }

    /** x pixel → seconds, clamped to the window. */
    private float xToTime(float x) {
        return clampToWindow(winStart + ((x - getPaddingLeft()) / contentWidth()) * span());
    }

    private float contentWidth() {
        return Math.max(1f, getWidth() - getPaddingLeft() - getPaddingRight());
    }

    // Mirror of the design's waveAmps(n, seed): deterministic LCG + layered sines.
    private void regenWave() {
        long s = Math.round(winStart) + 3;
        for (int i = 0; i < BARS; i++) {
            s = (s * 9301 + 49297) % 233280;
            double r = s / 233280.0;
            double env = 0.45 + 0.4 * Math.abs(Math.sin(i * 0.21) * Math.cos(i * 0.057));
            amps[i] = (float) Math.max(0.12, Math.min(1.0, env * (0.55 + r * 0.7)));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float left = getPaddingLeft();
        final float w = contentWidth();
        final float top = getPaddingTop();
        final float h = getHeight() - getPaddingTop() - getPaddingBottom();
        final float midY = top + h / 2f;

        final int solid = SegmentColors.solid(type, dark);
        final int region = SegmentColors.region(type, dark);

        // Selected-region tint behind the bars.
        float rl = timeToX(boundStart);
        float rr = timeToX(boundEnd);
        paintRegion.setColor(region);
        tmpRect.set(rl, top, rr, top + h);
        canvas.drawRoundRect(tmpRect, density * 6, density * 6, paintRegion);

        // Waveform bars (centered, mirrored top/bottom).
        float gap = density * 2;
        float barW = (w - gap * (BARS - 1)) / BARS;
        for (int i = 0; i < BARS; i++) {
            float bx = left + i * (barW + gap);
            float tm = winStart + ((i + 0.5f) / BARS) * span();
            boolean inside = tm >= boundStart && tm < boundEnd;
            boolean played = !Float.isNaN(playhead) && tm <= playhead;
            int color;
            int alpha;
            if (inside) {
                color = solid;
                alpha = 255;
            } else if (played) {
                color = colorOnSurf;
                alpha = 153;
            } else {
                color = colorOutlineVar;
                alpha = 128;
            }
            paintBar.setColor(color);
            paintBar.setAlpha(alpha);
            float barH = Math.max(density * 4, amps[i] * h);
            float bTop = midY - barH / 2f;
            tmpRect.set(bx, bTop, bx + barW, bTop + barH);
            canvas.drawRoundRect(tmpRect, density * 2, density * 2, paintBar);
        }

        // Playhead.
        if (!Float.isNaN(playhead) && playhead >= winStart && playhead <= winEnd) {
            float px = timeToX(playhead);
            paintPlayhead.setColor(colorOnSurf);
            canvas.drawRoundRect(px - density, top - density * 2, px + density,
                    top + h + density * 2, density, density, paintPlayhead);
            canvas.drawCircle(px, top - density * 2, density * 4, paintPlayhead);
        }

        // Handles.
        drawHandle(canvas, timeToX(boundStart), midY, h, solid);
        drawHandle(canvas, timeToX(boundEnd), midY, h, solid);

        // Edge affordance: a faint chevron when more episode exists beyond a
        // visible edge, hinting the window scrolls when a handle is dragged there.
        if (winStart > limitStart + 0.05f) {
            drawEdgeChevron(canvas, left + density * 6, midY, -1);
        }
        if (winEnd < limitEnd - 0.05f) {
            drawEdgeChevron(canvas, left + w - density * 6, midY, 1);
        }
    }

    private void drawEdgeChevron(Canvas canvas, float tipX, float midY, int dir) {
        float wdt = density * 5;
        float hgt = density * 7;
        paintEdgeHint.setAlpha(110);
        tmpPath.reset();
        tmpPath.moveTo(tipX, midY);
        tmpPath.lineTo(tipX - dir * wdt, midY - hgt);
        tmpPath.moveTo(tipX, midY);
        tmpPath.lineTo(tipX - dir * wdt, midY + hgt);
        paintEdgeHint.setStyle(Paint.Style.STROKE);
        paintEdgeHint.setStrokeWidth(density * 2);
        paintEdgeHint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawPath(tmpPath, paintEdgeHint);
    }

    private void drawHandle(Canvas canvas, float x, float midY, float h, int color) {
        paintHandle.setColor(color);
        // Vertical edge line spanning slightly beyond the track.
        canvas.drawRoundRect(x - density * 1.5f, midY - h / 2f - density * 8,
                x + density * 1.5f, midY + h / 2f + density * 8, density, density, paintHandle);
        // Rounded grip with two ticks (matches the design's pill grip).
        float gripW = density * 22;
        float gripH = density * 30;
        tmpRect.set(x - gripW / 2, midY - gripH / 2, x + gripW / 2, midY + gripH / 2);
        canvas.drawRoundRect(tmpRect, density * 8, density * 8, paintHandle);
        float tickH = density * 12;
        canvas.drawRoundRect(x - density * 3, midY - tickH / 2, x - density,
                midY + tickH / 2, density, density, paintHandleTick);
        canvas.drawRoundRect(x + density, midY - tickH / 2, x + density * 3,
                midY + tickH / 2, density, density, paintHandleTick);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final float x = event.getX();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                drag = pickTarget(x);
                getParent().requestDisallowInterceptTouchEvent(true);
                handleMove(x);
                return true;
            case MotionEvent.ACTION_MOVE:
                handleMove(x);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                stopPan();
                drag = Drag.NONE;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /** Grab the nearer handle if the touch is within the hit slop, else scrub. */
    private Drag pickTarget(float x) {
        float slop = density * 24;
        float dStart = Math.abs(x - timeToX(boundStart));
        float dEnd = Math.abs(x - timeToX(boundEnd));
        if (dStart <= slop && dStart <= dEnd) {
            return Drag.START;
        }
        if (dEnd <= slop) {
            return Drag.END;
        }
        return scrubListener != null ? Drag.SCRUB : Drag.NONE;
    }

    private void handleMove(float x) {
        float t = xToTime(x);
        switch (drag) {
            case START:
                boundStart = Math.min(t, boundEnd - MIN_GAP_SEC);
                emitBounds();
                invalidate();
                updatePanForEdge(x);
                break;
            case END:
                boundEnd = Math.max(t, boundStart + MIN_GAP_SEC);
                emitBounds();
                invalidate();
                updatePanForEdge(x);
                break;
            case SCRUB:
                if (scrubListener != null) {
                    scrubListener.onScrub(t);
                }
                break;
            default:
                break;
        }
    }

    /** Either handle, held within the edge margin, auto-scrolls the window that
     *  way: the left margin scrolls earlier, the right margin scrolls later. So
     *  a boundary can be dragged past the visible slice in <em>both</em>
     *  directions regardless of which handle it is. */
    private void updatePanForEdge(float x) {
        float margin = density * EDGE_PAN_MARGIN_DP;
        panTouchX = x;
        if (x <= getPaddingLeft() + margin && winStart > limitStart) {
            setPanDir(-1);
        } else if (x >= getWidth() - getPaddingRight() - margin && winEnd < limitEnd) {
            setPanDir(1);
        } else {
            setPanDir(0);
        }
    }

    private void setPanDir(int dir) {
        if (dir == panDir) {
            return;
        }
        panDir = dir;
        panHandler.removeCallbacks(panRunnable);
        if (dir != 0) {
            panHandler.post(panRunnable);
        }
    }

    private void stopPan() {
        panDir = 0;
        panHandler.removeCallbacks(panRunnable);
    }

    /** One auto-scroll step: shift the window in {@link #panDir} and drag the
     *  active handle along with the moving edge. Reschedules until the finger
     *  leaves the margin (panDir==0) or the window reaches the episode limit. */
    private void panTick() {
        if (panDir == 0) {
            return;
        }
        float step = Math.max(0.08f, span() * 0.02f) * panDir;
        float newStart = winStart + step;
        float newEnd = winEnd + step;
        if (newStart < limitStart) {
            newEnd += limitStart - newStart;
            newStart = limitStart;
        } else if (newEnd > limitEnd) {
            newStart -= newEnd - limitEnd;
            newEnd = limitEnd;
        }
        if (newStart == winStart && newEnd == winEnd) {
            stopPan();
            return;
        }
        winStart = newStart;
        winEnd = newEnd;
        // Keep the dragged handle under the stationary finger as the window
        // slides. xToTime clamps to the (just-moved) window, so the handle rides
        // the edge the finger is held against — in either direction.
        float t = xToTime(panTouchX);
        if (drag == Drag.START) {
            boundStart = Math.min(t, boundEnd - MIN_GAP_SEC);
        } else if (drag == Drag.END) {
            boundEnd = Math.max(t, boundStart + MIN_GAP_SEC);
        }
        regenWave();
        emitBounds();
        invalidate();
        panHandler.postDelayed(panRunnable, PAN_TICK_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopPan();
    }

    private void emitBounds() {
        if (boundsListener != null) {
            boundsListener.onBoundsChange(boundStart, boundEnd);
        }
    }

    /** Segment-type → color, ported from the design palette (mui.jsx t.seg). */
    static final class SegmentColors {
        private SegmentColors() {
        }

        static int solid(String type, boolean dark) {
            if (type == null) {
                type = "ad";
            }
            switch (type) {
                case "intro": return dark ? 0xFF9AA6F0 : 0xFF5566CC;
                case "outro": return dark ? 0xFF7FD1C4 : 0xFF2A9D8F;
                case "ad":
                default:      return dark ? 0xFFE0A23C : 0xFFC77800;
            }
        }

        /** Tinted region behind the bars (the design's {@code c.bg}, softened). */
        static int region(String type, boolean dark) {
            int base;
            switch (type == null ? "ad" : type) {
                case "intro":
                    base = dark ? 0xFF2D3A86 : 0xFFDFE1FF;
                    break;
                case "outro":
                    base = dark ? 0xFF1F4A44 : 0xFFC2E8E1;
                    break;
                case "ad":
                default:
                    base = dark ? 0xFF5A3A00 : 0xFFFFDCA8;
                    break;
            }
            int alpha = dark ? 128 : 204; // matches the design's 0.5 / 0.8 region opacity
            return (alpha << 24) | (base & 0x00FFFFFF);
        }
    }
}
