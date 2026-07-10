package de.danoeh.antennapod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.playback.service.trim.TrimClient;

import org.junit.Test;

import java.util.Date;

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
