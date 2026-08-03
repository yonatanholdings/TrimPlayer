package de.danoeh.antennapod.storage.database;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
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

import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A played episode must never linger in the queue (Up Next) -- whichever of the several
 * DBWriter#markItemPlayed overloads flips the read state (menu action, bulk select, or an
 * incoming account-sync progress row all funnel through one of these two).
 */
@RunWith(RobolectricTestRunner.class)
public class MarkPlayedDequeuesTest {
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

    private FeedItem subscribeAndQueueOneItem(String downloadUrl) throws ExecutionException, InterruptedException {
        Feed feed = new Feed(0, null, "title", "http://example.com", "description",
                null, "author", "en", null, downloadUrl,
                "http://example.com/image", null, downloadUrl, System.currentTimeMillis());
        FeedItem item = new FeedItem(0, "Item", "ItemId", "url", new java.util.Date(), FeedItem.UNPLAYED, feed);
        FeedMedia media = new FeedMedia(item, "http://download.url.net/ep.mp3", 1234567, "audio/mpeg");
        item.setMedia(media);
        feed.setItems(new java.util.ArrayList<>(List.of(item)));

        DBWriter.setCompleteFeed(feed).get();
        DBWriter.addQueueItem(context, item).get();
        assertTrue("precondition: item must start in the queue", inQueue(item.getId()));
        return item;
    }

    private boolean inQueue(long itemId) {
        for (FeedItem queued : DBReader.getQueue()) {
            if (queued.getId() == itemId) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void markingPlayedByIdRemovesFromQueue() throws ExecutionException, InterruptedException {
        FeedItem item = subscribeAndQueueOneItem("http://example.com/feed-by-id");

        DBWriter.markItemPlayed(FeedItem.PLAYED, item.getId()).get();

        assertFalse(inQueue(item.getId()));
    }

    @Test
    public void markingPlayedViaSyncOverloadRemovesFromQueue() throws ExecutionException, InterruptedException {
        // This is the exact overload TrimSyncWorker calls when an incoming account-sync
        // progress row reports the episode played on another device.
        FeedItem item = subscribeAndQueueOneItem("http://example.com/feed-sync");

        DBWriter.markItemPlayed(FeedItem.PLAYED, false, item.getId()).get();

        assertFalse(inQueue(item.getId()));
    }

    @Test
    public void markingPlayedByFeedItemRemovesFromQueue() throws ExecutionException, InterruptedException {
        FeedItem item = subscribeAndQueueOneItem("http://example.com/feed-object");

        DBWriter.markItemPlayed(item, FeedItem.PLAYED, true).get();

        assertFalse(inQueue(item.getId()));
    }

    @Test
    public void markingUnplayedLeavesItemInQueue() throws ExecutionException, InterruptedException {
        FeedItem item = subscribeAndQueueOneItem("http://example.com/feed-unplayed");

        DBWriter.markItemPlayed(FeedItem.UNPLAYED, item.getId()).get();

        assertTrue(inQueue(item.getId()));
    }
}
