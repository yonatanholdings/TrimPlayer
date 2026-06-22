package de.danoeh.antennapod.playback.service.internal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Guards {@link PlaybackEndGuard#shouldCompleteAtFeedDuration(long, long, long, long)} — the
 * phantom-tail end detection.
 *
 * <p>Streaming headerless/VBR MP3s give ExoPlayer no usable duration (TIME_UNSET), so it falls back
 * to the feed value and never ends at the right place; the playhead runs past the real end into a
 * silent tail. We end at the feed duration once the playhead clearly overruns it — but only when the
 * player has no known end beyond the feed duration, so a longer real file (or an under-stated feed
 * on a downloaded episode) is left for ExoPlayer to finish.
 */
public class PlaybackEndGuardTest {

    private static final long MARGIN = 10_000L;
    private static final long FEED = 2_869_000L; // reproduced episode: itunes:duration 47:49

    @Test
    public void completesWhenPlayheadOverrunsFeedEndWithNoKnownLongerEnd() {
        // Reproduced case: streaming, player duration fell back to the feed value, playhead has run
        // ~18s past the feed end into the silent tail.
        assertTrue(PlaybackEndGuard.shouldCompleteAtFeedDuration(
                /* position= */ 2_887_637, FEED, /* player= */ FEED, MARGIN));
    }

    @Test
    public void waitsUntilOverrunPassesTheMargin() {
        // Past the feed end but within the grace margin — keep playing a moment.
        assertFalse(PlaybackEndGuard.shouldCompleteAtFeedDuration(2_873_000, FEED, FEED, MARGIN));
    }

    @Test
    public void accurateFeedThatEndsOnTimeIsLeftToExoPlayer() {
        // Player stops at the feed end (no tail): never reaches feed+margin → ExoPlayer's own
        // STATE_ENDED handles it.
        assertFalse(PlaybackEndGuard.shouldCompleteAtFeedDuration(FEED, FEED, FEED, MARGIN));
    }

    @Test
    public void playerWithLongerKnownEndIsNotCutShort() {
        // e.g. a downloaded file whose feed under-states it: the player knows the real (longer) end
        // and will fire STATE_ENDED there. We must not preempt and lose real audio.
        assertFalse(PlaybackEndGuard.shouldCompleteAtFeedDuration(
                /* position= */ 2_900_000, FEED, /* player= */ 2_950_000, MARGIN));
    }

    @Test
    public void completesWhenPlayerDurationIsUnknownAndPlayheadOverran() {
        // Defensive: player duration reported as 0/unknown (not greater than the feed) and the
        // playhead has overrun → still end at the feed duration.
        assertTrue(PlaybackEndGuard.shouldCompleteAtFeedDuration(2_900_000, FEED, 0, MARGIN));
    }

    @Test
    public void noFeedDurationMeansNoAction() {
        // Feeds without itunes:duration give us no trusted length to end at.
        assertFalse(PlaybackEndGuard.shouldCompleteAtFeedDuration(2_900_000, 0, 0, MARGIN));
        assertFalse(PlaybackEndGuard.shouldCompleteAtFeedDuration(2_900_000, -1, FEED, MARGIN));
    }

    @Test
    public void marginBoundaryIsInclusive() {
        assertTrue(PlaybackEndGuard.shouldCompleteAtFeedDuration(FEED + MARGIN, FEED, FEED, MARGIN));
        assertFalse(PlaybackEndGuard.shouldCompleteAtFeedDuration(
                FEED + MARGIN - 1, FEED, FEED, MARGIN));
    }
}
