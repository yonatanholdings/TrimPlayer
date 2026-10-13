package de.danoeh.antennapod.storage.database;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.net.download.serviceinterface.AutoDownloadManager;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterface;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterfaceStub;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The new-arrivals filter: published after the last-seen watermark, not played,
 * and in no playlist.
 *
 * <p>This is the Inbox's replacement (NEW_ARRIVALS_WATERMARK_PLAN.md) and it
 * holds no per-episode state, so each of those three conditions is the only
 * thing keeping an episode on or off the list.
 *
 * <p>Fixtures are positioned relative to the watermark the preference seeds
 * itself to, and moved only forwards, so the tests drive the watermark purely
 * through its public monotonic API.
 */
@RunWith(RobolectricTestRunner.class)
public class NewArrivalsFilterTest {
    private Context context;
    private long watermark;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getContext();
        UserPreferences.init(context);
        PlaybackPreferences.init(context);
        AutoDownloadManager.setInstance(new AutoDownloadManager() {
            @Override
            public java.util.concurrent.Future<?> autodownloadUndownloadedItems(Context ctx) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }

            @Override
            public void performAutoCleanup(Context ctx) {
            }
        });
        DownloadServiceInterface.setImpl(new DownloadServiceInterfaceStub());
        PodDBAdapter.init(context);
        PodDBAdapter.deleteDatabase();
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.close();
        watermark = UserPreferences.getLastSeenPubDate();
    }

    @After
    public void tearDown() {
        PodDBAdapter.tearDownTests();
    }

    /** One subscribed feed whose items are published at the given times. */
    private List<FeedItem> subscribeWithPubDates(long... pubDates) throws ExecutionException, InterruptedException {
        Feed feed = new Feed(0, null, "title", "http://example.com", "description",
                null, "author", "en", null, "http://example.com/feed",
                "http://example.com/image", null, "http://example.com/feed",
                System.currentTimeMillis());
        List<FeedItem> items = new ArrayList<>();
        for (int i = 0; i < pubDates.length; i++) {
            FeedItem item = new FeedItem(0, "Item " + i, "ItemId" + i, "url" + i,
                    new Date(pubDates[i]), FeedItem.UNPLAYED, feed);
            item.setMedia(new FeedMedia(item, "http://download.url.net/ep" + i + ".mp3",
                    1234567, "audio/mpeg"));
            items.add(item);
        }
        feed.setItems(items);
        DBWriter.setCompleteFeed(feed).get();
        return feed.getItems();
    }

    private static List<Long> arrivalIds() {
        List<Long> ids = new ArrayList<>();
        for (FeedItem item : DBReader.getEpisodes(0, Integer.MAX_VALUE,
                new FeedItemFilter(FeedItemFilter.NEW_ARRIVALS), SortOrder.DATE_NEW_OLD)) {
            ids.add(item.getId());
        }
        return ids;
    }

    private static int arrivalCount() {
        return DBReader.getTotalEpisodeCount(new FeedItemFilter(FeedItemFilter.NEW_ARRIVALS));
    }

    @Test
    public void onlyEpisodesPublishedAfterTheWatermarkAreArrivals()
            throws ExecutionException, InterruptedException {
        List<FeedItem> items = subscribeWithPubDates(watermark - 60_000L, watermark + 60_000L);

        List<Long> arrivals = arrivalIds();

        assertTrue(arrivals.contains(items.get(1).getId()));
        assertFalse("the back catalogue must not read as new",
                arrivals.contains(items.get(0).getId()));
        assertEquals(1, arrivalCount());
    }

    @Test
    public void playingAnArrivalClearsIt() throws ExecutionException, InterruptedException {
        FeedItem item = subscribeWithPubDates(watermark + 60_000L).get(0);
        assertTrue("precondition", arrivalIds().contains(item.getId()));

        DBWriter.markItemPlayed(FeedItem.PLAYED, item.getId()).get();

        // Mark-played is the "remove it" gesture; it must drop off the list.
        assertFalse(arrivalIds().contains(item.getId()));
        assertEquals(0, arrivalCount());
    }

    @Test
    public void queueingAnArrivalClearsIt() throws ExecutionException, InterruptedException {
        FeedItem item = subscribeWithPubDates(watermark + 60_000L).get(0);
        assertTrue("precondition", arrivalIds().contains(item.getId()));

        DBWriter.addQueueItem(context, item).get();

        // "Queue it" is the other triage gesture: once scheduled it is no
        // longer pending, which is what keeps the list finishable.
        assertFalse(arrivalIds().contains(item.getId()));
        assertEquals(0, arrivalCount());
    }

    @Test
    public void addingToANamedPlaylistAlsoClearsIt() throws ExecutionException, InterruptedException {
        FeedItem item = subscribeWithPubDates(watermark + 60_000L).get(0);
        long playlistId = DBWriter.createPlaylist("Running").get();

        DBWriter.addPlaylistItems(playlistId, item).get();

        // The arrivals predicate spans every playlist, not just Up Next, so
        // scheduling into "Running" counts as triaged too.
        assertFalse(arrivalIds().contains(item.getId()));
        assertEquals(0, arrivalCount());
    }

    @Test
    public void advancingTheWatermarkDropsTheOlderArrivals()
            throws ExecutionException, InterruptedException {
        List<FeedItem> items = subscribeWithPubDates(watermark + 60_000L, watermark + 120_000L);
        assertEquals("precondition", 2, arrivalCount());

        UserPreferences.setLastSeenPubDate(watermark + 90_000L);

        List<Long> arrivals = arrivalIds();
        assertFalse("published before the new boundary", arrivals.contains(items.get(0).getId()));
        assertTrue("published after it", arrivals.contains(items.get(1).getId()));
    }

    @Test
    public void unqueueingAnArrivalBringsItBack() throws ExecutionException, InterruptedException {
        FeedItem item = subscribeWithPubDates(watermark + 60_000L).get(0);
        DBWriter.addQueueItem(context, item).get();
        assertEquals("precondition", 0, arrivalCount());

        DBWriter.removeQueueItem(context, false, item).get();

        // Nothing was consumed by queueing it -- there is no per-item state --
        // so taking it back out leaves it pending again.
        assertTrue(arrivalIds().contains(item.getId()));
    }
}
