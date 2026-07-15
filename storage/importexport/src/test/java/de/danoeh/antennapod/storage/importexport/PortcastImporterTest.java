package de.danoeh.antennapod.storage.importexport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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
    public void dropsEpisodeWithoutAnyIdentity() throws Exception {
        // No guid, no enclosureUrl, no title — nothing to join on.
        JSONObject ep = new JSONObject()
                .put("episodeStateId", "urn:trimplayer:item:99")
                .put("status", "unplayed");
        assertNull(PortcastImporter.parseEpisode(ep));
    }

    @Test
    public void parsesSpotifyEpisodeByTitleAndShowRef() throws Exception {
        // Spotify-sourced episode: no guid/enclosureUrl, just a title plus a
        // show reference. The importer must keep it (title is the join key)
        // and capture the show ref for feedUrl resolution.
        JSONObject ep = new JSONObject()
                .put("episodeStateId", "abc123")
                .put("subscriptionRef", new JSONObject()
                        .put("platformRefs", new JSONArray().put("spotify:show:7Fj0XEuUQLUqoMZQdsLXqp")))
                .put("platformRefs", new JSONArray().put("spotify:episode:5h2qd"))
                .put("title", "Episode 42: The One About Foo")
                .put("durationSeconds", 1800.0)
                .put("status", "completed")
                .put("completedAt", "2026-04-15T10:30:00Z");

        PortcastImporter.EpisodeState state = PortcastImporter.parseEpisode(ep);
        assertNotNull(state);
        assertEquals("", state.guid);
        assertEquals("", state.enclosureUrl);
        assertEquals("Episode 42: The One About Foo", state.title);
        assertEquals("spotify:show:7Fj0XEuUQLUqoMZQdsLXqp", state.showRef);
        assertEquals("", state.feedUrl);
        assertEquals("completed", state.status);
        assertEquals(1_800_000L, state.durationMs);
    }

    @Test
    public void dropsSpotifyEpisodeWithoutTitle() throws Exception {
        // A Spotify episode that somehow lost its title is unjoinable.
        JSONObject ep = new JSONObject()
                .put("subscriptionRef", new JSONObject()
                        .put("platformRefs", new JSONArray().put("spotify:show:xyz")))
                .put("platformRefs", new JSONArray().put("spotify:episode:1"))
                .put("status", "completed");
        assertNull(PortcastImporter.parseEpisode(ep));
    }

    @Test
    public void firstShowRefPicksSpotifyShowUrn() throws Exception {
        assertEquals("spotify:show:xyz", PortcastImporter.firstShowRef(
                new JSONArray().put("applepodcasts:show:abc").put("spotify:show:xyz")));
        assertEquals("", PortcastImporter.firstShowRef(new JSONArray().put("spotify:episode:1")));
        assertEquals("", PortcastImporter.firstShowRef(null));
    }

    @Test
    public void normalizeTitleAbsorbsCasingAndWhitespace() {
        assertEquals("the one about foo",
                PortcastImporter.normalizeTitle("  The   One About Foo  "));
        // Surrounding punctuation/quotes are stripped; internal kept.
        assertEquals("ep 42: foo & bar",
                PortcastImporter.normalizeTitle("\"Ep 42: Foo & Bar\""));
        assertEquals("", PortcastImporter.normalizeTitle(null));
        assertEquals("", PortcastImporter.normalizeTitle("   "));
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
    public void conflictDefaultPrefersFurthestProgress() {
        // Incoming completed, local only in-progress → incoming is further.
        assertTrue(PortcastImporter.preferIncomingByProgress(
                "completed", 0, /*localPlayed*/ false, /*localPos*/ 120_000));
        // Local completed, incoming only in-progress → keep local; don't un-complete.
        assertFalse(PortcastImporter.preferIncomingByProgress(
                "in_progress", 120_000, /*localPlayed*/ true, /*localPos*/ 0));
        // Both completed → keep local untouched.
        assertFalse(PortcastImporter.preferIncomingByProgress(
                "completed", 0, /*localPlayed*/ true, /*localPos*/ 0));
        assertFalse(PortcastImporter.preferIncomingByProgress(
                "archived", 0, /*localPlayed*/ true, /*localPos*/ 0));
        // Both in-progress: furthest resume position wins.
        assertTrue(PortcastImporter.preferIncomingByProgress(
                "in_progress", 600_000, false, 120_000));
        assertFalse(PortcastImporter.preferIncomingByProgress(
                "in_progress", 120_000, false, 600_000));
        // The silent-rewind case the old hardcoded default broke: local at 18 min,
        // incoming backup at 2 min → keep local, no rewind.
        assertFalse(PortcastImporter.preferIncomingByProgress(
                "in_progress", 120_000, false, 1_080_000));
        // Tie keeps local (no needless overwrite).
        assertFalse(PortcastImporter.preferIncomingByProgress(
                "in_progress", 300_000, false, 300_000));
        // Incoming unplayed (position 0) never clobbers a local in-progress.
        assertFalse(PortcastImporter.preferIncomingByProgress(
                "unplayed", 0, false, 300_000));
    }

    @Test
    public void normalizeFeedUrlCollapsesDedupeVariants() {
        // The PortCast re-import dedupe (PortcastSubscribeWorker#dedupeFeedUrl)
        // relies on these variants normalizing to one key so a second import
        // reuses the stored feed instead of subscribing a duplicate.
        String canonical = PortcastImporter.normalizeFeedUrl("https://example.com/feed");
        assertEquals(canonical, PortcastImporter.normalizeFeedUrl("http://example.com/feed"));
        assertEquals(canonical, PortcastImporter.normalizeFeedUrl("https://www.example.com/feed"));
        assertEquals(canonical, PortcastImporter.normalizeFeedUrl("https://example.com/feed/"));
        assertEquals(canonical, PortcastImporter.normalizeFeedUrl("  HTTPS://EXAMPLE.COM/feed//  "));
        // Distinct feeds must NOT collapse together.
        assertNotEquals(canonical, PortcastImporter.normalizeFeedUrl("https://example.com/other"));
        assertEquals("", PortcastImporter.normalizeFeedUrl(null));
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

    // ── staging merge (executeImport burst behavior) ─────────────────────────

    private static PortcastImporter.EpisodeState state(String guid, int positionMs) {
        PortcastImporter.EpisodeState s = new PortcastImporter.EpisodeState();
        s.guid = guid;
        s.status = "in_progress";
        s.positionMs = positionMs;
        return s;
    }

    private static PortcastImporter.ImportPreview previewOf(
            PortcastImporter.EpisodeState... states) {
        PortcastImporter.ImportPreview p = new PortcastImporter.ImportPreview();
        for (PortcastImporter.EpisodeState s : states) {
            p.nonConflictingStates.add(s);
        }
        return p;
    }

    /** The Garmin watch transmits progress as a burst of documents ending in an
     *  empty forced reply, each staged by its own executeImport before the
     *  worker chain consumes any of them. Staging must merge — a wholesale
     *  replace meant the empty final doc erased every state of the burst. */
    @Test
    public void stagingMergesBurstsInsteadOfReplacing() throws Exception {
        android.content.Context ctx = org.robolectric.RuntimeEnvironment.getApplication();
        PortcastImporter.clearEpisodeStates(ctx);
        PortcastImporter.clearPendingFeeds(ctx);

        PortcastImporter.stageForWorkers(ctx, previewOf(state("guid-a", 1000)));
        PortcastImporter.stageForWorkers(ctx, previewOf(state("guid-b", 2000)));
        // Same key again with a newer position — incoming must replace staged.
        PortcastImporter.stageForWorkers(ctx, previewOf(state("guid-a", 3000)));
        // The empty forced reply that used to wipe the whole staging.
        PortcastImporter.stageForWorkers(ctx, previewOf());

        java.util.List<PortcastImporter.EpisodeState> staged =
                PortcastImporter.loadEpisodeStates(ctx);
        assertEquals(2, staged.size());
        assertEquals("guid-a", staged.get(0).guid);
        assertEquals(3000, staged.get(0).positionMs);
        assertEquals("guid-b", staged.get(1).guid);
        assertEquals(2000, staged.get(1).positionMs);

        PortcastImporter.clearEpisodeStates(ctx);
        PortcastImporter.clearPendingFeeds(ctx);
    }
}
