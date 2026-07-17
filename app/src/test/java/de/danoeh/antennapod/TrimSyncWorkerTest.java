package de.danoeh.antennapod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.playback.service.trim.TrimClient;

import org.junit.Test;

import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

/**
 * Guards for the account-sync progress apply/push logic in {@link TrimSyncWorker}.
 *
 * <p>Regression context: a star toggle on a device that never played an episode
 * pushes a progress row with position 0, played=false and a fresh client_ts (so
 * the star wins LWW). Applying that row's position/played on another device reset
 * real listening progress to the beginning of the episode.
 */
public class TrimSyncWorkerTest {

    private static TrimClient.ProgressChange row(Long positionMs, boolean played) {
        TrimClient.ProgressChange p = new TrimClient.ProgressChange();
        p.episode_url = "https://example.com/ep.mp3";
        p.position_ms = positionMs;
        p.played = played;
        return p;
    }

    @Test
    public void starredOnlyRowsCarryNoPlayback() {
        // Star-toggle snapshots: never allowed to write position/played locally.
        assertFalse(TrimSyncWorker.representsPlayback(row(0L, false)));
        assertFalse(TrimSyncWorker.representsPlayback(row(null, false)));
    }

    @Test
    public void playbackRowsAreApplied() {
        assertTrue(TrimSyncWorker.representsPlayback(row(90_000L, false)));
        assertTrue(TrimSyncWorker.representsPlayback(row(0L, true))); // completion
        assertTrue(TrimSyncWorker.representsPlayback(row(null, true)));
    }

    private static FeedItem playedItem(String url, long lastPlayedMs) {
        FeedMedia media = new FeedMedia(null, url, 0, "audio/mpeg");
        media.setPlayedDuration(120_000);
        media.setLastPlayedTimeStatistics(lastPlayedMs);
        FeedItem item = new FeedItem();
        item.setMedia(media);
        item.setPlayed(true);
        return item;
    }

    @Test
    public void backfillHistoryEpisodesArePushed() {
        // Regression: the 60-day push window (playedRecently) left older listening off
        // the account, so web/new-device statistics looked empty. Episodes surfaced via
        // the history backfill chunk must be pushed just like recently-played ones,
        // stamped with their real last-played time for last-writer-wins.
        FeedItem oldPlay = playedItem("https://example.com/2016.mp3", 1_500_000_000_000L);
        List<TrimClient.ProgressChange> out = TrimSyncWorker.buildProgress(
                Collections.emptyList(),          // queue
                Collections.emptyList(),          // playedRecently (nothing in last 60d)
                Collections.singletonList(oldPlay), // backfill chunk
                Collections.emptyList(),          // favorites
                new HashSet<>(), new HashSet<>(), 9_999_999_999_999L);
        assertEquals(1, out.size());
        assertTrue(out.get(0).played);
        assertEquals("https://example.com/2016.mp3", out.get(0).episode_url);
        assertEquals(1_500_000_000_000L, out.get(0).client_ts); // real play time, not now
    }

    @Test
    public void backfillCompletesOnShortPage() {
        assertFalse(TrimSyncWorker.backfillComplete(500, 500)); // full page — more may remain
        assertTrue(TrimSyncWorker.backfillComplete(12, 500));   // short page — history exhausted
        assertTrue(TrimSyncWorker.backfillComplete(0, 500));
    }

    // --- named queues -------------------------------------------------------

    @Test
    public void queueNameNormalization() {
        assertEquals("default", TrimSyncWorker.normQueueName(null));
        assertEquals("default", TrimSyncWorker.normQueueName("  "));
        assertEquals("Running", TrimSyncWorker.normQueueName(" Running "));
        // Over-long names are capped to the server's 64-char limit.
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longName.append('x');
        }
        assertEquals(64, TrimSyncWorker.normQueueName(longName.toString()).length());
    }

    @Test
    public void diffQueueStampsActiveQueueOnAddsAndTombstones() {
        FeedItem item = playedItem("https://example.com/a.mp3", 1_000L);
        List<TrimClient.QueueChange> out = TrimSyncWorker.diffQueue(
                Collections.singletonList("https://example.com/gone.mp3"), // prev snapshot
                Collections.singletonList(item),                            // current queue
                Collections.singletonList("https://example.com/a.mp3"),
                "Running", 5_000L);
        assertEquals(2, out.size());
        for (TrimClient.QueueChange q : out) {
            assertEquals("Running", q.queue_name);
        }
        assertFalse(out.get(0).deleted); // the add
        assertTrue(out.get(1).deleted);  // the tombstone for the vanished url
    }

    @Test
    public void queueMarkerDiffEmitsCreatesAndDeletes() {
        HashSet<String> prev = new HashSet<>(Collections.singletonList("Driving"));
        HashSet<String> cur = new HashSet<>(Collections.singletonList("Running"));
        List<TrimClient.PrefChange> out = TrimSyncWorker.diffQueueNameMarkers(prev, cur, 7_000L);
        assertEquals(2, out.size());
        TrimClient.PrefChange created = out.get(0).deleted ? out.get(1) : out.get(0);
        TrimClient.PrefChange removed = out.get(0).deleted ? out.get(0) : out.get(1);
        assertEquals("__queue__:Running", created.rss_url);
        assertEquals(Float.valueOf(1f), created.playback_rate);
        assertEquals("__queue__:Driving", removed.rss_url);
        assertTrue(removed.deleted);
    }

    @Test
    public void playlistDiffEmitsAddsMovesAndTombstones() {
        java.util.Map<String, List<String>> prev = new java.util.HashMap<>();
        prev.put("Running", java.util.Arrays.asList("u1", "u2", "u3"));
        java.util.Map<String, List<String>> cur = new java.util.HashMap<>();
        // u2 removed, u4 appended, u3 therefore moves from index 2 to 1.
        cur.put("Running", java.util.Arrays.asList("u1", "u3", "u4"));
        List<TrimClient.QueueChange> out = TrimSyncWorker.diffPlaylists(prev, cur, 1_000L);

        java.util.Map<String, TrimClient.QueueChange> byUrl = new java.util.HashMap<>();
        for (TrimClient.QueueChange q : out) {
            assertEquals("Running", q.queue_name);
            byUrl.put(q.episode_url, q);
        }
        assertEquals(3, out.size()); // u3 move, u4 add, u2 tombstone; u1 unchanged
        assertFalse(byUrl.containsKey("u1"));
        assertEquals(Integer.valueOf(1), byUrl.get("u3").position);
        assertEquals(Integer.valueOf(2), byUrl.get("u4").position);
        assertTrue(byUrl.get("u2").deleted);
    }

    @Test
    public void deletedPlaylistTombstonesAllItsItems() {
        java.util.Map<String, List<String>> prev = new java.util.HashMap<>();
        prev.put("Driving", java.util.Arrays.asList("u1", "u2"));
        List<TrimClient.QueueChange> out = TrimSyncWorker.diffPlaylists(
                prev, new java.util.HashMap<>(), 1_000L);
        assertEquals(2, out.size());
        for (TrimClient.QueueChange q : out) {
            assertEquals("Driving", q.queue_name);
            assertTrue(q.deleted);
        }
    }

    @Test
    public void lwwKeyUsesFreshestOfStatisticsAndHistory() {
        FeedMedia media = new FeedMedia(null, "https://example.com/ep.mp3", 0, "audio/mpeg");

        // Import paths (watch flush, PortCast file) stamp history but may leave
        // statistics stale — the LWW key must still see the newer of the two.
        media.setLastPlayedTimeStatistics(1_000L);
        media.setLastPlayedTimeHistory(new Date(2_000L));
        assertEquals(2_000L, TrimSyncWorker.lastLocalPlaybackTs(media));

        media.setLastPlayedTimeStatistics(3_000L);
        assertEquals(3_000L, TrimSyncWorker.lastLocalPlaybackTs(media));

        media.setLastPlayedTimeHistory(null);
        assertEquals(3_000L, TrimSyncWorker.lastLocalPlaybackTs(media));
    }
}
