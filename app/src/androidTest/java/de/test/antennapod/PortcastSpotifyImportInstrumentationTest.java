package de.test.antennapod;

import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.ListenableWorker;
import androidx.work.testing.TestWorkerBuilder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import de.danoeh.antennapod.storage.importexport.PortcastImporter;
import de.danoeh.antennapod.storage.importexport.PortcastStateWorker;

/**
 * On-device proof that a Spotify-sourced import actually <em>populates the
 * app</em>: it runs the real {@link PortcastStateWorker} against the real
 * SQLite database and asserts the episode rows get marked played / positioned.
 *
 * <p>It seeds a feed whose items match the Spotify episodes by <b>title only</b>
 * — the stored RSS guids are deliberately different from the Spotify episode
 * IDs, and the stashed states carry no guid/enclosureUrl — so a pass means the
 * feed+title join in {@link PortcastStateWorker} reaches the DB writes that
 * surface in the UI.
 *
 * <p>The two genuinely external steps are intentionally out of scope here and
 * proven elsewhere: the WebView Spotify login + the network show→feed
 * resolution (the document-acquisition side, proven by executing the real
 * {@code portcast.js} builder and the parse/resolve unit tests). This test is
 * the DB-apply side.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class PortcastSpotifyImportInstrumentationTest {

    private static final String FEED_URL = "https://feeds.transistor.fm/acquired";
    private static final String TITLE_DONE = "Episode 42: The One About Foo";
    private static final String TITLE_MID = "Nvidia Part II";

    private Context context;
    private long doneItemId;
    private long midItemId;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        EspressoTestUtils.clearDatabase();
        EspressoTestUtils.clearPreferences();
        seedFeedWithRssItems();
        stashSpotifyEpisodeStates();
    }

    /** A subscribed+refreshed feed: items have real RSS guids (NOT the Spotify
     *  episode IDs) so the only thing the importer can join on is the title. */
    private void seedFeedWithRssItems() {
        Feed feed = new Feed(0, null, "Acquired", "https://acquired.fm", "desc",
                null, "Ben & David", "en", Feed.TYPE_RSS2, "acquired-guid", null, null,
                FEED_URL, System.currentTimeMillis());

        List<FeedItem> items = new ArrayList<>();
        items.add(rssItem(feed, TITLE_DONE, "rss-guid-done", "https://cdn/acquired/done.mp3", 1_800_000));
        items.add(rssItem(feed, TITLE_MID, "rss-guid-mid", "https://cdn/acquired/mid.mp3", 7_200_000));
        items.add(rssItem(feed, "Berkshire Hathaway", "rss-guid-new", "https://cdn/acquired/new.mp3", 3_600_000));
        feed.setItems(items);

        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.setCompleteFeed(feed);
        adapter.close();

        for (FeedItem item : feed.getItems()) {
            if (TITLE_DONE.equals(item.getTitle())) {
                doneItemId = item.getId();
            } else if (TITLE_MID.equals(item.getTitle())) {
                midItemId = item.getId();
            }
        }
        assertTrue("seed feed should have assigned item ids", doneItemId != 0 && midItemId != 0);
    }

    private static FeedItem rssItem(Feed feed, String title, String guid, String mediaUrl, int durationMs) {
        FeedItem item = new FeedItem(0, title, guid, "https://acquired.fm/" + guid,
                new Date(), FeedItem.UNPLAYED, feed);
        item.setMedia(new FeedMedia(0, item, durationMs, 0, 12_345L, "audio/mp3",
                null, mediaUrl, 0, null, 0, 0));
        return item;
    }

    /** Stash exactly what previewImport persists for Spotify episodes after the
     *  show ref resolves to FEED_URL: no guid, no enclosureUrl, just feedUrl +
     *  title + play state. */
    private void stashSpotifyEpisodeStates() {
        List<PortcastImporter.EpisodeState> states = new ArrayList<>();

        PortcastImporter.EpisodeState done = new PortcastImporter.EpisodeState();
        done.guid = "";
        done.enclosureUrl = "";
        done.feedUrl = FEED_URL;
        done.title = TITLE_DONE;
        done.status = "completed";
        done.durationMs = 1_800_000;
        done.lastPlayedMs = System.currentTimeMillis();
        states.add(done);

        PortcastImporter.EpisodeState mid = new PortcastImporter.EpisodeState();
        mid.guid = "";
        mid.enclosureUrl = "";
        mid.feedUrl = FEED_URL;
        mid.title = TITLE_MID;
        mid.status = "in_progress";
        mid.positionMs = 612_500;
        mid.durationMs = 7_200_000;
        states.add(mid);

        try {
            PortcastImporter.saveEpisodeStates(context, states);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void workerAppliesSpotifyStatesToRealDbByTitle() {
        Executor executor = Executors.newSingleThreadExecutor();
        PortcastStateWorker worker = TestWorkerBuilder.from(
                context, PortcastStateWorker.class, executor).build();

        ListenableWorker.Result result = worker.doWork();
        assertEquals(ListenableWorker.Result.success(), result);

        // "completed" Spotify episode → the title-matched RSS item is now played.
        await().atMost(10, TimeUnit.SECONDS)
                .until(() -> DBReader.getFeedItem(doneItemId).isPlayed());
        FeedItem done = DBReader.getFeedItem(doneItemId);
        assertNotNull(done);
        assertTrue("completed Spotify episode should mark the RSS item played", done.isPlayed());

        // "in_progress" Spotify episode → the title-matched RSS item has the
        // resume position written.
        await().atMost(10, TimeUnit.SECONDS)
                .until(() -> {
                    FeedItem it = DBReader.getFeedItem(midItemId);
                    return it.getMedia() != null && it.getMedia().getPosition() == 612_500;
                });
        FeedItem mid = DBReader.getFeedItem(midItemId);
        assertNotNull(mid.getMedia());
        assertEquals("in-progress Spotify episode should set the resume position",
                612_500, mid.getMedia().getPosition());
        assertTrue("in-progress episode should not be marked played", !mid.isPlayed());
    }
}
