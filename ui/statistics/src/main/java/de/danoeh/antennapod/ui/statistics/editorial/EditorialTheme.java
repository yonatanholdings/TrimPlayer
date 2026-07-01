package de.danoeh.antennapod.ui.statistics.editorial;

import android.content.Context;
import android.graphics.Typeface;
import androidx.core.content.ContextCompat;

import de.danoeh.antennapod.ui.statistics.R;

/**
 * Design tokens for the editorial statistics system.
 *
 * <p>Colors are sourced from {@code res/values/colors.xml} (light) and
 * {@code res/values-night/colors.xml} (dark) so the screens follow the
 * system theme. Custom views read via {@code EditorialTheme.ink(ctx)} etc.;
 * layouts reference {@code @color/editorial_*} directly.
 *
 * <p>Custom fonts (Instrument Serif, IBM Plex Mono) are loaded from
 * {@code res/font/} when the .ttf files are present. To add fonts: drop
 * the TTFs as {@code instrument_serif_regular.ttf} and
 * {@code ibm_plex_mono_regular.ttf}, then flip {@link #FONTS_BUNDLED} to true.
 */
public final class EditorialTheme {
    private EditorialTheme() {
    }

    // Set to true once font .ttf files are added to res/font/
    private static final boolean FONTS_BUNDLED = true;

    // ── Color accessors (theme-aware via resources) ──────────────────────────
    public static int paper(Context c) {
        return ContextCompat.getColor(c, R.color.editorial_paper);
    }

    public static int paperAlt(Context c)      { return ContextCompat.getColor(c, R.color.editorial_paper_alt); }

    public static int ink(Context c)           { return ContextCompat.getColor(c, R.color.editorial_ink); }

    public static int inkSoft(Context c)       { return ContextCompat.getColor(c, R.color.editorial_ink_soft); }

    public static int inkCaption(Context c)    { return ContextCompat.getColor(c, R.color.editorial_ink_caption); }

    public static int inkMuted(Context c)      { return ContextCompat.getColor(c, R.color.editorial_ink_muted); }

    public static int inkVeryMuted(Context c)  { return ContextCompat.getColor(c, R.color.editorial_ink_v_muted); }

    public static int vermilion(Context c)     { return ContextCompat.getColor(c, R.color.editorial_vermilion); }

    public static int vermilionSoft(Context c) { return ContextCompat.getColor(c, R.color.editorial_vermilion_soft); }

    public static int vermilionTint(Context c) { return ContextCompat.getColor(c, R.color.editorial_vermilion_tint); }

    public static int gold(Context c)          { return ContextCompat.getColor(c, R.color.editorial_gold); }

    public static int goldSoft(Context c)      { return ContextCompat.getColor(c, R.color.editorial_gold_soft); }

    public static int ruleThick(Context c)     { return ContextCompat.getColor(c, R.color.editorial_rule_thick); }

    public static int ruleFaint(Context c)     { return ContextCompat.getColor(c, R.color.editorial_rule_faint); }

    public static int ruleVeryFaint(Context c) { return ContextCompat.getColor(c, R.color.editorial_rule_v_faint); }

    // ── Legacy constants ──────────────────────────────────────────────────────
    // Kept for callers that don't have a Context handy (e.g. show-color palette).
    // Avoid using these in new code; prefer the context-aware getters above.
    /**
     * @deprecated use {@link #paper(Context)}.
     */
    @Deprecated public static final int BG          = 0xFFFFFFFF;
    /**
     * @deprecated use {@link #paperAlt(Context)}.
     */
    @Deprecated public static final int PAPER       = 0xFFFBF8F1;
    /**
     * @deprecated use {@link #ink(Context)}.
     */
    @Deprecated public static final int INK         = 0xFF15110D;
    /**
     * @deprecated use {@link #inkSoft(Context)}.
     */
    @Deprecated public static final int INK_SOFT    = 0xFF3A322A;
    /**
     * @deprecated use {@link #inkMuted(Context)}.
     */
    @Deprecated public static final int INK_MUTE    = 0xFF7A6E60;
    /**
     * @deprecated use {@link #vermilion(Context)}.
     */
    @Deprecated public static final int ACCENT      = 0xFFB8442E;
    /**
     * @deprecated use {@link #vermilionSoft(Context)}.
     */
    @Deprecated public static final int ACCENT_SOFT = 0xFFE8B9A4;
    /**
     * @deprecated use {@link #vermilionTint(Context)}.
     */
    @Deprecated public static final int ACCENT_TINT = 0xFFF3D6C4;
    /**
     * @deprecated use {@link #gold(Context)}.
     */
    @Deprecated public static final int GOLD        = 0xFFA47436;
    /**
     * @deprecated use {@link #goldSoft(Context)}.
     */
    @Deprecated public static final int GOLD_SOFT   = 0xFFE1C79A;
    /**
     * @deprecated use {@link #ruleFaint(Context)}.
     */
    @Deprecated public static final int FAINT       = 0x1A15110D;
    /**
     * @deprecated use {@link #ruleVeryFaint(Context)}.
     */
    @Deprecated public static final int VERY_FAINT  = 0x0D15110D;
    /**
     * @deprecated use {@link #ruleThick(Context)}.
     */
    @Deprecated public static final int RULE        = 0xFF15110D;

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
        if (!fontsLoaded) {
            loadFonts(ctx);
        }
        return cachedSerif != null ? cachedSerif : Typeface.SERIF;
    }

    public static Typeface getMono(Context ctx) {
        if (!fontsLoaded) {
            loadFonts(ctx);
        }
        return cachedMono != null ? cachedMono : Typeface.MONOSPACE;
    }

    private static synchronized void loadFonts(Context ctx) {
        if (fontsLoaded) {
            return;
        }
        fontsLoaded = true;
        if (!FONTS_BUNDLED) {
            return;
        }

        try {
            int serifResId = ctx.getResources().getIdentifier(
                    "instrument_serif_regular", "font", ctx.getPackageName());
            if (serifResId != 0) {
                Typeface t = androidx.core.content.res.ResourcesCompat.getFont(ctx, serifResId);
                if (t != null) {
                    cachedSerif = t;
                }
            }
        } catch (Exception ignored) {
            // intentionally ignored
        }

        try {
            int monoResId = ctx.getResources().getIdentifier(
                    "ibm_plex_mono_regular", "font", ctx.getPackageName());
            if (monoResId != 0) {
                Typeface t = androidx.core.content.res.ResourcesCompat.getFont(ctx, monoResId);
                if (t != null) {
                    cachedMono = t;
                }
            }
        } catch (Exception ignored) {
            // intentionally ignored
        }
    }
}
