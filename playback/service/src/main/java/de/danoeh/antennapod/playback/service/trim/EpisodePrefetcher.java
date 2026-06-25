package de.danoeh.antennapod.playback.service.trim;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;

import de.danoeh.antennapod.net.common.HttpCredentialEncoder;
import de.danoeh.antennapod.net.common.UserAgentInterceptor;

import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Warms the shared {@link StreamingCache} with the opening of an episode so that, when the user
 * reaches it, the first few minutes play instantly from disk (and survive a connectivity gap at
 * the episode boundary — the tunnel-while-driving case).
 *
 * <p>Uses Media3's {@link CacheWriter} to download a bounded byte range [0, maxBytes) into the
 * same {@link androidx.media3.datasource.cache.SimpleCache} the player reads from, keyed by the
 * stream URL (the default cache key, matching {@code ExoPlayerWrapper}). Already-cached spans are
 * skipped, so re-running this on every queue change is cheap and idempotent.
 */
@OptIn(markerClass = UnstableApi.class)
public final class EpisodePrefetcher {
    private static final String TAG = "EpisodePrefetcher";

    /**
     * Per-episode prefetch budget. ~5 minutes at ~160 kbps (and more — ~12 min — at the 64 kbps
     * typical of mono speech podcasts). Bounds both data usage and cache footprint.
     */
    public static final long PREFETCH_BYTES = 6L * 1024 * 1024;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "episode-prefetch");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private EpisodePrefetcher() {
    }

    public static void prefetchPrefix(Context context, String url, String user, String password) {
        prefetchPrefix(context, url, user, password, PREFETCH_BYTES);
    }

    public static void prefetchPrefix(Context context, String url, String user, String password, long maxBytes) {
        prefetch(context, url, user, password, maxBytes);
    }

    /**
     * Cache the WHOLE episode (not just the opening), so playback and any seek within it are served
     * from disk and survive going offline — e.g. the currently-playing and next-in-queue episodes
     * kept fully warm for the tunnel-while-driving case. Already-cached spans are skipped, so this is
     * cheap and idempotent on re-runs once the file is on disk.
     */
    public static void prefetchFull(Context context, String url, String user, String password) {
        prefetch(context, url, user, password, C.LENGTH_UNSET);
    }

    /**
     * @param length number of bytes from the start to cache, or {@link C#LENGTH_UNSET} for the
     *               whole resource.
     */
    private static void prefetch(Context context, String url, String user, String password, long length) {
        if (url == null || !url.startsWith("http")) {
            return;
        }
        final Context app = context.getApplicationContext();
        EXEC.execute(() -> {
            try {
                DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory();
                http.setUserAgent(UserAgentInterceptor.USER_AGENT);
                http.setAllowCrossProtocolRedirects(true);
                http.setKeepPostFor302Redirects(true);
                if (!TextUtils.isEmpty(user) && !TextUtils.isEmpty(password)) {
                    HashMap<String, String> requestProperties = new HashMap<>();
                    requestProperties.put("Authorization",
                            HttpCredentialEncoder.encode(user, password, "ISO-8859-1"));
                    http.setDefaultRequestProperties(requestProperties);
                }
                CacheDataSource dataSource = new CacheDataSource.Factory()
                        .setCache(StreamingCache.getInstance(app))
                        .setUpstreamDataSourceFactory(http)
                        .createDataSource();
                DataSpec dataSpec = new DataSpec.Builder()
                        .setUri(Uri.parse(url))
                        .setPosition(0)
                        .setLength(length)
                        .build();
                new CacheWriter(dataSource, dataSpec, null, null).cache();
                Log.d(TAG, "Prefetched " + (length == C.LENGTH_UNSET ? "full episode" : length + " bytes")
                        + " for " + url);
            } catch (Throwable t) {
                // Best-effort: a failed prefetch just means the episode streams normally.
                Log.d(TAG, "Prefetch failed for " + url + ": " + t.getMessage());
            }
        });
    }
}
