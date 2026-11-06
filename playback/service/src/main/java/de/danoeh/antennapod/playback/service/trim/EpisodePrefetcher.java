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
import androidx.media3.datasource.cache.ContentMetadata;

import de.danoeh.antennapod.net.common.HttpCredentialEncoder;
import de.danoeh.antennapod.net.common.UserAgentInterceptor;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
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
                // The variant URL is in the cache metadata as soon as the first bytes arrive;
                // pre-map then rather than after a full download that can take minutes.
                final boolean[] premapped = {false};
                new CacheWriter(dataSource, dataSpec, null, (requestLength, bytesCached, newBytesCached) -> {
                    if (!premapped[0]) {
                        premapped[0] = premapCopy(app, url);
                    }
                }).cache();
                if (!premapped[0]) {
                    premapCopy(app, url);
                }
                Log.d(TAG, "Prefetched " + (length == C.LENGTH_UNSET ? "full episode" : length + " bytes")
                        + " for " + url);
            } catch (Throwable t) {
                // Best-effort: a failed prefetch just means the episode streams normally.
                Log.d(TAG, "Prefetch failed for " + url + ": " + t.getMessage());
            }
        });
    }

    /** Variant URLs already handed to the server this process; queue changes re-run prefetch. */
    private static final Set<String> PREMAPPED = Collections.synchronizedSet(new HashSet<>());

    /**
     * Hand the server the variant URL this episode's stream resolved to, so it can align
     * that copy before playback reaches it (see {@link TrimClient#premapCopy}). On a feed
     * that stitches ads per listener region the map otherwise arrives 40-150 s into the
     * episode, after the pre-roll and intro (How To Do Things, 2026-11-04). The server
     * decides whether the podcast needs it; a plain tracking redirect costs one request.
     *
     * @return true once the variant URL is known (sent now or earlier), false while the
     *         stream has not resolved yet
     */
    private static boolean premapCopy(Context app, String url) {
        try {
            Uri resolved = ContentMetadata.getRedirectedUri(
                    StreamingCache.getInstance(app).getContentMetadata(url));
            if (resolved == null) {
                return false;
            }
            if (!"https".equals(resolved.getScheme()) || !PREMAPPED.add(resolved.toString())) {
                return true;
            }
            String proToken = UserPreferences.getTrimProToken();
            if (proToken != null && UserPreferences.getTrimProTokenExpiresMs() <= System.currentTimeMillis()) {
                proToken = null;
            }
            TrimClient.getInstance()
                    .premapCopy(url, resolved.toString(), UserPreferences.getOrCreateTrimClientId(), proToken)
                    .enqueue(new retrofit2.Callback<TrimClient.EpisodeSegmentsResponse>() {
                        @Override
                        public void onResponse(retrofit2.Call<TrimClient.EpisodeSegmentsResponse> call,
                                               retrofit2.Response<TrimClient.EpisodeSegmentsResponse> response) {
                            Log.d(TAG, "Pre-map sent (http " + response.code() + ") for " + url);
                        }

                        @Override
                        public void onFailure(retrofit2.Call<TrimClient.EpisodeSegmentsResponse> call,
                                              Throwable t) {
                            PREMAPPED.remove(resolved.toString()); // try again on the next prefetch
                        }
                    });
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "Pre-map failed for " + url + ": " + t.getMessage());
            return true;
        }
    }
}
