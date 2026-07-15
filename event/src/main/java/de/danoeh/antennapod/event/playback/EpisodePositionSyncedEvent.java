package de.danoeh.antennapod.event.playback;

/**
 * Posted after an external sync (Garmin watch PortCast doc, account progress
 * pull) writes a new playback position for an episode to the database.
 *
 * <p>PlaybackService listens: if that episode is currently loaded and not
 * actively playing, it adopts the synced position. Without this, a paused
 * player keeps its stale in-memory position and silently writes it back over
 * the synced one on its next save (pause/stop/shutdown) — making the sync look
 * like it never happened.
 */
public class EpisodePositionSyncedEvent {
    /**
     * {@code FeedMedia} id of the episode whose saved position changed.
     */
    public final long mediaId;
    /**
     * The new position in milliseconds, in the episode's original timeline.
     */
    public final int positionMs;

    public EpisodePositionSyncedEvent(long mediaId, int positionMs) {
        this.mediaId = mediaId;
        this.positionMs = positionMs;
    }
}
