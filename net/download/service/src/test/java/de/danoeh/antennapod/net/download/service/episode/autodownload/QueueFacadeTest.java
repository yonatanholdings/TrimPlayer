package de.danoeh.antennapod.net.download.service.episode.autodownload;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.net.download.serviceinterface.AutoDownloadManager;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Guardrails for the queue/playlist unification: the queue API (facade over the
 * default playlist) must behave exactly as the legacy Queue table did — ordering,
 * membership, next-in-queue — and the queue must stay isolated from named
 * playlists sharing the same storage.
 */
@RunWith(RobolectricTestRunner.class)
public class QueueFacadeTest {
    private static final long TIMEOUT = 5L;

    private Context context;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UserPreferences.init(context); // addQueueItem reads the enqueue-location pref
        PlaybackPreferences.init(context); // ...and the currently-playing item id
        AutoDownloadManager.setInstance(new AutoDownloadManagerImpl()); // queue writes trigger it
        PodDBAdapter.init(context);
        PodDBAdapter.deleteDatabase();
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.close();
    }

    @After
    public void tearDown() {
        PodDBAdapter.tearDownTests();
        DBWriter.tearDownTests();
    }

    @Test
    public void queueRoundtripPreservesOrder() throws Exception {
        List<Feed> feeds = DbTestUtils.saveFeedlist(1, 3, true);
        List<FeedItem> items = feeds.get(0).getItems();

        DBWriter.addQueueItem(context, items.get(2), items.get(0), items.get(1))
                .get(TIMEOUT, TimeUnit.SECONDS);
        List<FeedItem> queue = DBReader.getQueue();
        assertEquals(3, queue.size());
        assertEquals(items.get(2).getId(), queue.get(0).getId());
        assertEquals(items.get(0).getId(), queue.get(1).getId());
        assertEquals(items.get(1).getId(), queue.get(2).getId());

        // The id list view agrees with the item view.
        assertEquals(items.get(2).getId(), DBReader.getQueueIDList().get(0));
        assertEquals(3, DBReader.getQueueIDList().size());
    }

    @Test
    public void nextInQueueFollowsQueueOrder() throws Exception {
        List<Feed> feeds = DbTestUtils.saveFeedlist(1, 3, true);
        List<FeedItem> items = feeds.get(0).getItems();
        DBWriter.addQueueItem(context, items.get(0), items.get(1), items.get(2))
                .get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(items.get(1).getId(), DBReader.getNextInQueue(items.get(0)).getId());
        assertEquals(items.get(2).getId(), DBReader.getNextInQueue(items.get(1)).getId());
        assertNull(DBReader.getNextInQueue(items.get(2)));
    }

    @Test
    public void clearQueueEmptiesOnlyTheQueue() throws Exception {
        List<Feed> feeds = DbTestUtils.saveFeedlist(1, 2, true);
        List<FeedItem> items = feeds.get(0).getItems();
        DBWriter.addQueueItem(context, items.get(0), items.get(1))
                .get(TIMEOUT, TimeUnit.SECONDS);

        long playlistId = DBWriter.createPlaylist("Running").get(TIMEOUT, TimeUnit.SECONDS);
        DBWriter.addPlaylistItems(playlistId, items.get(0)).get(TIMEOUT, TimeUnit.SECONDS);

        DBWriter.clearQueue().get(TIMEOUT, TimeUnit.SECONDS);
        assertTrue(DBReader.getQueue().isEmpty());
        // The named playlist sharing the storage is untouched.
        assertEquals(1, DBReader.getPlaylistItems(playlistId).size());
    }

    @Test
    public void queueAndPlaylistsAreIsolated() throws Exception {
        List<Feed> feeds = DbTestUtils.saveFeedlist(1, 3, true);
        List<FeedItem> items = feeds.get(0).getItems();

        long playlistId = DBWriter.createPlaylist("Running").get(TIMEOUT, TimeUnit.SECONDS);
        DBWriter.addPlaylistItems(playlistId, items.get(0), items.get(1))
                .get(TIMEOUT, TimeUnit.SECONDS);
        DBWriter.addQueueItem(context, items.get(2)).get(TIMEOUT, TimeUnit.SECONDS);

        // Playlist items don't leak into the queue or vice versa.
        List<FeedItem> queue = DBReader.getQueue();
        assertEquals(1, queue.size());
        assertEquals(items.get(2).getId(), queue.get(0).getId());
        assertEquals(2, DBReader.getPlaylistItems(playlistId).size());

        // Removing from the playlist leaves the queue alone.
        DBWriter.removePlaylistItem(playlistId, items.get(0).getId())
                .get(TIMEOUT, TimeUnit.SECONDS);
        assertEquals(1, DBReader.getQueue().size());
    }

    @Test
    public void defaultPlaylistIdIsStableAndReserved() {
        long first = DBReader.getDefaultPlaylistId();
        long second = DBReader.getDefaultPlaylistId();
        assertEquals(first, second);
        assertTrue(first > 0);
        // The default playlist appears in the listing, flagged and first.
        assertTrue(DBReader.getPlaylists().get(0).isDefault());
        assertEquals(first, DBReader.getPlaylists().get(0).getId());
    }
}
