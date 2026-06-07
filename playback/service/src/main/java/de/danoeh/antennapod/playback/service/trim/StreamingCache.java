package de.danoeh.antennapod.playback.service.trim;

import android.content.Context;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;

/**
 * Process-wide singleton for the on-disk streaming cache.
 *
 * <p>Media3 permits only one {@link SimpleCache} per directory per process, so both the live
 * player ({@code ExoPlayerWrapper}) and the queue prefetcher ({@link EpisodePrefetcher}) must
 * share this instance — that's what lets a prefetched prefix be served straight from disk when
 * the user actually reaches the episode.
 *
 * <p>The cache lives under {@link Context#getNoBackupFilesDir()} (not {@code getCacheDir()}) so
 * the OS won't reclaim it under storage pressure, and its content index is persisted via
 * {@link StandaloneDatabaseProvider}. Both together mean prefetched audio survives an app
 * restart and a device reboot: on next launch the cache is reopened on the same directory and
 * the previously cached spans are immediately reusable.
 */
@OptIn(markerClass = UnstableApi.class)
public final class StreamingCache {
    /** LRU budget. Holds the current episode's look-ahead plus a few prefetched prefixes. */
    private static final long MAX_BYTES = 200L * 1024 * 1024;

    private static SimpleCache instance;

    private StreamingCache() {
    }

    public static synchronized SimpleCache getInstance(Context context) {
        if (instance == null) {
            Context app = context.getApplicationContext();
            File dir = new File(app.getNoBackupFilesDir(), "streaming-cache");
            instance = new SimpleCache(dir,
                    new LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                    new StandaloneDatabaseProvider(app));
        }
        return instance;
    }
}
