package de.danoeh.antennapod.storage.database.mapper;

import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.storage.database.PodDBAdapter;

import java.util.ArrayList;
import java.util.List;

public class FeedItemFilterQuery {
    private FeedItemFilterQuery() {
        // Must not be instantiated
    }

    /**
     * Express the filter using an SQL boolean statement that can be inserted into an SQL WHERE clause
     * to yield output filtered according to the rules of this filter.
     *
     * @return An SQL boolean statement that matches the desired items,
     *         empty string if there is nothing to filter
     */
    public static String generateFrom(FeedItemFilter filter) {
        // The keys used within this method, but explicitly combined with their table
        String keyRead = PodDBAdapter.TABLE_NAME_FEED_ITEMS + "." + PodDBAdapter.KEY_READ;
        String keyPosition = PodDBAdapter.TABLE_NAME_FEED_MEDIA + "." + PodDBAdapter.KEY_POSITION;
        String keyCompletionDate = PodDBAdapter.TABLE_NAME_FEED_MEDIA + "." + PodDBAdapter.KEY_LAST_PLAYED_TIME_HISTORY;
        String keyDownloaded = PodDBAdapter.TABLE_NAME_FEED_MEDIA + "." + PodDBAdapter.KEY_DOWNLOAD_DATE;
        String keyMediaId = PodDBAdapter.TABLE_NAME_FEED_MEDIA + "." + PodDBAdapter.KEY_ID;
        String keyItemId = PodDBAdapter.TABLE_NAME_FEED_ITEMS + "." + PodDBAdapter.KEY_ID;
        String keyFeedItem = PodDBAdapter.KEY_FEEDITEM;
        String tableFavorites = PodDBAdapter.TABLE_NAME_FAVORITES;
        // Queued-ness must be read from PlaylistItems, not the legacy Queue table.
        // Queue has been dormant since the queue/playlist unification (DBUpgrader
        // 3160000 copied it across and every write has gone to PlaylistItems
        // since), so a filter reading it sees only the frozen pre-upgrade
        // snapshot: episodes queued after the upgrade showed up under "Not
        // Queued", and episodes queued before it and since removed still counted
        // as queued. The queue IS the default playlist, so resolve its id in SQL
        // (generateFrom is static and has no PodDBAdapter to ask).
        String queuedItemIds = "SELECT " + keyFeedItem
                + " FROM " + PodDBAdapter.TABLE_NAME_PLAYLIST_ITEMS
                // PlaylistItems.feeditem has no NOT NULL constraint, and a single
                // NULL in the list would make the NOT IN below evaluate to NULL for
                // every row — i.e. "Not Queued" would silently match nothing.
                + " WHERE " + keyFeedItem + " IS NOT NULL"
                + " AND " + PodDBAdapter.KEY_PLAYLIST_ID + " = (SELECT " + PodDBAdapter.KEY_ID
                + " FROM " + PodDBAdapter.TABLE_NAME_PLAYLISTS
                + " WHERE " + PodDBAdapter.KEY_PLAYLIST_IS_DEFAULT + " = 1 LIMIT 1)";

        List<String> statements = new ArrayList<>();
        if (filter.showPlayed) {
            statements.add(keyRead + " = 1 ");
        } else if (filter.showUnplayed) {
            statements.add(" NOT " + keyRead + " = 1 "); // Match "New" items (read = -1) as well
        } else if (filter.showNew) {
            statements.add(keyRead + " = -1 ");
        }
        if (filter.showPaused) {
            statements.add(" (" + keyPosition + " NOT NULL AND " + keyPosition + " > 0 " + ") ");
        } else if (filter.showNotPaused) {
            statements.add(" (" + keyPosition + " IS NULL OR " + keyPosition + " = 0 " + ") ");
        }
        if (filter.showQueued) {
            statements.add(keyItemId + " IN (" + queuedItemIds + ") ");
        } else if (filter.showNotQueued) {
            statements.add(keyItemId + " NOT IN (" + queuedItemIds + ") ");
        }
        if (filter.showDownloaded) {
            statements.add(keyDownloaded + " > 0 ");
        } else if (filter.showNotDownloaded) {
            statements.add(keyDownloaded + " = 0 ");
        }
        if (filter.showHasMedia) {
            statements.add(keyMediaId + " NOT NULL ");
        } else if (filter.showNoMedia) {
            statements.add(keyMediaId + " IS NULL ");
        }
        if (filter.showIsFavorite) {
            statements.add(keyItemId + " IN (SELECT " + keyFeedItem + " FROM " + tableFavorites + ") ");
        } else if (filter.showNotFavorite) {
            statements.add(keyItemId + " NOT IN (SELECT " + keyFeedItem + " FROM " + tableFavorites + ") ");
        }
        if (filter.showInHistory) {
            statements.add(keyCompletionDate + " > 0 ");
        }
        if (!filter.includeNotSubscribed) {
            statements.add(PodDBAdapter.SELECT_WHERE_FEED_IS_SUBSCRIBED);
        }

        if (statements.isEmpty()) {
            return "";
        }

        StringBuilder query = new StringBuilder(" (" + statements.get(0));
        for (String r : statements.subList(1, statements.size())) {
            query.append(" AND ");
            query.append(r);
        }
        query.append(") ");
        return query.toString();
    }
}
