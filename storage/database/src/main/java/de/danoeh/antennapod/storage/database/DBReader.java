package de.danoeh.antennapod.storage.database;

import android.database.Cursor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.ArrayMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.danoeh.antennapod.model.feed.Bookmark;
import de.danoeh.antennapod.model.feed.Chapter;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedCounter;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.FeedOrder;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.model.feed.Playlist;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.model.feed.SubscriptionsFilter;
import de.danoeh.antennapod.model.download.DownloadResult;
import de.danoeh.antennapod.storage.database.mapper.ChapterCursor;
import de.danoeh.antennapod.storage.database.mapper.DownloadResultCursor;
import de.danoeh.antennapod.storage.database.mapper.FeedCursor;
import de.danoeh.antennapod.storage.database.mapper.FeedItemCursor;

/**
 * Provides methods for reading data from the AntennaPod database.
 * In general, all database calls in DBReader-methods are executed on the caller's thread.
 * This means that the caller should make sure that DBReader-methods are not executed on the GUI-thread.
 */
public final class DBReader {

    private static final String TAG = "DBReader";

    /**
     * Maximum size of the list returned by {@link #getDownloadLog()}.
     */
    private static final int DOWNLOAD_LOG_SIZE = 200;


    private DBReader() {
    }

    /**
     * Returns a list of Feeds, sorted alphabetically by their title.
     *
     * @return A list of Feeds, sorted alphabetically by their title.
     *      A Feed-object of the returned list does NOT have its list of FeedItems yet.
     *      The FeedItem-list can be loaded separately with getFeedItemList().
     */
    @NonNull
    public static List<Feed> getFeedList() {
        Log.d(TAG, "Extracting Feedlist");

        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (FeedCursor cursor = new FeedCursor(adapter.getAllFeedsCursor())) {
            List<Feed> feeds = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                feeds.add(cursor.getFeed());
            }
            return feeds;
        } finally {
            adapter.close();
        }
    }

    /**
     * Returns a list with the download URLs of all feeds.
     *
     * @return A list of Strings with the download URLs of all feeds.
     */
    public static List<String> getFeedListDownloadUrls() {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (Cursor cursor = adapter.getFeedCursorDownloadUrls()) {
            List<String> result = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                String url = cursor.getString(1);
                if (url != null && !url.startsWith(Feed.PREFIX_LOCAL_FOLDER)) {
                    result.add(url);
                }
            }
            return result;
        } finally {
            adapter.close();
        }
    }

    /**
     * Loads additional data in to the feed items from other database queries
     *
     * @param items the FeedItems who should have other data loaded
     */
    public static void loadAdditionalFeedItemListData(List<FeedItem> items) {
        loadTagsOfFeedItemList(items);
        loadFeedDataOfFeedItemList(items);
    }

    private static void loadTagsOfFeedItemList(List<FeedItem> items) {
        LongList favoriteIds = getFavoriteIDList();
        LongList queueIds = getQueueIDList();

        for (FeedItem item : items) {
            if (favoriteIds.contains(item.getId())) {
                item.addTag(FeedItem.TAG_FAVORITE);
            }
            if (queueIds.contains(item.getId())) {
                item.addTag(FeedItem.TAG_QUEUE);
            }
        }
    }

    /**
     * Takes a list of FeedItems and loads their corresponding Feed-objects from the database.
     * The feedID-attribute of a FeedItem must be set to the ID of its feed or the method will
     * not find the correct feed of an item.
     *
     * @param items The FeedItems whose Feed-objects should be loaded.
     */
    private static void loadFeedDataOfFeedItemList(List<FeedItem> items) {
        List<Feed> feeds = getFeedList();

        Map<Long, Feed> feedIndex = new ArrayMap<>(feeds.size());
        for (Feed feed : feeds) {
            feedIndex.put(feed.getId(), feed);
        }
        for (FeedItem item : items) {
            Feed feed = feedIndex.get(item.getFeedId());
            if (feed == null) {
                Log.w(TAG, "No match found for item with ID " + item.getId() + ". Feed ID was " + item.getFeedId());
                feed = new Feed("", "", "Error: Item without feed");
            }
            item.setFeed(feed);
        }
    }

    /**
     * Loads the list of FeedItems for a certain Feed-object.
     * This method should NOT be used if the FeedItems are not used.
     *
     * @param feed The Feed whose items should be loaded
     * @return A list with the FeedItems of the Feed. The Feed-attribute of the FeedItems will already be set correctly.
     */
    public static List<FeedItem> getFeedItemList(final Feed feed, final FeedItemFilter filter, SortOrder sortOrder,
                                                 int offset, int limit) {
        Log.d(TAG, "getFeedItemList() called with: " + "feed = [" + feed + "]");

        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (FeedItemCursor cursor = new FeedItemCursor(adapter.getItemsOfFeedCursor(
                feed, filter, sortOrder, offset, limit))) {
            List<FeedItem> items = extractItemlistFromCursor(cursor);
            feed.setItems(items);
            for (FeedItem item : items) {
                item.setFeed(feed);
            }
            return items;
        } finally {
            adapter.close();
        }
    }

    @NonNull
    private static List<FeedItem> extractItemlistFromCursor(FeedItemCursor cursor) {
        List<FeedItem> result = new ArrayList<>(cursor.getCount());
        while (cursor.moveToNext()) {
            result.add(cursor.getFeedItem());
        }
        return result;
    }

    /**
     * Loads the IDs of the FeedItems in the queue. This method should be preferred over
     * {@link #getQueue()} if the FeedItems of the queue are not needed.
     *
     * @return A list of IDs sorted by the same order as the queue.
     */
    public static LongList getQueueIDList() {
        Log.d(TAG, "getQueueIDList() called");
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (Cursor cursor = adapter.getQueueIDCursor()) {
            LongList queueIds = new LongList(cursor.getCount());
            while (cursor.moveToNext()) {
                queueIds.add(cursor.getLong(0));
            }
            return queueIds;
        } finally {
            adapter.close();
        }
    }

    /**
     * Gets the remaining queue size, given a current item, including the current item.
     * If the current item is not found it will return 0.
     */
    public static int getRemainingQueueSize(long existingId) {
        final LongList wholeQueue = getQueueIDList();

        // now try to find the id
        for (int i = 0; i < wholeQueue.size(); ++i) {
            if (wholeQueue.get(i) == existingId) {
                return wholeQueue.size() - i; // return however many are left, including us
            }
        }

        return 0;
    }

    /**
     * Loads a list of the FeedItems in the queue. If the FeedItems of the queue are not used directly, consider using
     * {@link #getQueueIDList()} instead.
     *
     * @return A list of FeedItems sorted by the same order as the queue.
     */
    @NonNull
    public static List<FeedItem> getQueue() {
        Log.d(TAG, "getQueue() called");

        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (FeedItemCursor cursor = new FeedItemCursor(adapter.getQueueCursor())) {
            List<FeedItem> items = extractItemlistFromCursor(cursor);
            loadAdditionalFeedItemListData(items);
            return items;
        } finally {
            adapter.close();
        }
    }

    private static LongList getFavoriteIDList() {
        Log.d(TAG, "getFavoriteIDList() called");

        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (Cursor cursor = adapter.getFavoritesIdsCursor()) {
            LongList favoriteIDs = new LongList(cursor.getCount());
            while (cursor.moveToNext()) {
                favoriteIDs.add(cursor.getLong(0));
            }
            return favoriteIDs;
        } finally {
            adapter.close();
        }
    }

    /**
     *
     * @param offset The first episode that should be loaded.
     * @param limit The maximum number of episodes that should be loaded.
     * @param filter The filter describing which episodes to filter out.
     */
    @NonNull
    public static List<FeedItem> getEpisodes(int offset, int limit, FeedItemFilter filter, SortOrder sortOrder) {
        Log.d(TAG, "getRecentlyPublishedEpisodes() called with: offset=" + offset + ", limit=" + limit);
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (FeedItemCursor cursor = new FeedItemCursor(adapter.getEpisodesCursor(offset, limit, filter, sortOrder))) {
            List<FeedItem> items = extractItemlistFromCursor(cursor);
            loadAdditionalFeedItemListData(items);
            return items;
        } finally {
            adapter.close();
        }
    }

    public static int getTotalEpisodeCount(FeedItemFilter filter) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (Cursor cursor = adapter.getEpisodeCountCursor(filter)) {
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
            return -1;
        } finally {
            adapter.close();
        }
    }

    public static int getFeedEpisodeCount(long feedId, FeedItemFilter filter) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (Cursor cursor = adapter.getFeedEpisodeCountCursor(feedId, filter)) {
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
            return -1;
        } finally {
            adapter.close();
        }
    }

    public static List<FeedItem> getRandomEpisodes(int limit, int seed) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (FeedItemCursor cursor = new FeedItemCursor(adapter.getRandomEpisodesCursor(limit, seed))) {
            List<FeedItem> items = extractItemlistFromCursor(cursor);
            loadAdditionalFeedItemListData(items);
            return items;
        } finally {
            adapter.close();
        }
    }

    /**
     * Loads the download log from the database.
     *
     * @return A list with DownloadStatus objects that represent the download log.
     * The size of the returned list is limited by {@link #DOWNLOAD_LOG_SIZE}.
     */
    public static List<DownloadResult> getDownloadLog() {
        Log.d(TAG, "getDownloadLog() called");

        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (DownloadResultCursor cursor = new DownloadResultCursor(adapter.getDownloadLogCursor(DOWNLOAD_LOG_SIZE))) {
            List<DownloadResult> downloadLog = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                downloadLog.add(cursor.getDownloadResult());
            }
            return downloadLog;
        } finally {
            adapter.close();
        }
    }

    /**
     * Loads the download log for a particular feed from the database.
     *
     * @param feedId Feed id for which the download log is loaded
     * @return A list with DownloadStatus objects that represent the feed's download log,
     * newest events first.
     */
    public static List<DownloadResult> getFeedDownloadLog(long feedId, long limit) {
        Log.d(TAG, "getFeedDownloadLog() called with: " + "feed = [" + feedId + "]");

        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (DownloadResultCursor cursor = new DownloadResultCursor(
                adapter.getDownloadLog(Feed.FEEDFILETYPE_FEED, feedId, limit))) {
            List<DownloadResult> downloadLog = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                downloadLog.add(cursor.getDownloadResult());
            }
            return downloadLog;
        } finally {
            adapter.close();
        }
    }

    /**
     * Loads a specific Feed from the database.
     *
     * @param feedId The ID of the Feed
     * @param filtered <code>true</code> if only the visible items should be loaded according to the feed filter.
     * @return The Feed or null if the Feed could not be found. The Feeds FeedItems will also be loaded from the
     *         database and the items-attribute will be set correctly.
     */
    @Nullable
    public static Feed getFeed(final long feedId, boolean filtered, int offset, int limit) {
        Log.d(TAG, "getFeed() called with: " + "feedId = [" + feedId + "]");
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        Feed feed = null;
        try (FeedCursor cursor = new FeedCursor(adapter.getFeedCursor(feedId))) {
            if (cursor.moveToNext()) {
                feed = cursor.getFeed();
                FeedItemFilter filter = (filtered && feed.getItemFilter() != null)
                        ? feed.getItemFilter() : FeedItemFilter.unfiltered();
                filter = new FeedItemFilter(filter, FeedItemFilter.INCLUDE_NOT_SUBSCRIBED);
                List<FeedItem> items = getFeedItemList(feed, filter, feed.getSortOrder(), offset, limit);
                for (FeedItem item : items) {
                    item.setFeed(feed);
                }
                loadTagsOfFeedItemList(items);
                feed.setItems(items);
            } else {
                Log.e(TAG, "getFeed could not find feed with id " + feedId);
            }
            return feed;
        } finally {
            adapter.close();
        }
    }

    /**
     * Loads a specific FeedItem from the database. This method should not be used for loading more
     * than one FeedItem because this method might query the database several times for each item.
     *
     * @param itemId The ID of the FeedItem
     * @return The FeedItem or null if the FeedItem could not be found.
     */
    @Nullable
    public static FeedItem getFeedItem(final long itemId) {
        Log.d(TAG, "getFeedItem() called with: " + "itemId = [" + itemId + "]");

        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (FeedItemCursor cursor = new FeedItemCursor(adapter.getFeedItemCursor(Long.toString(itemId)))) {
            List<FeedItem> list = extractItemlistFromCursor(cursor);
            if (!list.isEmpty()) {
                FeedItem item = list.get(0);
                loadAdditionalFeedItemListData(list);
                return item;
            }
        } finally {
            adapter.close();
        }
        return null;
    }

    /**
     * Get next feed item in queue following a particular feeditem
     *
     * @param item The FeedItem
     * @return The FeedItem next in queue or null if the FeedItem could not be found.
     */
    @Nullable
    public static FeedItem getNextInQueue(FeedItem item) {
        Log.d(TAG, "getNextInQueue() called with: " + "itemId = [" + item.getId() + "]");
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (FeedItemCursor cursor = new FeedItemCursor(adapter.getNextInQueue(item))) {
            List<FeedItem> list = extractItemlistFromCursor(cursor);
            if (!list.isEmpty()) {
                FeedItem nextItem = list.get(0);
                loadAdditionalFeedItemListData(list);
                return nextItem;
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            adapter.close();
        }
    }

    public static FeedItem getNextInFeed(FeedItem item) {
        Log.d(TAG, "getNextInFeed() called with: itemId = [" + item.getId() + "]");
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (FeedItemCursor cursor = new FeedItemCursor(adapter.getNextInFeed(item))) {
            List<FeedItem> list = extractItemlistFromCursor(cursor);
            if (!list.isEmpty()) {
                FeedItem nextItem = list.get(0);
                loadAdditionalFeedItemListData(list);
                return nextItem;
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            adapter.close();
        }
    }

    // ── Named playlists (TrimPlayer) ──────────────────────────────────────────

    /**
     * Loads all playlists with their current episode counts and total durations,
     * ordered by name.
     */
    @NonNull
    public static List<Playlist> getPlaylists() {
        Log.d(TAG, "getPlaylists() called");
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        List<Playlist> playlists = new ArrayList<>();
        try (Cursor cursor = adapter.getPlaylistsCursor()) {
            while (cursor.moveToNext()) {
                playlists.add(new Playlist(cursor.getLong(0), cursor.getString(1),
                        cursor.getInt(2), cursor.getLong(3)));
            }
            return playlists;
        } finally {
            adapter.close();
        }
    }

    /**
     * Like {@link #getPlaylists()}, but each playlist also carries up to 4 cover-art
     * urls for its card collage. One extra tiny query per playlist — fine for the
     * handful of playlists a user has.
     */
    @NonNull
    public static List<Playlist> getPlaylistsWithCovers() {
        List<Playlist> playlists = getPlaylists();
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try {
            for (Playlist playlist : playlists) {
                List<String> covers = new ArrayList<>();
                try (Cursor cursor = adapter.getPlaylistCoverUrlsCursor(playlist.getId(), 4)) {
                    while (cursor.moveToNext()) {
                        if (cursor.getString(0) != null) {
                            covers.add(cursor.getString(0));
                        }
                    }
                }
                playlist.setCoverUrls(covers);
            }
            return playlists;
        } finally {
            adapter.close();
        }
    }

    /**
     * Ids of every playlist containing the given episode (drives the checkmarks in
     * the add-to-playlist sheet).
     */
    @NonNull
    public static java.util.Set<Long> getPlaylistIdsForItem(long itemId) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        java.util.Set<Long> ids = new java.util.HashSet<>();
        try (Cursor cursor = adapter.getPlaylistIdsForItemCursor(itemId)) {
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0));
            }
            return ids;
        } finally {
            adapter.close();
        }
    }

    /**
     * Loads the episodes of a playlist, in playlist order.
     */
    @NonNull
    public static List<FeedItem> getPlaylistItems(long playlistId) {
        Log.d(TAG, "getPlaylistItems() called with: playlistId = [" + playlistId + "]");
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (FeedItemCursor cursor = new FeedItemCursor(adapter.getPlaylistItemsCursor(playlistId))) {
            List<FeedItem> items = extractItemlistFromCursor(cursor);
            loadAdditionalFeedItemListData(items);
            return items;
        } finally {
            adapter.close();
        }
    }

    /**
     * Returns the next episode after {@code item} in the given playlist, or null when
     * {@code item} is the last episode or is not part of the playlist.
     */
    @Nullable
    public static FeedItem getNextInPlaylist(long playlistId, FeedItem item) {
        Log.d(TAG, "getNextInPlaylist() called with: playlistId = [" + playlistId
                + "], itemId = [" + item.getId() + "]");
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (FeedItemCursor cursor = new FeedItemCursor(adapter.getNextInPlaylist(playlistId, item))) {
            List<FeedItem> list = extractItemlistFromCursor(cursor);
            if (!list.isEmpty()) {
                FeedItem nextItem = list.get(0);
                loadAdditionalFeedItemListData(list);
                return nextItem;
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            adapter.close();
        }
    }

    /** Feed ids whose new episodes auto-add to the given playlist. */
    @NonNull
    public static java.util.Set<Long> getPlaylistAutoFeedIds(long playlistId) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        java.util.Set<Long> ids = new java.util.HashSet<>();
        try (Cursor cursor = adapter.getPlaylistAutoFeedsCursor(playlistId)) {
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0));
            }
            return ids;
        } finally {
            adapter.close();
        }
    }

    /** Auto-add rules watching a feed: playlist_id -> rule creation time (cutoff). */
    @NonNull
    public static Map<Long, Long> getAutoRulesForFeed(long feedId) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        Map<Long, Long> rules = new HashMap<>();
        try (Cursor cursor = adapter.getAutoRulesForFeedCursor(feedId)) {
            while (cursor.moveToNext()) {
                rules.put(cursor.getLong(0), cursor.getLong(1));
            }
            return rules;
        } finally {
            adapter.close();
        }
    }

    /** Every auto-add rule as playlist_id -> (feed id -> rule created_at). */
    @NonNull
    public static Map<Long, Map<Long, Long>> getAllPlaylistAutoFeeds() {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        Map<Long, Map<Long, Long>> out = new HashMap<>();
        try (Cursor cursor = adapter.getAllPlaylistAutoFeedsCursor()) {
            while (cursor.moveToNext()) {
                long playlistId = cursor.getLong(0);
                Map<Long, Long> feeds = out.get(playlistId);
                if (feeds == null) {
                    feeds = new HashMap<>();
                    out.put(playlistId, feeds);
                }
                feeds.put(cursor.getLong(1), cursor.getLong(2));
            }
            return out;
        } finally {
            adapter.close();
        }
    }

    public static boolean isItemInPlaylist(long playlistId, long itemId) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try {
            return adapter.isItemInPlaylist(playlistId, itemId);
        } finally {
            adapter.close();
        }
    }

    @NonNull
    public static List<FeedItem> getPausedQueue(int limit) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (FeedItemCursor cursor = new FeedItemCursor(adapter.getPausedQueueCursor(limit))) {
            List<FeedItem> items = extractItemlistFromCursor(cursor);
            loadAdditionalFeedItemListData(items);
            return items;
        } finally {
            adapter.close();
        }
    }

    /**
     * Loads a specific FeedItem from the database.
     *
     * @param guid feed item guid
     * @param episodeUrl the feed item's url
     * @return The FeedItem or null if the FeedItem could not be found.
     *          Does NOT load additional attributes like feed or queue state.
     */
    public static FeedItem getFeedItemByGuidOrEpisodeUrl(final String guid, final String episodeUrl) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (FeedItemCursor cursor = new FeedItemCursor(adapter.getFeedItemCursor(guid, episodeUrl))) {
            List<FeedItem> list = extractItemlistFromCursor(cursor);
            if (!list.isEmpty()) {
                return list.get(0);
            }
            return null;
        } finally {
            adapter.close();
        }
    }

    /**
     * Loads shownotes information about a FeedItem.
     *
     * @param item The FeedItem
     */
    public static void loadDescriptionOfFeedItem(final FeedItem item) {
        Log.d(TAG, "loadDescriptionOfFeedItem() called with: " + "item = [" + item + "]");
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (Cursor cursor = adapter.getDescriptionOfItem(item)) {
            if (cursor.moveToFirst()) {
                int indexDescription = cursor.getColumnIndex(PodDBAdapter.KEY_DESCRIPTION);
                String description = cursor.getString(indexDescription);
                item.setDescriptionIfLonger(description);
            }
        } finally {
            adapter.close();
        }
    }

    /**
     * Loads the list of chapters that belongs to this FeedItem if available. This method overwrites
     * any chapters that this FeedItem has. If no chapters were found in the database, the chapters
     * reference of the FeedItem will be set to null.
     *
     * @param item The FeedItem
     */
    public static List<Chapter> loadChaptersOfFeedItem(final FeedItem item) {
        Log.d(TAG, "loadChaptersOfFeedItem() called with: " + "item = [" + item + "]");

        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (ChapterCursor cursor = new ChapterCursor(adapter.getSimpleChaptersOfFeedItemCursor(item))) {
            int chaptersCount = cursor.getCount();
            if (chaptersCount == 0) {
                item.setChapters(null);
                return null;
            }
            ArrayList<Chapter> chapters = new ArrayList<>();
            while (cursor.moveToNext()) {
                chapters.add(cursor.getChapter());
            }
            return chapters;
        } finally {
            adapter.close();
        }
    }

    /**
     * Searches the DB for a FeedMedia of the given id.
     *
     * @param mediaId The id of the object
     * @return The found object, or null if it does not exist
     */
    @Nullable
    public static FeedMedia getFeedMedia(final long mediaId) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();

        try (FeedItemCursor itemCursor = new FeedItemCursor(adapter.getFeedItemFromMediaIdCursor(mediaId))) {
            if (!itemCursor.moveToFirst()) {
                return null;
            }
            FeedItem item = itemCursor.getFeedItem();
            loadAdditionalFeedItemListData(Collections.singletonList(item));
            return item.getMedia();
        } finally {
            adapter.close();
        }
    }

    public static List<FeedItem> getFeedItemsWithUrl(List<String> urls) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (FeedItemCursor itemCursor = new FeedItemCursor(adapter.getFeedItemCursorByUrl(urls))) {
            List<FeedItem> items = extractItemlistFromCursor(itemCursor);
            loadAdditionalFeedItemListData(items);
            return items;
        } finally {
            adapter.close();
        }
    }

    public static class MonthlyStatisticsItem {
        private int year = 0;
        private int month = 0;
        private long timePlayed = 0;

        public int getYear() {
            return year;
        }

        public void setYear(final int year) {
            this.year = year;
        }

        public int getMonth() {
            return month;
        }

        public void setMonth(final int month) {
            this.month = month;
        }

        public long getTimePlayed() {
            return timePlayed;
        }

        public void setTimePlayed(final long timePlayed) {
            this.timePlayed = timePlayed;
        }
    }

    @NonNull
    public static List<MonthlyStatisticsItem> getMonthlyTimeStatistics() {
        List<MonthlyStatisticsItem> months = new ArrayList<>();
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (Cursor cursor = adapter.getMonthlyStatisticsCursor()) {
            int indexMonth = cursor.getColumnIndexOrThrow("month");
            int indexYear = cursor.getColumnIndexOrThrow("year");
            int indexTotalDuration = cursor.getColumnIndexOrThrow("total_duration");
            while (cursor.moveToNext()) {
                MonthlyStatisticsItem item = new MonthlyStatisticsItem();
                item.setMonth(Integer.parseInt(cursor.getString(indexMonth)));
                item.setYear(Integer.parseInt(cursor.getString(indexYear)));
                item.setTimePlayed(cursor.getLong(indexTotalDuration));
                months.add(item);
            }
        }
        adapter.close();
        return months;
    }

    public static class StatisticsResult {
        public List<StatisticsItem> feedTime = new ArrayList<>();
        public long oldestDate = System.currentTimeMillis();
    }

    public static class InsightPeriod {
        public final String label;
        public final long playedMs;
        public final long savedMs;

        InsightPeriod(String label, long playedMs, long savedMs) {
            this.label = label;
            this.playedMs = playedMs;
            this.savedMs = savedMs;
        }
    }

    public static List<InsightPeriod> getInsightsData() {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        List<InsightPeriod> result = new ArrayList<>();

        java.util.Calendar cal = java.util.Calendar.getInstance();
        long now = System.currentTimeMillis();

        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long todayStart = cal.getTimeInMillis();
        long yesterdayStart = todayStart - 86400000L;

        cal.set(java.util.Calendar.DAY_OF_WEEK, cal.getFirstDayOfWeek());
        if (cal.getTimeInMillis() > todayStart) {
            cal.add(java.util.Calendar.WEEK_OF_YEAR, -1);
        }
        long thisWeekStart = cal.getTimeInMillis();
        long lastWeekStart = thisWeekStart - 7 * 86400000L;

        cal.setTimeInMillis(todayStart);
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1);
        long thisMonthStart = cal.getTimeInMillis();
        int thisMonthIdx = cal.get(java.util.Calendar.MONTH);
        int currentYear = cal.get(java.util.Calendar.YEAR);
        cal.add(java.util.Calendar.MONTH, -1);
        long lastMonthStart = cal.getTimeInMillis();
        int lastMonthIdx = cal.get(java.util.Calendar.MONTH);
        int lastMonthYear = cal.get(java.util.Calendar.YEAR);

        cal.setTimeInMillis(todayStart);
        cal.set(java.util.Calendar.DAY_OF_YEAR, 1);
        long thisYearStart = cal.getTimeInMillis();

        String[] months = new java.text.DateFormatSymbols().getMonths();

        result.add(new InsightPeriod("Today",
                adapter.getPlayedTimePeriodMs(todayStart, Long.MAX_VALUE),
                adapter.getSkipTotalPeriodMs(todayStart, Long.MAX_VALUE)));
        result.add(new InsightPeriod("Yesterday",
                adapter.getPlayedTimePeriodMs(yesterdayStart, todayStart),
                adapter.getSkipTotalPeriodMs(yesterdayStart, todayStart)));
        result.add(new InsightPeriod("This week",
                adapter.getPlayedTimePeriodMs(thisWeekStart, Long.MAX_VALUE),
                adapter.getSkipTotalPeriodMs(thisWeekStart, Long.MAX_VALUE)));
        result.add(new InsightPeriod("Last week",
                adapter.getPlayedTimePeriodMs(lastWeekStart, thisWeekStart),
                adapter.getSkipTotalPeriodMs(lastWeekStart, thisWeekStart)));
        result.add(new InsightPeriod(months[thisMonthIdx],
                adapter.getPlayedTimePeriodMs(thisMonthStart, Long.MAX_VALUE),
                adapter.getSkipTotalPeriodMs(thisMonthStart, Long.MAX_VALUE)));
        String lastMonthLabel = lastMonthYear != currentYear
                ? months[lastMonthIdx] + " " + lastMonthYear : months[lastMonthIdx];
        result.add(new InsightPeriod(lastMonthLabel,
                adapter.getPlayedTimePeriodMs(lastMonthStart, thisMonthStart),
                adapter.getSkipTotalPeriodMs(lastMonthStart, thisMonthStart)));
        result.add(new InsightPeriod(String.valueOf(currentYear),
                adapter.getPlayedTimePeriodMs(thisYearStart, Long.MAX_VALUE),
                adapter.getSkipTotalPeriodMs(thisYearStart, Long.MAX_VALUE)));

        // Historical years — merge played and saved by year, exclude current year
        Map<Integer, long[]> yearMap = new HashMap<>();
        try (Cursor c = adapter.getYearlyPlayedCursor()) {
            int idxYear = c.getColumnIndexOrThrow("year");
            int idxPlayed = c.getColumnIndexOrThrow("played_ms");
            while (c.moveToNext()) {
                int year = c.getInt(idxYear);
                if (year != currentYear) {
                    long[] row = yearMap.get(year);
                    if (row == null) { row = new long[2]; yearMap.put(year, row); }
                    row[0] = c.getLong(idxPlayed);
                }
            }
        }
        try (Cursor c = adapter.getYearlySkipCursor()) {
            int idxYear = c.getColumnIndexOrThrow("year");
            int idxSaved = c.getColumnIndexOrThrow("saved_ms");
            while (c.moveToNext()) {
                int year = c.getInt(idxYear);
                if (year != currentYear) {
                    long[] row = yearMap.get(year);
                    if (row == null) { row = new long[2]; yearMap.put(year, row); }
                    row[1] = c.getLong(idxSaved);
                }
            }
        }
        List<Integer> years = new ArrayList<>(yearMap.keySet());
        Collections.sort(years, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                return Integer.compare(b, a);
            }
        });
        for (int year : years) {
            long[] row = yearMap.get(year);
            result.add(new InsightPeriod(String.valueOf(year), row[0], row[1]));
        }

        adapter.close();
        return result;
    }

    public static class MonthlySkipItem {
        public int year;
        public int month;
        public long totalMs;
    }

    /** One skip event prepared for upload to the backend /events endpoint. */
    public static class SkipEventToUpload {
        public long id;
        public String skipType;
        public int durationMs;
        public long timestampMs;
        public String episodeGuid;   // may be null if FeedItem was deleted
        public String episodeUrl;    // may be null
        public String rssUrl;        // may be null
    }

    @NonNull
    public static List<SkipEventToUpload> getSkipEventsToUpload(long minId, int limit) {
        List<SkipEventToUpload> result = new ArrayList<>();
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (Cursor c = adapter.getSkipEventsToUploadCursor(minId, limit)) {
            int idxId       = c.getColumnIndexOrThrow(PodDBAdapter.KEY_ID);
            int idxType     = c.getColumnIndexOrThrow(PodDBAdapter.KEY_SKIP_TYPE);
            int idxDuration = c.getColumnIndexOrThrow(PodDBAdapter.KEY_SKIP_DURATION_MS);
            int idxTs       = c.getColumnIndexOrThrow(PodDBAdapter.KEY_SKIP_TIMESTAMP);
            int idxGuid     = c.getColumnIndex("guid");
            int idxEpUrl    = c.getColumnIndex("episode_url");
            int idxRssUrl   = c.getColumnIndex("rss_url");
            while (c.moveToNext()) {
                SkipEventToUpload e = new SkipEventToUpload();
                e.id          = c.getLong(idxId);
                e.skipType    = c.getString(idxType);
                e.durationMs  = c.getInt(idxDuration);
                e.timestampMs = c.getLong(idxTs);
                e.episodeGuid = idxGuid >= 0 ? c.getString(idxGuid) : null;
                e.episodeUrl  = idxEpUrl >= 0 ? c.getString(idxEpUrl) : null;
                e.rssUrl      = idxRssUrl >= 0 ? c.getString(idxRssUrl) : null;
                result.add(e);
            }
        } finally {
            adapter.close();
        }
        return result;
    }

    /** Bookmarks of one episode, ordered by playback position. */
    @NonNull
    public static List<Bookmark> getBookmarks(long feedItemId) {
        List<Bookmark> result = new ArrayList<>();
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (Cursor c = adapter.getBookmarksCursor(feedItemId)) {
            result.addAll(extractBookmarksFromCursor(c));
        } finally {
            adapter.close();
        }
        return result;
    }

    private static List<Bookmark> extractBookmarksFromCursor(Cursor c) {
        List<Bookmark> result = new ArrayList<>();
        int idxId = c.getColumnIndexOrThrow(PodDBAdapter.KEY_ID);
        int idxItem = c.getColumnIndexOrThrow(PodDBAdapter.KEY_FEEDITEM);
        int idxPosition = c.getColumnIndexOrThrow(PodDBAdapter.KEY_POSITION);
        int idxNote = c.getColumnIndexOrThrow(PodDBAdapter.KEY_BOOKMARK_NOTE);
        int idxCreated = c.getColumnIndexOrThrow(PodDBAdapter.KEY_BOOKMARK_CREATED_AT);
        int idxSyncId = c.getColumnIndexOrThrow(PodDBAdapter.KEY_BOOKMARK_SYNC_ID);
        while (c.moveToNext()) {
            result.add(new Bookmark(c.getLong(idxId), c.getLong(idxItem),
                    c.getInt(idxPosition), c.getString(idxNote), c.getLong(idxCreated),
                    c.getString(idxSyncId)));
        }
        return result;
    }

    /** All bookmarks across episodes, newest first (without loading episodes). */
    @NonNull
    public static List<Bookmark> getAllBookmarks() {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (Cursor c = adapter.getAllBookmarksCursor()) {
            return extractBookmarksFromCursor(c);
        } finally {
            adapter.close();
        }
    }

    /** A bookmark together with its (fully loaded) episode, for the global bookmarks list. */
    public static class BookmarkWithItem {
        public final Bookmark bookmark;
        public final FeedItem item;

        public BookmarkWithItem(Bookmark bookmark, FeedItem item) {
            this.bookmark = bookmark;
            this.item = item;
        }
    }

    /**
     * All bookmarks across episodes, newest first, each paired with its episode.
     * Bookmarks whose episode no longer exists are skipped.
     */
    @NonNull
    public static List<BookmarkWithItem> getAllBookmarksWithItems() {
        List<Bookmark> bookmarks;
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (Cursor c = adapter.getAllBookmarksCursor()) {
            bookmarks = extractBookmarksFromCursor(c);
        } finally {
            adapter.close();
        }
        if (bookmarks.isEmpty()) {
            return new ArrayList<>();
        }

        Set<String> itemIdSet = new HashSet<>();
        for (Bookmark bookmark : bookmarks) {
            itemIdSet.add(String.valueOf(bookmark.getFeedItemId()));
        }
        List<String> itemIds = new ArrayList<>(itemIdSet);
        Map<Long, FeedItem> itemsById = new HashMap<>();
        adapter.open();
        try {
            // getFeedItemCursor rejects more than 800 IN-operator arguments
            for (int start = 0; start < itemIds.size(); start += 800) {
                List<String> chunk = itemIds.subList(start, Math.min(itemIds.size(), start + 800));
                try (FeedItemCursor cursor = new FeedItemCursor(
                        adapter.getFeedItemCursor(chunk.toArray(new String[0])))) {
                    List<FeedItem> items = extractItemlistFromCursor(cursor);
                    loadAdditionalFeedItemListData(items);
                    for (FeedItem item : items) {
                        itemsById.put(item.getId(), item);
                    }
                }
            }
        } finally {
            adapter.close();
        }

        List<BookmarkWithItem> result = new ArrayList<>();
        for (Bookmark bookmark : bookmarks) {
            FeedItem item = itemsById.get(bookmark.getFeedItemId());
            if (item != null) {
                result.add(new BookmarkWithItem(bookmark, item));
            }
        }
        return result;
    }

    public static class SkipStatistics {
        public long totalMs;
        public long introMs;
        public long outroMs;
        public long adMs;
        public long silenceMs;
        public long speedMs;
        public long todayMs;
        public long weekMs;
        public long monthMs;
        public List<MonthlySkipItem> monthly = new ArrayList<>();
    }

    @NonNull
    public static SkipStatistics getSkipStatistics() {
        SkipStatistics result = new SkipStatistics();
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();

        // Period boundaries
        long now = System.currentTimeMillis();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long startOfDay = cal.getTimeInMillis();
        long startOfWeek = startOfDay - (cal.get(java.util.Calendar.DAY_OF_WEEK) - cal.getFirstDayOfWeek()) * 86400000L;
        if (startOfWeek > startOfDay) {
            startOfWeek -= 7 * 86400000L;
        }
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1);
        long startOfMonth = cal.getTimeInMillis();

        // All-time totals by type
        try (Cursor c = adapter.getSkipEventStatsCursor(0, now)) {
            int idxType = c.getColumnIndexOrThrow(PodDBAdapter.KEY_SKIP_TYPE);
            int idxTotal = c.getColumnIndexOrThrow("total_ms");
            while (c.moveToNext()) {
                String type = c.getString(idxType);
                long ms = c.getLong(idxTotal);
                result.totalMs += ms;
                switch (type) {
                    case "intro":    result.introMs   = ms; break;
                    case "outro":    result.outroMs   = ms; break;
                    case "ad":       result.adMs      = ms; break;
                    case "silence":  result.silenceMs = ms; break;
                    case "speed":    result.speedMs   = ms; break;
                }
            }
        }

        // Period totals (today/week/month) — single aggregation query each
        result.todayMs  = sumSkipPeriod(adapter, startOfDay, now);
        result.weekMs   = sumSkipPeriod(adapter, startOfWeek, now);
        result.monthMs  = sumSkipPeriod(adapter, startOfMonth, now);

        // Monthly history
        try (Cursor c = adapter.getSkipEventsMonthlyCursor()) {
            int idxYear  = c.getColumnIndexOrThrow("year");
            int idxMonth = c.getColumnIndexOrThrow("month");
            int idxMs    = c.getColumnIndexOrThrow("total_ms");
            while (c.moveToNext()) {
                MonthlySkipItem item = new MonthlySkipItem();
                item.year    = c.getInt(idxYear);
                item.month   = c.getInt(idxMonth);
                item.totalMs = c.getLong(idxMs);
                result.monthly.add(item);
            }
        }

        adapter.close();
        return result;
    }

    private static long sumSkipPeriod(PodDBAdapter adapter, long from, long to) {
        try (Cursor c = adapter.getSkipEventStatsCursor(from, to)) {
            int idxTotal = c.getColumnIndexOrThrow("total_ms");
            long sum = 0;
            while (c.moveToNext()) {
                sum += c.getLong(idxTotal);
            }
            return sum;
        }
    }

    /** Per-type skip-event totals for an arbitrary time window. Subset of
     *  {@link SkipStatistics} — drops today/week/month + monthly history — for
     *  cheaper drill-down queries (e.g. "show me 2024 only"). */
    public static class SkipBreakdown {
        public long totalMs;
        public long introMs;
        public long outroMs;
        public long adMs;
        public long silenceMs;
        public long speedMs;
    }

    @NonNull
    public static SkipBreakdown getSkipBreakdown(long from, long to) {
        SkipBreakdown r = new SkipBreakdown();
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (Cursor c = adapter.getSkipEventStatsCursor(from, to)) {
            int idxType = c.getColumnIndexOrThrow(PodDBAdapter.KEY_SKIP_TYPE);
            int idxTotal = c.getColumnIndexOrThrow("total_ms");
            while (c.moveToNext()) {
                String type = c.getString(idxType);
                long ms = c.getLong(idxTotal);
                r.totalMs += ms;
                switch (type) {
                    case "intro":   r.introMs   = ms; break;
                    case "outro":   r.outroMs   = ms; break;
                    case "ad":      r.adMs      = ms; break;
                    case "silence": r.silenceMs = ms; break;
                    case "speed":   r.speedMs   = ms; break;
                }
            }
        }
        adapter.close();
        return r;
    }

    /**
     * The user's real, content-weighted average playback speed, derived from the
     * same durable data the time-saved statistics use: total content actually
     * played divided by the wall-clock time it took (content minus the time saved
     * by speeding up). Speed-saved time is recorded as {@code sessionContent *
     * (1 - 1/speed)} per session, so {@code content - speedSaved} is exactly the
     * real seconds spent listening. A user who always listens at 1× yields 1.00;
     * someone who mostly listens at 2× approaches 2.00.
     *
     * <p>This replaces the old proxy of {@code UserPreferences.getPlaybackSpeed()}
     * (the configured <em>default</em> speed control), which never reflected what
     * the user actually listened at.
     *
     * @return the average speed (&ge; 1.0), or 0 when there isn't enough listening
     *         history yet to report a real value (so callers can show "—" rather
     *         than a fabricated number).
     */
    public static float getAveragePlaybackSpeed() {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try {
            long content = adapter.getTotalPlayedDuration();
            if (content <= 0) {
                return 0f;  // nothing listened yet -> unknown
            }
            long speedSaved = 0;
            try (Cursor c = adapter.getSkipEventStatsCursor(0, System.currentTimeMillis())) {
                int idxType = c.getColumnIndexOrThrow(PodDBAdapter.KEY_SKIP_TYPE);
                int idxTotal = c.getColumnIndexOrThrow("total_ms");
                while (c.moveToNext()) {
                    if ("speed".equals(c.getString(idxType))) {
                        speedSaved = c.getLong(idxTotal);
                    }
                }
            }
            long wallClock = content - speedSaved;
            if (wallClock <= 0) {
                return 0f;  // corrupt/desynced data -> report unknown, not a lie
            }
            float speed = (float) content / (float) wallClock;
            // Guard against implausible values from any content/skip-bucket desync
            // (real playback speed never exceeds a few ×).
            return speed > 10f ? 0f : speed;
        } finally {
            adapter.close();
        }
    }

    /**
     * Searches the DB for statistics.
     *
     * @return The list of statistics objects
     */
    @NonNull
    public static StatisticsResult getStatistics(boolean includeMarkedAsPlayed,
                                                 long timeFilterFrom, long timeFilterTo) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();

        StatisticsResult result = new StatisticsResult();
        long sixMonthsAgo = System.currentTimeMillis() - (long) (1000L * 3600 * 24 * 30.44 * 6);
        try (FeedCursor cursor = new FeedCursor(adapter.getFeedStatisticsCursor(
                includeMarkedAsPlayed, timeFilterFrom, timeFilterTo, sixMonthsAgo))) {
            int indexOldestDate = cursor.getColumnIndexOrThrow("oldest_date");
            int indexNumEpisodes = cursor.getColumnIndexOrThrow("num_episodes");
            int indexEpisodesStarted = cursor.getColumnIndexOrThrow("episodes_started");
            int indexTotalTime = cursor.getColumnIndexOrThrow("total_time");
            int indexPlayedTime = cursor.getColumnIndexOrThrow("played_time");
            int indexNumDownloaded = cursor.getColumnIndexOrThrow("num_downloaded");
            int indexDownloadSize = cursor.getColumnIndexOrThrow("download_size");
            int indexNumRecentUnplayed = cursor.getColumnIndexOrThrow("num_recent_unplayed");
            int indexSkippedTime = cursor.getColumnIndex("skipped_time");

            while (cursor.moveToNext()) {
                Feed feed = cursor.getFeed();

                long feedPlayedTime = cursor.getLong(indexPlayedTime) / 1000;
                long feedTotalTime = cursor.getLong(indexTotalTime) / 1000;
                long episodes = cursor.getLong(indexNumEpisodes);
                long episodesStarted = cursor.getLong(indexEpisodesStarted);
                long totalDownloadSize = cursor.getLong(indexDownloadSize);
                long episodesDownloadCount = cursor.getLong(indexNumDownloaded);
                long oldestDate = cursor.getLong(indexOldestDate);
                boolean hasRecentUnplayed = cursor.getLong(indexNumRecentUnplayed) > 0;
                long feedSkippedTime = indexSkippedTime >= 0 ? cursor.getLong(indexSkippedTime) / 1000 : 0;

                if (episodes > 0 && oldestDate < Long.MAX_VALUE) {
                    result.oldestDate = Math.min(result.oldestDate, oldestDate);
                }

                result.feedTime.add(new StatisticsItem(feed, feedTotalTime, feedPlayedTime, episodes,
                        episodesStarted, totalDownloadSize, episodesDownloadCount, hasRecentUnplayed, feedSkippedTime));
            }
        }
        adapter.close();
        return result;
    }

    // ─── Editorial stats (Statistics Redesign) ───────────────────────────────

    public static class EditorialStats {
        public long totalPlayedMs;
        public long totalSavedMs;
        public long savedSpeedMs;
        public long savedSilenceMs;
        public long savedIntrosMs;
        public long savedOutrosMs;
        public long savedAdsMs;
        public int episodesStarted;
        public int episodesCompleted;
        public int episodesInProgress;
        /** Started, not completed, last played &gt;= 30 days ago (or never via stats). */
        public int episodesAbandoned;
        public int streakDays;
        public int topHourLocal;
        /** Minutes listened per hour-of-day, index 0–23. */
        public long[] byHour = new long[24];
        /** Minutes listened per weekday, index 0=Sun…6=Sat. */
        public long[] byDay = new long[7];
        /** Skip ms per weekday × category, [dow][cat] where cat is
         *  0=speed, 1=silence, 2=ads, 3=intros, 4=outros. */
        public long[][] byDaySaved = new long[7][5];
        /** Hours listened per week, last 12 weeks (index 0 = oldest). */
        public float[] weekly = new float[12];
        /** Intensity grid [26 weeks][7 days], 0–4 (oldest week = index 0). */
        public int[][] heatmap = new int[26][7];
        /** Raw listening ms per cell — same shape as {@link #heatmap}. Used for
         *  the "tap a cell to see the day" detail label without re-querying. */
        public long[][] heatmapMs = new long[26][7];
        /** Epoch ms of the Sunday that the heatmap's week 0 starts on; combined
         *  with (weekIdx * 7 + dayIdx) gives the calendar date for any cell. */
        public long heatmapStartMs;
        /** Aggregated hours per year, ascending by year. */
        public List<YearItem> yearly = new ArrayList<>();
        /** Top shows (up to 9, rest collapsed into "Other"). */
        public List<ShowItem> shows = new ArrayList<>();

        public static class YearItem {
            public final int year;
            public final float hrs;

            public YearItem(int year, float hrs) {
                this.year = year;
                this.hrs = hrs;
            }
        }

        public static class ShowItem {
            public final long feedId;
            public final String title;
            public final String imageUrl;
            public final float hrs;
            public final int pct;
            public final int color;

            public ShowItem(long feedId, String title, String imageUrl, float hrs, int pct, int color) {
                this.feedId = feedId;
                this.title = title;
                this.imageUrl = imageUrl;
                this.hrs = hrs;
                this.pct = pct;
                this.color = color;
            }
        }
    }

    public static class FeedDetail {
        public final long feedId;
        public final String title;
        public final String imageUrl;
        public final int color;
        public final long subscribedMs;
        public final int episodesTotal;
        public final int episodesPlayed;
        public final float hrsListened;
        public final float hrsSaved;
        /** Hours per week, last 12 weeks (index 0 = oldest). */
        public final float[] weekly;

        public FeedDetail(long feedId, String title, String imageUrl, int color,
                          long subscribedMs, int episodesTotal, int episodesPlayed,
                          float hrsListened, float hrsSaved, float[] weekly) {
            this.feedId = feedId;
            this.title = title;
            this.imageUrl = imageUrl;
            this.color = color;
            this.subscribedMs = subscribedMs;
            this.episodesTotal = episodesTotal;
            this.episodesPlayed = episodesPlayed;
            this.hrsListened = hrsListened;
            this.hrsSaved = hrsSaved;
            this.weekly = weekly;
        }
    }

    @NonNull
    public static EditorialStats getEditorialStats() {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        EditorialStats s = new EditorialStats();

        // byHour
        try (Cursor c = adapter.getByHourCursor()) {
            int iHour = c.getColumnIndexOrThrow("hour");
            int iMin = c.getColumnIndexOrThrow("minutes");
            while (c.moveToNext()) {
                int h = c.getInt(iHour);
                if (h >= 0 && h < 24) {
                    s.byHour[h] = c.getLong(iMin);
                }
            }
        }

        // topHourLocal
        long maxHour = 0;
        for (int h = 0; h < 24; h++) {
            if (s.byHour[h] > maxHour) { maxHour = s.byHour[h]; s.topHourLocal = h; }
        }

        // byDay
        try (Cursor c = adapter.getByDayCursor()) {
            int iDow = c.getColumnIndexOrThrow("dow");
            int iMin = c.getColumnIndexOrThrow("minutes");
            while (c.moveToNext()) {
                int d = c.getInt(iDow);
                if (d >= 0 && d < 7) {
                    s.byDay[d] = c.getLong(iMin);
                }
            }
        }

        // heatmap + weekly (last 26 weeks daily data)
        long nowMs = System.currentTimeMillis();
        long heatmapStart = nowMs - 26L * 7 * 86400_000L;
        java.util.Map<String, Long> dailyMs = new java.util.LinkedHashMap<>();
        try (Cursor c = adapter.getDailyListeningCursor(heatmapStart, nowMs)) {
            int iDay = c.getColumnIndexOrThrow("day");
            int iMs = c.getColumnIndexOrThrow("ms");
            while (c.moveToNext()) {
                dailyMs.put(c.getString(iDay), c.getLong(iMs));
            }
        }

        // Build heatmap: bucket 0-4 by quintile of max
        java.util.Calendar cal = java.util.Calendar.getInstance();
        // align to start of the week containing heatmapStart (Sun=0)
        cal.setTimeInMillis(heatmapStart);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        int dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1; // 0=Sun
        cal.add(java.util.Calendar.DAY_OF_YEAR, -dayOfWeek);

        long maxDay = 1;
        for (long v : dailyMs.values()) if (v > maxDay) maxDay = v;

        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        s.heatmapStartMs = cal.getTimeInMillis();
        for (int w = 0; w < 26; w++) {
            for (int d = 0; d < 7; d++) {
                String key = sdf.format(cal.getTime());
                long ms = dailyMs.containsKey(key) ? dailyMs.get(key) : 0L;
                int bucket = ms == 0 ? 0 : (int) Math.min(4, 1 + (ms * 4) / maxDay);
                s.heatmap[w][d] = bucket;
                s.heatmapMs[w][d] = ms;
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
            }
        }

        // weekly: aggregate daily data into 12 weekly buckets
        long weekStart = nowMs - 12L * 7 * 86400_000L;
        java.util.Calendar wCal = java.util.Calendar.getInstance();
        wCal.setTimeInMillis(weekStart);
        wCal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        wCal.set(java.util.Calendar.MINUTE, 0);
        wCal.set(java.util.Calendar.SECOND, 0);
        wCal.set(java.util.Calendar.MILLISECOND, 0);
        for (int w = 0; w < 12; w++) {
            long weekMs = 0;
            for (int d = 0; d < 7; d++) {
                String key = sdf.format(wCal.getTime());
                weekMs += dailyMs.containsKey(key) ? dailyMs.get(key) : 0L;
                wCal.add(java.util.Calendar.DAY_OF_YEAR, 1);
            }
            s.weekly[w] = weekMs / 3_600_000f;
        }

        // streak: consecutive days with listening ending today
        java.util.Calendar streakCal = java.util.Calendar.getInstance();
        streakCal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        streakCal.set(java.util.Calendar.MINUTE, 0);
        streakCal.set(java.util.Calendar.SECOND, 0);
        streakCal.set(java.util.Calendar.MILLISECOND, 0);
        int streak = 0;
        for (int i = 0; i < 365; i++) {
            String key = sdf.format(streakCal.getTime());
            if (dailyMs.containsKey(key) && dailyMs.get(key) > 0) {
                streak++;
            } else if (i == 0) {
                // today no data yet — still try yesterday
            } else {
                break;
            }
            streakCal.add(java.util.Calendar.DAY_OF_YEAR, -1);
        }
        s.streakDays = streak;

        // Skip statistics (reuse existing method via its sub-queries)
        try (Cursor c = adapter.getSkipEventStatsCursor(0, nowMs)) {
            int iType = c.getColumnIndexOrThrow(PodDBAdapter.KEY_SKIP_TYPE);
            int iMs = c.getColumnIndexOrThrow("total_ms");
            while (c.moveToNext()) {
                String type = c.getString(iType);
                long ms = c.getLong(iMs);
                s.totalSavedMs += ms;
                switch (type) {
                    case "speed":   s.savedSpeedMs   = ms; break;
                    case "silence": s.savedSilenceMs = ms; break;
                    case "intro":   s.savedIntrosMs  = ms; break;
                    case "outro":   s.savedOutrosMs  = ms; break;
                    case "ad":      s.savedAdsMs     = ms; break;
                }
            }
        }

        // Skip statistics broken down by day-of-week × category, so the
        // Activity tab's "tap a day" interaction can filter the Time Saved card.
        try (Cursor c = adapter.getSkipEventStatsByDayCursor()) {
            int iDow = c.getColumnIndexOrThrow("dow");
            int iType = c.getColumnIndexOrThrow(PodDBAdapter.KEY_SKIP_TYPE);
            int iMs = c.getColumnIndexOrThrow("total_ms");
            while (c.moveToNext()) {
                int dow = c.getInt(iDow);
                if (dow < 0 || dow > 6) {
                    continue;
                }
                int cat = skipCategoryIndex(c.getString(iType));
                if (cat < 0) {
                    continue;
                }
                s.byDaySaved[dow][cat] = c.getLong(iMs);
            }
        }

        // Total played (all time) — total_duration is already in milliseconds
        try (Cursor c = adapter.getMonthlyStatisticsCursor()) {
            int iMs = c.getColumnIndexOrThrow("total_duration");
            while (c.moveToNext()) {
                s.totalPlayedMs += c.getLong(iMs);
            }
        }

        // Episode counts. Abandoned = started, not finished, no playback activity
        // in the last 30 days (keeps in_progress meaning "actively being listened to").
        long abandonedCutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
        try (Cursor c = adapter.getGlobalEpisodeCountsCursor(abandonedCutoff)) {
            if (c.moveToFirst()) {
                s.episodesStarted  = c.getInt(c.getColumnIndexOrThrow("total_started"));
                s.episodesCompleted = c.getInt(c.getColumnIndexOrThrow("completed"));
                s.episodesInProgress = c.getInt(c.getColumnIndexOrThrow("in_progress"));
                s.episodesAbandoned = c.getInt(c.getColumnIndexOrThrow("abandoned"));
            }
        }

        // Yearly aggregation from monthly data — total_duration is already in milliseconds
        java.util.TreeMap<Integer, Long> yearMap = new java.util.TreeMap<>();
        try (Cursor c = adapter.getMonthlyStatisticsCursor()) {
            int iYear = c.getColumnIndexOrThrow("year");
            int iMs = c.getColumnIndexOrThrow("total_duration");
            while (c.moveToNext()) {
                int yr = Integer.parseInt(c.getString(iYear));
                yearMap.merge(yr, c.getLong(iMs), Long::sum);
            }
        }
        for (java.util.Map.Entry<Integer, Long> e : yearMap.entrySet()) {
            s.yearly.add(new EditorialStats.YearItem(e.getKey(), e.getValue() / 3_600_000f));
        }

        // Shows: feed-level playback totals
        StatisticsResult feedStats = getStatistics(false, 0, Long.MAX_VALUE);
        Collections.sort(feedStats.feedTime, (a, b) -> Long.compare(b.timePlayed, a.timePlayed));
        long totalPlayed = 0;
        for (StatisticsItem it : feedStats.feedTime) {
            totalPlayed += it.timePlayed;
        }

        int[] palette = {0xFFf4a261, 0xFF2a9d8f, 0xFFe76f51, 0xFF264653,
                         0xFFa06cd5, 0xFF83c5be, 0xFFbc4749, 0xFF588157, 0xFF9aa0a6};
        int colorIdx = 0;
        float otherHrs = 0;
        long threshold = totalPlayed / 25; // <4% → "Other"
        for (StatisticsItem it : feedStats.feedTime) {
            if (it.timePlayed == 0) {
                continue;
            }
            int pct = totalPlayed > 0 ? (int) Math.round(it.timePlayed * 100.0 / totalPlayed) : 0;
            if (it.timePlayed < threshold || s.shows.size() >= 8) {
                otherHrs += it.timePlayed / 3_600_000f;
            } else {
                s.shows.add(new EditorialStats.ShowItem(
                        it.feed.getId(), it.feed.getTitle(), it.feed.getImageUrl(),
                        it.timePlayed / 3_600_000f, pct,
                        palette[colorIdx % palette.length] | 0xFF000000));
                colorIdx++;
            }
        }
        if (otherHrs > 0) {
            s.shows.add(new EditorialStats.ShowItem(-1, "Other", null, otherHrs, 0, 0xFF9aa0a6));
        }

        adapter.close();
        return s;
    }

    /** Maps the skip_type string to the byDaySaved column index. -1 if unknown. */
    private static int skipCategoryIndex(String type) {
        if (type == null) {
            return -1;
        }
        switch (type) {
            case "speed":   return 0;
            case "silence": return 1;
            case "ad":      return 2;
            case "intro":   return 3;
            case "outro":   return 4;
            default:        return -1;
        }
    }

    @NonNull
    public static FeedDetail getFeedDetail(long feedId) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();

        Feed feed = getFeed(feedId, false, 0, 0);
        if (feed == null) {
            adapter.close();
            return new FeedDetail(feedId, "Unknown", null, 0xFF9aa0a6,
                    0, 0, 0, 0, 0, new float[12]);
        }

        // Feed-level stats
        StatisticsResult r = getStatistics(false, 0, Long.MAX_VALUE);
        float hrsListened = 0, hrsSaved = 0;
        int totalEp = 0, playedEp = 0;
        for (StatisticsItem it : r.feedTime) {
            if (it.feed.getId() == feedId) {
                hrsListened = it.timePlayed / 3_600_000f;
                hrsSaved = it.timeSkipped / 3_600_000f;
                totalEp = (int) it.episodes;
                playedEp = (int) it.episodesStarted;
                break;
            }
        }

        // Weekly sparkline (last 12 weeks)
        long nowMs = System.currentTimeMillis();
        long weekStart = nowMs - 12L * 7 * 86400_000L;
        java.util.Map<String, Long> dailyMs = new java.util.LinkedHashMap<>();
        try (Cursor c = adapter.getFeedDailyListeningCursor(feedId, weekStart, nowMs)) {
            int iDay = c.getColumnIndexOrThrow("day");
            int iMs = c.getColumnIndexOrThrow("ms");
            while (c.moveToNext()) {
                dailyMs.put(c.getString(iDay), c.getLong(iMs));
            }
        }
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        java.util.Calendar wCal = java.util.Calendar.getInstance();
        wCal.setTimeInMillis(weekStart);
        wCal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        wCal.set(java.util.Calendar.MINUTE, 0);
        wCal.set(java.util.Calendar.SECOND, 0);
        wCal.set(java.util.Calendar.MILLISECOND, 0);
        float[] weekly = new float[12];
        for (int w = 0; w < 12; w++) {
            long wMs = 0;
            for (int d = 0; d < 7; d++) {
                String key = sdf.format(wCal.getTime());
                wMs += dailyMs.containsKey(key) ? dailyMs.get(key) : 0L;
                wCal.add(java.util.Calendar.DAY_OF_YEAR, 1);
            }
            weekly[w] = wMs / 3_600_000f;
        }

        long subscribedMs = feed.getLastRefreshAttempt() > 0 ? feed.getLastRefreshAttempt() : 0;
        int color = 0xFF9aa0a6;

        adapter.close();
        return new FeedDetail(feedId, feed.getTitle(), feed.getImageUrl(), color,
                subscribedMs, totalEp, playedEp, hrsListened, hrsSaved, weekly);
    }

    /** Episodes whose last-played time falls in [fromMs, toMs), most recent first.
     *  Each item's Feed is populated; queue/favorite tags are not. */
    @NonNull
    public static List<FeedItem> getEpisodesPlayedInPeriod(long fromMs, long toMs) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (FeedItemCursor cursor = new FeedItemCursor(
                adapter.getFeedItemsPlayedInPeriodCursor(fromMs, toMs))) {
            List<FeedItem> items = extractItemlistFromCursor(cursor);
            loadFeedDataOfFeedItemList(items);
            return items;
        } finally {
            adapter.close();
        }
    }

    /** Up to {@code limit} episodes played strictly before {@code beforeMs} (most
     *  recent first) — the account-sync history backfill's paging query. */
    public static List<FeedItem> getEpisodesPlayedBefore(long beforeMs, int limit) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (FeedItemCursor cursor = new FeedItemCursor(
                adapter.getFeedItemsPlayedBeforeCursor(beforeMs, limit))) {
            List<FeedItem> items = extractItemlistFromCursor(cursor);
            loadFeedDataOfFeedItemList(items);
            return items;
        } finally {
            adapter.close();
        }
    }

    public static long getTimeBetweenReleaseAndPlayback(long timeFilterFrom, long timeFilterTo) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (Cursor cursor = adapter.getTimeBetweenReleaseAndPlayback(timeFilterFrom, timeFilterTo)) {
            cursor.moveToFirst();
            long result = cursor.getLong(0);
            adapter.close();
            return result;
        }
    }

    /**
     * Returns data necessary for displaying the navigation drawer. This includes
     * the list of subscriptions, the number of items in the queue and the number of unread
     * items.
     */
    @NonNull
    public static NavDrawerData getNavDrawerData(@Nullable SubscriptionsFilter subscriptionsFilter,
                                                 FeedOrder feedOrder, FeedCounter feedCounter, int feedState) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();

        final Map<Long, Integer> feedCounters = adapter.getFeedCounters(feedCounter);
        List<Feed> allFeeds = getFeedList();
        List<Feed> typeFilteredFeeds = new ArrayList<>();
        for (Feed feed : allFeeds) {
            if (feed.getState() == feedState) {
                typeFilteredFeeds.add(feed);
            }
        }
        if (subscriptionsFilter == null) {
            subscriptionsFilter = new SubscriptionsFilter("");
        }
        List<Feed> feeds = SubscriptionsFilterExecutor.filter(typeFilteredFeeds, feedCounters, subscriptionsFilter);

        Comparator<Feed> comparator;
        switch (feedOrder) {
            case COUNTER:
                comparator = (lhs, rhs) -> {
                    long counterLhs = feedCounters.containsKey(lhs.getId()) ? feedCounters.get(lhs.getId()) : 0;
                    long counterRhs = feedCounters.containsKey(rhs.getId()) ? feedCounters.get(rhs.getId()) : 0;
                    if (counterLhs > counterRhs) {
                        // reverse natural order: podcast with most unplayed episodes first
                        return -1;
                    } else if (counterLhs == counterRhs) {
                        return lhs.getTitle().compareToIgnoreCase(rhs.getTitle());
                    } else {
                        return 1;
                    }
                };
                break;
            case ALPHABETICAL:
                comparator = (lhs, rhs) -> {
                    String t1 = lhs.getTitle();
                    String t2 = rhs.getTitle();
                    if (t1 == null) {
                        return 1;
                    } else if (t2 == null) {
                        return -1;
                    } else {
                        return t1.compareToIgnoreCase(t2);
                    }
                };
                break;
            case MOST_PLAYED:
                final Map<Long, Integer> playedCounters = adapter.getPlayedEpisodesCounters();
                comparator = (lhs, rhs) -> {
                    long counterLhs = playedCounters.containsKey(lhs.getId()) ? playedCounters.get(lhs.getId()) : 0;
                    long counterRhs = playedCounters.containsKey(rhs.getId()) ? playedCounters.get(rhs.getId()) : 0;
                    if (counterLhs > counterRhs) {
                        // podcast with most played episodes first
                        return -1;
                    } else if (counterLhs == counterRhs) {
                        return lhs.getTitle().compareToIgnoreCase(rhs.getTitle());
                    } else {
                        return 1;
                    }
                };
                break;
            default:
                final Map<Long, Long> recentPubDates = adapter.getMostRecentItemDates();
                comparator = (lhs, rhs) -> {
                    long dateLhs = recentPubDates.containsKey(lhs.getId()) ? recentPubDates.get(lhs.getId()) : 0;
                    long dateRhs = recentPubDates.containsKey(rhs.getId()) ? recentPubDates.get(rhs.getId()) : 0;
                    return Long.compare(dateRhs, dateLhs);
                };
                break;
        }

        Collections.sort(feeds, comparator);
        final int queueSize = adapter.getQueueSize();
        final int numNewItems = getTotalEpisodeCount(new FeedItemFilter(FeedItemFilter.NEW));
        final int numDownloadedItems = getTotalEpisodeCount(new FeedItemFilter(FeedItemFilter.DOWNLOADED));

        NavDrawerData.TagItem untaggedTag = new NavDrawerData.TagItem(FeedPreferences.TAG_UNTAGGED);
        Map<String, NavDrawerData.TagItem> tags = new HashMap<>();
        for (Feed feed : feeds) {
            if (feed.getPreferences().getTags().isEmpty() || (feed.getPreferences().getTags().size()) == 1
                    && feed.getPreferences().getTags().contains(FeedPreferences.TAG_ROOT)) {
                untaggedTag.addFeed(feed, 0);
            }
            for (String tag : feed.getPreferences().getTags()) {
                if (!tags.containsKey(tag)) {
                    tags.put(tag, new NavDrawerData.TagItem(tag));
                }
                int counter = feedCounters.containsKey(feed.getId()) ? feedCounters.get(feed.getId()) : 0;
                tags.get(tag).addFeed(feed, counter);
            }
        }
        List<NavDrawerData.TagItem> tagsSorted = new ArrayList<>(tags.values());
        Collections.sort(tagsSorted, (o1, o2) -> o1.getTitle().compareToIgnoreCase(o2.getTitle()));

        if (!untaggedTag.getFeeds().isEmpty()) {
            tagsSorted.add(0, untaggedTag);
        }

        NavDrawerData result = new NavDrawerData(feeds, tagsSorted,
                queueSize, numNewItems, numDownloadedItems, feedCounters);
        adapter.close();
        return result;
    }

    public static List<NavDrawerData.TagItem> getAllTags(int feedState) {
        Map<String, NavDrawerData.TagItem> tags = new HashMap<>();
        List<Feed> allFeeds = getFeedList();
        List<Feed> feeds = new ArrayList<>();
        for (Feed feed : allFeeds) {
            if (feed.getState() == feedState) {
                feeds.add(feed);
            }
        }
        NavDrawerData.TagItem untaggedTag = new NavDrawerData.TagItem(FeedPreferences.TAG_UNTAGGED);
        for (Feed feed : feeds) {
            if (feed.getPreferences().getTags().isEmpty() || (feed.getPreferences().getTags().size()) == 1
                    && feed.getPreferences().getTags().contains(FeedPreferences.TAG_ROOT)) {
                untaggedTag.addFeed(feed, 0);
            }
            for (String tag : feed.getPreferences().getTags()) {
                if (FeedPreferences.TAG_ROOT.equals(tag)) {
                    continue;
                }
                if (!tags.containsKey(tag)) {
                    tags.put(tag, new NavDrawerData.TagItem(tag));
                }
                tags.get(tag).addFeed(feed, 0);
            }
        }
        List<NavDrawerData.TagItem> tagsSorted = new ArrayList<>(tags.values());
        Collections.sort(tagsSorted, (o1, o2) -> o1.getTitle().compareToIgnoreCase(o2.getTitle()));
        if (!untaggedTag.getFeeds().isEmpty()) {
            tagsSorted.add(0, untaggedTag);
        }
        // Root tag here means "all feeds", this is different from the nav drawer.
        NavDrawerData.TagItem rootTag = new NavDrawerData.TagItem(FeedPreferences.TAG_ROOT);
        for (Feed feed : feeds) {
            rootTag.addFeed(feed, 0);
        }
        tagsSorted.add(0, rootTag);
        return tagsSorted;
    }

    public static List<FeedItem> searchFeedItems(final long feedId, final String query, int state) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (FeedItemCursor searchResult = new FeedItemCursor(adapter.searchItems(feedId, query, state))) {
            List<FeedItem> items = extractItemlistFromCursor(searchResult);
            loadAdditionalFeedItemListData(items);
            return items;
        } finally {
            adapter.close();
        }
    }

    public static List<Feed> searchFeeds(final String query, int state) {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        try (FeedCursor cursor = new FeedCursor(adapter.searchFeeds(query, state))) {
            List<Feed> items = new ArrayList<>();
            while (cursor.moveToNext()) {
                items.add(cursor.getFeed());
            }
            return items;
        } finally {
            adapter.close();
        }
    }
}
