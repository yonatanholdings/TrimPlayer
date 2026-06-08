package de.danoeh.antennapod.ui.screen.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentContainerView;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.databinding.HomeFragmentBinding;
import de.danoeh.antennapod.event.FeedListUpdateEvent;
import de.danoeh.antennapod.event.AnalyticsEvent;
import de.danoeh.antennapod.event.FeedUpdateRunningEvent;
import de.danoeh.antennapod.actionbutton.PlayActionButton;
import de.danoeh.antennapod.actionbutton.StreamActionButton;
import de.danoeh.antennapod.onboarding.OnboardingActivity;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.net.download.serviceinterface.FeedUpdateManager;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.echo.EchoConfig;
import de.danoeh.antennapod.ui.screen.SearchFragment;
import de.danoeh.antennapod.ui.screen.home.sections.DownloadsSection;
import de.danoeh.antennapod.ui.screen.home.sections.EchoSection;
import de.danoeh.antennapod.ui.screen.home.sections.EpisodesSurpriseSection;
import de.danoeh.antennapod.ui.screen.home.sections.InboxSection;
import de.danoeh.antennapod.ui.screen.home.sections.MonthlyStatsSection;
import de.danoeh.antennapod.ui.screen.home.sections.QueueSection;
import de.danoeh.antennapod.ui.screen.home.sections.SubscriptionsSection;
import de.danoeh.antennapod.ui.screen.home.settingsdialog.HomePreferences;
import de.danoeh.antennapod.ui.screen.home.settingsdialog.HomeSectionsSettingsDialog;
import de.danoeh.antennapod.ui.view.LiftOnScrollListener;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;

/**
 * Shows unread or recently published episodes
 */
public class HomeFragment extends Fragment implements Toolbar.OnMenuItemClickListener {

    public static final String TAG = "HomeFragment";
    public static final String PREF_NAME = "PrefHomeFragment";
    public static final String PREF_HIDE_ECHO = "HideEcho";

    private static final String KEY_UP_ARROW = "up_arrow";
    private boolean displayUpArrow;
    private HomeFragmentBinding viewBinding;
    private Disposable disposable;
    private Disposable firstPlayDisposable;

    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        viewBinding = HomeFragmentBinding.inflate(inflater);
        viewBinding.toolbar.inflateMenu(R.menu.home);
        viewBinding.toolbar.setOnMenuItemClickListener(this);
        if (savedInstanceState != null) {
            displayUpArrow = savedInstanceState.getBoolean(KEY_UP_ARROW);
        }
        viewBinding.homeScrollView.setOnScrollChangeListener(new LiftOnScrollListener(viewBinding.appbar));
        ((MainActivity) requireActivity()).setupToolbarToggle(viewBinding.toolbar, displayUpArrow);
        populateSectionList();
        updateWelcomeScreenVisibility();

        // Empty-state shortcut: tell a new user where to bring their podcasts from.
        viewBinding.welcomeImportButton.setOnClickListener(v ->
                startActivity(new Intent(getContext(), OnboardingActivity.class)));
        maybeShowFirstPlayNudge();

        viewBinding.swipeRefresh.setDistanceToTriggerSync(getResources().getInteger(R.integer.swipe_refresh_distance));
        viewBinding.swipeRefresh.setOnRefreshListener(() ->
                FeedUpdateManager.getInstance().runOnceOrAsk(requireContext()));

        return viewBinding.getRoot();
    }

    private void populateSectionList() {
        viewBinding.homeContainer.removeAllViews();

        SharedPreferences prefs = getContext().getSharedPreferences(HomeFragment.PREF_NAME, Context.MODE_PRIVATE);
        if (EchoConfig.isCurrentlyVisible() && prefs.getInt(PREF_HIDE_ECHO, 0) != EchoConfig.RELEASE_YEAR) {
            addSection(new EchoSection());
        }

        List<String> sectionTags = HomePreferences.getSortedSectionTags(getContext());
        for (String sectionTag : sectionTags) {
            addSection(getSection(sectionTag));
        }
    }

    private void addSection(Fragment section) {
        FragmentContainerView containerView = new FragmentContainerView(getContext());
        containerView.setId(View.generateViewId());
        viewBinding.homeContainer.addView(containerView);
        getChildFragmentManager().beginTransaction().replace(containerView.getId(), section).commit();
    }

    private Fragment getSection(String tag) {
        switch (tag) {
            case MonthlyStatsSection.TAG:
                return new MonthlyStatsSection();
            case QueueSection.TAG:
                return new QueueSection();
            case InboxSection.TAG:
                return new InboxSection();
            case EpisodesSurpriseSection.TAG:
                return new EpisodesSurpriseSection();
            case SubscriptionsSection.TAG:
                return new SubscriptionsSection();
            case DownloadsSection.TAG:
                return new DownloadsSection();
            default:
                return null;
        }
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    public void onEventMainThread(FeedUpdateRunningEvent event) {
        viewBinding.swipeRefresh.setRefreshing(event.isFeedUpdateRunning);
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.homesettings_items) {
            new HomeSectionsSettingsDialog(getContext(), this::populateSectionList).show();
            return true;
        } else if (item.getItemId() == R.id.refresh_item) {
            FeedUpdateManager.getInstance().runOnceOrAsk(requireContext());
            return true;
        } else if (item.getItemId() == R.id.action_search) {
            ((MainActivity) getActivity()).loadChildFragment(SearchFragment.newInstance());
            return true;
        }
        return false;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(KEY_UP_ARROW, displayUpArrow);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        // Re-evaluate every time the screen becomes visible, not only on a live
        // FeedListUpdateEvent: an import that ran while this fragment was stopped
        // (e.g. behind the onboarding screen) adds episodes whose event we missed,
        // which would otherwise leave the empty "welcome" state stuck on screen.
        updateWelcomeScreenVisibility();
        maybeShowFirstPlayNudge();
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
        if (firstPlayDisposable != null) {
            firstPlayDisposable.dispose();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onFeedListChanged(FeedListUpdateEvent event) {
        updateWelcomeScreenVisibility();
        // As imported feeds/episodes land, re-check whether we can now offer first play.
        maybeShowFirstPlayNudge();
    }

    /**
     * One-time post-import nudge: if onboarding armed it, show a "press play, we skip
     * the boring parts" card on the first available episode — turning a populated
     * library into the actual activation event (a play). Re-checked as episodes
     * materialize; the arming flag is consumed only once we have an episode to offer.
     */
    private void maybeShowFirstPlayNudge() {
        if (viewBinding == null || getContext() == null) {
            return;
        }
        SharedPreferences prefs = getContext()
                .getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(MainActivity.PREF_PENDING_FIRST_PLAY, false)) {
            return;
        }
        if (firstPlayDisposable != null) {
            firstPlayDisposable.dispose();
        }
        firstPlayDisposable = Observable.fromCallable(HomeFragment::findFirstPlayEpisode)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(items -> {
                    if (viewBinding == null || items.isEmpty()) {
                        return; // episodes not materialized yet; re-checked on next event
                    }
                    String source = prefs.getString(
                            MainActivity.PREF_PENDING_FIRST_PLAY_SOURCE, "onboarding");
                    prefs.edit()
                            .remove(MainActivity.PREF_PENDING_FIRST_PLAY)
                            .remove(MainActivity.PREF_PENDING_FIRST_PLAY_SOURCE)
                            .apply();
                    showFirstPlayNudge(items.get(0), source);
                }, error -> Log.e(TAG, "first-play nudge lookup failed", error));
    }

    /** Most-recent in-progress episode (we imported resume positions), else the newest
     *  playable episode, else empty (import hasn't materialized yet). Static + package-
     *  visible so the selection can be unit-verified against the real DB. */
    static List<FeedItem> findFirstPlayEpisode() {
        List<FeedItem> inProgress = DBReader.getEpisodes(0, 1,
                new FeedItemFilter(FeedItemFilter.PAUSED), SortOrder.DATE_NEW_OLD);
        if (!inProgress.isEmpty()) {
            return inProgress;
        }
        return DBReader.getEpisodes(0, 1,
                new FeedItemFilter(FeedItemFilter.HAS_MEDIA), SortOrder.DATE_NEW_OLD);
    }

    private void showFirstPlayNudge(FeedItem episode, String source) {
        viewBinding.firstPlayEpisode.setText(episode.getTitle());
        viewBinding.firstPlayNudge.setOnClickListener(v -> {
            EventBus.getDefault().post(AnalyticsEvent.onboardingFirstPlayStarted(source));
            // Guarantee playback (stream if not downloaded) — the nudge says "press play".
            boolean downloaded = episode.getMedia() != null && episode.getMedia().isDownloaded();
            if (downloaded) {
                new PlayActionButton(episode).onClick(requireContext());
            } else {
                new StreamActionButton(episode).onClick(requireContext());
            }
            viewBinding.firstPlayNudge.setVisibility(View.GONE);
        });
        viewBinding.firstPlayDismiss.setOnClickListener(v ->
                viewBinding.firstPlayNudge.setVisibility(View.GONE));
        viewBinding.firstPlayNudge.setVisibility(View.VISIBLE);
    }

    private void updateWelcomeScreenVisibility() {
        if (disposable != null) {
            disposable.dispose();
        }
        disposable = Observable.fromCallable(() -> DBReader.getTotalEpisodeCount(FeedItemFilter.unfiltered()))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(numEpisodes -> {
                    boolean hasEpisodes = numEpisodes != 0;
                    viewBinding.welcomeContainer.setVisibility(hasEpisodes ? View.GONE : View.VISIBLE);
                    viewBinding.homeContainer.setVisibility(hasEpisodes ? View.VISIBLE : View.GONE);
                    viewBinding.swipeRefresh.setVisibility(hasEpisodes ? View.VISIBLE : View.GONE);
                    if (!hasEpisodes) {
                        viewBinding.homeScrollView.setScrollY(0);
                    }
                    boolean bottomNav = UserPreferences.isBottomNavigationEnabled();
                    viewBinding.arrowBottomIcon.setVisibility(bottomNav ? View.VISIBLE : View.GONE);
                    viewBinding.arrowSidebarIcon.setVisibility(bottomNav ? View.GONE : View.VISIBLE);
                }, error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

}
