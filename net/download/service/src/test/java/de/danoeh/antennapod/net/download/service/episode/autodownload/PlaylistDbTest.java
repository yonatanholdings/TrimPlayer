package de.danoeh.antennapod.net.download.service.episode.autodownload;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.Playlist;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.database.PodDBAdapter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the named-playlist storage layer and the "next episode in the same playlist"
 * auto-advance logic (TrimPlayer multi-playlist feature).
 */
@RunWith(RobolectricTestRunner.class)
public class PlaylistDbTest {
    private static final long TIMEOUT = 5L;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
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
    public void testCreateRenameRemovePlaylist() throws Exception {
        long id = DBWriter.createPlaylist("Morning").get(TIMEOUT, TimeUnit.SECONDS);
        assertTrue(id > 0);

        // Since the queue/playlist unification the DEFAULT playlist (the Queue)
        // always exists and lists first.
        List<Playlist> playlists = DBReader.getPlaylists();
        assertEquals(2, playlists.size());
        assertTrue(playlists.get(0).isDefault());
        assertEquals("Morning", playlists.get(1).getName());
        assertEquals(0, playlists.get(1).getEpisodeCount());

        DBWriter.renamePlaylist(id, "Commute").get(TIMEOUT, TimeUnit.SECONDS);
        assertEquals("Commute", DBReader.getPlaylists().get(1).getName());

        DBWriter.removePlaylist(id).get(TIMEOUT, TimeUnit.SECONDS);
        playlists = DBReader.getPlaylists();
        assertEquals(1, playlists.size());
        assertTrue(playlists.get(0).isDefault());
    }

    @Test
    public void testAddAndRemoveItems() throws Exception {
        List<Feed> feeds = DbTestUtils.saveFeedlist(1, 4, true);
        List<FeedItem> items = feeds.get(0).getItems();
        long id = DBWriter.createPlaylist("P").get(TIMEOUT, TimeUnit.SECONDS);

        DBWriter.addPlaylistItems(id, items.get(0), items.get(1), items.get(2))
                .get(TIMEOUT, TimeUnit.SECONDS);
        // Duplicate add is ignored.
        DBWriter.addPlaylistItems(id, items.get(1)).get(TIMEOUT, TimeUnit.SECONDS);

        List<FeedItem> playlistItems = DBReader.getPlaylistItems(id);
        assertEquals(3, playlistItems.size());
        assertEquals(items.get(0).getId(), playlistItems.get(0).getId());
        assertEquals(items.get(1).getId(), playlistItems.get(1).getId());
        assertEquals(items.get(2).getId(), playlistItems.get(2).getId());
        assertEquals(3, DBReader.getPlaylists().get(1).getEpisodeCount()); // [0] is the Queue

        assertTrue(DBReader.isItemInPlaylist(id, items.get(1).getId()));
        assertFalse(DBReader.isItemInPlaylist(id, items.get(3).getId()));

        DBWriter.removePlaylistItem(id, items.get(1).getId()).get(TIMEOUT, TimeUnit.SECONDS);
        playlistItems = DBReader.getPlaylistItems(id);
        assertEquals(2, playlistItems.size());
        assertEquals(items.get(0).getId(), playlistItems.get(0).getId());
        assertEquals(items.get(2).getId(), playlistItems.get(1).getId());
    }

    @Test
    public void testReorderPreservesOrder() throws Exception {
        List<Feed> feeds = DbTestUtils.saveFeedlist(1, 3, true);
        List<FeedItem> items = feeds.get(0).getItems();
        long id = DBWriter.createPlaylist("P").get(TIMEOUT, TimeUnit.SECONDS);
        DBWriter.addPlaylistItems(id, items.get(0), items.get(1), items.get(2))
                .get(TIMEOUT, TimeUnit.SECONDS);

        List<FeedItem> reordered = DBReader.getPlaylistItems(id);
        // Move last to first.
        FeedItem last = reordered.remove(2);
        reordered.add(0, last);
        DBWriter.setPlaylistItems(id, reordered).get(TIMEOUT, TimeUnit.SECONDS);

        List<FeedItem> result = DBReader.getPlaylistItems(id);
        assertEquals(items.get(2).getId(), result.get(0).getId());
        assertEquals(items.get(0).getId(), result.get(1).getId());
        assertEquals(items.get(1).getId(), result.get(2).getId());
    }

    @Test
    public void testGetNextInPlaylist() throws Exception {
        List<Feed> feeds = DbTestUtils.saveFeedlist(1, 3, true);
        List<FeedItem> items = feeds.get(0).getItems();
        long id = DBWriter.createPlaylist("P").get(TIMEOUT, TimeUnit.SECONDS);
        DBWriter.addPlaylistItems(id, items.get(0), items.get(1), items.get(2))
                .get(TIMEOUT, TimeUnit.SECONDS);

        FeedItem next = DBReader.getNextInPlaylist(id, items.get(0));
        assertNotNull(next);
        assertEquals(items.get(1).getId(), next.getId());

        next = DBReader.getNextInPlaylist(id, items.get(1));
        assertNotNull(next);
        assertEquals(items.get(2).getId(), next.getId());

        // Last item has no successor.
        assertNull(DBReader.getNextInPlaylist(id, items.get(2)));
    }

    @Test
    public void testGetNextInPlaylistForNonMemberIsNull() throws Exception {
        List<Feed> feeds = DbTestUtils.saveFeedlist(1, 3, true);
        List<FeedItem> items = feeds.get(0).getItems();
        long id = DBWriter.createPlaylist("P").get(TIMEOUT, TimeUnit.SECONDS);
        DBWriter.addPlaylistItems(id, items.get(0)).get(TIMEOUT, TimeUnit.SECONDS);

        // items.get(2) is not part of the playlist.
        assertNull(DBReader.getNextInPlaylist(id, items.get(2)));
    }

    @Test
    public void testRemovePlaylistRemovesItems() throws Exception {
        List<Feed> feeds = DbTestUtils.saveFeedlist(1, 2, true);
        List<FeedItem> items = feeds.get(0).getItems();
        long id = DBWriter.createPlaylist("P").get(TIMEOUT, TimeUnit.SECONDS);
        DBWriter.addPlaylistItems(id, items.get(0), items.get(1)).get(TIMEOUT, TimeUnit.SECONDS);

        DBWriter.removePlaylist(id).get(TIMEOUT, TimeUnit.SECONDS);
        assertTrue(DBReader.getPlaylistItems(id).isEmpty());
    }
}
