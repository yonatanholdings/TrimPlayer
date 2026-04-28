package de.danoeh.antennapod.ui.echo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

public class WordmarkDrawable extends Drawable {
    private static final String TEXT = "TrimPlayer Echo";
    private final Paint paintFill;
    private final Paint paintStroke;
    private final Paint paintText;

    public WordmarkDrawable(Context context) {
        paintFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintFill.setColor(0xffffffff);
        paintFill.setStyle(Paint.Style.FILL);

        paintStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintStroke.setColor(0xffffffff);
        paintStroke.setStyle(Paint.Style.STROKE);
        paintStroke.setStrokeCap(Paint.Cap.ROUND);

        paintText = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintText.setColor(0xffffffff);
        paintText.setStyle(Paint.Style.FILL);
        paintText.setTextAlign(Paint.Align.LEFT);
        Typeface typeface = ResourcesCompat.getFont(context, R.font.sarabun_semi_bold);
        paintText.setTypeface(typeface);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        float h = bounds.height();
        float iconSize = h * 0.78f;
        float padding = h * 0.11f;
        float iconLeft = bounds.left + padding;
        float iconTop = bounds.top + padding;

        // Scale icon paths from 108×108 viewport
        float s = iconSize / 108f;

        // Vertical stem
        paintStroke.setStrokeWidth(4.5f * s);
        canvas.drawLine(iconLeft + 36 * s, iconTop + 22 * s,
                iconLeft + 36 * s, iconTop + 86 * s, paintStroke);

        // Horizontal crossbar
        canvas.drawLine(iconLeft + 36 * s, iconTop + 54 * s,
                iconLeft + 79 * s, iconTop + 54 * s, paintStroke);

        // Top play triangle
        Path top = new Path();
        top.moveTo(iconLeft + 41 * s, iconTop + 22 * s);
        top.lineTo(iconLeft + 79 * s, iconTop + 49 * s);
        top.lineTo(iconLeft + 41 * s, iconTop + 49 * s);
        top.close();
        canvas.drawPath(top, paintFill);

        // Bottom play triangle
        Path bottom = new Path();
        bottom.moveTo(iconLeft + 41 * s, iconTop + 59 * s);
        bottom.lineTo(iconLeft + 79 * s, iconTop + 59 * s);
        bottom.lineTo(iconLeft + 41 * s, iconTop + 86 * s);
        bottom.close();
        canvas.drawPath(bottom, paintFill);

        // "TrimPlayer Echo" text
        float textX = iconLeft + iconSize + padding * 1.5f;
        paintText.setTextSize(h * 0.52f);
        float textY = bounds.centerY() - (paintText.descent() + paintText.ascent()) / 2f;
        canvas.drawText(TEXT, textX, textY, paintText);
    }

    @Override
    public int getIntrinsicWidth() {
        return 1000;
    }

    @Override
    public int getIntrinsicHeight() {
        return 111;
    }

    @Override
    public void setAlpha(int alpha) {
        paintFill.setAlpha(alpha);
        paintStroke.setAlpha(alpha);
        paintText.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paintFill.setColorFilter(colorFilter);
        paintStroke.setColorFilter(colorFilter);
        paintText.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
