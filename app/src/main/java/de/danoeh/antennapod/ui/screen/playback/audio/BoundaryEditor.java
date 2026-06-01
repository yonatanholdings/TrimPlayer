package de.danoeh.antennapod.ui.screen.playback.audio;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
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

    /** Window (zoomed view range) and current selection, in seconds. */
    private float winStart = 0f;
    private float winEnd = 1f;
    private float boundStart = 0f;
    private float boundEnd = 1f;
    private float playhead = Float.NaN;
    private String type = "ad";

    private float density;
    private float[] amps = new float[BARS];

    private final Paint paintBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintRegion = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintHandle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintHandleTick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintPlayhead = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();

    private boolean dark;
    private int colorOutlineVar;
    private int colorOnSurf;

    /** Which element the active pointer grabbed. */
    private enum Drag { NONE, START, END, SCRUB }
    private Drag drag = Drag.NONE;

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

    /** Nudge the start edge by {@code deltaSec}, clamped to the window and min gap. */
    public void nudgeStart(float deltaSec) {
        float v = clampToWindow(boundStart + deltaSec);
        boundStart = Math.min(v, boundEnd - MIN_GAP_SEC);
        emitBounds();
        invalidate();
    }

    public void nudgeEnd(float deltaSec) {
        float v = clampToWindow(boundEnd + deltaSec);
        boundEnd = Math.max(v, boundStart + MIN_GAP_SEC);
        emitBounds();
        invalidate();
    }

    private float span() {
        return winEnd - winStart;
    }

    private float clampToWindow(float v) {
        return Math.max(winStart, Math.min(winEnd, v));
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
                break;
            case END:
                boundEnd = Math.max(t, boundStart + MIN_GAP_SEC);
                emitBounds();
                invalidate();
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
