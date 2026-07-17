package de.danoeh.antennapod;

import android.content.Context;

import androidx.annotation.NonNull;

import de.danoeh.antennapod.event.BookmarksChangedEvent;
import de.danoeh.antennapod.event.FavoritesEvent;
import de.danoeh.antennapod.event.FeedListUpdateEvent;
import de.danoeh.antennapod.event.PlaylistEvent;
import de.danoeh.antennapod.event.QueueEvent;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * Kicks an immediate (debounced) TrimBrain account sync when the user changes
 * their library — bookmarks, favorites, the queue, or subscriptions — so the
 * change reaches the other devices + web in seconds instead of waiting for the
 * ~2h periodic {@link TrimSyncWorker}.
 *
 * <p>All handlers funnel into {@link TrimSyncWorker#requestSyncSoon(Context)},
 * whose WorkManager REPLACE + short delay coalesces a burst of events (even
 * across entity types — a favorite + queue + feed change together) into a single
 * sync. The worker only pushes genuine diffs (change-journal) and no-ops when
 * logged out, so extra events are cheap.
 *
 * <p>Playback progress is deliberately NOT wired here: it changes constantly
 * during playback and stays on the periodic cadence.
 *
 * <p>Echo: applying a server change also posts these events, scheduling one
 * follow-up sync that finds nothing new, writes no rows, posts no event, and
 * terminates — no loop, just one harmless extra round-trip per remote change.
 */
public class TrimSyncSubscriber {
    private final Context context;

    public TrimSyncSubscriber(Context context) {
        this.context = context.getApplicationContext();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onBookmarksChanged(@NonNull BookmarksChangedEvent ev) {
        TrimSyncWorker.requestSyncSoon(context);
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onFavoritesChanged(@NonNull FavoritesEvent ev) {
        TrimSyncWorker.requestSyncSoon(context);
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onQueueChanged(@NonNull QueueEvent ev) {
        TrimSyncWorker.requestSyncSoon(context);
    }

    // Playlist created/renamed/deleted or its episodes changed — playlists sync
    // 1:1 with the account's named queues.
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onPlaylistChanged(@NonNull PlaylistEvent ev) {
        TrimSyncWorker.requestSyncSoon(context);
    }

    // Subscribe / unsubscribe. Also fires on feed-content refreshes, but the diff
    // is then empty (push nothing) and the debounce coalesces refresh bursts, so
    // the extra cost is one cheap sync per refresh cycle.
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onFeedListChanged(@NonNull FeedListUpdateEvent ev) {
        TrimSyncWorker.requestSyncSoon(context);
    }
}
