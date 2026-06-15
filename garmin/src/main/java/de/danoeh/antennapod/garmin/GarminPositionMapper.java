package de.danoeh.antennapod.garmin;

/**
 * Converts a playback position reported by the Garmin watch (in the rendered,
 * trimmed + speed-adjusted timeline) back to original episode time, so it can be
 * written to the app's playback position.
 *
 * <p>Inverse of the render transform (see {@link GarminRenderManifest}):
 * <pre>
 *   render:  original --(drop skipped, concat kept)--> concatenated --(/speed)--> rendered
 *   invert:  rendered --(*speed)--> concatenated --(walk kept ranges)--> original
 * </pre>
 *
 * <p>Pure Java so it is unit-testable on the JVM.
 */
public final class GarminPositionMapper {

    private GarminPositionMapper() {
    }

    /**
     * Map a rendered-timeline position to original episode seconds.
     *
     * @param renderedSeconds position in the rendered file
     * @param manifest        how the episode was rendered
     * @return the corresponding position in the original episode
     */
    public static double renderedToOriginal(double renderedSeconds, GarminRenderManifest manifest) {
        if (renderedSeconds <= 0) {
            return manifest.keptRanges.isEmpty() ? 0.0 : manifest.keptRanges.get(0).startSeconds;
        }

        // Undo the tempo change to get a position along the concatenated kept audio.
        double concatPos = renderedSeconds * manifest.speed;

        // Walk the kept ranges, accumulating their lengths, to find which original
        // range concatPos falls in and the offset within it.
        double cumulative = 0.0;
        GarminRenderManifest.Range last = null;
        for (GarminRenderManifest.Range r : manifest.keptRanges) {
            double len = r.length();
            if (concatPos < cumulative + len) {
                return r.startSeconds + (concatPos - cumulative);
            }
            cumulative += len;
            last = r;
        }

        // Past the end of the rendered audio — clamp to the end of the last kept range.
        return last != null ? last.endSeconds : 0.0;
    }

    /** Convenience overload returning whole milliseconds (what FeedMedia stores). */
    public static int renderedSecondsToOriginalMs(double renderedSeconds, GarminRenderManifest manifest) {
        return (int) Math.round(renderedToOriginal(renderedSeconds, manifest) * 1000.0);
    }
}
