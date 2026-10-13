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
 * The "Queued" / "Not Queued" episode filters must agree with the live queue.
 *
 * <p>They used to be answered from the legacy {@code Queue} table, which has been
 * dormant since the queue/playlist unification — every queue write goes to
 * {@code PlaylistItems} now. The filters therefore saw a frozen snapshot: an
 * episode the user had just queued still showed up under "Not Queued"
 * (feedback thread #17, 2026-09-24).
 */
@RunWith(RobolectricTestRunner.class)
public class QueuedFilterTest {
    private Context context;

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
    }

    @After
    public void tearDown() {
        PodDBAdapter.tearDownTests();
    }

    /** Two subscribed episodes of one feed, neither of them queued yet. */
    private List<FeedItem> subscribeTwoItems() throws ExecutionException, InterruptedException {
        Feed feed = new Feed(0, null, "title", "http://example.com", "description",
                null, "author", "en", null, "http://example.com/feed",
                "http://example.com/image", null, "http://example.com/feed",
                System.currentTimeMillis());
        List<FeedItem> items = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            FeedItem item = new FeedItem(0, "Item " + i, "ItemId" + i, "url" + i,
                    new Date(i * 1000L), FeedItem.UNPLAYED, feed);
            item.setMedia(new FeedMedia(item, "http://download.url.net/ep" + i + ".mp3",
                    1234567, "audio/mpeg"));
            items.add(item);
        }
        feed.setItems(items);
        DBWriter.setCompleteFeed(feed).get();
        return feed.getItems();
    }

    private static List<Long> idsMatching(String filter) {
        List<Long> ids = new ArrayList<>();
        for (FeedItem item : DBReader.getEpisodes(0, Integer.MAX_VALUE,
                new FeedItemFilter(filter), SortOrder.DATE_NEW_OLD)) {
            ids.add(item.getId());
        }
        return ids;
    }

    @Test
    public void queuedFilterSeesAFreshlyQueuedEpisode() throws ExecutionException, InterruptedException {
        List<FeedItem> items = subscribeTwoItems();
        FeedItem queued = items.get(0);
        FeedItem loose = items.get(1);

        DBWriter.addQueueItem(context, queued).get();

        assertTrue("a queued episode must match QUEUED",
                idsMatching(FeedItemFilter.QUEUED).contains(queued.getId()));
        assertFalse("an unqueued episode must not match QUEUED",
                idsMatching(FeedItemFilter.QUEUED).contains(loose.getId()));
    }

    @Test
    public void notQueuedFilterHidesAFreshlyQueuedEpisode() throws ExecutionException, InterruptedException {
        List<FeedItem> items = subscribeTwoItems();
        FeedItem queued = items.get(0);
        FeedItem loose = items.get(1);

        DBWriter.addQueueItem(context, queued).get();

        // This is the exact user-visible symptom from feedback #17: the queued
        // episode kept showing up in a list filtered to "Not Queued".
        assertFalse("a queued episode must NOT match NOT_QUEUED",
                idsMatching(FeedItemFilter.NOT_QUEUED).contains(queued.getId()));
        assertTrue("an unqueued episode must match NOT_QUEUED",
                idsMatching(FeedItemFilter.NOT_QUEUED).contains(loose.getId()));
    }

    @Test
    public void removingFromQueueFlipsBothFilters() throws ExecutionException, InterruptedException {
        List<FeedItem> items = subscribeTwoItems();
        FeedItem item = items.get(0);
        DBWriter.addQueueItem(context, item).get();

        DBWriter.removeQueueItem(context, false, item).get();

        assertFalse(idsMatching(FeedItemFilter.QUEUED).contains(item.getId()));
        assertTrue(idsMatching(FeedItemFilter.NOT_QUEUED).contains(item.getId()));
    }

    @Test
    public void withAnEmptyQueueNothingIsQueuedAndEverythingIsNot()
            throws ExecutionException, InterruptedException {
        List<FeedItem> items = subscribeTwoItems();

        // Guards the SQL degenerate case: when no default playlist row exists yet
        // the id subquery yields no rows, so NOT IN must still match every item
        // rather than collapsing to NULL and matching none.
        assertEquals(0, idsMatching(FeedItemFilter.QUEUED).size());
        assertEquals(items.size(), idsMatching(FeedItemFilter.NOT_QUEUED).size());
    }
}
