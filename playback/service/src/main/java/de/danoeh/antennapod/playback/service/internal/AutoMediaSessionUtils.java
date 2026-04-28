package de.danoeh.antennapod.playback.service.internal;

import java.util.List;
import java.util.Locale;

public final class AutoMediaSessionUtils {
    private AutoMediaSessionUtils() {}

    /**
     * Returns the next speed in the preset cycle, wrapping from last back to first.
     * If {@code current} is not in {@code presets}, returns the first preset.
     */
    public static float nextSpeedPreset(float current, List<Float> presets) {
        int pos = presets.indexOf(current);
        // pos == -1 (not a preset): get(0) is the natural fallback
        return (pos == presets.size() - 1) ? presets.get(0) : presets.get(pos + 1);
    }


    /**
     * Formats a playback speed as a compact label for Android Auto.
     * Examples: 1.0 → "1×", 1.5 → "1.5×", 0.75 → "0.75×"
     */
    public static String formatSpeedLabel(float speed) {
        if (speed == (int) speed) {
            return (int) speed + "×";
        }
        String s = String.format(Locale.US, "%.2f", speed).replaceAll("0+$", "");
        return s + "×";
    }
}
