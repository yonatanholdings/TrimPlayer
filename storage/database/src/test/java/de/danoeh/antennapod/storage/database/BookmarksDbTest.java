package de.danoeh.antennapod.storage.database;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import de.danoeh.antennapod.model.feed.Bookmark;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class BookmarksDbTest {
    private static final long ITEM_A = 1;
    private static final long ITEM_B = 2;

    private PodDBAdapter adapter;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        PodDBAdapter.init(context);
        adapter = PodDBAdapter.getInstance();
    }

    @After
    public void tearDown() {
        PodDBAdapter.tearDownTests();
    }

    @Test
    public void testBookmarkCrud() {
        adapter.open();
        adapter.insertBookmark(ITEM_A, 90000, "later one");
        adapter.insertBookmark(ITEM_A, 30000, null);
        adapter.insertBookmark(ITEM_B, 10000, "other episode");
        adapter.close();

        // Ordered by position, scoped to the episode, null note normalized to ""
        List<Bookmark> bookmarks = DBReader.getBookmarks(ITEM_A);
        assertEquals(2, bookmarks.size());
        assertEquals(30000, bookmarks.get(0).getPosition());
        assertEquals("", bookmarks.get(0).getNote());
        assertEquals(90000, bookmarks.get(1).getPosition());
        assertEquals("later one", bookmarks.get(1).getNote());
        assertTrue(bookmarks.get(0).getCreatedAt() > 0);

        adapter.open();
        adapter.updateBookmarkNote(bookmarks.get(0).getId(), "found it");
        adapter.close();
        assertEquals("found it", DBReader.getBookmarks(ITEM_A).get(0).getNote());

        adapter.open();
        adapter.deleteBookmark(bookmarks.get(1).getId());
        adapter.close();
        bookmarks = DBReader.getBookmarks(ITEM_A);
        assertEquals(1, bookmarks.size());
        assertEquals(30000, bookmarks.get(0).getPosition());

        // The other episode's bookmark is untouched
        assertEquals(1, DBReader.getBookmarks(ITEM_B).size());
    }

    @Test
    public void testSyncIdGenerationAndAdoption() {
        adapter.open();
        adapter.insertBookmark(ITEM_A, 10000, "locally created");
        adapter.insertBookmark(ITEM_A, 20000, "from sync", 1_700_000_000_000L, "wire-id-1");
        adapter.close();

        List<Bookmark> bookmarks = DBReader.getBookmarks(ITEM_A);
        assertEquals(2, bookmarks.size());
        // Local inserts get a generated stable sync id; synced inserts keep the wire id.
        assertNotNull(bookmarks.get(0).getSyncId());
        assertFalse(bookmarks.get(0).getSyncId().isEmpty());
        assertEquals("wire-id-1", bookmarks.get(1).getSyncId());
        assertEquals(1_700_000_000_000L, bookmarks.get(1).getCreatedAt());

        // Adoption re-keys the row to another device's id and takes its note.
        adapter.open();
        adapter.adoptBookmarkSyncId(bookmarks.get(0).getId(), "adopted-id", "merged note");
        adapter.close();
        Bookmark adopted = DBReader.getBookmarks(ITEM_A).get(0);
        assertEquals("adopted-id", adopted.getSyncId());
        assertEquals("merged note", adopted.getNote());
    }

    @Test
    public void testGetAllBookmarksWithItems() throws Exception {
        Feed feed = new Feed(0, null, "title", "http://example.com", "description",
                "http://example.com/payment", "author", "en", null, "http://example.com/feed",
                "http://example.com/image", null, "http://example.com/feed", System.currentTimeMillis());
        feed.setItems(new ArrayList<>());
        FeedItem item = new FeedItem(0, "Item", "ItemId", "url", new Date(), FeedItem.PLAYED, feed);
        item.setMedia(new FeedMedia(item, "http://download.url.net/", 1234567, "audio/mpeg"));
        feed.getItems().add(item);
        DBWriter.setCompleteFeed(feed).get();

        adapter.open();
        adapter.insertBookmark(item.getId(), 60000, "real episode");
        adapter.insertBookmark(item.getId() + 1000, 5000, "dangling — episode missing");
        adapter.close();

        List<DBReader.BookmarkWithItem> rows = DBReader.getAllBookmarksWithItems();
        assertEquals(1, rows.size());
        assertEquals("real episode", rows.get(0).bookmark.getNote());
        assertEquals(item.getId(), rows.get(0).item.getId());
        assertEquals("Item", rows.get(0).item.getTitle());
    }
}
