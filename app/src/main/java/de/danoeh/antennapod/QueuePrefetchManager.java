package de.danoeh.antennapod;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.playback.service.trim.EpisodePrefetcher;
import de.danoeh.antennapod.storage.database.DBReader;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the opening of the next few queued episodes warm on disk so playback survives a
 * connectivity gap at an episode boundary (e.g. an episode ends mid-tunnel while driving with
 * Android Auto). Delegates the actual byte fetching to {@link EpisodePrefetcher}, which writes
 * into the shared, persistent streaming cache.
 *
 * <p>Triggered on app start and on every (debounced) queue change. Idempotent: already-cached
 * prefixes are skipped, and already-downloaded episodes are ignored entirely.
 */
public final class QueuePrefetchManager {
    private static final String TAG = "QueuePrefetchManager";

    /** How many upcoming queued episodes to keep a prefix cached for. */
    private static final int EPISODES_TO_PREFETCH = 3;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "queue-prefetch");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private QueuePrefetchManager() {
    }

    public static void prefetchTopOfQueue(Context context) {
        final Context app = context.getApplicationContext();
        EXEC.execute(() -> {
            try {
                if (!isConnected(app)) {
                    return;
                }
                List<FeedItem> queue = DBReader.getQueue();
                if (queue == null) {
                    return;
                }
                int scheduled = 0;
                for (FeedItem item : queue) {
                    if (scheduled >= EPISODES_TO_PREFETCH) {
                        break;
                    }
                    FeedMedia media = item != null ? item.getMedia() : null;
                    if (media == null || media.isDownloaded()) {
                        // Already on disk in full — nothing to prefetch.
                        continue;
                    }
                    String url = media.getStreamUrl();
                    if (url == null || !url.startsWith("http")) {
                        continue;
                    }
                    String user = null;
                    String password = null;
                    if (item.getFeed() != null) {
                        FeedPreferences prefs = item.getFeed().getPreferences();
                        if (prefs != null) {
                            user = prefs.getUsername();
                            password = prefs.getPassword();
                        }
                    }
                    EpisodePrefetcher.prefetchPrefix(app, url, user, password);
                    scheduled++;
                }
                Log.d(TAG, "Scheduled prefetch for " + scheduled + " queued episode(s)");
            } catch (Throwable t) {
                Log.d(TAG, "prefetchTopOfQueue failed: " + t.getMessage());
            }
        });
    }

    private static boolean isConnected(Context context) {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        Network network = cm.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
