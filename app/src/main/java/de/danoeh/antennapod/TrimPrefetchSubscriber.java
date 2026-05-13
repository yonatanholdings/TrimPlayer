package de.danoeh.antennapod;

import de.danoeh.antennapod.event.NewEpisodesPrefetchEvent;
import de.danoeh.antennapod.net.common.TrimPrefetcher;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * Bridges {@link NewEpisodesPrefetchEvent} (posted from FeedDatabaseWriter on feed refresh)
 * to {@link TrimPrefetcher}, which lives in the network module and doesn't itself know
 * about EventBus.
 */
public class TrimPrefetchSubscriber {

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onNewEpisodes(NewEpisodesPrefetchEvent ev) {
        if (ev == null || ev.items == null) return;
        for (NewEpisodesPrefetchEvent.Item it : ev.items) {
            TrimPrefetcher.prefetchAnalyze(it.rssUrl, it.episodeUrl, it.episodeGuid);
        }
    }
}
