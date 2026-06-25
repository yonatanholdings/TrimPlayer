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
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the currently-playing episode and the next one in the queue FULLY cached on disk, so
 * playback and any seek within them are served from the cache and survive going offline (e.g. an
 * episode boundary or a forward seek mid-tunnel while driving with Android Auto). Delegates the
 * byte fetching to {@link EpisodePrefetcher}, which writes into the shared, persistent streaming
 * cache ({@code StreamingCache}, sized to hold both full episodes).
 *
 * <p>Triggered on app start and on every (debounced) queue change. Idempotent: already-cached
 * spans are skipped, and already-downloaded episodes are ignored entirely.
 *
 * <p>Full caching only runs on an <b>unmetered</b> (Wi-Fi) connection so it never silently burns
 * mobile data on whole episodes; on a metered connection it falls back to warming just the opening
 * of each (the lightweight prefix), which still covers a brief connectivity gap.
 */
public final class QueuePrefetchManager {
    private static final String TAG = "QueuePrefetchManager";

    /** How many upcoming episodes (current + next) to keep fully cached. */
    private static final int EPISODES_TO_CACHE_FULLY = 2;

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
                // Full-cache whole episodes on Wi-Fi, or on mobile only if the user has allowed
                // episode downloads over mobile data (same setting AntennaPod uses for downloads).
                // Otherwise keep just the opening warm so we never silently burn mobile data.
                final boolean allowFull = isUnmetered(app) || UserPreferences.isAllowMobileEpisodeDownload();
                List<FeedItem> queue = DBReader.getQueue();
                if (queue == null) {
                    queue = Collections.emptyList();
                }

                // Start from the currently-playing episode if it's in the queue (so we keep IT and
                // the next one warm); otherwise start at the head (nothing playing → warm the first
                // two up-next).
                int start = indexOfCurrentlyPlaying(queue);

                int scheduled = 0;
                for (int i = start; i < queue.size() && scheduled < EPISODES_TO_CACHE_FULLY; i++) {
                    FeedItem item = queue.get(i);
                    FeedMedia media = item != null ? item.getMedia() : null;
                    if (media == null || media.isDownloaded()) {
                        // Already fully on disk (downloaded) — nothing to cache. Don't count it,
                        // so the next streamable episode still gets warmed.
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
                    if (allowFull) {
                        EpisodePrefetcher.prefetchFull(app, url, user, password);
                    } else {
                        // Metered + mobile-download disabled: keep just the opening warm.
                        EpisodePrefetcher.prefetchPrefix(app, url, user, password);
                    }
                    scheduled++;
                }
                Log.d(TAG, "Scheduled " + (allowFull ? "full" : "prefix (metered)")
                        + " prefetch for " + scheduled + " episode(s)");
            } catch (Throwable t) {
                Log.d(TAG, "prefetchTopOfQueue failed: " + t.getMessage());
            }
        });
    }

    /**
     * Index of the currently-playing episode within the queue, or 0 (the head) when nothing is
     * playing or the playing episode isn't queued. {@link PlaybackPreferences} exposes the playing
     * FeedMedia id, which we match against each queued item's media id.
     */
    private static int indexOfCurrentlyPlaying(List<FeedItem> queue) {
        long currentMediaId = PlaybackPreferences.getCurrentlyPlayingFeedMediaId();
        if (currentMediaId == PlaybackPreferences.NO_MEDIA_PLAYING) {
            return 0;
        }
        for (int i = 0; i < queue.size(); i++) {
            FeedMedia media = queue.get(i) != null ? queue.get(i).getMedia() : null;
            if (media != null && media.getId() == currentMediaId) {
                return i;
            }
        }
        return 0;
    }

    private static boolean isConnected(Context context) {
        NetworkCapabilities caps = activeCapabilities(context);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static boolean isUnmetered(Context context) {
        NetworkCapabilities caps = activeCapabilities(context);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    private static NetworkCapabilities activeCapabilities(Context context) {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return null;
        }
        Network network = cm.getActiveNetwork();
        if (network == null) {
            return null;
        }
        return cm.getNetworkCapabilities(network);
    }
}
