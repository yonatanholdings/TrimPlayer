package de.test.antennapod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.ListenableWorker;
import androidx.work.testing.TestWorkerBuilder;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import de.danoeh.antennapod.storage.importexport.PortcastImporter;
import de.danoeh.antennapod.storage.importexport.PortcastSubscribeWorker;

/**
 * On-device regression guards for the two PortCast re-import correctness fixes,
 * exercised against the real SQLite database:
 *
 * <ol>
 *   <li><b>Feed dedupe</b> — re-importing a feed whose URL differs only by
 *       scheme / {@code www.} / trailing slash must reuse the existing
 *       subscription, not create a duplicate. The seeded feed carries an Atom
 *       {@code feedIdentifier} on purpose: that's the case where the old
 *       {@code FeedDatabaseWriter} dedupe (keyed on
 *       {@link Feed#getIdentifyingValue()}) silently fails, so a pass proves the
 *       {@link PortcastSubscribeWorker} URL-match short-circuit reaches the DB.</li>
 *   <li><b>Conflict default</b> — {@link PortcastImporter#previewImport} must
 *       default each conflict to whichever side has more progress, so tapping
 *       straight through never rewinds a local position or un-completes an
 *       episode.</li>
 * </ol>
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class PortcastReimportInstrumentationTest {

    private static final String SHOW_URL = "https://feeds.example.com/show";

    private Context context;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        EspressoTestUtils.clearDatabase();
        EspressoTestUtils.clearPreferences();
    }

    // ── Q2: feed dedupe on re-import ─────────────────────────────────────────

    @Test
    public void reimportWithUrlVariantReusesExistingFeedInsteadOfDuplicating() {
        seedExistingAtomFeed(SHOW_URL);

        // Same show, URL drifted: http instead of https + a trailing slash.
        runSubscribeWorkerFor("http://feeds.example.com/show/", "Show");

        assertEquals("a URL-variant re-import must reuse the existing feed",
                1, DBReader.getFeedList().size());
    }

    @Test
    public void reimportWithDistinctUrlAddsSeparateFeed() {
        seedExistingAtomFeed(SHOW_URL);

        // A genuinely different feed must NOT be swallowed by the dedupe.
        runSubscribeWorkerFor("https://feeds.example.com/other", "Other Show");

        assertEquals("a distinct feed URL must create a separate subscription",
                2, DBReader.getFeedList().size());
    }

    /** Seed a subscribed feed that already carries an Atom feedIdentifier — the
     *  case where matching on {@link Feed#getIdentifyingValue()} would miss a
     *  URL-only match and the worker must dedupe on the URL itself. */
    private void seedExistingAtomFeed(String downloadUrl) {
        Feed feed = new Feed(0, null, "Show", "https://example.com", "desc",
                null, "Author", "en", Feed.TYPE_ATOM1, "atom-id-show", null, null,
                downloadUrl, System.currentTimeMillis());
        feed.setItems(new ArrayList<>());

        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.setCompleteFeed(feed);
        adapter.close();

        assertEquals("precondition: exactly one feed seeded", 1, DBReader.getFeedList().size());
    }

    private void runSubscribeWorkerFor(String feedUrl, String title) {
        PortcastImporter.PortFeed pf = new PortcastImporter.PortFeed();
        pf.feedUrl = feedUrl;
        pf.title = title;
        pf.subscriptionId = ""; // empty → no SubscriptionIdIndex shortcut; forces URL dedupe
        try {
            PortcastImporter.savePendingFeeds(context, Collections.singletonList(pf));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Executor executor = Executors.newSingleThreadExecutor();
        PortcastSubscribeWorker worker = TestWorkerBuilder.from(
                context, PortcastSubscribeWorker.class, executor).build();
        ListenableWorker.Result result = worker.doWork();
        assertEquals(ListenableWorker.Result.success(), result);
    }

    // ── Q1: furthest-progress conflict default ───────────────────────────────

    @Test
    public void previewDefaultsConflictsToFurthestProgress() throws Exception {
        // Two locally-played items. "done" is 2 min in; "mid" is 18 min in.
        Feed feed = new Feed(0, null, "Show", "https://example.com", "desc",
                null, "Author", "en", Feed.TYPE_RSS2, "rss-show", null, null,
                SHOW_URL, System.currentTimeMillis());
        List<FeedItem> items = new ArrayList<>();
        items.add(localItem(feed, "Done Episode", "rss-guid-done", 120_000));      // 2:00
        items.add(localItem(feed, "Mid Episode", "rss-guid-mid", 1_080_000));      // 18:00
        feed.setItems(items);

        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.setCompleteFeed(feed);
        adapter.close();

        // Backup: "done" was finished (completed); "mid" is only 2 min in (behind local).
        String json = new JSONObject()
                .put("portcast", "0.1.0")
                .put("episodes", new JSONArray()
                        .put(new JSONObject()
                                .put("guid", "rss-guid-done")
                                .put("status", "completed"))
                        .put(new JSONObject()
                                .put("guid", "rss-guid-mid")
                                .put("status", "in_progress")
                                .put("positionSeconds", 120)))
                .toString();

        PortcastImporter.ImportPreview preview;
        try (InputStream stream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
            preview = PortcastImporter.previewImport(context, stream);
        }

        assertEquals("both played episodes should surface as conflicts",
                2, preview.conflicts.size());

        PortcastImporter.ConflictEpisode done = conflictForGuid(preview, "rss-guid-done");
        PortcastImporter.ConflictEpisode mid = conflictForGuid(preview, "rss-guid-mid");
        assertNotNull(done);
        assertNotNull(mid);

        // Incoming finished an episode local had only started → default to incoming.
        assertTrue("completed backup should win over a partial local play",
                done.useIncoming);
        // Incoming is BEHIND local (2 min vs 18 min) → keep local, don't rewind.
        assertFalse("a backup behind the local position must not rewind by default",
                mid.useIncoming);
    }

    private static FeedItem localItem(Feed feed, String title, String guid, int positionMs) {
        FeedItem item = new FeedItem(0, title, guid, "https://example.com/" + guid,
                new Date(), FeedItem.UNPLAYED, feed);
        item.setMedia(new FeedMedia(0, item, 1_800_000, positionMs, 12_345L, "audio/mp3",
                null, "https://cdn/" + guid + ".mp3", 0, null, 0, 0));
        return item;
    }

    private static PortcastImporter.ConflictEpisode conflictForGuid(
            PortcastImporter.ImportPreview preview, String guid) {
        for (PortcastImporter.ConflictEpisode c : preview.conflicts) {
            if (c.incomingState != null && guid.equals(c.incomingState.guid)) {
                return c;
            }
        }
        return null;
    }
}
