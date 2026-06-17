package de.danoeh.antennapod.playback.service.internal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Guards {@link ExoPlayerWrapper#flushSeekTarget(long, long)} — the speed-change "dance" flush-seek.
 *
 * <p>The dance re-reads the live position 80ms after a debounce and seeks there +1ms. If that read
 * lands during a transient state (buffering after an ad-skip seek, or an in-flight network
 * re-prepare) it can return 0, and seeking to 0+1 restarts the episode from the beginning — the same
 * failure class as the network-recovery position loss. The fallback to a known-good captured
 * position must prevent that.
 */
public class ExoPlayerWrapperFlushSeekTest {

    @Test
    public void usesLivePositionWhenValid() {
        // Normal case: live read is good, fallback ignored. +1ms nudge.
        assertEquals(120_001, ExoPlayerWrapper.flushSeekTarget(120_000, 5_000));
    }

    @Test
    public void fallsBackToCapturedPositionWhenLiveReadsZero() {
        // The bug: a transient 0 live read mid-episode must NOT seek to the beginning.
        assertEquals(120_001, ExoPlayerWrapper.flushSeekTarget(0, 120_000));
    }

    @Test
    public void fallsBackWhenLiveReadsNegative() {
        // ExoPlayer can return a negative/unset position in some idle states.
        assertEquals(120_001, ExoPlayerWrapper.flushSeekTarget(-1, 120_000));
    }

    @Test
    public void genuinelyAtStartStaysNearStart() {
        // Both zero (really at the very beginning) → nudging to 1ms is correct, not a regression.
        assertEquals(1, ExoPlayerWrapper.flushSeekTarget(0, 0));
    }
}
