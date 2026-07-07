package de.danoeh.antennapod.storage.importexport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.danoeh.antennapod.model.feed.Bookmark;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.model.feed.FeedPreferences.AutoDeleteAction;
import de.danoeh.antennapod.model.feed.FeedPreferences.AutoDownloadSetting;
import de.danoeh.antennapod.model.feed.FeedPreferences.NewEpisodesAction;
import de.danoeh.antennapod.model.feed.VolumeAdaptionSetting;

/**
 * Validates the PortCast v0.1.0 document we emit against the spec's hard
 * invariants. Uses Robolectric for {@code org.json}.
 */
@RunWith(RobolectricTestRunner.class)
public class PortcastExporterTest {

    @Test
    public void emitsSpecRequiredTopLevelFields() throws Exception {
        JSONObject doc = PortcastExporter.buildDocument(
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptySet(), "TrimPlayer User", defaultGlobals(), "1.0.0");

        // Spec: required top-level keys.
        assertEquals("0.1.0", doc.getString("portcast"));
        assertNotNull(doc.getString("generatedAt"));
        assertEquals("TrimPlayer", doc.getJSONObject("generator").getString("name"));
        assertEquals("1.0.0", doc.getJSONObject("generator").getString("version"));
        assertEquals("TrimPlayer User", doc.getJSONObject("owner").getString("displayName"));
        assertNotNull(doc.getJSONArray("subscriptions"));
        assertNotNull(doc.getJSONArray("episodes"));
    }

    @Test
    public void subscriptionsCarryFeedUrlAndRequiredFields() throws Exception {
        Feed feed = subscribedFeed(1, "https://example.com/feed.xml", "Example Show");
        feed.getPreferences().getTags().add("Tech");
        feed.getPreferences().getTags().add(FeedPreferences.TAG_ROOT); // should be filtered

        JSONObject doc = PortcastExporter.buildDocument(
                Collections.singletonList(feed), Collections.emptyList(), Collections.emptyList(),
                Collections.emptySet(), "Yonatan", defaultGlobals(), "1.0.0");

        JSONArray subs = doc.getJSONArray("subscriptions");
        assertEquals(1, subs.length());
        JSONObject sub = subs.getJSONObject(0);

        // Spec required on Subscription: subscriptionId, title, updatedAt; anyOf {feedUrl, podcastGuid}.
        assertEquals("urn:trimplayer:feed:1", sub.getString("subscriptionId"));
        assertEquals("Example Show", sub.getString("title"));
        assertNotNull(sub.getString("updatedAt"));
        assertEquals("https://example.com/feed.xml", sub.getString("feedUrl"));
        assertFalse("podcastGuid not yet supported in v0.1 exporter", sub.has("podcastGuid"));

        JSONArray tags = sub.getJSONArray("tags");
        assertEquals(1, tags.length()); // #root filtered out
        assertEquals("Tech", tags.getString(0));
    }

    @Test
    public void episodeStateRespectsSpecInvariants() throws Exception {
        Feed feed = subscribedFeed(1, "https://example.com/feed.xml", "Example Show");

        // A: completed (played=true)
        FeedItem completed = item(100, "Ep A", "guid-a", new Date(1_700_000_000_000L), feed);
        FeedMedia mediaA = media(1000, completed, 1800_000, 1_800_000, "audio/mp3");
        mediaA.setLastPlayedTimeStatistics(1_700_100_000_000L);
        completed.setMedia(mediaA);
        completed.setPlayed(true);

        // B: in-progress (position > 0, not played)
        FeedItem inProgress = item(101, "Ep B", "guid-b", new Date(1_700_001_000_000L), feed);
        FeedMedia mediaB = media(1001, inProgress, 1800_000, 600_000, "audio/mp3");
        inProgress.setMedia(mediaB);

        // C: unplayed
        FeedItem unplayed = item(102, "Ep C", "guid-c", new Date(1_700_002_000_000L), feed);
        FeedMedia mediaC = media(1002, unplayed, 1800_000, 0, "audio/mp3");
        unplayed.setMedia(mediaC);

        // D: starred via favorites set
        FeedItem starred = item(103, "Ep D", "guid-d", new Date(1_700_003_000_000L), feed);
        FeedMedia mediaD = media(1003, starred, 1800_000, 0, "audio/mp3");
        starred.setMedia(mediaD);
        Set<Long> favorites = new HashSet<>(Collections.singletonList(103L));

        // E: dropped — has no guid AND no enclosureUrl
        FeedItem orphan = item(104, "Ep E", null, new Date(1_700_004_000_000L), feed);

        // F: dropped — references a feed that isn't subscribed
        Feed unsubscribed = new Feed(2, null, "Other", null, null, null, null, null, null,
                null, null, null, "https://other.example.com/feed.xml", 0);
        unsubscribed.setState(Feed.STATE_NOT_SUBSCRIBED);
        FeedItem nonSubItem = item(105, "Ep F", "guid-f", new Date(1_700_005_000_000L), unsubscribed);

        List<FeedItem> items = Arrays.asList(completed, inProgress, unplayed, starred, orphan, nonSubItem);

        JSONObject doc = PortcastExporter.buildDocument(
                Arrays.asList(feed, unsubscribed), items, Collections.emptyList(),
                favorites, "Yonatan", defaultGlobals(), "1.0.0");

        JSONArray episodes = doc.getJSONArray("episodes");
        // orphan (no guid+no url) and nonSubItem (unsubscribed feed) dropped → 4 emitted.
        assertEquals(4, episodes.length());

        JSONObject epA = findByGuid(episodes, "guid-a");
        assertEquals("completed", epA.getString("status"));
        assertTrue("completed episode keeps lastPlayedAt", epA.has("lastPlayedAt"));
        assertTrue("completed episode emits completedAt", epA.has("completedAt"));
        assertFalse("completed episode must not require positionSeconds", epA.has("positionSeconds"));

        JSONObject epB = findByGuid(episodes, "guid-b");
        assertEquals("in_progress", epB.getString("status"));
        // Spec MUST: positionSeconds is required when status is in_progress.
        assertEquals(600.0, epB.getDouble("positionSeconds"), 0.001);

        JSONObject epC = findByGuid(episodes, "guid-c");
        assertEquals("unplayed", epC.getString("status"));

        JSONObject epD = findByGuid(episodes, "guid-d");
        assertTrue(epD.getBoolean("starred"));

        // All episodes must reference a subscription in this doc.
        Set<String> feedUrls = new HashSet<>();
        JSONArray subs = doc.getJSONArray("subscriptions");
        for (int i = 0; i < subs.length(); i++) {
            feedUrls.add(subs.getJSONObject(i).getString("feedUrl"));
        }
        for (int i = 0; i < episodes.length(); i++) {
            JSONObject ep = episodes.getJSONObject(i);
            String refUrl = ep.getJSONObject("subscriptionRef").getString("feedUrl");
            assertTrue("episode subscriptionRef must match a subscription",
                    feedUrls.contains(refUrl));
            assertTrue("episode must carry guid or enclosureUrl",
                    ep.has("guid") || ep.has("enclosureUrl"));
        }
    }

    @Test
    public void queueIsOneBasedAndSkipsItemsWithoutIdentity() throws Exception {
        Feed feed = subscribedFeed(1, "https://example.com/feed.xml", "Example Show");
        FeedItem a = item(100, "A", "guid-a", new Date(), feed);
        a.setMedia(media(1000, a, 60_000, 0, "audio/mp3"));
        FeedItem b = item(101, "B", "guid-b", new Date(), feed);
        b.setMedia(media(1001, b, 60_000, 0, "audio/mp3"));
        FeedItem orphan = item(102, "Orphan", null, new Date(), feed); // no media, no guid

        JSONObject doc = PortcastExporter.buildDocument(
                Collections.singletonList(feed), Collections.emptyList(),
                Arrays.asList(a, orphan, b), Collections.emptySet(),
                "Yonatan", defaultGlobals(), "1.0.0");

        JSONArray queue = doc.getJSONArray("queue");
        assertEquals(2, queue.length());
        assertEquals(1, queue.getJSONObject(0).getInt("position"));
        assertEquals("guid-a", queue.getJSONObject(0).getJSONObject("episodeRef").getString("guid"));
        assertEquals(2, queue.getJSONObject(1).getInt("position"));
        assertEquals("guid-b", queue.getJSONObject(1).getJSONObject("episodeRef").getString("guid"));
    }

    @Test
    public void preferencesEmitsGlobalAndPerFeedOverridesOnly() throws Exception {
        // Feed 1: has overrides; Feed 2: only defaults → must not appear under perFeed.
        Feed withOverrides = subscribedFeed(1, "https://example.com/a.xml", "A");
        withOverrides.getPreferences().setFeedPlaybackSpeed(1.5f);
        withOverrides.getPreferences().setFeedSkipIntro(15);
        withOverrides.getPreferences().setFeedSkipEnding(30);

        Feed noOverrides = subscribedFeed(2, "https://example.com/b.xml", "B");
        // leave defaults

        PortcastExporter.GlobalPrefs globals = new PortcastExporter.GlobalPrefs(
                1.25f, 30, 10, true);

        JSONObject doc = PortcastExporter.buildDocument(
                Arrays.asList(withOverrides, noOverrides),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptySet(), "Yonatan", globals, "1.0.0");

        JSONObject prefs = doc.getJSONObject("preferences");
        JSONObject global = prefs.getJSONObject("global");
        assertEquals(1.25, global.getDouble("playbackRate"), 0.0001);
        assertEquals(30, global.getInt("skipForwardSeconds"));
        assertEquals(10, global.getInt("skipBackwardSeconds"));
        assertTrue(global.getBoolean("trimSilence"));

        JSONObject perFeed = prefs.getJSONObject("perFeed");
        // noOverrides has no rate/intro/outro overrides, but the extensions block is
        // always emitted (TrimPlayer-specific defaults round-trip). So both feeds
        // will be present; just assert the override values for the first one.
        JSONObject overrideEntry = perFeed.getJSONObject("https://example.com/a.xml");
        assertEquals(1.5, overrideEntry.getDouble("playbackRate"), 0.0001);
        assertEquals(15, overrideEntry.getInt("skipIntroSeconds"));
        assertEquals(30, overrideEntry.getInt("skipOutroSeconds"));
        assertTrue(overrideEntry.has("extensions"));

        JSONObject ext = overrideEntry.getJSONObject("extensions");
        assertTrue(ext.has("com.trimplayer.skips"));
    }

    @Test
    public void bookmarksRoundTripThroughExtensionNamespace() throws Exception {
        Feed feed = subscribedFeed(1, "https://example.com/feed.xml", "Example Show");
        FeedItem withBookmarks = item(100, "Ep A", "guid-a", new Date(1_700_000_000_000L), feed);
        withBookmarks.setMedia(media(1000, withBookmarks, 1800_000, 0, "audio/mp3"));
        FeedItem without = item(101, "Ep B", "guid-b", new Date(1_700_001_000_000L), feed);
        without.setMedia(media(1001, without, 1800_000, 0, "audio/mp3"));

        Map<Long, List<Bookmark>> bookmarks = new HashMap<>();
        bookmarks.put(100L, Arrays.asList(
                new Bookmark(1, 100, 754_500, "great quote", 1_700_100_000_000L),
                new Bookmark(2, 100, 60_000, "", 1_700_200_000_000L)));

        JSONObject doc = PortcastExporter.buildDocument(
                Collections.singletonList(feed), Arrays.asList(withBookmarks, without),
                Collections.emptyList(), Collections.emptySet(), bookmarks,
                "Yonatan", defaultGlobals(), "1.0.0");

        JSONArray episodes = doc.getJSONArray("episodes");
        JSONObject epA = findByGuid(episodes, "guid-a");
        JSONArray emitted = epA.getJSONObject("extensions")
                .getJSONArray(PortcastExporter.EXT_EPISODE_BOOKMARKS);
        assertEquals(2, emitted.length());
        assertEquals(754.5, emitted.getJSONObject(0).getDouble("positionSeconds"), 0.001);
        assertEquals("great quote", emitted.getJSONObject(0).getString("note"));
        assertNotNull(emitted.getJSONObject(0).getString("createdAt"));
        assertFalse("empty note omitted", emitted.getJSONObject(1).has("note"));

        JSONObject epB = findByGuid(episodes, "guid-b");
        assertFalse("no extensions block without bookmarks", epB.has("extensions"));

        // Round-trip: the importer reads back what the exporter wrote.
        PortcastImporter.EpisodeState state = PortcastImporter.parseEpisode(epA);
        assertNotNull(state);
        assertEquals(2, state.bookmarks.size());
        assertEquals(754_500, state.bookmarks.get(0).positionMs);
        assertEquals("great quote", state.bookmarks.get(0).note);
        assertTrue(state.bookmarks.get(0).createdAtMs > 0);
        assertEquals(60_000, state.bookmarks.get(1).positionMs);
        assertEquals("", state.bookmarks.get(1).note);

        PortcastImporter.EpisodeState stateB = PortcastImporter.parseEpisode(epB);
        assertNotNull(stateB);
        assertTrue(stateB.bookmarks.isEmpty());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static PortcastExporter.GlobalPrefs defaultGlobals() {
        return new PortcastExporter.GlobalPrefs(1.0f, 30, 10, false);
    }

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
        // The 7-arg test ctor sets `feed` but not `feedId`; buildEpisodes joins by feedId.
        item.setFeedId(feed.getId());
        return item;
    }

    private static FeedMedia media(long id, FeedItem item, int durationMs, int positionMs, String mimeType) {
        return new FeedMedia(id, item, durationMs, positionMs, 0L, mimeType, null,
                "https://example.com/episode-" + id + ".mp3",
                0L, null, 0, 0L);
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
