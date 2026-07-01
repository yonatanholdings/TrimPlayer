package de.danoeh.antennapod.net.discovery;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Side-channel between {@link PodcastSearcher} implementations and the UI that
 * consumes their results. When a searcher scrapes a share-link page (YouTube,
 * SoundCloud, Spotify, etc.) it can stash an episode-title hint here keyed by
 * the URL the user shared. The screen that opens the resolved feed can then
 * consume the hint and deep-link past the show page to the specific episode.
 *
 * <p>Bounded LRU so a long-running process doesn't accumulate entries — entries
 * are tiny and typically consumed within seconds of being written.
 */
public final class EpisodeTitleCache {
    private static final int MAX_ENTRIES = 16;

    private static final Map<String, String> ENTRIES =
            new LinkedHashMap<String, String>(MAX_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private EpisodeTitleCache() {
    }

    public static synchronized void put(String shareUrl, String episodeTitle) {
        if (shareUrl == null || episodeTitle == null || episodeTitle.isEmpty()) {
            return;
        }
        ENTRIES.put(shareUrl, episodeTitle);
    }

    /** One-shot read: returns and removes the cached episode title for a share URL,
     *  or null if none was captured. */
    public static synchronized String consume(String shareUrl) {
        if (shareUrl == null) {
            return null;
        }
        return ENTRIES.remove(shareUrl);
    }
}
