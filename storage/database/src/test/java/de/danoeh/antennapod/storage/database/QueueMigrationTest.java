package de.danoeh.antennapod.storage.database;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Runs the REAL 3150000 → 3160000 upgrade (queue/playlist unification) against a
 * database in the legacy shape and proves the queue migrates losslessly: every
 * row lands in the new default playlist, order preserved — including
 * non-contiguous legacy ids, which is exactly what a real device has after
 * months of queue churn.
 */
@RunWith(RobolectricTestRunner.class)
public class QueueMigrationTest {

    private SQLiteDatabase db;

    @Before
    public void setUp() {
        db = SQLiteDatabase.create(null);
        // Legacy (pre-3160000) shape of the three tables the migration touches.
        db.execSQL("CREATE TABLE Queue (id INTEGER PRIMARY KEY, feeditem INTEGER, feed INTEGER)");
        db.execSQL("CREATE TABLE Playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT)");
        db.execSQL("CREATE TABLE PlaylistItems (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " playlist_id INTEGER, feeditem INTEGER, feed INTEGER, position INTEGER)");
    }

    @After
    public void tearDown() {
        db.close();
    }

    @Test
    public void queueRowsMigrateInOrderDespiteIdGaps() {
        // Queue positions are the row ids; real devices have gaps from removals.
        db.execSQL("INSERT INTO Queue (id, feeditem, feed) VALUES (5, 101, 1)");
        db.execSQL("INSERT INTO Queue (id, feeditem, feed) VALUES (17, 102, 1)");
        db.execSQL("INSERT INTO Queue (id, feeditem, feed) VALUES (30, 103, 2)");

        DBUpgrader.upgrade(db, 3150000, 3160000);

        long defaultId = queryLong("SELECT id FROM Playlists WHERE is_default = 1");
        List<long[]> items = playlistItems(defaultId);
        assertEquals(3, items.size());
        // Ranks are dense 0..n-1 in legacy-id order, regardless of gaps.
        assertEquals(101, items.get(0)[0]);
        assertEquals(0, items.get(0)[1]);
        assertEquals(102, items.get(1)[0]);
        assertEquals(1, items.get(1)[1]);
        assertEquals(103, items.get(2)[0]);
        assertEquals(2, items.get(2)[1]);
        // The legacy table is left in place as the rollback net.
        assertEquals(3, queryLong("SELECT COUNT(*) FROM Queue"));
    }

    @Test
    public void emptyQueueStillGetsTheDefaultPlaylistRow() {
        DBUpgrader.upgrade(db, 3150000, 3160000);
        assertEquals(1, queryLong("SELECT COUNT(*) FROM Playlists WHERE is_default = 1"));
        long defaultId = queryLong("SELECT id FROM Playlists WHERE is_default = 1");
        assertTrue(playlistItems(defaultId).isEmpty());
    }

    @Test
    public void existingNamedPlaylistsSurviveUntouched() {
        db.execSQL("INSERT INTO Playlists (title) VALUES ('Running')");
        long runningId = queryLong("SELECT id FROM Playlists WHERE title = 'Running'");
        db.execSQL("INSERT INTO PlaylistItems (playlist_id, feeditem, feed, position)"
                + " VALUES (" + runningId + ", 201, 3, 0)");
        db.execSQL("INSERT INTO Queue (id, feeditem, feed) VALUES (1, 101, 1)");

        DBUpgrader.upgrade(db, 3150000, 3160000);

        // Named playlist intact and not flagged default.
        assertEquals(0, queryLong("SELECT is_default FROM Playlists WHERE id = " + runningId));
        assertEquals(1, playlistItems(runningId).size());
        assertEquals(201, playlistItems(runningId).get(0)[0]);
        // Queue landed in its own (new) default row.
        long defaultId = queryLong("SELECT id FROM Playlists WHERE is_default = 1");
        assertTrue(defaultId != runningId);
        assertEquals(101, playlistItems(defaultId).get(0)[0]);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private long queryLong(String sql) {
        try (Cursor c = db.rawQuery(sql, null)) {
            assertTrue("no row for: " + sql, c.moveToFirst());
            return c.getLong(0);
        }
    }

    /** (feeditem, position) pairs of a playlist, ordered by position. */
    private List<long[]> playlistItems(long playlistId) {
        List<long[]> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT feeditem, position FROM PlaylistItems"
                + " WHERE playlist_id = " + playlistId + " ORDER BY position", null)) {
            while (c.moveToNext()) {
                out.add(new long[]{c.getLong(0), c.getLong(1)});
            }
        }
        return out;
    }
}
