package de.danoeh.antennapod.event;

/**
 * Posted when the set of named playlists changes (created, renamed, deleted) or when the contents
 * of a playlist change (episodes added/removed/reordered). Screens showing playlists reload on it.
 */
public class PlaylistEvent {
    /**
     * Affected playlist id, or {@code 0} when the change concerns the whole list of playlists.
     */
    public final long playlistId;

    public PlaylistEvent(long playlistId) {
        this.playlistId = playlistId;
    }

    /** A playlist was created, renamed or deleted — reload the list of playlists. */
    public static PlaylistEvent listChanged() {
        return new PlaylistEvent(0);
    }

    /** The contents of a specific playlist changed — reload that playlist. */
    public static PlaylistEvent contentChanged(long playlistId) {
        return new PlaylistEvent(playlistId);
    }
}
