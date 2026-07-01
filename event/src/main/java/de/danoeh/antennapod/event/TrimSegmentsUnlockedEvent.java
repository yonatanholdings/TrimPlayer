package de.danoeh.antennapod.event;

/**
 * Posted when the polling-driven refetch in PlaybackService.trimStartPolling
 * receives segments for the currently-playing episode after an /analyze run
 * completes mid-session. The UI shows a brief Snackbar so the user sees the
 * payoff from the analyze they kicked off earlier.
 *
 * <p>Only fired when the refetch returns at least one segment — empty refetches
 * are silent (the analyze ran but didn't promote canonical segments matching
 * this episode).
 */
public class TrimSegmentsUnlockedEvent {
    public final String podcastTitle;

    public TrimSegmentsUnlockedEvent(String podcastTitle) {
        this.podcastTitle = podcastTitle == null ? "" : podcastTitle;
    }
}
