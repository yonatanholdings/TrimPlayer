package de.danoeh.antennapod.garmin;

import java.util.ArrayList;
import java.util.List;

/**
 * Records how an episode was rendered for the Garmin watch, so the watch's
 * reported playback position (which is in the rendered, trimmed + speed-adjusted
 * timeline) can be mapped back to original episode time.
 *
 * <p>The render drops a set of "skipped" ranges (ads/intros/silence), concatenates
 * the kept ranges, then applies a pitch-preserving tempo change. This manifest
 * stores the kept ranges in <em>original</em> episode seconds plus the speed, which
 * is everything {@link GarminPositionMapper} needs to invert the transform.
 *
 * <p>Pure Java (no Android deps) so the mapping logic is unit-testable on the JVM.
 */
public class GarminRenderManifest {

    /** A half-open interval [startSeconds, endSeconds) of kept original audio. */
    public static class Range {
        public final double startSeconds;
        public final double endSeconds;

        public Range(double startSeconds, double endSeconds) {
            this.startSeconds = startSeconds;
            this.endSeconds = endSeconds;
        }

        public double length() {
            return Math.max(0.0, endSeconds - startSeconds);
        }
    }

    /** PortCast episode guid this render belongs to. */
    public final String guid;

    /** Pitch-preserved tempo factor applied after trimming (e.g. 1.5 = 1.5x). */
    public final double speed;

    /** Kept ranges in original episode time, in playback order. */
    public final List<Range> keptRanges;

    public GarminRenderManifest(String guid, double speed, List<Range> keptRanges) {
        this.guid = guid;
        this.speed = speed <= 0 ? 1.0 : speed;
        this.keptRanges = keptRanges != null ? keptRanges : new ArrayList<>();
    }

    /** Total kept (concatenated) length in original seconds, before the speed-up. */
    public double concatenatedLengthSeconds() {
        double total = 0.0;
        for (Range r : keptRanges) {
            total += r.length();
        }
        return total;
    }

    /** Duration of the rendered file in seconds (kept length divided by speed). */
    public double renderedDurationSeconds() {
        return concatenatedLengthSeconds() / speed;
    }

    /**
     * Build a manifest from the same inputs the renderer uses: the skipped ranges
     * (original seconds) and the source duration. The kept ranges are the
     * complement of the skipped ranges over [0, durationSeconds).
     */
    public static GarminRenderManifest fromSkipped(String guid, double speed,
                                                   List<Range> skipped, double durationSeconds) {
        List<Range> sorted = new ArrayList<>();
        if (skipped != null) {
            for (Range r : skipped) {
                if (r.endSeconds > r.startSeconds) {
                    sorted.add(new Range(Math.max(0.0, r.startSeconds),
                            Math.min(durationSeconds, r.endSeconds)));
                }
            }
        }
        sorted.sort((a, b) -> Double.compare(a.startSeconds, b.startSeconds));

        List<Range> kept = new ArrayList<>();
        double cursor = 0.0;
        for (Range r : sorted) {
            if (r.startSeconds > cursor) {
                kept.add(new Range(cursor, r.startSeconds));
            }
            cursor = Math.max(cursor, r.endSeconds);
        }
        if (cursor < durationSeconds) {
            kept.add(new Range(cursor, durationSeconds));
        }
        if (kept.isEmpty()) {
            kept.add(new Range(0.0, durationSeconds));
        }
        return new GarminRenderManifest(guid, speed, kept);
    }
}
