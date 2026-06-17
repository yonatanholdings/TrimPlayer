package de.danoeh.antennapod.playback.service.trim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Locks the auto-skip forward-only invariant: a late-arriving or out-of-order trim segment must
 * never cause a backward seek toward the intro. {@code PlaybackService}'s position observer routes
 * its skip decision through {@link TrimSkipGeometry}, so these cases guard the real behavior.
 */
public class TrimSkipGeometryTest {

    @Test
    public void lateIntroBehindPlayheadIsNotSkipped() {
        // Intro segment [0, 30s] arrives late, after the listener is already 5 minutes in.
        // It must NOT be considered skippable — that would seek back to 30s, i.e. toward the intro.
        int endMs = TrimSkipGeometry.cappedEndMs(30, 3_600_000);
        assertFalse(TrimSkipGeometry.playheadInside(300_000, 0, endMs));
    }

    @Test
    public void playheadInsideSegmentIsSkippedForward() {
        // Listener sits inside the intro [0, 30s] at 15s — skip is valid and targets endMs > pos.
        int endMs = TrimSkipGeometry.cappedEndMs(30, 3_600_000);
        assertTrue(TrimSkipGeometry.playheadInside(15_000, 0, endMs));
        assertTrue("auto-skip must be forward", endMs > 15_000);
    }

    @Test
    public void positionAtSegmentEndIsNotInside() {
        // Boundary: exactly at endMs is already out of the segment (half-open interval), no re-skip.
        assertFalse(TrimSkipGeometry.playheadInside(30_000, 0, 30_000));
    }

    @Test
    public void positionAtSegmentStartIsInside() {
        assertTrue(TrimSkipGeometry.playheadInside(0, 0, 30_000));
    }

    @Test
    public void forwardOnlyInvariantHoldsAcrossSamples() {
        // Property: whenever playheadInside is true, the seek target (endMs) is strictly ahead of pos,
        // so the skip can never move playback backward — regardless of where the segment sits.
        int[][] segments = {{0, 30_000}, {120_000, 150_000}, {3_500_000, 3_600_000}};
        for (int[] seg : segments) {
            for (int pos = 0; pos <= 3_600_000; pos += 7_000) {
                if (TrimSkipGeometry.playheadInside(pos, seg[0], seg[1])) {
                    assertTrue("skip target " + seg[1] + " must be > pos " + pos, seg[1] > pos);
                }
            }
        }
    }

    @Test
    public void endIsCappedToDurationButUncappedWhenUnknown() {
        // An outro the backend extended past the real end is capped so we never seek past duration.
        assertEquals(3_600_000, TrimSkipGeometry.cappedEndMs(3_650 /* s */, 3_600_000));
        // Duration not yet known (streaming start): no cap applied.
        assertEquals(3_650_000, TrimSkipGeometry.cappedEndMs(3_650, 0));
    }
}
