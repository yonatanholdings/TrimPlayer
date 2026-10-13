package de.danoeh.antennapod.ui.screen;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.event.FeedListUpdateEvent;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.event.UnreadItemsUpdateEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.episodeslist.EpisodesListFragment;
import de.danoeh.antennapod.ui.screen.feed.ItemSortDialog;

import org.greenrobot.eventbus.EventBus;

import java.util.List;

/**
 * "New episodes" — everything published since the last-seen watermark that the
 * user has neither played nor scheduled into a playlist.
 *
 * <p>The Inbox's successor. It carries no per-episode state: the list is derived
 * from {@link UserPreferences#getLastSeenPubDate()}, so triage happens either by
 * moving that boundary ("Mark all as seen") or by acting on an episode — adding
 * it to a playlist or marking it played both drop it off the list.
 *
 * <p>The watermark is advanced only on an explicit action, never on opening the
 * screen: the Inbox's value was that an interrupted triage session survived.
 */
public class NewArrivalsFragment extends EpisodesListFragment {
    public static final String TAG = "NewArrivalsFragment";
    private static Pair<Integer, Integer> scrollPosition = null;

    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View root = super.onCreateView(inflater, container, savedInstanceState);
        toolbar.inflateMenu(R.menu.new_arrivals);
        toolbar.setTitle(R.string.new_arrivals_label);
        updateToolbar();
        emptyView.setIcon(R.drawable.ic_feed);
        emptyView.setTitle(R.string.no_new_arrivals_head_label);
        emptyView.setMessage(R.string.no_new_arrivals_label);
        return root;
    }

    @Override
    protected FeedItemFilter getFilter() {
        return new FeedItemFilter(FeedItemFilter.NEW_ARRIVALS);
    }

    @Override
    protected String getFragmentTag() {
        return TAG;
    }

    @Override
    public void onPause() {
        super.onPause();
        scrollPosition = recyclerView.getScrollPosition();
    }

    @Override
    protected void onItemsFirstLoaded() {
        recyclerView.restoreScrollPosition(scrollPosition);
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        if (super.onMenuItemClick(item)) {
            return true;
        }
        if (item.getItemId() == R.id.mark_all_seen_item) {
            markAllSeen();
            return true;
        } else if (item.getItemId() == R.id.new_arrivals_sort) {
            new NewArrivalsSortDialog().show(getChildFragmentManager(), "SortDialog");
            return true;
        }
        return false;
    }

    @NonNull
    @Override
    protected List<FeedItem> loadData() {
        return DBReader.getEpisodes(0, page * EPISODES_PER_PAGE, getFilter(), getSortOrder());
    }

    @NonNull
    @Override
    protected List<FeedItem> loadMoreData(int page) {
        return DBReader.getEpisodes((page - 1) * EPISODES_PER_PAGE, EPISODES_PER_PAGE,
                getFilter(), getSortOrder());
    }

    @Override
    protected int loadTotalItemCount() {
        return DBReader.getTotalEpisodeCount(getFilter());
    }

    private static SortOrder getSortOrder() {
        return UserPreferences.getInboxSortedOrder();
    }

    /**
     * Move the boundary to now. Everything currently listed becomes seen, so
     * this is offered as a deliberate menu action rather than happening when
     * the screen is opened or scrolled.
     */
    private void markAllSeen() {
        UserPreferences.markAllArrivalsSeen();
        EventBus.getDefault().post(new MessageEvent(getString(R.string.marked_all_seen_msg)));
        // The list and every arrivals count elsewhere are derived from the
        // watermark, so both need to re-query: the episode list listens for
        // UnreadItemsUpdateEvent, the Home rail for either.
        EventBus.getDefault().post(new UnreadItemsUpdateEvent());
    }

    public static class NewArrivalsSortDialog extends ItemSortDialog {
        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            sortOrder = UserPreferences.getInboxSortedOrder();
        }

        @Override
        protected void onAddItem(int title, SortOrder ascending, SortOrder descending, boolean ascendingIsDefault) {
            if (ascending == SortOrder.DATE_OLD_NEW || ascending == SortOrder.DURATION_SHORT_LONG) {
                super.onAddItem(title, ascending, descending, ascendingIsDefault);
            }
        }

        @Override
        protected void onSelectionChanged() {
            super.onSelectionChanged();
            UserPreferences.setInboxSortedOrder(sortOrder);
            EventBus.getDefault().post(new FeedListUpdateEvent(0));
        }
    }
}
