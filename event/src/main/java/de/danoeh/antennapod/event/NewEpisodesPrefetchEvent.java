package de.danoeh.antennapod.event;

import java.util.List;

/**
 * Fired after a feed refresh detects new episodes worth analyzing on the backend.
 * Carries (rssUrl, episodeUrl, episodeGuid) tuples so the prefetcher can ask the
 * backend to start producing segments before the user presses play.
 */
public class NewEpisodesPrefetchEvent {
    public final List<Item> items;

    public NewEpisodesPrefetchEvent(List<Item> items) {
        this.items = items;
    }

    public static class Item {
        public final String rssUrl;
        public final String episodeUrl;
        public final String episodeGuid;

        public Item(String rssUrl, String episodeUrl, String episodeGuid) {
            this.rssUrl = rssUrl;
            this.episodeUrl = episodeUrl;
            this.episodeGuid = episodeGuid;
        }
    }
}
