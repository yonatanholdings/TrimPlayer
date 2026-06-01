package de.danoeh.antennapod.storage.importexport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Unit tests for the pure-Java JSON parsing in {@link PortcastImporter}. The
 * full {@code previewImport()} path hits {@code DBReader} and is exercised by
 * instrumentation; here we lock the per-section parsing behavior.
 */
@RunWith(RobolectricTestRunner.class)
public class PortcastImporterTest {

    @Test
    public void parsesSubscriptionAndAttachesPerFeedPrefs() throws Exception {
        JSONObject sub = new JSONObject()
                .put("subscriptionId", "urn:trimplayer:feed:1")
                .put("feedUrl", "https://example.com/feed.xml")
                .put("title", "Example Show")
                .put("tags", new JSONArray().put("Tech").put("Daily"));

        JSONObject perFeed = new JSONObject().put("https://example.com/feed.xml", new JSONObject()
                .put("playbackRate", 1.5)
                .put("skipIntroSeconds", 15)
                .put("skipOutroSeconds", 30)
                .put("extensions", new JSONObject().put("com.trimplayer.skips",
                        new JSONObject().put("trimSkipAds", false))));

        PortcastImporter.PortFeed pf = PortcastImporter.parseSubscription(sub, perFeed);
        assertNotNull(pf);
        assertEquals("https://example.com/feed.xml", pf.feedUrl);
        assertEquals("Example Show", pf.title);
        assertEquals(2, pf.tags.size());
        assertEquals(1.5f, pf.playbackSpeed, 0.0001);
        assertEquals(15, pf.skipIntroSec);
        assertEquals(30, pf.skipOutroSec);
        assertNotNull(pf.extensions);
        assertTrue(pf.extensions.has("com.trimplayer.skips"));
    }

    @Test
    public void dropsSubscriptionWithoutFeedUrl() throws Exception {
        // Spec allows feedUrl OR podcastGuid OR platformRefs. The importer
        // accepts the first and last; podcastGuid-only subs are still
        // dropped (rare; resolving podcastGuid isn't in scope for M2).
        JSONObject sub = new JSONObject()
                .put("subscriptionId", "urn:somewhere:1")
                .put("podcastGuid", "917393e3-1b1e-5cef-ace4-edaa54e1f810")
                .put("title", "Guid-only show");
        assertNull(PortcastImporter.parseSubscription(sub, null));
    }

    @Test
    public void acceptsPlatformRefsOnlySubscription() throws Exception {
        // Spotify-sourced subscriptions arrive with platformRefs but no
        // feedUrl; the importer keeps them and flags needsResolution so
        // SpotifyShowResolver gets called before the subscribe phase.
        JSONObject sub = new JSONObject()
                .put("subscriptionId", "ff888d1e340f4d1193f652f072d21519")
                .put("title", "Acquired")
                .put("author", "Ben Gilbert and David Rosenthal")
                .put("imageUrl", "https://i.scdn.co/image/abc")
                .put("platformRefs", new JSONArray().put("spotify:show:7Fj0XEuUQLUqoMZQdsLXqp"));

        PortcastImporter.PortFeed pf = PortcastImporter.parseSubscription(sub, null);
        assertNotNull(pf);
        assertTrue("expected needsResolution=true for platformRefs-only sub", pf.needsResolution);
        assertEquals("", pf.feedUrl);
        assertEquals("Acquired", pf.title);
        assertEquals("Ben Gilbert and David Rosenthal", pf.author);
        assertEquals("https://i.scdn.co/image/abc", pf.imageUrl);
        assertEquals("ff888d1e340f4d1193f652f072d21519", pf.subscriptionId);
        assertEquals(1, pf.platformRefs.size());
        assertEquals("spotify:show:7Fj0XEuUQLUqoMZQdsLXqp", pf.platformRefs.get(0));
    }

    @Test
    public void extractsSpotifyShowIdFromPlatformRefs() {
        assertEquals("7Fj0XEuUQLUqoMZQdsLXqp",
                PortcastImporter.spotifyShowIdFrom(
                        java.util.Arrays.asList("spotify:show:7Fj0XEuUQLUqoMZQdsLXqp")));
        // Mixed refs: returns the spotify:show entry, ignores the rest.
        assertEquals("xyz",
                PortcastImporter.spotifyShowIdFrom(java.util.Arrays.asList(
                        "applepodcasts:show:abc", "spotify:show:xyz")));
        // No spotify ref → null.
        assertNull(PortcastImporter.spotifyShowIdFrom(
                java.util.Arrays.asList("applepodcasts:show:abc")));
        // Null-safety.
        assertNull(PortcastImporter.spotifyShowIdFrom(null));
    }

    @Test
    public void platformRefsOnlySubDoesNotPullPerFeedPrefs() throws Exception {
        // Per-feed prefs are keyed by feedUrl; there's nothing to key by for
        // a platformRefs-only sub, so the importer must not crash or pull
        // wrong overrides when perFeedPrefs is populated.
        JSONObject sub = new JSONObject()
                .put("subscriptionId", "id-1")
                .put("title", "Some Show")
                .put("platformRefs", new JSONArray().put("spotify:show:xyz"));
        JSONObject perFeed = new JSONObject().put("https://example.com/feed.xml", new JSONObject()
                .put("playbackRate", 2.0));

        PortcastImporter.PortFeed pf = PortcastImporter.parseSubscription(sub, perFeed);
        assertNotNull(pf);
        assertEquals(0f, pf.playbackSpeed, 0.0001);
    }

    @Test
    public void parsesEpisodeWithCompletedStatus() throws Exception {
        JSONObject ep = new JSONObject()
                .put("episodeStateId", "urn:trimplayer:item:42")
                .put("subscriptionRef", new JSONObject().put("feedUrl", "https://example.com/feed.xml"))
                .put("guid", "guid-42")
                .put("enclosureUrl", "https://example.com/ep42.mp3")
                .put("status", "completed")
                .put("durationSeconds", 1800.0)
                .put("completedAt", "2026-04-15T10:30:00Z")
                .put("lastPlayedAt", "2026-04-15T10:31:00Z")
                .put("starred", true);

        PortcastImporter.EpisodeState state = PortcastImporter.parseEpisode(ep);
        assertNotNull(state);
        assertEquals("guid-42", state.guid);
        assertEquals("https://example.com/ep42.mp3", state.enclosureUrl);
        assertEquals("https://example.com/feed.xml", state.feedUrl);
        assertEquals("completed", state.status);
        assertEquals(1_800_000L, state.durationMs);
        assertTrue(state.starred);
        // Prefer completedAt over lastPlayedAt for chart attribution.
        assertEquals(PortcastImporter.parseRfc3339("2026-04-15T10:30:00Z"), state.lastPlayedMs);
    }

    @Test
    public void parsesInProgressEpisodeWithPositionSeconds() throws Exception {
        JSONObject ep = new JSONObject()
                .put("subscriptionRef", new JSONObject().put("feedUrl", "https://example.com/feed.xml"))
                .put("guid", "guid-x")
                .put("status", "in_progress")
                .put("positionSeconds", 612.5);

        PortcastImporter.EpisodeState state = PortcastImporter.parseEpisode(ep);
        assertNotNull(state);
        assertEquals("in_progress", state.status);
        assertEquals(612_500, state.positionMs);
    }

    @Test
    public void dropsEpisodeWithoutGuidOrEnclosure() throws Exception {
        JSONObject ep = new JSONObject()
                .put("episodeStateId", "urn:trimplayer:item:99")
                .put("status", "unplayed");
        assertNull(PortcastImporter.parseEpisode(ep));
    }

    @Test
    public void parsesQueueEntry() throws Exception {
        JSONObject q = new JSONObject()
                .put("position", 1)
                .put("episodeRef", new JSONObject().put("guid", "guid-a"));
        PortcastImporter.QueueEntry e = PortcastImporter.parseQueueEntry(q);
        assertNotNull(e);
        assertEquals("guid-a", e.guid);
        assertEquals("", e.enclosureUrl);
    }

    @Test
    public void dropsQueueEntryWithoutEpisodeRef() throws Exception {
        JSONObject q = new JSONObject().put("position", 1);
        assertNull(PortcastImporter.parseQueueEntry(q));
    }

    @Test
    public void parseRfc3339HandlesCommonShapes() {
        // yyyy-MM-dd'T'HH:mm:ss'Z'
        assertTrue(PortcastImporter.parseRfc3339("2026-04-15T10:30:00Z") > 0);
        // with millis
        assertTrue(PortcastImporter.parseRfc3339("2026-04-15T10:30:00.123Z") > 0);
        // with explicit timezone offset
        assertTrue(PortcastImporter.parseRfc3339("2026-04-15T10:30:00+02:00") > 0);
        // empty / malformed
        assertEquals(0, PortcastImporter.parseRfc3339(""));
        assertEquals(0, PortcastImporter.parseRfc3339("not a date"));
    }
}
