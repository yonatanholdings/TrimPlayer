package de.danoeh.antennapod.playback.service.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;

public class NextEpisodePickerTest {
    private static final long NO_PLAYLIST = 0;
    private static final long NO_RUN = -1;

    private static FeedItem episode(long id) {
        FeedItem item = new FeedItem();
        item.setId(id);
        item.setMedia(new FeedMedia(item, "https://example.com/" + id + ".mp3", 0, "audio/mpeg"));
        return item;
    }

    /** One queue plus "next unplayed episode of the same show" links. */
    private static class FakeDb implements NextEpisodePicker.Lookup {
        final List<FeedItem> queue = new ArrayList<>();
        final Map<Long, FeedItem> nextInShow = new HashMap<>();

        @Override
        public boolean isInPlaylist(long playlistId, long itemId) {
            return false;
        }

        @Override
        public FeedItem nextInPlaylist(long playlistId, FeedItem item) {
            return null;
        }

        @Override
        public FeedItem nextInQueue(FeedItem item) {
            for (int i = 0; i < queue.size() - 1; i++) {
                if (queue.get(i).getId() == item.getId()) {
                    return queue.get(i + 1);
                }
            }
            return null;
        }

        @Override
        public FeedItem nextInFeed(FeedItem item) {
            return nextInShow.get(item.getId());
        }

        @Override
        public FeedItem firstInPlaylist(long playlistId, long excludeItemId) {
            List<FeedItem> list = playlistId == NO_PLAYLIST ? queue : playlist;
            for (FeedItem item : list) {
                if (item.getId() != excludeItemId) {
                    return item;
                }
            }
            return null;
        }

        final List<FeedItem> playlist = new ArrayList<>();
    }

    @Test
    public void runThroughShowIsNotHijackedByAnAutoAddedEpisode() {
        // How To Do Things, 2026-11-05: 8315 was reached through its show and appended to the
        // queue when it started; the daily news 25366 was auto-added behind it.
        FakeDb db = new FakeDb();
        FeedItem playing = episode(8315);
        db.queue.add(episode(2058));
        db.queue.add(playing);
        db.queue.add(episode(25366));
        db.nextInShow.put(8315L, episode(25013));

        NextEpisodePicker.Pick pick = NextEpisodePicker.pick(playing, NO_PLAYLIST, NO_PLAYLIST, 8315, db);

        assertEquals(25013, pick.item.getId());
        assertTrue(pick.fromFeed);
    }

    @Test
    public void episodeTheUserPickedFollowsTheQueue() {
        FakeDb db = new FakeDb();
        FeedItem playing = episode(8315);
        db.queue.add(playing);
        db.queue.add(episode(25366));
        db.nextInShow.put(8315L, episode(25013));

        NextEpisodePicker.Pick pick = NextEpisodePicker.pick(playing, NO_PLAYLIST, NO_PLAYLIST, NO_RUN, db);

        assertEquals(25366, pick.item.getId());
        assertFalse(pick.fromFeed);
    }

    @Test
    public void lastInQueueContinuesThroughTheShowAndStartsARun() {
        FakeDb db = new FakeDb();
        FeedItem playing = episode(23519);
        db.queue.add(playing);
        db.nextInShow.put(23519L, episode(24902));

        NextEpisodePicker.Pick pick = NextEpisodePicker.pick(playing, NO_PLAYLIST, NO_PLAYLIST, NO_RUN, db);

        assertEquals(24902, pick.item.getId());
        assertTrue(pick.fromFeed);
    }

    @Test
    public void runFallsBackToTheQueueWhenTheShowIsFinished() {
        FakeDb db = new FakeDb();
        FeedItem playing = episode(25013);
        db.queue.add(playing);
        db.queue.add(episode(25366));

        NextEpisodePicker.Pick pick = NextEpisodePicker.pick(playing, NO_PLAYLIST, NO_PLAYLIST, 25013, db);

        assertEquals(25366, pick.item.getId());
        assertFalse(pick.fromFeed);
    }

    @Test
    public void nothingLeftAnywhere() {
        FakeDb db = new FakeDb();
        FeedItem playing = episode(25366);
        db.queue.add(playing);

        NextEpisodePicker.Pick pick = NextEpisodePicker.pick(playing, NO_PLAYLIST, NO_PLAYLIST, NO_RUN, db);

        assertNull(pick.item);
        assertFalse(pick.fromFeed);
    }

    @Test
    public void lastInQueueAndNewestOfItsShowWrapsToTheTopOfTheQueue() {
        // 2026-11-05: the auto-added daily episode 25366 ended last in a 95-episode queue.
        FakeDb db = new FakeDb();
        FeedItem playing = episode(25366);
        db.queue.add(episode(2058));
        db.queue.add(episode(6426));
        db.queue.add(playing);

        NextEpisodePicker.Pick pick = NextEpisodePicker.pick(playing, NO_PLAYLIST, NO_PLAYLIST, NO_RUN, db);

        assertEquals(2058, pick.item.getId());
        assertFalse(pick.fromFeed);
    }

    @Test
    public void endOfANamedPlaylistWrapsWithinThatPlaylist() {
        FakeDb db = new FakeDb() {
            @Override
            public boolean isInPlaylist(long playlistId, long itemId) {
                return playlistId == 7;
            }
        };
        FeedItem playing = episode(3);
        db.playlist.add(episode(1));
        db.playlist.add(playing);
        db.queue.add(episode(99));

        NextEpisodePicker.Pick pick = NextEpisodePicker.pick(playing, 7, NO_PLAYLIST, NO_RUN, db);

        assertEquals(1, pick.item.getId());
        assertFalse(pick.leftPlaylist);
    }

    @Test
    public void leavingTheActivePlaylistIsReported() {
        FakeDb db = new FakeDb();
        FeedItem playing = episode(1);
        db.queue.add(playing);
        db.queue.add(episode(2));

        NextEpisodePicker.Pick pick = NextEpisodePicker.pick(playing, 7, NO_PLAYLIST, NO_RUN, db);

        assertTrue(pick.leftPlaylist);
        assertEquals(2, pick.item.getId());
    }
}
