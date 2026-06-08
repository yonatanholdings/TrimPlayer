package de.danoeh.antennapod.ui.screen.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import de.test.antennapod.EspressoTestUtils;

/**
 * On-device verification of {@link HomeFragment#findFirstPlayEpisode()} — the
 * selection behind the post-import "press play" nudge. The risky assumption is that
 * the PAUSED filter surfaces a resume-position episode and the HAS_MEDIA fallback
 * surfaces the newest playable one; these run the real DB queries to lock that.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class FirstPlayEpisodeSelectionTest {

    @Before
    public void setUp() {
        EspressoTestUtils.clearDatabase();
        EspressoTestUtils.clearPreferences();
    }

    @Test
    public void picksInProgressEvenWhenNotNewest() {
        Feed feed = newSubscribedFeed("https://example.com/feed");
        List<FeedItem> items = new ArrayList<>();
        // Newest, but un-started.
        items.add(item(feed, "Newest", "g-new", new Date(2_000_000L), FeedItem.UNPLAYED, 0));
        // Older, but in progress (an imported resume position) → should win.
        items.add(item(feed, "Resume me", "g-prog", new Date(1_000_000L), FeedItem.UNPLAYED, 120_000));
        persist(feed, items);

        List<FeedItem> result = HomeFragment.findFirstPlayEpisode();
        assertEquals(1, result.size());
        assertEquals("an in-progress episode should win over a newer un-started one",
                "Resume me", result.get(0).getTitle());
    }

    @Test
    public void fallsBackToNewestPlayableWhenNoneInProgress() {
        Feed feed = newSubscribedFeed("https://example.com/feed2");
        List<FeedItem> items = new ArrayList<>();
        items.add(item(feed, "Older", "g-old", new Date(1_000_000L), FeedItem.UNPLAYED, 0));
        items.add(item(feed, "Newest", "g-new", new Date(2_000_000L), FeedItem.UNPLAYED, 0));
        persist(feed, items);

        List<FeedItem> result = HomeFragment.findFirstPlayEpisode();
        assertEquals(1, result.size());
        assertEquals("with nothing in progress, the newest playable episode is offered",
                "Newest", result.get(0).getTitle());
    }

    @Test
    public void emptyWhenLibraryHasNoEpisodes() {
        assertTrue("no episodes → nothing to offer (import not materialized yet)",
                HomeFragment.findFirstPlayEpisode().isEmpty());
    }

    private static Feed newSubscribedFeed(String url) {
        Feed feed = new Feed(0, null, "Show", "https://example.com", "desc", null,
                "Author", "en", Feed.TYPE_RSS2, "feed-id-" + Math.abs(url.hashCode()), null, null,
                url, System.currentTimeMillis());
        feed.setState(Feed.STATE_SUBSCRIBED);
        feed.setItems(new ArrayList<>());
        return feed;
    }

    private static FeedItem item(Feed feed, String title, String guid, Date pubDate,
                                 int state, int positionMs) {
        FeedItem fi = new FeedItem(0, title, guid, "https://example.com/" + guid,
                pubDate, state, feed);
        fi.setMedia(new FeedMedia(0, fi, 1_800_000, positionMs, 12_345L, "audio/mp3",
                null, "https://cdn/" + guid + ".mp3", 0, null, 0, 0));
        return fi;
    }

    private static void persist(Feed feed, List<FeedItem> items) {
        feed.setItems(items);
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.setCompleteFeed(feed);
        adapter.close();
    }
}
