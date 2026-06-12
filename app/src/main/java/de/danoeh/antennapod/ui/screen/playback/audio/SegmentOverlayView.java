package de.danoeh.antennapod.ui.screen.playback.audio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Thin overlay that paints the amber trim-segment markers on top of the mini
 * player's progress bar in the bottom sheet's collapsed footer. The footer uses
 * a Material {@code LinearProgressIndicator} (not the custom ChapterSeekBar), so
 * this sibling view reproduces the same amber wash the full player draws on its
 * seek bar — see {@link ChapterSeekBar#setSegments}.
 */
public class SegmentOverlayView extends View {

    private float[] segmentStarts;
    private float[] segmentEnds;
    private final Paint paintSegment = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SegmentOverlayView(Context context) {
        super(context);
        init();
    }

    public SegmentOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SegmentOverlayView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        // Same amber wash as ChapterSeekBar so the marker reads identically
        // whether the user is in the full player or the collapsed footer.
        paintSegment.setColor(0xCCFFB300);
    }

    /**
     * Mark detected trim segments. Each is a fraction [0..1] of media duration;
     * arrays must align. Pass null to clear.
     */
    public void setSegments(final float[] starts, final float[] ends) {
        this.segmentStarts = starts;
        this.segmentEnds = ends;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (segmentStarts == null || segmentEnds == null || segmentStarts.length == 0) {
            return;
        }
        float width = getWidth();
        float height = getHeight();
        int count = Math.min(segmentStarts.length, segmentEnds.length);
        for (int i = 0; i < count; i++) {
            float a = segmentStarts[i];
            float b = segmentEnds[i];
            if (a < 0) {
                a = 0;
            }
            if (b > 1) {
                b = 1;
            }
            if (b <= a) {
                continue;
            }
            canvas.drawRect(a * width, 0, b * width, height, paintSegment);
        }
    }
}
