package de.danoeh.antennapod.playback.service.internal;

import androidx.annotation.Nullable;

import de.danoeh.antennapod.model.feed.FeedItem;

/**
 * Chooses the episode that auto-advance plays when the current one finishes.
 *
 * <p>Order: the active named playlist, then the queue, then the next unplayed episode of the
 * same show. An episode the player reached through that last step is part of a run through
 * one show (a back catalogue played in order), so its own successor comes from the show
 * first. Without that, an episode auto-added to the end of the queue while the run was going
 * cut in, and since it was the newest of its show and the last in the queue, playback
 * stopped: How To Do Things, 2026-11-05, after a daily-news episode joined the queue behind
 * the playing episode (playing always appends it to the queue).
 */
public final class NextEpisodePicker {

    /** Database lookups the choice depends on. */
    public interface Lookup {
        boolean isInPlaylist(long playlistId, long itemId);

        @Nullable
        FeedItem nextInPlaylist(long playlistId, FeedItem item);

        @Nullable
        FeedItem nextInQueue(FeedItem item);

        @Nullable
        FeedItem nextInFeed(FeedItem item);
    }

    /** What to play next and how it was found. */
    public static final class Pick {
        @Nullable
        public final FeedItem item;
        /** True when the item continues a run through one show. */
        public final boolean fromFeed;
        /** True when the current episode left the active playlist, ending that context. */
        public final boolean leftPlaylist;

        Pick(@Nullable FeedItem item, boolean fromFeed, boolean leftPlaylist) {
            this.item = item;
            this.fromFeed = fromFeed;
            this.leftPlaylist = leftPlaylist;
        }
    }

    private NextEpisodePicker() {
    }

    /**
     * @param activePlaylistId   playlist playback is following, or {@code noPlaylist}
     * @param feedRunItemId      id of the episode last reached by continuing through its show
     */
    public static Pick pick(FeedItem current, long activePlaylistId, long noPlaylist,
                            long feedRunItemId, Lookup lookup) {
        if (current.getId() == feedRunItemId) {
            FeedItem next = playable(lookup.nextInFeed(current));
            if (next != null) {
                return new Pick(next, true, false);
            }
        }
        FeedItem next = null;
        boolean leftPlaylist = false;
        if (activePlaylistId != noPlaylist) {
            if (lookup.isInPlaylist(activePlaylistId, current.getId())) {
                next = playable(lookup.nextInPlaylist(activePlaylistId, current));
            } else {
                leftPlaylist = true;
            }
        }
        if (next == null) {
            next = playable(lookup.nextInQueue(current));
        }
        if (next != null) {
            return new Pick(next, false, leftPlaylist);
        }
        next = playable(lookup.nextInFeed(current));
        return new Pick(next, next != null, leftPlaylist);
    }

    @Nullable
    private static FeedItem playable(@Nullable FeedItem item) {
        return item == null || item.getMedia() == null ? null : item;
    }
}
