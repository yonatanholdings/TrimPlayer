package de.danoeh.antennapod.net.download.service.episode.autodownload;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.net.download.serviceinterface.AutoDownloadManager;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.database.InboxDeprecationMigration;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Inbox deprecation guardrails: the one-time migration converts legacy
 * add-to-queue routing into rules on the default playlist and clears NEW
 * flags; the re-anchored auto-download draws its candidates from playlist
 * membership instead of the NEW flag.
 */
@RunWith(RobolectricTestRunner.class)
public class InboxDeprecationTest {
    private static final long TIMEOUT = 5L;

    private Context context;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UserPreferences.init(context);
        PlaybackPreferences.init(context);
        AutoDownloadManager.setInstance(new AutoDownloadManagerImpl());
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


    /** DbTestUtils feeds have no FeedPreferences row; attach one so the
     *  routing/auto-download settings under test exist. */
    private static FeedPreferences attachPrefs(Feed feed) {
        FeedPreferences prefs = new FeedPreferences(feed.getId(),
                FeedPreferences.AutoDownloadSetting.GLOBAL,
                FeedPreferences.AutoDeleteAction.GLOBAL,
                de.danoeh.antennapod.model.feed.VolumeAdaptionSetting.OFF,
                FeedPreferences.NewEpisodesAction.GLOBAL, null, null);
        feed.setPreferences(prefs);
        return prefs;
    }

    @Test
    public void migrationConvertsQueueRoutingToRulesAndClearsNewFlags() throws Exception {
        List<Feed> feeds = DbTestUtils.saveFeedlist(3, 2, true);
        // Feed 0: explicit ADD_TO_QUEUE — must gain a rule onto the default playlist.
        FeedPreferences p0 = attachPrefs(feeds.get(0));
        p0.setNewEpisodesAction(FeedPreferences.NewEpisodesAction.ADD_TO_QUEUE);
        DBWriter.setFeedPreferences(p0).get(TIMEOUT, TimeUnit.SECONDS);
        // Feed 1: GLOBAL with a global default of inbox — no rule.
        FeedPreferences p1 = attachPrefs(feeds.get(1));
        DBWriter.setFeedPreferences(p1).get(TIMEOUT, TimeUnit.SECONDS);
        // Feed 2: explicit ADD_TO_INBOX — no rule.
        FeedPreferences p2 = attachPrefs(feeds.get(2));
        p2.setNewEpisodesAction(FeedPreferences.NewEpisodesAction.ADD_TO_INBOX);
        DBWriter.setFeedPreferences(p2).get(TIMEOUT, TimeUnit.SECONDS);

        // Give every item the legacy NEW flag.
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.setFeedItems(FeedItem.UNPLAYED, FeedItem.NEW);
        adapter.close();

        InboxDeprecationMigration.run(false /* global default is not add-to-queue */);

        long defaultId = DBReader.getDefaultPlaylistId();
        Set<Long> ruleFeeds = DBReader.getPlaylistAutoFeedIds(defaultId);
        assertEquals(1, ruleFeeds.size());
        assertTrue(ruleFeeds.contains(feeds.get(0).getId()));

        // No NEW-flagged items remain anywhere.
        for (Feed feed : DBReader.getFeedList()) {
            for (FeedItem item : DBReader.getFeedItemList(feed,
                    new de.danoeh.antennapod.model.feed.FeedItemFilter(),
                    de.danoeh.antennapod.model.feed.SortOrder.DATE_NEW_OLD,
                    0, Integer.MAX_VALUE)) {
                assertFalse("item still NEW: " + item.getId(), item.isNew());
            }
        }

        // Idempotent: a second run neither duplicates rules nor fails.
        InboxDeprecationMigration.run(false);
        assertEquals(1, DBReader.getPlaylistAutoFeedIds(defaultId).size());
    }

    @Test
    public void migrationHonorsGlobalAddToQueueDefault() throws Exception {
        List<Feed> feeds = DbTestUtils.saveFeedlist(1, 1, true);
        DBWriter.setFeedPreferences(attachPrefs(feeds.get(0))).get(TIMEOUT, TimeUnit.SECONDS);

        InboxDeprecationMigration.run(true /* global default IS add-to-queue */);

        assertTrue(DBReader.getPlaylistAutoFeedIds(DBReader.getDefaultPlaylistId())
                .contains(feeds.get(0).getId()));
    }

    @Test
    public void autoDownloadCandidatesComeFromPlaylists() throws Exception {
        List<Feed> feeds = DbTestUtils.saveFeedlist(1, 4, true);
        List<FeedItem> items = feeds.get(0).getItems();
        // Feed allows auto-download regardless of the global toggle.
        FeedPreferences fp = attachPrefs(feeds.get(0));
        fp.setAutoDownload(FeedPreferences.AutoDownloadSetting.ENABLED);
        DBWriter.setFeedPreferences(fp).get(TIMEOUT, TimeUnit.SECONDS);

        // Two items in the queue (default playlist), one in a custom playlist,
        // one in no playlist at all.
        DBWriter.addQueueItem(context, items.get(0), items.get(1)).get(TIMEOUT, TimeUnit.SECONDS);
        long playlistId = DBWriter.createPlaylist("Running").get(TIMEOUT, TimeUnit.SECONDS);
        DBWriter.addPlaylistItems(playlistId, items.get(2)).get(TIMEOUT, TimeUnit.SECONDS);

        List<FeedItem> withQueue = AutomaticDownloadAlgorithm.selectPlaylistCandidates(true);
        assertEquals(3, withQueue.size());
        // Queue first (download priority), then the custom playlist.
        assertEquals(items.get(0).getId(), withQueue.get(0).getId());
        assertEquals(items.get(1).getId(), withQueue.get(1).getId());
        assertEquals(items.get(2).getId(), withQueue.get(2).getId());

        // Unplaylisted items are never candidates (the NEW flag is dead).
        for (FeedItem candidate : withQueue) {
            assertTrue(candidate.getId() != items.get(3).getId());
        }

        // Queue excluded when the setting is off; playlists remain.
        List<FeedItem> withoutQueue = AutomaticDownloadAlgorithm.selectPlaylistCandidates(false);
        assertEquals(1, withoutQueue.size());
        assertEquals(items.get(2).getId(), withoutQueue.get(0).getId());
    }
}
