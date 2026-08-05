package de.danoeh.antennapod.storage.database;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;

/**
 * Regression test for a real crash: a device whose local DB version was below 3140000
 * (never had a Playlists table) upgrading straight to >= 3160000 in one pass. The
 * {@code oldVersion < 3140000} block creates Playlists using the CURRENT
 * CREATE_TABLE_PLAYLISTS, which already bakes in is_default. The
 * {@code oldVersion < 3160000} block used to unconditionally ALTER TABLE ADD COLUMN
 * is_default on top of that -- "duplicate column name: is_default". SQLite DDL is
 * transactional and Android wraps onUpgrade() in one transaction, so the failure
 * rolled the whole upgrade back, leaving the on-disk DB version unchanged and the
 * app crashing on every subsequent launch.
 *
 * <p>3130000 is picked as oldVersion because it's above every version-gated block
 * before 3140000 (so none of them fire against this minimal schema) and below 3140000
 * (so the Playlists table doesn't exist yet, reproducing the real device state).
 */
@RunWith(RobolectricTestRunner.class)
public class PlaylistFreshTableMigrationTest {

    private SQLiteDatabase db;

    @Before
    public void setUp() {
        db = SQLiteDatabase.create(null);
        // The only pre-existing table a device below 3140000 has that this migration
        // range touches -- Playlists/PlaylistItems/PlaylistFeeds don't exist yet.
        db.execSQL("CREATE TABLE Queue (id INTEGER PRIMARY KEY, feeditem INTEGER, feed INTEGER)");
    }

    @After
    public void tearDown() {
        db.close();
    }

    @Test
    public void freshPlaylistsTableDoesNotDoubleApplyIsDefaultColumn() {
        db.execSQL("INSERT INTO Queue (id, feeditem, feed) VALUES (1, 101, 1)");

        // Must not throw "duplicate column name: is_default".
        DBUpgrader.upgrade(db, 3130000, 3170000);

        assertEquals(1, queryLong("SELECT COUNT(*) FROM Playlists WHERE is_default = 1"));
        assertEquals(1, queryLong("SELECT COUNT(*) FROM PlaylistItems"));
    }

    @Test
    public void freshPlaylistsTableWithEmptyQueueStillMigratesCleanly() {
        DBUpgrader.upgrade(db, 3130000, 3170000);

        assertEquals(1, queryLong("SELECT COUNT(*) FROM Playlists WHERE is_default = 1"));
        assertEquals(0, queryLong("SELECT COUNT(*) FROM PlaylistItems"));
    }

    private long queryLong(String sql) {
        try (Cursor c = db.rawQuery(sql, null)) {
            c.moveToFirst();
            return c.getLong(0);
        }
    }
}
