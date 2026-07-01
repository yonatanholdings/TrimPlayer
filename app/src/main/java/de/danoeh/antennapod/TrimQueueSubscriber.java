package de.danoeh.antennapod;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import de.danoeh.antennapod.event.QueueEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.common.TrimPrefetcher;
import de.danoeh.antennapod.net.common.TrimPrefetcher.QueueItem;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Bridges AntennaPod's {@link QueueEvent} to the TrimBrain backend:
 *
 *  <p>1. For ADDED / ADDED_ITEMS: fire a one-shot
 *     {@link TrimPrefetcher#prefetchAnalyze(String, String, String)} per new
 *     item so the backend starts analyzing right away. Idempotent server-side.
 *  2. For ANY queue change: schedule a debounced
 *     {@link TrimPrefetcher#postQueue(String, List)} that sends the full
 *     current queue snapshot. The backend uses this to prioritize prefetch
 *     work across all users.
 *
 * <p>Debounce window is 5s — short enough that a manual add-then-play sequence
 * gets the queue posted before playback starts, long enough that a bulk-add
 * (e.g. "add all episodes") only triggers one network call.
 */
public class TrimQueueSubscriber {
    private static final String TAG = "TrimQueueSubscriber";
    private static final long DEBOUNCE_MS = 5_000L;

    private final Timer debounceTimer = new Timer("trim-queue-debounce", true);
    private TimerTask pendingTask = null;
    private final Context context;

    public TrimQueueSubscriber(Context context) {
        this.context = context.getApplicationContext();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onQueueEvent(@NonNull QueueEvent ev) {
        // 1) Per-item prefetch on add — most direct way to get "just queued" items
        //    analyzed before the user reaches them. SET_QUEUE delivers the whole
        //    queue at once (e.g. after restore from backup) so iterate items list.
        switch (ev.action) {
            case ADDED:
                prefetchOne(ev.item);
                break;
            case ADDED_ITEMS:
            case SET_QUEUE:
                if (ev.items != null) {
                    for (FeedItem item : ev.items) {
                        prefetchOne(item);
                    }
                }
                break;
            default:
                // REMOVED / IRREVERSIBLE_REMOVED / CLEARED / DELETED_MEDIA / SORTED / MOVED
                // need no per-item prefetch — the queue snapshot below covers them.
                break;
        }

        // 2) Debounced queue snapshot post — coalesces bulk operations so the
        //    backend gets one POST /queue per quiet period rather than per event.
        scheduleSnapshotPost();
    }

    private static void prefetchOne(FeedItem item) {
        if (item == null) {
            return;
        }
        try {
            String rssUrl = item.getFeed() != null ? item.getFeed().getDownloadUrl() : null;
            FeedMedia media = item.getMedia();
            String episodeUrl = media != null ? media.getStreamUrl() : null;
            String guid = item.getItemIdentifier();
            if (rssUrl == null || episodeUrl == null) {
                return;
            }
            TrimPrefetcher.prefetchAnalyze(rssUrl, episodeUrl, guid);
        } catch (Exception e) {
            Log.d(TAG, "prefetchOne failed: " + e.getMessage());
        }
    }

    private synchronized void scheduleSnapshotPost() {
        if (pendingTask != null) {
            pendingTask.cancel();
        }
        pendingTask = new TimerTask() {
            @Override
            public void run() {
                try {
                    postSnapshotNow();
                } catch (Exception e) {
                    Log.d(TAG, "snapshot post failed: " + e.getMessage());
                }
                // Re-warm the prefix cache for the (possibly reordered) top of the queue.
                QueuePrefetchManager.prefetchTopOfQueue(context);
            }
        };
        debounceTimer.schedule(pendingTask, DEBOUNCE_MS);
    }

    private static void postSnapshotNow() {
        List<FeedItem> queue = DBReader.getQueue();
        if (queue == null) {
            return;
        }
        List<QueueItem> items = new ArrayList<>(queue.size());
        for (FeedItem fi : queue) {
            if (fi == null || fi.getFeed() == null) {
                continue;
            }
            FeedMedia media = fi.getMedia();
            if (media == null) {
                continue;
            }
            String rssUrl = fi.getFeed().getDownloadUrl();
            String episodeUrl = media.getStreamUrl();
            if (rssUrl == null || episodeUrl == null) {
                continue;
            }
            items.add(new QueueItem(rssUrl, episodeUrl, fi.getItemIdentifier()));
        }
        String clientId = UserPreferences.getOrCreateTrimClientId();
        Log.d(TAG, "Posting queue snapshot: " + items.size() + " items");
        TrimPrefetcher.postQueue(clientId, items);
    }
}
