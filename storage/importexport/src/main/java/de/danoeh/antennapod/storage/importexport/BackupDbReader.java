package de.danoeh.antennapod.storage.importexport;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.util.Log;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import de.danoeh.antennapod.storage.database.mapper.FeedCursor;
import de.danoeh.antennapod.storage.database.mapper.FeedItemCursor;

/**
 * Reads a <em>separate</em> AntennaPod/TrimPlayer database backup file
 * (read-only — never the live DB) into in-memory model objects, so a backup can
 * be merged into the current library without overwriting it.
 *
 * <p>The models are handed to {@link PortcastExporter#buildDocument} by
 * {@link AntennaPodDbToPortcast}, then run through the additive PortCast import
 * pipeline ({@link PortcastImporter} → subscribe/state workers). Row→model
 * conversion reuses the same cursor mappers and projections the live DB uses,
 * via the static {@code PodDBAdapter.get*Cursor(SQLiteDatabase)} helpers.
 */
public final class BackupDbReader {
    private static final String TAG = "BackupDbReader";

    /**
     * What a backup contains, shaped exactly as {@link PortcastExporter#buildDocument} expects.
     */
    public static final class Library {
        public final List<Feed> feeds;
        public final List<FeedItem> episodes;
        public final List<FeedItem> queue;
        public final Set<Long> favoriteIds;

        Library(List<Feed> feeds, List<FeedItem> episodes, List<FeedItem> queue, Set<Long> favoriteIds) {
            this.feeds = feeds;
            this.episodes = episodes;
            this.queue = queue;
            this.favoriteIds = favoriteIds;
        }
    }

    private BackupDbReader() { }

    /**
     * Copy the picked backup to a private temp file and read it. SQLite can only
     * open a file path, not a {@code content://} stream, so the copy is required.
     * The temp file (and any rollback journal) is removed before returning. Must
     * run off the main thread.
     */
    public static Library readFromUri(Context context, Uri uri) throws IOException {
        File temp = new File(context.getCacheDir(), "merge_backup_tmp.db");
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Cannot open backup file");
            }
            FileUtils.copyInputStreamToFile(in, temp);
            return read(temp);
        } finally {
            FileUtils.deleteQuietly(temp);
            FileUtils.deleteQuietly(new File(temp.getAbsolutePath() + "-journal"));
        }
    }

    /** Read a backup database file already on local storage. Package-visible for tests. */
    static Library read(File dbFile) throws IOException {
        SQLiteDatabase db;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        } catch (SQLiteException e) {
            throw new IOException("Backup is not a valid database", e);
        }
        try {
            if (db.getVersion() > PodDBAdapter.VERSION) {
                throw new IOException("Backup was created by a newer version of the app");
            }
            List<Feed> feeds = readFeeds(db);
            List<FeedItem> episodes = readEpisodes(db);
            List<FeedItem> queue = readQueue(db);
            Set<Long> favoriteIds = readFavoriteIds(db);
            Log.d(TAG, "Read backup: " + feeds.size() + " feeds, " + episodes.size()
                    + " episodes, " + queue.size() + " queued, " + favoriteIds.size() + " favorites");
            return new Library(feeds, episodes, queue, favoriteIds);
        } catch (SQLiteException e) {
            // Columns a foreign/older backup lacks are defaulted by the projection
            // (see PodDBAdapter.backupProjection), so this is a genuinely corrupt or
            // structurally incompatible backup (e.g. a missing table).
            throw new IOException("Backup database could not be read", e);
        } finally {
            db.close();
        }
    }

    private static List<Feed> readFeeds(SQLiteDatabase db) {
        List<Feed> feeds = new ArrayList<>();
        try (FeedCursor cursor = new FeedCursor(PodDBAdapter.getAllFeedsCursor(db))) {
            while (cursor.moveToNext()) {
                feeds.add(cursor.getFeed());
            }
        }
        return feeds;
    }

    private static List<FeedItem> readEpisodes(SQLiteDatabase db) {
        List<FeedItem> items = new ArrayList<>();
        try (FeedItemCursor cursor = new FeedItemCursor(PodDBAdapter.getAllFeedItemsCursor(db))) {
            while (cursor.moveToNext()) {
                items.add(cursor.getFeedItem());
            }
        }
        return items;
    }

    private static List<FeedItem> readQueue(SQLiteDatabase db) {
        List<FeedItem> queue = new ArrayList<>();
        try (FeedItemCursor cursor = new FeedItemCursor(PodDBAdapter.getQueueCursor(db))) {
            while (cursor.moveToNext()) {
                queue.add(cursor.getFeedItem());
            }
        }
        return queue;
    }

    private static Set<Long> readFavoriteIds(SQLiteDatabase db) {
        Set<Long> ids = new HashSet<>();
        try (android.database.Cursor cursor = PodDBAdapter.getFavoriteItemIdsCursor(db)) {
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0));
            }
        }
        return ids;
    }
}
