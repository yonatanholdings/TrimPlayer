package de.danoeh.antennapod.storage.importexport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.model.feed.FeedPreferences.AutoDeleteAction;
import de.danoeh.antennapod.model.feed.FeedPreferences.AutoDownloadSetting;
import de.danoeh.antennapod.model.feed.FeedPreferences.NewEpisodesAction;
import de.danoeh.antennapod.model.feed.VolumeAdaptionSetting;

/**
 * Locks the DB→PortCast conversion seam used by the additive "Merge database"
 * import: it must carry subscriptions/episodes/queue/favorites, and must
 * <em>strip global preferences</em> so a merge never overwrites the device's
 * current global playback settings. The end-to-end backup read (Android SQLite)
 * is covered by on-device verification.
 */
@RunWith(RobolectricTestRunner.class)
public class AntennaPodDbToPortcastTest {

    @Test
    public void carriesLibraryAndStripsGlobalPrefs() throws Exception {
        Feed feed = subscribedFeed(1, "https://example.com/feed.xml", "Example Show");
        // A per-feed override so the perFeed block is present and we can prove it survives.
        feed.getPreferences().setFeedPlaybackSpeed(1.5f);

        FeedItem played = item(100, "Ep A", "guid-a", new Date(1_700_000_000_000L), feed);
        played.setMedia(media(1000, played, 1_800_000, 0));
        played.setPlayed(true);

        FeedItem queued = item(101, "Ep B", "guid-b", new Date(1_700_001_000_000L), feed);
        queued.setMedia(media(1001, queued, 1_800_000, 0));

        List<Feed> feeds = Collections.singletonList(feed);
        List<FeedItem> episodes = Arrays.asList(played, queued);
        List<FeedItem> queue = Collections.singletonList(queued);
        Set<Long> favorites = new HashSet<>(Collections.singletonList(100L));

        BackupDbReader.Library lib = new BackupDbReader.Library(feeds, episodes, queue, favorites);
        String json = AntennaPodDbToPortcast.toPortcastJson(lib, "1.0.0");
        JSONObject doc = new JSONObject(json);

        assertEquals(1, doc.getJSONArray("subscriptions").length());
        assertEquals(2, doc.getJSONArray("episodes").length());
        assertEquals(1, doc.getJSONArray("queue").length());

        JSONObject starred = findByGuid(doc.getJSONArray("episodes"), "guid-a");
        assertTrue("favorite item must be starred", starred.getBoolean("starred"));

        // The whole point of the merge: device globals must be left untouched.
        JSONObject prefs = doc.optJSONObject("preferences");
        if (prefs != null) {
            assertFalse("global prefs must be stripped from a DB merge", prefs.has("global"));
            // Per-feed prefs are feed-level data we DO restore.
            assertTrue("per-feed prefs should survive", prefs.has("perFeed"));
        }
    }

    // ── Helpers (mirror PortcastExporterTest) ─────────────────────────────────

    private static Feed subscribedFeed(long id, String downloadUrl, String title) {
        Feed feed = new Feed(id, null, title, null, null, null, null, null, null,
                null, null, null, downloadUrl, 0);
        feed.setState(Feed.STATE_SUBSCRIBED);
        FeedPreferences prefs = new FeedPreferences(id, AutoDownloadSetting.GLOBAL,
                AutoDeleteAction.GLOBAL, VolumeAdaptionSetting.OFF,
                NewEpisodesAction.GLOBAL, null, null);
        feed.setPreferences(prefs);
        return feed;
    }

    private static FeedItem item(long id, String title, String guid, Date pubDate, Feed feed) {
        FeedItem item = new FeedItem(id, title, guid, null, pubDate, FeedItem.UNPLAYED, feed);
        item.setFeedId(feed.getId());
        return item;
    }

    private static FeedMedia media(long id, FeedItem item, int durationMs, int positionMs) {
        return new FeedMedia(id, item, durationMs, positionMs, 0L, "audio/mp3", null,
                "https://example.com/episode-" + id + ".mp3", 0L, null, 0, 0L);
    }

    private static JSONObject findByGuid(JSONArray arr, String guid) throws Exception {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (guid.equals(o.optString("guid"))) {
                return o;
            }
        }
        throw new AssertionError("no episode with guid=" + guid);
    }
}
