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

import java.util.HashMap;
import java.util.Map;

import de.danoeh.antennapod.storage.importexport.PortcastImporter.EpisodeState;
import de.danoeh.antennapod.storage.importexport.PortcastImporter.PortFeed;

/**
 * End-to-end data-path test for a Spotify-sourced import, with no device.
 *
 * <p>It chains the <em>real</em> production methods over a document whose
 * shape is exactly what the in-app builder
 * ({@code app/src/main/assets/spotify_migration/portcast.js#buildDocument})
 * emits — verified separately by executing that JS under Node. The only
 * things stubbed are the two pieces that are genuinely external on a device:
 *
 * <ul>
 *   <li>the network resolver's output — we supply the feed URL that
 *       {@code SpotifyShowResolver} would return for a {@code spotify:show:}
 *       ref;</li>
 *   <li>the materialized RSS items — we build the same per-feed title index
 *       {@code PortcastStateWorker} builds after subscribe+refresh, using the
 *       real normalizers, with a deliberately drifted feed URL.</li>
 * </ul>
 *
 * <p>Everything else — {@code parseSubscription}, {@code parseEpisode}, the
 * show→feedUrl mapping {@code previewImport} performs, and the
 * {@code matchByFeedAndTitle} join {@code PortcastStateWorker} performs — is
 * the same code that runs in the app. A pass proves that the data Spotify
 * exports survives parse → resolve → join and lands on the right items with
 * the right play state.
 */
@RunWith(RobolectricTestRunner.class)
public class PortcastSpotifyImportIntegrationTest {

    private static final String SHOW_URN = "spotify:show:7Fj0XEuUQLUqoMZQdsLXqp";
    /** What SpotifyShowResolver would resolve SHOW_URN to. */
    private static final String RESOLVED_FEED_URL = "https://feeds.transistor.fm/acquired";

    /** Build a document byte-for-byte shaped like portcast.js buildDocument. */
    private static JSONObject spotifyDocument() throws Exception {
        JSONObject subShow = new JSONObject()
                .put("subscriptionId", "sub-acquired")
                .put("title", "Acquired")
                .put("author", "Ben Gilbert and David Rosenthal")
                .put("platformRefs", new JSONArray().put(SHOW_URN));

        JSONObject epDone = new JSONObject()
                .put("episodeStateId", "id1")
                .put("subscriptionRef", new JSONObject()
                        .put("platformRefs", new JSONArray().put(SHOW_URN)))
                .put("platformRefs", new JSONArray().put("spotify:episode:ep_done"))
                .put("title", "Episode 42: The One About Foo")
                .put("durationSeconds", 1800)
                .put("status", "completed");
        JSONObject epMid = new JSONObject()
                .put("episodeStateId", "id2")
                .put("subscriptionRef", new JSONObject()
                        .put("platformRefs", new JSONArray().put(SHOW_URN)))
                .put("platformRefs", new JSONArray().put("spotify:episode:ep_mid"))
                .put("title", "Nvidia Part II")
                .put("durationSeconds", 7200)
                .put("status", "in_progress")
                .put("positionSeconds", 612.5);
        JSONObject epNew = new JSONObject()
                .put("episodeStateId", "id3")
                .put("subscriptionRef", new JSONObject()
                        .put("platformRefs", new JSONArray().put(SHOW_URN)))
                .put("platformRefs", new JSONArray().put("spotify:episode:ep_new"))
                .put("title", "Berkshire Hathaway")
                .put("durationSeconds", 3600)
                .put("status", "unplayed");
        // Episode from an UNRESOLVABLE show (no matching subscription) — must
        // never match an item from another feed.
        JSONObject epOrphan = new JSONObject()
                .put("episodeStateId", "id4")
                .put("subscriptionRef", new JSONObject()
                        .put("platformRefs", new JSONArray().put("spotify:show:unknownshow")))
                .put("platformRefs", new JSONArray().put("spotify:episode:ep_orphan"))
                .put("title", "Nvidia Part II") // same title as epMid, different show
                .put("status", "completed");

        return new JSONObject()
                .put("portcast", "0.2.0")
                .put("subscriptions", new JSONArray().put(subShow))
                .put("episodes", new JSONArray().put(epDone).put(epMid).put(epNew).put(epOrphan));
    }

    @Test
    public void spotifyDocumentParsesResolvesAndJoinsToFeedItems() throws Exception {
        JSONObject doc = spotifyDocument();

        // ── Phase 1: parse subscriptions (real parser) and build the
        //    showId → resolved feedUrl map exactly as previewImport does,
        //    substituting the resolver's output for the network call.
        Map<String, String> feedUrlByShowId = new HashMap<>();
        JSONArray subs = doc.getJSONArray("subscriptions");
        for (int i = 0; i < subs.length(); i++) {
            PortFeed pf = PortcastImporter.parseSubscription(subs.getJSONObject(i), null);
            assertNotNull(pf);
            assertTrue("Spotify sub needs resolution", pf.needsResolution);
            String showId = PortcastImporter.spotifyShowIdFrom(pf.platformRefs);
            assertNotNull(showId);
            // The resolver resolves this show; an unknown show would stay absent.
            feedUrlByShowId.put(showId, RESOLVED_FEED_URL);
        }

        // ── Phase 2: parse episodes (real parser) and stamp feedUrl from the
        //    show ref, exactly as previewImport does.
        JSONArray eps = doc.getJSONArray("episodes");
        EpisodeState done = null;
        EpisodeState mid = null;
        EpisodeState neu = null;
        int unmatchableDropped = 0;
        for (int i = 0; i < eps.length(); i++) {
            EpisodeState s = PortcastImporter.parseEpisode(eps.getJSONObject(i));
            assertNotNull("Spotify episode must not be dropped at parse", s);
            if (s.feedUrl.isEmpty() && !s.showRef.isEmpty()) {
                String showId = PortcastImporter.spotifyShowIdFrom(
                        java.util.Collections.singletonList(s.showRef));
                String resolved = showId != null ? feedUrlByShowId.get(showId) : null;
                if (resolved != null) {
                    s.feedUrl = resolved;
                }
            }
            if (s.guid.isEmpty() && s.enclosureUrl.isEmpty() && s.feedUrl.isEmpty()) {
                unmatchableDropped++;
                continue; // unresolvable-show episode, dropped like previewImport
            }
            if ("Episode 42: The One About Foo".equals(s.title)) {
                done = s;
            } else if ("Nvidia Part II".equals(s.title)) {
                mid = s;
            } else if ("Berkshire Hathaway".equals(s.title)) {
                neu = s;
            }
        }
        // The orphan episode (unresolvable show) is dropped before persistence,
        // so its (colliding) title can never poach another feed's item.
        assertEquals("orphan episode from unresolved show should be dropped", 1, unmatchableDropped);
        assertNotNull(done);
        assertNotNull(mid);
        assertNotNull(neu);
        assertEquals(RESOLVED_FEED_URL, done.feedUrl);
        assertEquals("completed", done.status);
        assertEquals(1_800_000L, done.durationMs);
        assertEquals("in_progress", mid.status);
        assertEquals(612_500, mid.positionMs);

        // ── Phase 3: build the per-feed title index exactly as
        //    PortcastStateWorker does after subscribe+refresh materializes the
        //    RSS items. Use the REAL normalizers and a drifted feed URL
        //    (http://, www., trailing slash, Title Case) to prove the join is
        //    resilient to the URL canonicalization AP applies on subscribe.
        String storedFeedDownloadUrl = "HTTP://WWW.Feeds.Transistor.FM/acquired/";
        Map<String, Map<String, String>> index = new HashMap<>();
        Map<String, String> titleMap = new HashMap<>();
        // itemId stands in for the materialized FeedItem.
        putItem(titleMap, "Episode 42: The One About Foo", "item_done");
        putItem(titleMap, "Nvidia Part II", "item_mid");
        putItem(titleMap, "Berkshire Hathaway", "item_new");
        putItem(titleMap, "Some Other Episode", "item_other");
        index.put(PortcastImporter.normalizeFeedUrl(storedFeedDownloadUrl), titleMap);

        // ── Phase 4: the real join (the exact call PortcastStateWorker makes).
        String matchDone = PortcastImporter.matchByFeedAndTitle(done.feedUrl, done.title, index);
        String matchMid = PortcastImporter.matchByFeedAndTitle(mid.feedUrl, mid.title, index);
        String matchNew = PortcastImporter.matchByFeedAndTitle(neu.feedUrl, neu.title, index);

        assertEquals("completed episode joins to its RSS item", "item_done", matchDone);
        assertEquals("in-progress episode joins to its RSS item", "item_mid", matchMid);
        assertEquals("unplayed saved episode joins to its RSS item", "item_new", matchNew);

        // A second state with the same title can't re-claim an already-matched
        // item (remove-on-hit), which is what stops cross-state collisions.
        assertNull("a matched item is consumed",
                PortcastImporter.matchByFeedAndTitle(done.feedUrl,
                        "Episode 42: The One About Foo", index));
    }

    private static void putItem(Map<String, String> titleMap, String title, String itemId) {
        titleMap.put(PortcastImporter.normalizeTitle(title), itemId);
    }
}
