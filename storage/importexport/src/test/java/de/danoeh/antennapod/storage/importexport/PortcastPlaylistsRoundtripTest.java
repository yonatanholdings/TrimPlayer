package de.danoeh.antennapod.storage.importexport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.model.feed.VolumeAdaptionSetting;

/**
 * The com.trimplayer.playlists extension must roundtrip through export
 * (PortcastExporter.buildPlaylists) and import (PortcastImporter.parsePlaylist):
 * playlist names, episode order, and auto-add rule cutoffs survive byte-exact
 * enough that a phone → file → phone (or web) journey loses nothing.
 */
@RunWith(RobolectricTestRunner.class)
public class PortcastPlaylistsRoundtripTest {

    private static final long CUTOFF_MS = 1_784_200_000_000L; // some fixed instant

    @Test
    public void playlistsRoundtripThroughExportAndImport() throws Exception {
        Feed feed = subscribedFeed(1, "https://example.com/feed.xml", "Show");
        FeedItem a = item(11, "A", "guid-a", feed);
        FeedItem b = item(12, "B", "guid-b", feed);

        PortcastExporter.PlaylistExport running =
                new PortcastExporter.PlaylistExport("Running", Arrays.asList(a, b));
        running.autoAddSinceByFeedUrl.put("https://example.com/feed.xml", CUTOFF_MS);
        PortcastExporter.PlaylistExport empty =
                new PortcastExporter.PlaylistExport("Driving", Collections.emptyList());

        JSONObject doc = PortcastExporter.buildDocument(
                Collections.singletonList(feed), Collections.emptyList(), Collections.emptyList(),
                Collections.emptySet(), new HashMap<>(), Arrays.asList(running, empty),
                "Owner", new PortcastExporter.GlobalPrefs(1.0f, 30, 10, false), "1.0.0");

        JSONArray ext = doc.getJSONObject("extensions")
                .getJSONArray(PortcastExporter.EXT_PLAYLISTS);
        assertEquals(2, ext.length());

        // Import side: parse each element back.
        PortcastImporter.PortPlaylist parsedRunning =
                PortcastImporter.parsePlaylist(ext.getJSONObject(0));
        assertEquals("Running", parsedRunning.name);
        assertEquals(2, parsedRunning.entries.size());
        assertEquals("guid-a", parsedRunning.entries.get(0).guid);
        assertEquals("guid-b", parsedRunning.entries.get(1).guid);
        // RFC 3339 has second precision — the cutoff survives to the second.
        long parsedSince = parsedRunning.autoAddSinceByFeedUrl.get("https://example.com/feed.xml");
        assertEquals(CUTOFF_MS / 1000, parsedSince / 1000);

        PortcastImporter.PortPlaylist parsedEmpty =
                PortcastImporter.parsePlaylist(ext.getJSONObject(1));
        assertEquals("Driving", parsedEmpty.name);
        assertTrue(parsedEmpty.entries.isEmpty());
        assertTrue(parsedEmpty.autoAddSinceByFeedUrl.isEmpty());
    }

    @Test
    public void noPlaylistsMeansNoExtensionsObject() throws Exception {
        JSONObject doc = PortcastExporter.buildDocument(
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptySet(), "Owner",
                new PortcastExporter.GlobalPrefs(1.0f, 30, 10, false), "1.0.0");
        assertFalse(doc.has("extensions"));
    }

    @Test
    public void namelessPlaylistIsRejectedOnImport() throws Exception {
        JSONObject nameless = new JSONObject().put("episodes", new JSONArray());
        assertNull(PortcastImporter.parsePlaylist(nameless));
    }

    // ── helpers (mirrors PortcastExporterTest) ───────────────────────────────

    private static Feed subscribedFeed(long id, String downloadUrl, String title) {
        Feed feed = new Feed(id, null, title, null, null, null, null, null, null,
                null, null, null, downloadUrl, 0);
        feed.setState(Feed.STATE_SUBSCRIBED);
        FeedPreferences prefs = new FeedPreferences(id,
                FeedPreferences.AutoDownloadSetting.GLOBAL,
                FeedPreferences.AutoDeleteAction.GLOBAL, VolumeAdaptionSetting.OFF,
                FeedPreferences.NewEpisodesAction.GLOBAL, null, null);
        feed.setPreferences(prefs);
        return feed;
    }

    private static FeedItem item(long id, String title, String guid, Feed feed) {
        FeedItem item = new FeedItem(id, title, guid, null, new Date(0), FeedItem.UNPLAYED, feed);
        item.setFeedId(feed.getId());
        item.setMedia(new FeedMedia(id, item, 60_000, 0, 0L, "audio/mpeg", null,
                "https://example.com/episode-" + id + ".mp3", 0L, null, 0, 0L));
        return item;
    }
}
