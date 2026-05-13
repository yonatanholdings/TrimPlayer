package de.danoeh.antennapod.ui.statistics.editorial;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;

/**
 * Design tokens for the editorial statistics system.
 *
 * Custom fonts (Instrument Serif, IBM Plex Mono) are loaded from
 * ui/statistics/src/main/res/font/ when the .ttf files are present.
 * To add fonts: drop the TTF files as:
 *   res/font/instrument_serif_regular.ttf
 *   res/font/ibm_plex_mono_regular.ttf
 * then change FONTS_BUNDLED to true.
 */
public final class EditorialTheme {
    private EditorialTheme() {}

    // Set to true once font .ttf files are added to res/font/
    private static final boolean FONTS_BUNDLED = false;

    // ── Color palette ─────────────────────────────────────────────────────────
    public static final int BG          = 0xFFFFFFFF;
    public static final int PAPER       = 0xFFFBF8F1;
    public static final int INK         = 0xFF15110D;
    public static final int INK_SOFT    = 0xFF3A322A;
    public static final int INK_MUTE    = 0xFF7A6E60;
    public static final int ACCENT      = 0xFFB8442E;
    public static final int ACCENT_SOFT = 0xFFE8B9A4;
    public static final int ACCENT_TINT = 0xFFF3D6C4;
    public static final int GOLD        = 0xFFA47436;
    public static final int GOLD_SOFT   = 0xFFE1C79A;
    public static final int FAINT       = 0x1A15110D; // ~10% ink
    public static final int VERY_FAINT  = 0x0D15110D; // ~5% ink
    public static final int RULE        = 0xFF15110D;

    // ── Show palette ──────────────────────────────────────────────────────────
    public static final int[] SHOW_COLORS = {
            0xFFf4a261, 0xFF2a9d8f, 0xFFe76f51, 0xFF264653,
            0xFFa06cd5, 0xFF83c5be, 0xFFbc4749, 0xFF588157, 0xFF9aa0a6
    };

    // ── Typography ────────────────────────────────────────────────────────────
    private static Typeface cachedSerif;
    private static Typeface cachedMono;
    private static volatile boolean fontsLoaded;

    public static Typeface getSerif(Context ctx) {
        if (!fontsLoaded) loadFonts(ctx);
        return cachedSerif != null ? cachedSerif : Typeface.SERIF;
    }

    public static Typeface getMono(Context ctx) {
        if (!fontsLoaded) loadFonts(ctx);
        return cachedMono != null ? cachedMono : Typeface.MONOSPACE;
    }

    private static synchronized void loadFonts(Context ctx) {
        if (fontsLoaded) return;
        fontsLoaded = true;
        if (!FONTS_BUNDLED) return;

        // Load Instrument Serif (serif numerals / headlines)
        try {
            int serifResId = ctx.getResources().getIdentifier(
                    "instrument_serif_regular", "font", ctx.getPackageName());
            if (serifResId != 0) {
                Typeface t = androidx.core.content.res.ResourcesCompat.getFont(ctx, serifResId);
                if (t != null) cachedSerif = t;
            }
        } catch (Exception ignored) {}

        // Load IBM Plex Mono (labels / section headers)
        try {
            int monoResId = ctx.getResources().getIdentifier(
                    "ibm_plex_mono_regular", "font", ctx.getPackageName());
            if (monoResId != 0) {
                Typeface t = androidx.core.content.res.ResourcesCompat.getFont(ctx, monoResId);
                if (t != null) cachedMono = t;
            }
        } catch (Exception ignored) {}
    }
}
