package de.danoeh.antennapod.storage.database;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import de.danoeh.antennapod.model.feed.Bookmark;

import static org.junit.Assert.assertEquals;
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
}
