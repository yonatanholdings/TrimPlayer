package de.danoeh.antennapod.storage.importexport.spotify;

import android.util.Log;

import java.util.List;
import java.util.Locale;

import de.danoeh.antennapod.net.discovery.PodcastIndexPodcastSearcher;
import de.danoeh.antennapod.net.discovery.PodcastSearchResult;

/**
 * Fallback resolver that turns a Spotify show's title + author into an RSS
 * feed URL by searching PodcastIndex. PodcastIndex does NOT expose a
 * Spotify-show-id → feed lookup, so this is a fuzzy-match strategy: search
 * by combined title + author, score each result with Jaro-Winkler against
 * the input, and accept only when both title and author scores cross fixed
 * thresholds.
 *
 * <p>Reuses the existing {@link PodcastIndexPodcastSearcher} from
 * {@code :net:discovery} so the HMAC-SHA1 auth + PodcastIndex API key live
 * in exactly one place. The searcher is Rx; we call {@code blockingGet()}
 * because the orchestrator is already off-main-thread and the per-call
 * timeout is enforced by the underlying HTTP client.
 *
 * <p>Thresholds are educated guesses, NOT validated. See
 * {@code trimplayer-android-migration-plan.md} §9 for the post-launch
 * tuning plan.
 */
public final class PodcastIndexResolver implements ResolverImpl {

    private static final String TAG = "PodcastIndexResolver";
    private static final String NAME = "podcastindex";

    private static final double TITLE_THRESHOLD = 0.90;
    private static final double AUTHOR_THRESHOLD = 0.85;
    /** Cap to avoid pathological cases where PodcastIndex returns hundreds
     *  of weakly-matching shows. The first 20 are essentially always
     *  ranked by PodcastIndex's own scoring — good enough as a starting set. */
    private static final int MAX_RESULTS_TO_CONSIDER = 20;
    /** Weighting for the combined score reported back in
     *  {@link Resolution.Resolved#confidence}. Acceptance is gated by the
     *  individual thresholds above, so this only affects ranking. */
    private static final double TITLE_WEIGHT = 0.6;
    private static final double AUTHOR_WEIGHT = 0.4;

    private final PodcastIndexPodcastSearcher searcher;

    public PodcastIndexResolver() {
        this(new PodcastIndexPodcastSearcher());
    }

    PodcastIndexResolver(PodcastIndexPodcastSearcher searcher) {
        this.searcher = searcher;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Resolution resolve(ResolverInput input) {
        String query = buildQuery(input);
        if (query.isEmpty()) {
            return new Resolution.Unresolvable(NAME + "-empty-query");
        }

        List<PodcastSearchResult> results;
        try {
            results = searcher.search(query).blockingGet();
        } catch (Exception e) {
            Log.w(TAG, "search failed for " + query + ": " + e.getMessage());
            return new Resolution.Unresolvable(NAME + "-error: " + e.getMessage());
        }
        if (results == null || results.isEmpty()) {
            return new Resolution.Unresolvable(NAME + "-no-results");
        }

        String normTitle = normalize(input.title);
        String normAuthor = normalize(input.author);

        PodcastSearchResult bestCandidate = null;
        double bestCombined = -1.0;
        double bestTitleScore = 0.0;
        double bestAuthorScore = 0.0;
        int considered = 0;

        for (PodcastSearchResult r : results) {
            if (considered >= MAX_RESULTS_TO_CONSIDER) {
                break;
            }
            considered++;
            if (r == null || r.feedUrl == null) {
                continue;
            }
            double titleScore = JaroWinkler.similarity(normTitle, normalize(r.title));
            double authorScore = JaroWinkler.similarity(normAuthor, normalize(r.author));
            double combined = TITLE_WEIGHT * titleScore + AUTHOR_WEIGHT * authorScore;
            if (combined > bestCombined) {
                bestCombined = combined;
                bestTitleScore = titleScore;
                bestAuthorScore = authorScore;
                bestCandidate = r;
            }
        }

        if (bestCandidate == null) {
            return new Resolution.Unresolvable(NAME + "-no-results-with-feedurl");
        }

        // Log every decision so we can rebuild a tuning sample from logs
        // until we have proper telemetry — see plan §9.
        Log.d(TAG, String.format(Locale.US,
                "candidate title=%.2f author=%.2f combined=%.2f for query=\"%s\" -> %s",
                bestTitleScore, bestAuthorScore, bestCombined, query, bestCandidate.feedUrl));

        if (bestTitleScore < TITLE_THRESHOLD || bestAuthorScore < AUTHOR_THRESHOLD) {
            return new Resolution.Unresolvable(NAME + "-below-threshold");
        }
        return new Resolution.Resolved(bestCandidate.feedUrl, bestCombined, NAME);
    }

    private static String buildQuery(ResolverInput input) {
        StringBuilder sb = new StringBuilder();
        if (input.title != null && !input.title.isEmpty()) {
            sb.append(input.title.trim());
        }
        if (input.author != null && !input.author.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(input.author.trim());
        }
        return sb.toString();
    }

    /** Lowercase, strip punctuation, collapse whitespace. Diacritics are
     *  preserved — for international shows (Spotify's catalogue has plenty)
     *  removing them can drop the score below threshold for the very show
     *  the user is looking for. */
    private static String normalize(String s) {
        if (s == null) return "";
        String lowered = s.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lowered.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < lowered.length(); i++) {
            char c = lowered.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
                lastWasSpace = false;
            } else {
                if (!lastWasSpace && out.length() > 0) {
                    out.append(' ');
                    lastWasSpace = true;
                }
            }
        }
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == ' ') end--;
        return out.substring(0, end);
    }
}
