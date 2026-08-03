package de.danoeh.antennapod.storage.database;

import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Runs the REAL 3160000 → 3170000 upgrade (duplicate-feed cleanup + unique
 * download_url index) against a database in the legacy shape. Reproduces the
 * bug where a concurrent auto-subscribe / feed-refresh run could slip a
 * second Feeds row past the app-level dedup check, so the same podcast shows
 * up twice in the subscriptions list.
 */
@RunWith(RobolectricTestRunner.class)
public class FeedDedupMigrationTest {

    private SQLiteDatabase db;

    @Before
    public void setUp() {
        db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE Feeds (id INTEGER PRIMARY KEY AUTOINCREMENT, download_url TEXT)");
        db.execSQL("CREATE TABLE FeedItems (id INTEGER PRIMARY KEY AUTOINCREMENT, feed INTEGER, read INTEGER)");
        db.execSQL("CREATE TABLE FeedMedia (id INTEGER PRIMARY KEY AUTOINCREMENT, feeditem INTEGER,"
                + " downloaded INTEGER, position INTEGER)");
        db.execSQL("CREATE TABLE Queue (id INTEGER PRIMARY KEY AUTOINCREMENT, feeditem INTEGER, feed INTEGER)");
        db.execSQL("CREATE TABLE PlaylistItems (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " playlist_id INTEGER, feeditem INTEGER, feed INTEGER, position INTEGER)");
        db.execSQL("CREATE TABLE Favorites (id INTEGER PRIMARY KEY AUTOINCREMENT, feeditem INTEGER, feed INTEGER)");
        db.execSQL("CREATE TABLE TrimBookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, feeditem INTEGER)");
        db.execSQL("CREATE TABLE TrimSkipEvents (id INTEGER PRIMARY KEY AUTOINCREMENT, feeditem INTEGER)");
        db.execSQL("CREATE TABLE SimpleChapters (id INTEGER PRIMARY KEY AUTOINCREMENT, feeditem INTEGER)");
        db.execSQL("CREATE TABLE DownloadLog (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " feedfile INTEGER, feedfile_type INTEGER)");
    }

    @After
    public void tearDown() {
        db.close();
    }

    @Test
    public void ghostDuplicateWithNoUserDataIsDeleted() {
        long keeperId = insertFeed("https://feeds.example.com/show.xml");
        long ghostId = insertFeed("https://feeds.example.com/show.xml");
        long keeperItem = insertItem(keeperId, /*read=*/1);
        insertItem(ghostId, /*read=*/0); // ghost's own back-catalog fetch, never touched

        DBUpgrader.upgrade(db, 3160000, 3170000);

        assertEquals(1, feedCount("https://feeds.example.com/show.xml"));
        assertTrue(feedExists(keeperId));
        assertFalse(feedExists(ghostId));
        assertEquals(1, queryLong("SELECT COUNT(*) FROM FeedItems WHERE feed=" + keeperId));
        assertEquals(0, queryLong("SELECT COUNT(*) FROM FeedItems WHERE feed=" + ghostId));
        assertTrue(keeperItem > 0);
    }

    @Test
    public void bothCopiesUntouchedKeepsOldest() {
        long firstId = insertFeed("https://feeds.example.com/untouched.xml");
        long secondId = insertFeed("https://feeds.example.com/untouched.xml");

        DBUpgrader.upgrade(db, 3160000, 3170000);

        assertEquals(1, feedCount("https://feeds.example.com/untouched.xml"));
        assertTrue(feedExists(firstId));
        assertFalse(feedExists(secondId));
    }

    @Test
    public void bothCopiesWithUserDataAreLeftAlone() {
        long firstId = insertFeed("https://feeds.example.com/contested.xml");
        long secondId = insertFeed("https://feeds.example.com/contested.xml");
        insertItem(firstId, /*read=*/1);
        insertItem(secondId, /*read=*/1);

        DBUpgrader.upgrade(db, 3160000, 3170000);

        // A migration shouldn't guess which copy to keep when both have real data.
        assertEquals(2, feedCount("https://feeds.example.com/contested.xml"));
        assertTrue(feedExists(firstId));
        assertTrue(feedExists(secondId));
    }

    @Test
    public void distinctFeedsWithNullOrEmptyUrlAreNeverTouched() {
        long a = insertFeed(null);
        long b = insertFeed(null);
        long c = insertFeed("");

        DBUpgrader.upgrade(db, 3160000, 3170000);

        assertTrue(feedExists(a));
        assertTrue(feedExists(b));
        assertTrue(feedExists(c));
    }

    @Test
    public void uniqueIndexRejectsNewDuplicateAfterMigration() {
        insertFeed("https://feeds.example.com/locked.xml");

        DBUpgrader.upgrade(db, 3160000, 3170000);

        try {
            db.execSQL("INSERT INTO Feeds (download_url) VALUES ('https://feeds.example.com/locked.xml')");
            fail("expected the unique index to reject a second row for the same URL");
        } catch (SQLiteConstraintException expected) {
            // good -- the DB layer now enforces what the app-level check used to miss
        }

        // Multiple feeds with no URL (e.g. local feeds) must still be allowed to coexist.
        db.execSQL("INSERT INTO Feeds (download_url) VALUES (NULL)");
        db.execSQL("INSERT INTO Feeds (download_url) VALUES (NULL)");
        assertEquals(2, queryLong("SELECT COUNT(*) FROM Feeds WHERE download_url IS NULL"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private long insertFeed(String downloadUrl) {
        if (downloadUrl == null) {
            db.execSQL("INSERT INTO Feeds (download_url) VALUES (NULL)");
        } else {
            db.execSQL("INSERT INTO Feeds (download_url) VALUES (?)", new Object[]{downloadUrl});
        }
        return queryLong("SELECT MAX(id) FROM Feeds");
    }

    private long insertItem(long feedId, int read) {
        db.execSQL("INSERT INTO FeedItems (feed, read) VALUES (" + feedId + ", " + read + ")");
        return queryLong("SELECT MAX(id) FROM FeedItems");
    }

    private boolean feedExists(long feedId) {
        return queryLong("SELECT COUNT(*) FROM Feeds WHERE id=" + feedId) == 1;
    }

    private long feedCount(String url) {
        return queryLong("SELECT COUNT(*) FROM Feeds WHERE download_url='" + url + "'");
    }

    private long queryLong(String sql) {
        try (Cursor c = db.rawQuery(sql, null)) {
            assertTrue("no row for: " + sql, c.moveToFirst());
            return c.getLong(0);
        }
    }
}
