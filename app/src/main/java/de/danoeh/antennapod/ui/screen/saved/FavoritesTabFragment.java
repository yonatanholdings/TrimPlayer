package de.danoeh.antennapod.ui.screen.saved;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.event.FavoritesEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.episodeslist.EpisodesListFragment;

/**
 * Favorites tab of the "Saved" screen: all episodes the user starred, with the
 * usual episode-list machinery (swipe actions, multi-select). Unlike the
 * Episodes screen's favorites toggle, the filter here is pinned. The hosting
 * {@link SavedFragment} owns the toolbar, so this fragment hides its own.
 */
public class FavoritesTabFragment extends EpisodesListFragment {
    public static final String TAG = "FavoritesTabFragment";

    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = super.onCreateView(inflater, container, savedInstanceState);
        root.findViewById(R.id.appbar).setVisibility(View.GONE);
        emptyView.setIcon(R.drawable.ic_star);
        emptyView.setTitle(R.string.no_favorite_episodes_head_label);
        emptyView.setMessage(R.string.no_favorite_episodes_label);
        return root;
    }

    @NonNull
    @Override
    protected List<FeedItem> loadData() {
        return DBReader.getEpisodes(0, page * EPISODES_PER_PAGE, getFilter(),
                UserPreferences.getAllEpisodesSortOrder());
    }

    @NonNull
    @Override
    protected List<FeedItem> loadMoreData(int page) {
        return DBReader.getEpisodes((page - 1) * EPISODES_PER_PAGE, EPISODES_PER_PAGE, getFilter(),
                UserPreferences.getAllEpisodesSortOrder());
    }

    @Override
    protected int loadTotalItemCount() {
        return DBReader.getTotalEpisodeCount(getFilter());
    }

    @Override
    protected FeedItemFilter getFilter() {
        // Favorites of no-longer-subscribed feeds stay visible, mirroring the
        // Episodes screen's favorites toggle.
        return new FeedItemFilter(FeedItemFilter.IS_FAVORITE, FeedItemFilter.INCLUDE_NOT_SUBSCRIBED);
    }

    @Override
    protected String getFragmentTag() {
        return TAG;
    }

    @Override
    protected boolean ownsScreenToolbar() {
        return false;
    }

    /** The base class only updates rows already in the list (FeedItemEvent), so a
     *  newly starred episode wouldn't appear until the next full load — reload. */
    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void onFavoritesChanged(FavoritesEvent event) {
        loadItems();
    }
}
