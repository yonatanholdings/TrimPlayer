package de.danoeh.antennapod.storage.database;

import de.danoeh.antennapod.event.NewEpisodesPrefetchEvent;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link DBWriter#selectSubscribePrefetchItems(Feed)} — the on-subscribe
 * pre-analyze selection that makes a start-fresh listener's first episode trimmed on first play.
 * Items are supplied newest-first (the DATE_NEW_OLD order setFeedState loads them in).
 */
@RunWith(RobolectricTestRunner.class)
public class DBWriterSubscribePrefetchTest {
    private static final String RSS_URL = "https://example.com/feed.rss";

    private static Feed feedWithItems(int count) {
        Feed feed = new Feed(RSS_URL, null, "Test Feed");
        List<FeedItem> items = new ArrayList<>();
        // item-0 is newest, item-(count-1) is oldest (DATE_NEW_OLD).
        for (int i = 0; i < count; i++) {
            items.add(itemWithMedia("item-" + i, "https://example.com/media-" + i + ".mp3"));
        }
        feed.setItems(items);
        return feed;
    }

    private static FeedItem itemWithMedia(String identifier, String mediaUrl) {
        FeedItem item = new FeedItem();
        item.setItemIdentifier(identifier);
        item.setTitle(identifier);
        if (mediaUrl != null) {
            item.setMedia(new FeedMedia(item, mediaUrl, 1, "audio/mpeg"));
        }
        return item;
    }

    private static List<String> guidsOf(List<NewEpisodesPrefetchEvent.Item> picks) {
        List<String> guids = new ArrayList<>(picks.size());
        for (NewEpisodesPrefetchEvent.Item it : picks) {
            guids.add(it.episodeGuid);
        }
        return guids;
    }

    @Test
    public void selectsNewestThreeAndOldestThree() {
        // 10 episodes: newest = item-0..2, oldest = item-9,8,7.
        List<NewEpisodesPrefetchEvent.Item> picks = DBWriter.selectSubscribePrefetchItems(feedWithItems(10));
        assertEquals(6, picks.size());
        assertEquals(List.of("item-0", "item-1", "item-2", "item-9", "item-8", "item-7"), guidsOf(picks));
        // rss + media urls carried through correctly for the analyze call.
        assertEquals(RSS_URL, picks.get(0).rssUrl);
        assertEquals("https://example.com/media-0.mp3", picks.get(0).episodeUrl);
        assertEquals("https://example.com/media-9.mp3", picks.get(3).episodeUrl);
    }

    @Test
    public void shortFeedYieldsEachEpisodeOnce() {
        // 4 episodes: newest edge {0,1,2} and oldest edge {3,2,1} overlap — must de-dup to all 4.
        List<NewEpisodesPrefetchEvent.Item> picks = DBWriter.selectSubscribePrefetchItems(feedWithItems(4));
        assertEquals(4, picks.size());
        assertEquals(List.of("item-0", "item-1", "item-2", "item-3"), guidsOf(picks));
    }

    @Test
    public void exactlySixHasNoDuplicates() {
        List<NewEpisodesPrefetchEvent.Item> picks = DBWriter.selectSubscribePrefetchItems(feedWithItems(6));
        assertEquals(6, picks.size());
        assertEquals(List.of("item-0", "item-1", "item-2", "item-5", "item-4", "item-3"), guidsOf(picks));
    }

    @Test
    public void singleEpisodeYieldsThatEpisode() {
        List<NewEpisodesPrefetchEvent.Item> picks = DBWriter.selectSubscribePrefetchItems(feedWithItems(1));
        assertEquals(1, picks.size());
        assertEquals("item-0", picks.get(0).episodeGuid);
    }

    @Test
    public void emptyFeedYieldsNothing() {
        assertTrue(DBWriter.selectSubscribePrefetchItems(feedWithItems(0)).isEmpty());
    }

    @Test
    public void localFeedIsSkipped() {
        Feed local = new Feed(Feed.PREFIX_LOCAL_FOLDER + "music", null, "Local");
        local.setItems(List.of(itemWithMedia("item-0", "file:///x.mp3")));
        assertTrue(DBWriter.selectSubscribePrefetchItems(local).isEmpty());
    }

    @Test
    public void itemsWithoutMediaAreSkipped() {
        Feed feed = new Feed(RSS_URL, null, "Mixed");
        List<FeedItem> items = new ArrayList<>();
        items.add(itemWithMedia("has-media", "https://example.com/a.mp3"));
        items.add(itemWithMedia("no-media", null));   // no FeedMedia attached
        feed.setItems(items);
        List<NewEpisodesPrefetchEvent.Item> picks = DBWriter.selectSubscribePrefetchItems(feed);
        assertEquals(List.of("has-media"), guidsOf(picks));
    }
}
