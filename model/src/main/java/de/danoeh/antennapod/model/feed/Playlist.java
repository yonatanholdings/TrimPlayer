package de.danoeh.antennapod.model.feed;

/**
 * A named, ordered collection of episodes that behaves like an additional queue.
 *
 * <p>TrimPlayer feature: unlike the single global {@code Queue}, the user can create any number of
 * named playlists. Playing an episode from a playlist makes that playlist the active playback
 * context, so when the episode finishes the next episode in the <em>same</em> playlist is played.
 */
public class Playlist {
    public static final long NO_PLAYLIST = 0;

    private long id;
    private String name;
    /** Number of episodes in the playlist. Transient — populated when loaded for display. */
    private final int episodeCount;

    public Playlist(long id, String name) {
        this(id, name, 0);
    }

    public Playlist(long id, String name, int episodeCount) {
        this.id = id;
        this.name = name;
        this.episodeCount = episodeCount;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getEpisodeCount() {
        return episodeCount;
    }
}
