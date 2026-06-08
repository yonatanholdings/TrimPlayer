package de.danoeh.antennapod.storage.importexport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.storage.database.PodDBAdapter;

/**
 * Exercises the real SQLite read path of the additive "Merge database" import
 * against a backup shaped like a <em>stock AntennaPod</em> export: it has every
 * column AntennaPod ships but lacks the TrimPlayer-only {@code feed_trim_skip_*}
 * columns (added at DB version 3110000).
 *
 * <p>Regression guard: those missing columns used to make the projection throw
 * "no such column", which {@link BackupDbReader} turned into the generic
 * "Backup database could not be read" — so every genuine AntennaPod backup
 * failed to merge. The projection now defaults absent columns instead.
 */
@RunWith(RobolectricTestRunner.class)
public class BackupDbReaderTest {

    @Test
    public void readsAntennaPodBackupMissingTrimColumns() throws Exception {
        File dbFile = File.createTempFile("antennapod_backup", ".db",
                RuntimeEnvironment.getApplication().getCacheDir());
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        try {
            createAntennaPodSchema(db);

            ContentValues feed = new ContentValues();
            feed.put(PodDBAdapter.KEY_TITLE, "Example Show");
            feed.put(PodDBAdapter.KEY_DOWNLOAD_URL, "https://example.com/feed.xml");
            feed.put(PodDBAdapter.KEY_STATE, Feed.STATE_SUBSCRIBED);
            feed.put(PodDBAdapter.KEY_FEED_PLAYBACK_SPEED, 1.0f);
            long feedId = db.insert(PodDBAdapter.TABLE_NAME_FEEDS, null, feed);

            ContentValues media = new ContentValues();
            media.put(PodDBAdapter.KEY_FEEDITEM, 10L);
            media.put(PodDBAdapter.KEY_DOWNLOAD_URL, "https://example.com/ep1.mp3");
            media.put(PodDBAdapter.KEY_DURATION, 1_800_000);
            media.put(PodDBAdapter.KEY_POSITION, 0);
            long mediaId = db.insert(PodDBAdapter.TABLE_NAME_FEED_MEDIA, null, media);

            ContentValues item = new ContentValues();
            item.put(PodDBAdapter.KEY_ID, 10L);
            item.put(PodDBAdapter.KEY_TITLE, "Episode 1");
            item.put(PodDBAdapter.KEY_ITEM_IDENTIFIER, "guid-1");
            item.put(PodDBAdapter.KEY_PUBDATE, 1_700_000_000_000L);
            item.put(PodDBAdapter.KEY_READ, Feed.STATE_SUBSCRIBED);
            item.put(PodDBAdapter.KEY_FEED, feedId);
            item.put(PodDBAdapter.KEY_MEDIA, mediaId);
            db.insert(PodDBAdapter.TABLE_NAME_FEED_ITEMS, null, item);

            ContentValues queue = new ContentValues();
            queue.put(PodDBAdapter.KEY_FEEDITEM, 10L);
            queue.put(PodDBAdapter.KEY_FEED, feedId);
            db.insert(PodDBAdapter.TABLE_NAME_QUEUE, null, queue);

            ContentValues favorite = new ContentValues();
            favorite.put(PodDBAdapter.KEY_FEEDITEM, 10L);
            favorite.put(PodDBAdapter.KEY_FEED, feedId);
            db.insert(PodDBAdapter.TABLE_NAME_FAVORITES, null, favorite);
        } finally {
            db.close();
        }

        BackupDbReader.Library lib = BackupDbReader.read(dbFile);

        assertEquals("subscription must be read", 1, lib.feeds.size());
        Feed readFeed = lib.feeds.get(0);
        assertEquals("Example Show", readFeed.getTitle());
        // The whole point: the absent trim columns default to "on" instead of
        // crashing the read.
        assertTrue(readFeed.getPreferences().isTrimSkipIntros());
        assertTrue(readFeed.getPreferences().isTrimSkipAds());
        assertTrue(readFeed.getPreferences().isTrimSkipOutros());

        assertEquals("episode must be read", 1, lib.episodes.size());
        assertEquals("queue must be read", 1, lib.queue.size());
        assertTrue("favorite must be read", lib.favoriteIds.contains(10L));
    }

    /**
     * Stock-AntennaPod schema: the full set of columns AntennaPod ships, minus
     * the TrimPlayer-only {@code feed_trim_skip_*} columns. Built from the same
     * {@code KEY_*} constants the live schema uses so it tracks renames.
     */
    private static void createAntennaPodSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + PodDBAdapter.TABLE_NAME_FEEDS + " ("
                + PodDBAdapter.KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + PodDBAdapter.KEY_TITLE + " TEXT,"
                + PodDBAdapter.KEY_CUSTOM_TITLE + " TEXT,"
                + PodDBAdapter.KEY_FILE_URL + " TEXT,"
                + PodDBAdapter.KEY_DOWNLOAD_URL + " TEXT,"
                + PodDBAdapter.KEY_LAST_REFRESH_ATTEMPT + " INTEGER,"
                + PodDBAdapter.KEY_LINK + " TEXT,"
                + PodDBAdapter.KEY_DESCRIPTION + " TEXT,"
                + PodDBAdapter.KEY_PAYMENT_LINK + " TEXT,"
                + PodDBAdapter.KEY_LASTUPDATE + " TEXT,"
                + PodDBAdapter.KEY_LANGUAGE + " TEXT,"
                + PodDBAdapter.KEY_AUTHOR + " TEXT,"
                + PodDBAdapter.KEY_IMAGE_URL + " TEXT,"
                + PodDBAdapter.KEY_TYPE + " TEXT,"
                + PodDBAdapter.KEY_FEED_IDENTIFIER + " TEXT,"
                + PodDBAdapter.KEY_AUTO_DOWNLOAD_ENABLED + " INTEGER DEFAULT 1,"
                + PodDBAdapter.KEY_USERNAME + " TEXT,"
                + PodDBAdapter.KEY_PASSWORD + " TEXT,"
                + PodDBAdapter.KEY_INCLUDE_FILTER + " TEXT DEFAULT '',"
                + PodDBAdapter.KEY_EXCLUDE_FILTER + " TEXT DEFAULT '',"
                + PodDBAdapter.KEY_MINIMAL_DURATION_FILTER + " INTEGER DEFAULT -1,"
                + PodDBAdapter.KEY_KEEP_UPDATED + " INTEGER DEFAULT 1,"
                + PodDBAdapter.KEY_IS_PAGED + " INTEGER DEFAULT 0,"
                + PodDBAdapter.KEY_NEXT_PAGE_LINK + " TEXT,"
                + PodDBAdapter.KEY_HIDE + " TEXT,"
                + PodDBAdapter.KEY_SORT_ORDER + " TEXT,"
                + PodDBAdapter.KEY_LAST_UPDATE_FAILED + " INTEGER DEFAULT 0,"
                + PodDBAdapter.KEY_AUTO_DELETE_ACTION + " INTEGER DEFAULT 0,"
                + PodDBAdapter.KEY_FEED_PLAYBACK_SPEED + " REAL DEFAULT -1,"
                + PodDBAdapter.KEY_FEED_SKIP_SILENCE + " INTEGER DEFAULT 0,"
                + PodDBAdapter.KEY_FEED_VOLUME_ADAPTION + " INTEGER DEFAULT 0,"
                + PodDBAdapter.KEY_FEED_TAGS + " TEXT,"
                + PodDBAdapter.KEY_FEED_SKIP_INTRO + " INTEGER DEFAULT 0,"
                + PodDBAdapter.KEY_FEED_SKIP_ENDING + " INTEGER DEFAULT 0,"
                + PodDBAdapter.KEY_EPISODE_NOTIFICATION + " INTEGER DEFAULT 0,"
                + PodDBAdapter.KEY_STATE + " INTEGER DEFAULT 0,"
                + PodDBAdapter.KEY_NEW_EPISODES_ACTION + " INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE " + PodDBAdapter.TABLE_NAME_FEED_ITEMS + " ("
                + PodDBAdapter.KEY_ID + " INTEGER PRIMARY KEY,"
                + PodDBAdapter.KEY_TITLE + " TEXT,"
                + PodDBAdapter.KEY_PUBDATE + " INTEGER,"
                + PodDBAdapter.KEY_READ + " INTEGER,"
                + PodDBAdapter.KEY_LINK + " TEXT,"
                + PodDBAdapter.KEY_DESCRIPTION + " TEXT,"
                + PodDBAdapter.KEY_PAYMENT_LINK + " TEXT,"
                + PodDBAdapter.KEY_MEDIA + " INTEGER,"
                + PodDBAdapter.KEY_FEED + " INTEGER,"
                + PodDBAdapter.KEY_HAS_CHAPTERS + " INTEGER,"
                + PodDBAdapter.KEY_ITEM_IDENTIFIER + " TEXT,"
                + PodDBAdapter.KEY_IMAGE_URL + " TEXT,"
                + PodDBAdapter.KEY_AUTO_DOWNLOAD_ENABLED + " INTEGER,"
                + PodDBAdapter.KEY_PODCASTINDEX_CHAPTER_URL + " TEXT,"
                + PodDBAdapter.KEY_PODCASTINDEX_TRANSCRIPT_TYPE + " TEXT,"
                + PodDBAdapter.KEY_PODCASTINDEX_TRANSCRIPT_URL + " TEXT,"
                + PodDBAdapter.KEY_SOCIAL_INTERACT_URL + " TEXT)");

        db.execSQL("CREATE TABLE " + PodDBAdapter.TABLE_NAME_FEED_MEDIA + " ("
                + PodDBAdapter.KEY_ID + " INTEGER PRIMARY KEY,"
                + PodDBAdapter.KEY_DURATION + " INTEGER,"
                + PodDBAdapter.KEY_FILE_URL + " TEXT,"
                + PodDBAdapter.KEY_DOWNLOAD_URL + " TEXT,"
                + PodDBAdapter.KEY_DOWNLOAD_DATE + " INTEGER,"
                + PodDBAdapter.KEY_POSITION + " INTEGER,"
                + PodDBAdapter.KEY_SIZE + " INTEGER,"
                + PodDBAdapter.KEY_MIME_TYPE + " TEXT,"
                + PodDBAdapter.KEY_LAST_PLAYED_TIME_HISTORY + " INTEGER,"
                + PodDBAdapter.KEY_FEEDITEM + " INTEGER,"
                + PodDBAdapter.KEY_PLAYED_DURATION + " INTEGER,"
                + PodDBAdapter.KEY_SKIPPED_DURATION + " INTEGER,"
                + PodDBAdapter.KEY_HAS_EMBEDDED_PICTURE + " INTEGER,"
                + PodDBAdapter.KEY_LAST_PLAYED_TIME_STATISTICS + " INTEGER)");

        db.execSQL("CREATE TABLE " + PodDBAdapter.TABLE_NAME_QUEUE + " ("
                + PodDBAdapter.KEY_ID + " INTEGER PRIMARY KEY,"
                + PodDBAdapter.KEY_FEEDITEM + " INTEGER,"
                + PodDBAdapter.KEY_FEED + " INTEGER)");

        db.execSQL("CREATE TABLE " + PodDBAdapter.TABLE_NAME_FAVORITES + " ("
                + PodDBAdapter.KEY_ID + " INTEGER PRIMARY KEY,"
                + PodDBAdapter.KEY_FEEDITEM + " INTEGER,"
                + PodDBAdapter.KEY_FEED + " INTEGER)");
    }
}
