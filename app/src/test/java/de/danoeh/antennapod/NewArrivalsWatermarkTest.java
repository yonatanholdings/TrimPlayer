package de.danoeh.antennapod;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import de.danoeh.antennapod.playback.service.trim.TrimClient;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Guards for the new-arrivals watermark: the monotonic preference itself, and
 * the {@code __seen__} reserved pref row that carries it between devices.
 *
 * <p>See NEW_ARRIVALS_WATERMARK_PLAN.md. The watermark replaced the Inbox's
 * per-episode {@code isNew} flag, so correctness here is what stops arrivals
 * either resurrecting or disappearing unread.
 */
@RunWith(RobolectricTestRunner.class)
public class NewArrivalsWatermarkTest {

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        UserPreferences.init(context);
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .edit().remove(UserPreferences.PREF_LAST_SEEN_PUBDATE).apply();
    }

    // ── the preference ──────────────────────────────────────────────────────

    @Test
    public void firstReadSeedsAStableStamp() {
        long before = System.currentTimeMillis();
        long first = UserPreferences.getLastSeenPubDate();
        long second = UserPreferences.getLastSeenPubDate();

        assertTrue("seeded at roughly now", first >= before);
        // Must persist, not re-derive from the clock on every read: a moving
        // default would mean arrivals never accumulate.
        assertEquals(first, second);
    }

    @Test
    public void advancingForwardsIsStored() {
        long seeded = UserPreferences.getLastSeenPubDate();

        UserPreferences.setLastSeenPubDate(seeded + 60_000L);

        assertEquals(seeded + 60_000L, UserPreferences.getLastSeenPubDate());
    }

    @Test
    public void movingBackwardsIsRefused() {
        long seeded = UserPreferences.getLastSeenPubDate();

        UserPreferences.setLastSeenPubDate(seeded - 60_000L);

        // Monotonic: an older value would resurrect arrivals the user cleared.
        assertEquals(seeded, UserPreferences.getLastSeenPubDate());
    }

    @Test
    public void markAllSeenMovesTheBoundaryForward() {
        long seeded = UserPreferences.getLastSeenPubDate();
        UserPreferences.setLastSeenPubDate(seeded - 10_000L); // no-op, proves the clamp

        UserPreferences.markAllArrivalsSeen();

        assertTrue(UserPreferences.getLastSeenPubDate() >= seeded);
    }

    // ── the synced __seen__ row ─────────────────────────────────────────────

    private static TrimClient.PrefChange marker(String key, boolean deleted) {
        TrimClient.PrefChange p = new TrimClient.PrefChange();
        p.rss_url = key;
        p.playback_rate = deleted ? null : 1f;
        p.deleted = deleted;
        return p;
    }

    @Test
    public void anUnchangedWatermarkPushesNothing() {
        assertTrue(TrimSyncWorker.diffSeenMarker(1_700_000_000_000L, 1_700_000_000_000L, 1L).isEmpty());
        assertTrue("nothing to push before the first seed",
                TrimSyncWorker.diffSeenMarker(0L, 0L, 1L).isEmpty());
    }

    @Test
    public void theFirstPushCarriesNoTombstone() {
        List<TrimClient.PrefChange> out = TrimSyncWorker.diffSeenMarker(0L, 1_700_000_000_000L, 7L);

        assertEquals(1, out.size());
        assertEquals(TrimSyncWorker.SEEN_MARKER_PREFIX + "1700000000000", out.get(0).rss_url);
        assertEquals(false, out.get(0).deleted);
        assertEquals(7L, out.get(0).client_ts);
    }

    @Test
    public void advancingTombstonesThePreviousKey() {
        List<TrimClient.PrefChange> out =
                TrimSyncWorker.diffSeenMarker(1_700_000_000_000L, 1_700_000_060_000L, 9L);

        // Without the tombstone the server accumulates one row per advance,
        // since the value is encoded in the key.
        assertEquals(2, out.size());
        assertEquals(TrimSyncWorker.SEEN_MARKER_PREFIX + "1700000000000", out.get(0).rss_url);
        assertTrue(out.get(0).deleted);
        assertEquals(TrimSyncWorker.SEEN_MARKER_PREFIX + "1700000060000", out.get(1).rss_url);
        assertEquals(false, out.get(1).deleted);
    }

    @Test
    public void incomingRowsResolveToTheLatestWatermark() {
        long seen = TrimSyncWorker.seenFromPrefs(Arrays.asList(
                marker(TrimSyncWorker.SEEN_MARKER_PREFIX + "1700000000000", false),
                marker(TrimSyncWorker.SEEN_MARKER_PREFIX + "1700000060000", false)));

        assertEquals(1_700_000_060_000L, seen);
    }

    @Test
    public void tombstonesAndForeignKeysAreIgnored() {
        long seen = TrimSyncWorker.seenFromPrefs(Arrays.asList(
                marker(TrimSyncWorker.SEEN_MARKER_PREFIX + "1700000060000", true),
                marker("__queue_title__:Running", false),
                marker("https://example.com/feed.xml", false)));

        assertEquals(0L, seen);
    }

    @Test
    public void malformedMarkerIsSkippedRatherThanFatal() {
        long seen = TrimSyncWorker.seenFromPrefs(Arrays.asList(
                marker(TrimSyncWorker.SEEN_MARKER_PREFIX + "not-a-number", false),
                marker(TrimSyncWorker.SEEN_MARKER_PREFIX + "1700000000000", false)));

        assertEquals(1_700_000_000_000L, seen);
    }

    @Test
    public void anEmptyOrNullDeltaIsHarmless() {
        assertEquals(0L, TrimSyncWorker.seenFromPrefs(null));
        assertEquals(0L, TrimSyncWorker.seenFromPrefs(new ArrayList<>()));
    }

    @Test
    public void mergeIsMonotonicNotLastWriterWins() {
        // The apply path calls setLastSeenPubDate, so an older incoming value
        // cannot undo a triage performed on this device.
        long local = UserPreferences.getLastSeenPubDate();
        long incoming = TrimSyncWorker.seenFromPrefs(Collections.singletonList(
                marker(TrimSyncWorker.SEEN_MARKER_PREFIX + (local - 120_000L), false)));

        UserPreferences.setLastSeenPubDate(incoming);

        assertEquals(local, UserPreferences.getLastSeenPubDate());
    }
}
