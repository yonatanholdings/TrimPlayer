package de.danoeh.antennapod.storage.importexport.spotify;

import android.util.Log;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates Spotify-show → feed-URL resolution across the resolver chain.
 *
 * <p>For each {@link ResolverInput} we walk the chain in priority order;
 * the first {@link Resolution.Resolved} wins. Inputs are resolved in
 * parallel up to {@link #MAX_CONCURRENCY}, with a total per-import budget
 * of {@link #TOTAL_BUDGET_SECONDS} seconds. Inputs that don't get a result
 * within budget are returned as {@code Unresolvable("budget-exceeded")} so
 * the importer can surface them in the manual-search list rather than
 * silently dropping the row.
 *
 * <p>Per-call (per-resolver) timeouts are NOT enforced here — they're the
 * responsibility of each {@link ResolverImpl}'s HTTP client config. The
 * OkHttp client in {@code :net:common}
 * ({@code AntennapodHttpClient.getHttpClient()}) ships with sensible
 * connect/read defaults that bound each call to a few seconds.
 *
 * <p>Results are cached for the lifetime of a single resolver instance
 * keyed by {@code spotifyShowId}, so retries within one import attempt
 * don't re-spend the budget on the same show.
 */
public final class SpotifyShowResolver {

    private static final String TAG = "SpotifyShowResolver";

    public static final int MAX_CONCURRENCY = 8;
    public static final int TOTAL_BUDGET_SECONDS = 120;

    /** Optional reporter for "X of Y resolved." Fires once per completed
     *  input, on the worker thread that finished it. The orchestrator
     *  doesn't marshal back to main — callers are responsible. */
    public interface ProgressCallback {
        void onProgress(int resolved, int total);
    }

    private final List<ResolverImpl> chain;
    private final Map<String, Resolution> cache = new ConcurrentHashMap<>();

    public SpotifyShowResolver() {
        this(Arrays.asList(new TrimBrainResolver(), new PodcastIndexResolver()));
    }

    /** Test seam. */
    public SpotifyShowResolver(List<ResolverImpl> chain) {
        this.chain = chain;
    }

    /**
     * Blocking. Resolves each input in parallel under the concurrency and
     * total-budget caps. Output positions match {@code inputs} positions.
     * Never returns null entries; budget-exhausted inputs come back as
     * {@code Unresolvable("budget-exceeded")}.
     */
    public List<Resolution> resolveAll(List<ResolverInput> inputs, @Nullable ProgressCallback progress) {
        if (inputs == null || inputs.isEmpty()) return Collections.emptyList();

        final int n = inputs.size();
        final Resolution[] out = new Resolution[n];
        final ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(MAX_CONCURRENCY, n), namedThreadFactory());
        try {
            final AtomicInteger done = new AtomicInteger();
            final List<Future<?>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                final int idx = i;
                final ResolverInput input = inputs.get(i);
                futures.add(pool.submit(() -> {
                    out[idx] = resolveOne(input);
                    int d = done.incrementAndGet();
                    if (progress != null) {
                        try {
                            progress.onProgress(d, n);
                        } catch (Exception e) {
                            Log.w(TAG, "progress callback threw", e);
                        }
                    }
                }));
            }

            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(TOTAL_BUDGET_SECONDS);
            for (Future<?> f : futures) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    f.cancel(true);
                    continue;
                }
                try {
                    f.get(remaining, TimeUnit.NANOSECONDS);
                } catch (TimeoutException te) {
                    f.cancel(true);
                } catch (CancellationException ce) {
                    // Already cancelled — fine.
                } catch (Exception e) {
                    Log.w(TAG, "future threw", e);
                }
            }

            for (int i = 0; i < n; i++) {
                if (out[i] == null) {
                    out[i] = new Resolution.Unresolvable("budget-exceeded");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return Arrays.asList(out);
    }

    private Resolution resolveOne(ResolverInput input) {
        if (input.spotifyShowId != null) {
            Resolution cached = cache.get(input.spotifyShowId);
            if (cached != null) return cached;
        }
        Resolution last = new Resolution.Unresolvable("no-resolver-attempted");
        for (ResolverImpl r : chain) {
            Resolution outcome;
            try {
                outcome = r.resolve(input);
            } catch (Exception e) {
                outcome = new Resolution.Unresolvable(r.name() + "-threw: " + e.getMessage());
            }
            last = outcome != null ? outcome : new Resolution.Unresolvable(r.name() + "-null");
            if (last.isResolved()) break;
        }
        if (input.spotifyShowId != null) {
            cache.put(input.spotifyShowId, last);
        }
        return last;
    }

    private static java.util.concurrent.ThreadFactory namedThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "SpotifyShowResolver-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
