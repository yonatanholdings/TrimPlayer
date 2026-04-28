package de.danoeh.antennapod.playback.service.internal;

import java.util.Date;

import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.Playable;

/**
 * Provides utility methods for Playable objects.
 */
public abstract class PlayableUtils {
    /**
     * Saves the current position of this object.
     *
     * @param newPosition  new playback position in ms
     * @param timestamp  current time in ms
     */
    public static void saveCurrentPosition(Playable playable, int newPosition, long timestamp) {
        playable.setPosition(newPosition);
        playable.setLastPlayedTimeStatistics(timestamp);

        if (playable instanceof FeedMedia) {
            FeedMedia media = (FeedMedia) playable;
            media.setLastPlayedTimeHistory(new Date(timestamp));
            FeedItem item = media.getItem();
            if (item != null && item.isNew()) {
                DBWriter.markItemPlayed(FeedItem.UNPLAYED, item.getId());
            }
            // Treat startPosition == -1 as 0 (episode played from beginning, onPlaybackStart was never called).
            // Without this, fresh episodes never accumulate played_duration and are excluded from statistics.
            int effectiveStart = Math.max(media.getStartPosition(), 0);
            if (playable.getPosition() > effectiveStart) {
                media.setPlayedDuration(media.getPlayedDurationWhenStarted()
                        + playable.getPosition() - effectiveStart);
            }
            DBWriter.setFeedMediaPlaybackInformation(media);
        }
    }
}
