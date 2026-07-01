package de.danoeh.antennapod.playback.service.trim;

/**
 * Pure geometry for the auto-skip position check, extracted from {@code PlaybackService}'s position
 * observer so the forward-only invariant is unit-testable on the JVM.
 *
 * <p><b>Why this exists:</b> a trim segment can arrive late (the backend lookup is async and resolves
 * mid-playback) or out of order, and arrival clears the skip de-dup so every segment is re-evaluated.
 * The safety property we must never violate is that auto-skip only ever seeks <em>forward</em> — a
 * late segment whose range is already behind the playhead (e.g. an intro the listener has passed)
 * must be ignored, never seeked back into.
 */
public final class TrimSkipGeometry {

    private TrimSkipGeometry() {
    }

    /**
     * Segment end in ms, capped to the episode duration when it is known ({@code durationMs > 0}).
     */
    public static int cappedEndMs(double segmentEndSeconds, int durationMs) {
        int endMs = (int) (segmentEndSeconds * 1000);
        if (durationMs > 0) {
            endMs = Math.min(endMs, durationMs);
        }
        return endMs;
    }

    /**
     * Whether the playhead sits inside {@code [startMs, endMs)}. Auto-skip fires only when this is
     * true and then seeks to {@code endMs}, which is strictly greater than {@code posMs} — so a skip
     * is always forward. A late or out-of-order segment already behind the playhead
     * ({@code endMs <= posMs}) returns false and is never skipped, so late segments can never seek
     * backward toward the intro.
     */
    public static boolean playheadInside(int posMs, int startMs, int endMs) {
        return posMs >= startMs && posMs < endMs;
    }
}
