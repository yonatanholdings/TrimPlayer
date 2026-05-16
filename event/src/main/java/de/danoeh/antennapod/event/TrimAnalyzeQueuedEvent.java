package de.danoeh.antennapod.event;

/**
 * Posted when PlaybackService queues a {@code /analyze} request because the
 * current episode has no canonical segments yet. The UI uses this to surface
 * a one-time community-framing dialog explaining what just happened.
 */
public class TrimAnalyzeQueuedEvent {
    public final String podcastTitle;

    public TrimAnalyzeQueuedEvent(String podcastTitle) {
        this.podcastTitle = podcastTitle == null ? "" : podcastTitle;
    }
}
