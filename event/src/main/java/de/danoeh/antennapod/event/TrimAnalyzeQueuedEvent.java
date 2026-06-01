package de.danoeh.antennapod.event;

/**
 * Posted when PlaybackService queues a {@code /analyze} request because the
 * current episode has no canonical segments yet. The UI uses this to surface
 * a brief Snackbar explaining that mapping is in progress.
 */
public class TrimAnalyzeQueuedEvent {
    public final String podcastTitle;

    public TrimAnalyzeQueuedEvent(String podcastTitle) {
        this.podcastTitle = podcastTitle == null ? "" : podcastTitle;
    }
}
