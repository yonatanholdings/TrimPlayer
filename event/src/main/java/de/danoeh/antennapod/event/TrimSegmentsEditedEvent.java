package de.danoeh.antennapod.event;

/**
 * Posted when the local segment-edit sheet writes a change to
 * {@code TrimSegmentCache} (boundary drag, relabel, or "Not a skip" removal).
 *
 * PlaybackService listens so the running episode's in-memory segment snapshot
 * is refreshed immediately — without this, an edit made mid-playback wouldn't
 * affect the auto-skip loop until the episode was reloaded.
 *
 * Carries the episode GUID so the service can ignore edits for an episode other
 * than the one currently playing.
 */
public class TrimSegmentsEditedEvent {
    public final String episodeGuid;

    public TrimSegmentsEditedEvent(String episodeGuid) {
        this.episodeGuid = episodeGuid == null ? "" : episodeGuid;
    }
}
