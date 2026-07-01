package de.danoeh.antennapod.ui.statistics.editorial;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.palette.graphics.Palette;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import java.util.function.IntConsumer;

/**
 * Extracts a per-feed dominant color via Glide + AndroidX Palette and caches
 * the result in-memory so repeated lookups during a scroll session are free.
 *
 * Used by the subscriptions list row indicator (and the donut chart) instead
 * of the fixed 9-color palette in {@code EditorialTheme.SHOW_COLORS}. Falls
 * back to the supplied fallback color when the bitmap can't be loaded or
 * Palette can't extract a meaningful swatch.
 *
 * Color selection prefers, in order: vibrant → muted → dominant. The vibrant
 * swatch is usually the cover's brand color; muted covers fall back better
 * than dominant (which can return near-black for dark covers).
 */
public final class FeedColorCache {
    private static final LruCache<Long, Integer> CACHE = new LruCache<>(64);

    private FeedColorCache() {}

    /** Synchronous read; returns null if no entry. */
    @Nullable
    public static Integer peek(long feedId) {
        return CACHE.get(feedId);
    }

    /** Resolve and apply asynchronously. {@code onColor} fires once with the
     *  chosen color (cached, extracted, or fallback). It may fire synchronously
     *  if the color is already cached. */
    public static void apply(Context ctx, long feedId, @Nullable String imageUrl,
                              int fallback, @NonNull IntConsumer onColor) {
        Integer cached = CACHE.get(feedId);
        if (cached != null) {
            onColor.accept(cached);
            return;
        }
        if (imageUrl == null || imageUrl.isEmpty()) {
            onColor.accept(fallback);
            return;
        }
        Glide.with(ctx).asBitmap().load(imageUrl)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap bitmap,
                                                @Nullable Transition<? super Bitmap> transition) {
                        Palette.from(bitmap).generate(palette -> {
                            int chosen = pickColor(palette, fallback);
                            CACHE.put(feedId, chosen);
                            onColor.accept(chosen);
                        });
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                        // Lifecycle teardown — no callback. View will recycle.
                    }

                    @Override
                    public void onLoadFailed(@Nullable Drawable errorDrawable) {
                        onColor.accept(fallback);
                    }
                });
    }

    private static int pickColor(@Nullable Palette palette, int fallback) {
        if (palette == null) {
            return fallback;
        }
        int c = palette.getVibrantColor(0);
        if (c == 0) {
            c = palette.getMutedColor(0);
        }
        if (c == 0) {
            c = palette.getDominantColor(fallback);
        }
        // Floor lightness to keep colors from disappearing on a cream paper bg.
        // (Pure-white covers can extract near-white; nudge them down to a usable tone.)
        float[] hsl = new float[3];
        Color.colorToHSV(c, hsl);
        if (hsl[2] > 0.85f) {
            hsl[2] = 0.65f;
        }
        return Color.HSVToColor(hsl);
    }
}
