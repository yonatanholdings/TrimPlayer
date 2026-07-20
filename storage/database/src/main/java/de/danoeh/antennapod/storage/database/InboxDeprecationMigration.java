package de.danoeh.antennapod.storage.database;

import android.util.Log;

import java.util.List;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedPreferences;

/**
 * One-time migration for the Inbox deprecation (playlists-only model).
 *
 * <p>Converts the legacy routing into rules and clears the legacy state:
 * <ul>
 *   <li>every subscribed feed whose effective {@code NewEpisodesAction} was
 *       ADD_TO_QUEUE gets an auto-add rule onto the default playlist (the
 *       Queue / "Up Next"), cutoff = now — same routing outcome, now synced;</li>
 *   <li>every NEW-flagged item is downgraded to UNPLAYED (the Inbox is gone;
 *       episodes stay reachable in All Episodes / the feed screen).</li>
 * </ul>
 * Idempotent by construction (re-adding a rule keeps the original cutoff and
 * the NEW sweep is a no-op the second time), but callers gate it behind a
 * one-shot preference anyway. Must run off the main thread.
 *
 * @param globalNewEpisodesActionIsAddToQueue the resolved GLOBAL default, passed
 *        in because UserPreferences lives in another module.
 */
public final class InboxDeprecationMigration {
    private static final String TAG = "InboxDeprecation";

    private InboxDeprecationMigration() {
    }

    public static void run(boolean globalNewEpisodesActionIsAddToQueue) {
        List<Feed> feeds = DBReader.getFeedList();
        long defaultPlaylistId = DBReader.getDefaultPlaylistId();
        long now = System.currentTimeMillis();
        int rules = 0;
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try {
            for (Feed feed : feeds) {
                if (feed.getState() != Feed.STATE_SUBSCRIBED || feed.getPreferences() == null) {
                    continue;
                }
                FeedPreferences.NewEpisodesAction action =
                        feed.getPreferences().getNewEpisodesAction();
                boolean effectiveAddToQueue =
                        action == FeedPreferences.NewEpisodesAction.ADD_TO_QUEUE
                        || (action == FeedPreferences.NewEpisodesAction.GLOBAL
                                && globalNewEpisodesActionIsAddToQueue);
                if (effectiveAddToQueue) {
                    adapter.addPlaylistAutoFeed(defaultPlaylistId, feed.getId(), now);
                    rules++;
                }
            }
            // The Inbox is gone: NEW items become plain unplayed episodes.
            adapter.setFeedItems(FeedItem.NEW, FeedItem.UNPLAYED);
        } finally {
            adapter.close();
        }
        Log.i(TAG, "Inbox deprecation migration: " + rules
                + " queue-routing feed(s) converted to rules; NEW flags cleared");
    }
}
