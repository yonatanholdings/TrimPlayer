package de.danoeh.antennapod.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentContainerView;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import com.bumptech.glide.Glide;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.snackbar.Snackbar;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.event.EpisodeDownloadEvent;
import de.danoeh.antennapod.event.FeedUpdateRunningEvent;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.event.TrimAnalyzeQueuedEvent;
import de.danoeh.antennapod.event.TrimAnalyzedEmptyEvent;
import de.danoeh.antennapod.event.TrimSegmentsUnlockedEvent;
import de.danoeh.antennapod.model.download.DownloadStatus;
import de.danoeh.antennapod.net.download.service.feed.FeedUpdateManagerImpl;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterface;
import de.danoeh.antennapod.net.download.serviceinterface.FeedUpdateManager;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueue;
import de.danoeh.antennapod.onboarding.OnboardingActivity;
import de.danoeh.antennapod.playback.cast.CastEnabledActivity;
import de.danoeh.antennapod.playback.service.PlaybackServiceInterface;
import de.danoeh.antennapod.portcast.PortcastImportProgress;
import de.danoeh.antennapod.storage.databasemaintenanceservice.DatabaseMaintenanceWorker;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.TransitionEffect;
import de.danoeh.antennapod.ui.appstartintent.MainActivityStarter;
import de.danoeh.antennapod.ui.appstartintent.MediaButtonStarter;
import de.danoeh.antennapod.ui.common.IntentUtils;
import de.danoeh.antennapod.ui.common.ThemeSwitcher;
import de.danoeh.antennapod.ui.common.ThemeUtils;
import de.danoeh.antennapod.ui.discovery.DiscoveryFragment;
import de.danoeh.antennapod.ui.screen.AddFeedFragment;
import de.danoeh.antennapod.ui.screen.AllEpisodesFragment;
import de.danoeh.antennapod.ui.screen.PlaybackHistoryFragment;
import de.danoeh.antennapod.ui.screen.SearchFragment;
import de.danoeh.antennapod.ui.screen.download.CompletedDownloadsFragment;
import de.danoeh.antennapod.ui.screen.download.DownloadLogFragment;
import de.danoeh.antennapod.ui.screen.drawer.BottomNavigation;
import de.danoeh.antennapod.ui.screen.drawer.NavDrawerFragment;
import de.danoeh.antennapod.ui.screen.drawer.NavigationNames;
import de.danoeh.antennapod.ui.screen.feed.FeedItemlistFragment;
import de.danoeh.antennapod.ui.screen.home.HomeFragment;
import de.danoeh.antennapod.ui.screen.playback.audio.AudioPlayerFragment;
import de.danoeh.antennapod.ui.screen.preferences.PreferenceActivity;
import de.danoeh.antennapod.ui.screen.playlist.PlaylistsFragment;
import de.danoeh.antennapod.ui.screen.queue.QueueFragment;
import de.danoeh.antennapod.ui.screen.rating.RatingDialogManager;
import de.danoeh.antennapod.ui.screen.saved.SavedFragment;
import de.danoeh.antennapod.ui.screen.subscriptions.SubscriptionFragment;
import de.danoeh.antennapod.ui.view.BottomSheetBackPressedCallback;
import de.danoeh.antennapod.ui.view.LockableBottomSheetBehavior;
import org.apache.commons.lang3.ArrayUtils;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The activity that is shown when the user launches the app.
 */
public class MainActivity extends CastEnabledActivity {

    private static final String TAG = "MainActivity";
    public static final String MAIN_FRAGMENT_TAG = "main";

    public static final String PREF_NAME = "MainActivityPrefs";
    public static final String PREF_IS_FIRST_LAUNCH = "prefMainActivityIsFirstLaunch";
    /** Set by a full database restore before it force-restarts; consumed here to kick
     *  a one-shot feed refresh so restored feeds fetch their cover art immediately. */
    public static final String PREF_PENDING_RESTORE_REFRESH = "prefPendingRestoreRefresh";
    /** Set when onboarding kicks off an import; consumed by HomeFragment to show a
     *  one-time "press play, we skip the boring parts" nudge once an episode lands. */
    public static final String PREF_PENDING_FIRST_PLAY = "prefPendingFirstPlay";
    /**
     * Import source string that armed {@link #PREF_PENDING_FIRST_PLAY}, for analytics.
     */
    public static final String PREF_PENDING_FIRST_PLAY_SOURCE = "prefPendingFirstPlaySource";

    public static final String EXTRA_REFRESH_ON_START = "refresh_on_start";
    public static final String KEY_GENERATED_VIEW_ID = "generated_view_id";

    private @Nullable DrawerLayout drawerLayout;
    private @Nullable ActionBarDrawerToggle drawerToggle;
    private BottomNavigation bottomNavigation;
    private View navDrawer;
    private LockableBottomSheetBehavior<FragmentContainerView> sheetBehavior;
    private BottomSheetBackPressedCallback bottomSheetBackPressedCallback;
    private OnBackPressedCallback openDefaultPageBackPressedCallback;
    private final RecyclerView.RecycledViewPool recycledViewPool = new RecyclerView.RecycledViewPool();
    private int lastTheme = 0;
    private Insets systemBarInsets = Insets.NONE;
    /** True once the user closes the import status banner; reset when the import
     *  ends so a later import shows the banner again. */
    private boolean importBannerDismissed = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        lastTheme = ThemeSwitcher.getNoTitleTheme(this);
        setTheme(lastTheme);
        if (savedInstanceState != null) {
            ensureGeneratedViewIdGreaterThan(savedInstanceState.getInt(KEY_GENERATED_VIEW_ID, 0));
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        recycledViewPool.setMaxRecycledViews(R.id.view_type_episode_item, 25);
        checkFirstLaunch();

        drawerLayout = findViewById(R.id.drawer_layout);
        navDrawer = findViewById(R.id.navDrawerFragment);
        bottomNavigation = new BottomNavigation(findViewById(R.id.bottomNavigationView)) {
            @Override
            public void onItemSelected(@IdRes int itemId) {
                sheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
                if (itemId == R.id.bottom_navigation_settings) {
                    startActivity(new Intent(MainActivity.this, PreferenceActivity.class));
                    return;
                }
                loadFragment(NavigationNames.getBottomNavigationFragmentTag(itemId), null);
            }
        };
        if (UserPreferences.isBottomNavigationEnabled()) {
            bottomNavigation.buildMenu();
            if (drawerLayout == null) { // Tablet mode
                navDrawer.setVisibility(View.GONE);
            } else {
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
            }
            drawerLayout = null;
            bottomNavigation.onCreateView();
        } else {
            bottomNavigation.hide();
            bottomNavigation = null;
            setNavDrawerSize();
        }
        openDefaultPageBackPressedCallback = new OpenDefaultPageBackPressedCallback();

        // Consume navigation bar insets - we apply them in setPlayerVisible()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_view), (v, insets) -> {
            systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            updateInsets();
            return new WindowInsetsCompat.Builder(insets)
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.NONE)
                    .build();
        });

        final FragmentManager fm = getSupportFragmentManager();
        if (fm.findFragmentByTag(MAIN_FRAGMENT_TAG) == null) {
            if (!UserPreferences.DEFAULT_PAGE_REMEMBER.equals(UserPreferences.getDefaultPage())) {
                loadFragment(UserPreferences.getDefaultPage(), null);
            } else {
                String lastFragment = NavDrawerFragment.getLastNavFragment(this);
                if (ArrayUtils.contains(getResources().getStringArray(R.array.nav_drawer_section_tags), lastFragment)) {
                    loadFragment(lastFragment, null);
                } else {
                    try {
                        loadFeedFragmentById(Integer.parseInt(lastFragment), null);
                    } catch (NumberFormatException e) {
                        // it's not a number, this happens if we removed
                        // a label from the NAV_DRAWER_TAGS
                        // give them a nice default...
                        loadFragment(HomeFragment.TAG, null);
                    }
                }
            }
        }

        FragmentTransaction transaction = fm.beginTransaction();
        NavDrawerFragment navDrawerFragment = new NavDrawerFragment();
        transaction.replace(R.id.navDrawerFragment, navDrawerFragment, NavDrawerFragment.TAG);
        AudioPlayerFragment audioPlayerFragment = new AudioPlayerFragment();
        transaction.replace(R.id.audioplayerFragment, audioPlayerFragment, AudioPlayerFragment.TAG);
        transaction.commit();

        FragmentContainerView bottomSheet = findViewById(R.id.audioplayerFragment);
        sheetBehavior = (LockableBottomSheetBehavior<FragmentContainerView>) BottomSheetBehavior.from(bottomSheet);
        sheetBehavior.setHideable(false);
        sheetBehavior.addBottomSheetCallback(bottomSheetCallback);
        bottomSheetBackPressedCallback = new BottomSheetBackPressedCallback(false, sheetBehavior, bottomSheet);

        FeedUpdateManager.getInstance().restartUpdateAlarm(this, false);
        SynchronizationQueue.getInstance().syncIfNotSyncedRecently();
        DatabaseMaintenanceWorker.enqueueIfNeeded(this);

        WorkManager.getInstance(this)
                .getWorkInfosByTagLiveData(FeedUpdateManagerImpl.WORK_TAG_FEED_UPDATE)
                .observe(this, workInfos -> {
                    boolean isRefreshingFeeds = false;
                    for (WorkInfo workInfo : workInfos) {
                        if (workInfo.getState() == WorkInfo.State.RUNNING) {
                            isRefreshingFeeds = true;
                        } else if (workInfo.getState() == WorkInfo.State.ENQUEUED) {
                            isRefreshingFeeds = true;
                        }
                    }
                    EventBus.getDefault().postSticky(new FeedUpdateRunningEvent(isRefreshingFeeds));
                });
        setupImportStatusBanner();
        WorkManager.getInstance(this)
                .getWorkInfosByTagLiveData(DownloadServiceInterface.WORK_TAG)
                .observe(this, workInfos -> {
                    Map<String, DownloadStatus> updatedEpisodes = new HashMap<>();
                    for (WorkInfo workInfo : workInfos) {
                        String downloadUrl = null;
                        for (String tag : workInfo.getTags()) {
                            if (tag.startsWith(DownloadServiceInterface.WORK_TAG_EPISODE_URL)) {
                                downloadUrl = tag.substring(DownloadServiceInterface.WORK_TAG_EPISODE_URL.length());
                            }
                        }
                        if (downloadUrl == null) {
                            continue;
                        }
                        int status;
                        if (workInfo.getState() == WorkInfo.State.RUNNING) {
                            status = DownloadStatus.STATE_RUNNING;
                        } else if (workInfo.getState() == WorkInfo.State.ENQUEUED
                                || workInfo.getState() == WorkInfo.State.BLOCKED) {
                            status = DownloadStatus.STATE_QUEUED;
                        } else {
                            status = DownloadStatus.STATE_COMPLETED;
                        }
                        int progress = workInfo.getProgress().getInt(DownloadServiceInterface.WORK_DATA_PROGRESS, -1);
                        if (progress == -1 && status != DownloadStatus.STATE_COMPLETED) {
                            status = DownloadStatus.STATE_QUEUED;
                            progress = 0;
                        }
                        if (updatedEpisodes.containsKey(downloadUrl) && status == DownloadStatus.STATE_COMPLETED) {
                            continue; // In case of a duplicate, prefer running/queued over completed
                        }
                        updatedEpisodes.put(downloadUrl, new DownloadStatus(status, progress));
                    }
                    DownloadServiceInterface.get().setCurrentDownloads(updatedEpisodes);
                    EventBus.getDefault().postSticky(new EpisodeDownloadEvent(updatedEpisodes));
                });
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateInsets();
    }

    /**
     * View.generateViewId stores the current ID in a static variable.
     * When the process is killed, the variable gets reset.
     * This makes sure that we do not get ID collisions
     * and therefore errors when trying to restore state from another view.
     */
    @SuppressWarnings("StatementWithEmptyBody")
    private void ensureGeneratedViewIdGreaterThan(int minimum) {
        while (View.generateViewId() <= minimum) {
            // Generate new IDs
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_GENERATED_VIEW_ID, View.generateViewId());
    }

    private final BottomSheetBehavior.BottomSheetCallback bottomSheetCallback = new AntennaPodBottomSheetCallback();

    private class AntennaPodBottomSheetCallback extends BottomSheetBehavior.BottomSheetCallback {
        @Override
        public void onStateChanged(@NonNull View view, int state) {
            if (state == BottomSheetBehavior.STATE_COLLAPSED) {
                onSlide(view, 0.0f);
                bottomSheetBackPressedCallback.setEnabled(false);
            } else if (state == BottomSheetBehavior.STATE_EXPANDED) {
                onSlide(view, 1.0f);
                bottomSheetBackPressedCallback.setEnabled(true);
            } else if (state == BottomSheetBehavior.STATE_HIDDEN) {
                IntentUtils.sendLocalBroadcast(MainActivity.this,
                        PlaybackServiceInterface.ACTION_SHUTDOWN_PLAYBACK_SERVICE);
                PlaybackPreferences.writeNoMediaPlaying();
                setPlayerVisible(false);
                bottomSheetBackPressedCallback.setEnabled(false);
            }
        }

        @Override
        public void onSlide(@NonNull View view, float slideOffset) {
            AudioPlayerFragment audioPlayer = (AudioPlayerFragment) getSupportFragmentManager()
                    .findFragmentByTag(AudioPlayerFragment.TAG);
            if (audioPlayer == null) {
                return;
            }

            if (slideOffset == 0.0f) { //STATE_COLLAPSED
                audioPlayer.scrollToPage(AudioPlayerFragment.POS_COVER);
            }

            audioPlayer.fadePlayerToToolbar(slideOffset);
        }
    }

    /** Wire the non-blocking in-app banner that reflects a running PortCast /
     *  Spotify import. WorkManager is the single source of truth, so the banner
     *  survives process death and reappears if the user relaunches mid-import. */
    private void setupImportStatusBanner() {
        View bar = findViewById(R.id.importStatusBar);
        findViewById(R.id.importStatusDismiss).setOnClickListener(v -> {
            importBannerDismissed = true;
            bar.setVisibility(View.GONE);
        });
        PortcastImportProgress.observe(this).observe(this, this::updateImportBanner);
    }

    private void updateImportBanner(@Nullable PortcastImportProgress progress) {
        View bar = findViewById(R.id.importStatusBar);
        if (progress == null) {
            // Import finished (or none running): clear the dismiss latch so the
            // next import surfaces the banner again.
            importBannerDismissed = false;
            bar.setVisibility(View.GONE);
            return;
        }
        if (importBannerDismissed) {
            return;
        }
        TextView text = findViewById(R.id.importStatusText);
        if (progress.getPhase() == PortcastImportProgress.Phase.SUBSCRIBING) {
            text.setText(progress.hasCount()
                    ? getString(R.string.import_status_subscribing,
                            progress.getCurrent(), progress.getTotal())
                    : getString(R.string.import_status_subscribing_indeterminate));
        } else {
            text.setText(R.string.import_status_applying);
        }
        bar.setVisibility(View.VISIBLE);
    }

    public void setupToolbarToggle(@NonNull MaterialToolbar toolbar, boolean displayUpArrow) {
        if (drawerLayout != null) { // Tablet layout does not have a drawer
            if (drawerToggle != null) {
                drawerLayout.removeDrawerListener(drawerToggle);
            }
            drawerToggle = new ActionBarDrawerToggle(this, drawerLayout, toolbar,
                    R.string.drawer_open, R.string.drawer_close);
            drawerLayout.addDrawerListener(drawerToggle);
            drawerToggle.syncState();
            drawerToggle.setDrawerIndicatorEnabled(!displayUpArrow);
            drawerToggle.setToolbarNavigationClickListener(v -> getSupportFragmentManager().popBackStack());
        } else if (!displayUpArrow) {
            toolbar.setNavigationIcon(null);
        } else {
            toolbar.setNavigationIcon(ThemeUtils.getDrawableFromAttr(this, R.attr.homeAsUpIndicator));
            toolbar.setNavigationOnClickListener(v -> getSupportFragmentManager().popBackStack());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (drawerLayout != null && drawerToggle != null) {
            drawerLayout.removeDrawerListener(drawerToggle);
        }
        if (bottomNavigation != null) {
            bottomNavigation.onDestroyView();
        }
    }

    private void checkFirstLaunch() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(PREF_IS_FIRST_LAUNCH, true)) {
            FeedUpdateManager.getInstance().restartUpdateAlarm(this, true);
            UserPreferences.setBottomNavigationEnabled(true);

            SharedPreferences.Editor edit = prefs.edit();
            edit.putBoolean(PREF_IS_FIRST_LAUNCH, false);
            edit.apply();

            // Surface the skippable "Bring your podcasts with you" import screen over
            // Home, so a brand-new user can populate their library before bouncing off
            // an empty list. Shown once (gated by the same first-launch flag).
            startActivity(new Intent(this, OnboardingActivity.class));
        }

        // A full database restore force-restarts the app and leaves the restored feeds
        // without downloaded cover art; nothing else triggers an immediate refresh, so
        // do it once here to avoid blank "white tiles" until the hourly job runs.
        if (prefs.getBoolean(PREF_PENDING_RESTORE_REFRESH, false)) {
            prefs.edit().putBoolean(PREF_PENDING_RESTORE_REFRESH, false).apply();
            FeedUpdateManager.getInstance().runOnce(this);
        }
    }

    public boolean isDrawerOpen() {
        return drawerLayout != null && navDrawer != null && drawerLayout.isDrawerOpen(navDrawer);
    }

    public LockableBottomSheetBehavior<FragmentContainerView> getBottomSheet() {
        return sheetBehavior;
    }

    private void updateInsets() {
        setPlayerVisible(findViewById(R.id.audioplayerFragment).getVisibility() == View.VISIBLE);
    }

    public void setPlayerVisible(boolean visible) {
        getBottomSheet().setLocked(!visible);
        findViewById(R.id.audioplayerFragment).setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            bottomSheetCallback.onStateChanged(null, getBottomSheet().getState()); // Update toolbar visibility
        } else {
            getBottomSheet().setState(BottomSheetBehavior.STATE_COLLAPSED);
        }
        View bottomPaddingView = findViewById(R.id.bottom_padding);
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) bottomPaddingView.getLayoutParams();
        params.height = systemBarInsets.bottom;
        bottomPaddingView.setLayoutParams(params);

        int externalPlayerHeight = (int) getResources().getDimension(R.dimen.external_player_height);
        FragmentContainerView mainView = findViewById(R.id.main_content_view);
        params = (ViewGroup.MarginLayoutParams) mainView.getLayoutParams();
        params.setMargins(systemBarInsets.left, 0, systemBarInsets.right, (visible ? externalPlayerHeight : 0));
        mainView.setLayoutParams(params);
        sheetBehavior.setPeekHeight(externalPlayerHeight);
        sheetBehavior.setHideable(true);
        sheetBehavior.setGestureInsetBottomIgnored(true);

        FragmentContainerView playerView = findViewById(R.id.playerFragment);
        ViewGroup.MarginLayoutParams playerParams = (ViewGroup.MarginLayoutParams) playerView.getLayoutParams();
        playerParams.setMargins(systemBarInsets.left, 0, systemBarInsets.right, 0);
        playerView.setLayoutParams(playerParams);
        RelativeLayout playerContent = findViewById(R.id.playerContent);
        playerContent.setPadding(systemBarInsets.left, systemBarInsets.top, systemBarInsets.right, 0);
    }

    public RecyclerView.RecycledViewPool getRecycledViewPool() {
        return recycledViewPool;
    }

    public Fragment createFragmentInstance(String tag, Bundle args) {
        Log.d(TAG, "loadFragment(tag: " + tag + ", args: " + args + ")");
        Fragment fragment;
        switch (tag) {
            case HomeFragment.TAG:
                fragment = new HomeFragment();
                break;
            case QueueFragment.TAG:
                fragment = new QueueFragment();
                break;
            case PlaylistsFragment.TAG:
                fragment = new PlaylistsFragment();
                break;
            case AllEpisodesFragment.TAG:
                fragment = new AllEpisodesFragment();
                break;
            case CompletedDownloadsFragment.TAG:
                fragment = new CompletedDownloadsFragment();
                break;
            case PlaybackHistoryFragment.TAG:
                fragment = new PlaybackHistoryFragment();
                break;
            case AddFeedFragment.TAG:
                fragment = new AddFeedFragment();
                break;
            case SubscriptionFragment.TAG:
                fragment = new SubscriptionFragment();
                break;
            case SavedFragment.TAG:
                fragment = new SavedFragment();
                break;
            case DiscoveryFragment.TAG:
                fragment = new DiscoveryFragment();
                break;
            default:
                // default to home screen
                fragment = new HomeFragment();
                args = null;
                break;
        }
        if (args != null) {
            fragment.setArguments(args);
        }
        return fragment;
    }

    public void loadFragment(String tag, Bundle args) {
        NavDrawerFragment.saveLastNavFragment(this, tag);
        if (bottomNavigation != null) {
            bottomNavigation.updateSelectedItem(tag);
        }
        loadFragment(createFragmentInstance(tag, args));
    }

    public void loadFeedFragmentById(long feedId, Bundle args) {
        Fragment fragment = FeedItemlistFragment.newInstance(feedId);
        if (args != null) {
            fragment.setArguments(args);
        }
        NavDrawerFragment.saveLastNavFragment(this, String.valueOf(feedId));
        loadFragment(fragment);
    }

    public void loadFragment(Fragment fragment) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        // clear back stack
        for (int i = 0; i < fragmentManager.getBackStackEntryCount(); i++) {
            fragmentManager.popBackStack();
        }
        FragmentTransaction t = fragmentManager.beginTransaction();
        t.replace(R.id.main_content_view, fragment, MAIN_FRAGMENT_TAG);
        fragmentManager.popBackStack();
        // TODO: we have to allow state loss here
        // since this function can get called from an AsyncTask which
        // could be finishing after our app has already committed state
        // and is about to get shutdown.  What we *should* do is
        // not commit anything in an AsyncTask, but that's a bigger
        // change than we want now.
        t.commitAllowingStateLoss();

        if (drawerLayout != null) { // Tablet layout does not have a drawer
            drawerLayout.closeDrawer(navDrawer);
        }
    }

    public void loadChildFragment(Fragment fragment, TransitionEffect transition, String navigationTag) {
        Objects.requireNonNull(fragment);
        if (navigationTag != null && bottomNavigation != null) {
            bottomNavigation.updateSelectedItem(navigationTag);
        }
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();

        if (transition == TransitionEffect.FADE) {
            transaction.setCustomAnimations(R.anim.fade_in, R.anim.fade_out);
        } else if (transition == TransitionEffect.SLIDE) {
            transaction.setCustomAnimations(
                    R.anim.slide_right_in,
                    R.anim.slide_left_out,
                    R.anim.slide_left_in,
                    R.anim.slide_right_out);
        }

        transaction
                .hide(getSupportFragmentManager().findFragmentByTag(MAIN_FRAGMENT_TAG))
                .add(R.id.main_content_view, fragment, MAIN_FRAGMENT_TAG)
                .addToBackStack(null)
                .commit();
    }

    public void loadChildFragment(Fragment fragment, TransitionEffect transition) {
        loadChildFragment(fragment, transition, null);
    }

    public void loadChildFragment(Fragment fragment) {
        loadChildFragment(fragment, TransitionEffect.NONE);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        if (drawerToggle != null) { // Tablet layout does not have a drawer
            drawerToggle.syncState();
        }
    }

    private void restartActivity() {
        finish();
        startActivity(new Intent(this, MainActivity.class));
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (drawerToggle != null) { // Tablet layout does not have a drawer
            drawerToggle.onConfigurationChanged(newConfig);
        }
        setNavDrawerSize();

        @StyleRes int requiredTheme = ThemeSwitcher.getNoTitleTheme(this);
        if (requiredTheme != lastTheme) {
            restartActivity();
        }
    }

    private void setNavDrawerSize() {
        if (drawerToggle == null) { // Tablet layout does not have a drawer
            return;
        }
        float screenPercent = getResources().getInteger(R.integer.nav_drawer_screen_size_percent) * 0.01f;
        int width = (int) (getScreenWidth() * screenPercent);
        int maxWidth = (int) getResources().getDimension(R.dimen.nav_drawer_max_screen_size);

        navDrawer.getLayoutParams().width = Math.min(width, maxWidth);
    }

    private int getScreenWidth() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        return displayMetrics.widthPixels;
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        if (getBottomSheet().getState() == BottomSheetBehavior.STATE_EXPANDED) {
            bottomSheetCallback.onSlide(null, 1.0f);
        }
    }

    private de.danoeh.antennapod.playback.service.trim.EntitlementStore.Listener entitlementListener;

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        new RatingDialogManager(this).showIfNeeded();
        getOnBackPressedDispatcher().addCallback(this, openDefaultPageBackPressedCallback);
        getOnBackPressedDispatcher().addCallback(this, bottomSheetBackPressedCallback);

        // Pro paywall surfaces (Phase 1, 2026-05-19): one listener that fires
        // the upsell on quota_exceeded and the grandfather welcome on first
        // beta_grandfather confirmation. Both helpers are idempotent so
        // re-firing on every entitlement change is safe.
        de.danoeh.antennapod.playback.service.trim.EntitlementStore store =
                de.danoeh.antennapod.playback.service.trim.EntitlementStore.get();
        // Apply current snapshot once on resume (in case the listener missed
        // a transition while the activity was stopped).
        handleEntitlementSnapshot(store.snapshot());
        entitlementListener = this::handleEntitlementSnapshot;
        store.addListener(entitlementListener);
    }

    private void handleEntitlementSnapshot(
            de.danoeh.antennapod.playback.service.trim.EntitlementStore.Snapshot snapshot) {
        if (snapshot == null || isFinishing()) {
            return;
        }
        runOnUiThread(() -> {
            if (snapshot.isQuotaExceeded()) {
                de.danoeh.antennapod.ui.screen.preferences.pro.TrimProDialogs
                        .showQuotaUpsell(this, snapshot);
            } else if (snapshot.isBetaGrandfather()) {
                de.danoeh.antennapod.ui.screen.preferences.pro.TrimProDialogs
                        .showBetaGrandfatherWelcomeIfNeeded(this, snapshot);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handleNavIntent();

        boolean hasBottomNavigation = bottomNavigation != null;
        if (lastTheme != ThemeSwitcher.getNoTitleTheme(this)
                || hasBottomNavigation != UserPreferences.isBottomNavigationEnabled()) {
            restartActivity();
        }
        if (UserPreferences.getHiddenDrawerItems().contains(NavDrawerFragment.getLastNavFragment(this))) {
            loadFragment(UserPreferences.getDefaultPage(), null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        lastTheme = ThemeSwitcher.getNoTitleTheme(this); // Don't recreate activity when a result is pending
    }

    @Override
    protected void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
        if (entitlementListener != null) {
            de.danoeh.antennapod.playback.service.trim.EntitlementStore.get()
                    .removeListener(entitlementListener);
            entitlementListener = null;
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Glide.get(this).trimMemory(level);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Glide.get(this).clearMemory();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (drawerToggle != null && drawerToggle.onOptionsItemSelected(item)) { // Tablet layout does not have a drawer
            return true;
        } else if (item.getItemId() == android.R.id.home) {
            if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                getSupportFragmentManager().popBackStack();
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    class OpenDefaultPageBackPressedCallback extends OnBackPressedCallback {
        OpenDefaultPageBackPressedCallback() {
            super(true);
        }

        @Override
        public void handleOnBackPressed() {
            String defaultPage = UserPreferences.getDefaultPage();
            if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                getSupportFragmentManager().popBackStack();
            } else if (!NavDrawerFragment.getLastNavFragment(MainActivity.this).equals(defaultPage)
                    && !UserPreferences.DEFAULT_PAGE_REMEMBER.equals(defaultPage)) {
                loadFragment(defaultPage, null);
            } else if (UserPreferences.backButtonOpensDrawer() && drawerLayout != null && bottomNavigation == null) {
                drawerLayout.openDrawer(navDrawer);
            } else {
                finish();
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventMainThread(MessageEvent event) {
        Log.d(TAG, "onEvent(" + event + ")");
        Snackbar snackbar;
        if (getBottomSheet().getState() == BottomSheetBehavior.STATE_COLLAPSED) {
            snackbar = Snackbar.make(findViewById(R.id.main_content_view), event.message, Snackbar.LENGTH_LONG);
            if (findViewById(R.id.audioplayerFragment).getVisibility() == View.VISIBLE) {
                snackbar.setAnchorView(findViewById(R.id.audioplayerFragment));
            }
        } else {
            snackbar = Snackbar.make(findViewById(android.R.id.content), event.message, Snackbar.LENGTH_LONG);
        }
        snackbar.show();

        if (event.action != null) {
            snackbar.setAction(event.actionText, v -> event.action.accept(this));
        }
    }

    /**
     * Fires when PlaybackService asks the backend to map a podcast we haven't
     * seen before. Surfaced as a custom Snackbar so the user understands their
     * first play of this show takes a moment to start skipping ads, without
     * interrupting playback.
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onTrimAnalyzeQueued(TrimAnalyzeQueuedEvent event) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        String podcast = event.podcastTitle.isEmpty()
                ? getString(R.string.trim_analyze_queued_fallback_podcast_name)
                : event.podcastTitle;
        showTrimStatusSnackbar(
                getString(R.string.trim_analyze_queued_title, podcast),
                getString(R.string.trim_analyze_queued_body),
                R.drawable.ic_content_cut,
                R.color.trim_badge_pending);
    }

    /**
     * Fires when polling-driven refetch sees the first non-empty segment list
     * for the currently-playing episode — i.e. the analyze the user kicked off
     * has paid off mid-session. Shares the queued snackbar's layout so the two
     * states feel like one progression (mapping → mapped) with a distinct icon.
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onTrimSegmentsUnlocked(TrimSegmentsUnlockedEvent event) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        String podcast = event.podcastTitle.isEmpty()
                ? getString(R.string.trim_analyze_queued_fallback_podcast_name)
                : event.podcastTitle;
        showTrimStatusSnackbar(
                getString(R.string.trim_segments_unlocked_title, podcast),
                getString(R.string.trim_segments_unlocked_body),
                R.drawable.ic_check_circle_outline,
                R.color.trim_badge_done);
    }

    /**
     * Resolves a prior "Mapping…" snackbar when the backend confirms the
     * episode has been analyzed but produced no skippable segments — same
     * shape as the unlocked path, distinct copy so the user sees the actual
     * outcome instead of being left hanging on "Mapping…".
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onTrimAnalyzedEmpty(TrimAnalyzedEmptyEvent event) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        String podcast = event.podcastTitle.isEmpty()
                ? getString(R.string.trim_analyze_queued_fallback_podcast_name)
                : event.podcastTitle;
        showTrimStatusSnackbar(
                getString(R.string.trim_analyzed_empty_title, podcast),
                getString(R.string.trim_analyzed_empty_body),
                R.drawable.ic_check_circle_outline,
                R.color.trim_badge_done);
    }

    /**
     * Build a two-line Snackbar (icon badge + bold title + supporting body) on
     * top of the standard Material rounded background. The default TextView is
     * left in place (invisible) so the SnackbarContentLayout still measures
     * itself correctly; our custom view is overlaid on the snackbar's frame.
     */
    private void showTrimStatusSnackbar(String title, String body, int iconRes, int badgeTintRes) {
        Snackbar snackbar;
        if (getBottomSheet().getState() == BottomSheetBehavior.STATE_COLLAPSED) {
            snackbar = de.danoeh.antennapod.ui.view.TrimSnackbar.make(findViewById(R.id.main_content_view),
                    title, body, iconRes, badgeTintRes, Snackbar.LENGTH_LONG);
            if (findViewById(R.id.audioplayerFragment).getVisibility() == View.VISIBLE) {
                snackbar.setAnchorView(findViewById(R.id.audioplayerFragment));
            }
        } else {
            snackbar = de.danoeh.antennapod.ui.view.TrimSnackbar.make(findViewById(android.R.id.content),
                    title, body, iconRes, badgeTintRes, Snackbar.LENGTH_LONG);
        }
        snackbar.setDuration(5000);
        snackbar.show();
    }

    /**
     * Auto-boost confirmation: shown when the user double-pressed volume-up at
     * max (or stepped down past half-max from max) and PlaybackService bumped
     * the per-feed volumeAdaptionSetting. Snackbar includes an UNDO action that
     * restores the previous setting — important because the gesture is
     * implicit and a misfire should be cheap to recover from.
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onVolumeBoostAutoChanged(
            de.danoeh.antennapod.event.VolumeBoostAutoChangedEvent event) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        String podcast = event.podcastTitle.isEmpty()
                ? getString(R.string.app_action_fallback_podcast_name)
                : event.podcastTitle;
        String boostName;
        switch (event.newSetting) {
            case OFF:          boostName = getString(R.string.volume_boost_off); break;
            case LIGHT_BOOST:  boostName = getString(R.string.volume_boost_light); break;
            case MEDIUM_BOOST: boostName = getString(R.string.volume_boost_medium); break;
            case HEAVY_BOOST:  boostName = getString(R.string.volume_boost_heavy); break;
            default:           boostName = event.newSetting.name();
        }
        String msg = getString(R.string.volume_boost_auto_changed, podcast, boostName);
        Snackbar snackbar;
        if (getBottomSheet().getState() == BottomSheetBehavior.STATE_COLLAPSED) {
            snackbar = Snackbar.make(findViewById(R.id.main_content_view), msg, Snackbar.LENGTH_LONG);
            if (findViewById(R.id.audioplayerFragment).getVisibility() == View.VISIBLE) {
                snackbar.setAnchorView(findViewById(R.id.audioplayerFragment));
            }
        } else {
            snackbar = Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_LONG);
        }
        // UNDO: revert to previousSetting. Uses the same broadcast event the
        // boost ladder uses so PlaybackService applies and persists the revert.
        final long feedId = event.feedId;
        final de.danoeh.antennapod.model.feed.VolumeAdaptionSetting revertTo = event.previousSetting;
        snackbar.setAction(R.string.undo_label, v -> revertVolumeBoost(feedId, revertTo));
        snackbar.show();
    }

    /** Revert helper: persist the original setting + tell the running player. */
    private void revertVolumeBoost(long feedId,
            de.danoeh.antennapod.model.feed.VolumeAdaptionSetting revertTo) {
        io.reactivex.rxjava3.core.Completable.fromAction(() -> {
            de.danoeh.antennapod.model.feed.Feed feed =
                    de.danoeh.antennapod.storage.database.DBReader.getFeed(feedId, false, 0, 0);
            if (feed == null || feed.getPreferences() == null) {
                return;
            }
            feed.getPreferences().setVolumeAdaptionSetting(revertTo);
            de.danoeh.antennapod.storage.database.DBWriter.setFeedPreferences(feed.getPreferences());
            org.greenrobot.eventbus.EventBus.getDefault().post(
                    new de.danoeh.antennapod.event.settings.VolumeAdaptionChangedEvent(revertTo, feedId));
        }).subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io()).subscribe();
    }

    /** Load the feed's items, find the one matching {@code feedItemId}, and push
     *  ItemPagerFragment on top of the FeedItemlistFragment that was just loaded.
     *  Used by share-link handlers (e.g. YouTube) that want to deep-link past the
     *  show page directly to the episode. Silently no-ops if the item is gone. */
    private void openFeedItemAfterFeed(long feedId, long feedItemId) {
        io.reactivex.rxjava3.core.Single.<de.danoeh.antennapod.model.feed.Feed>fromCallable(() ->
                        de.danoeh.antennapod.storage.database.DBReader.getFeed(
                                feedId, false, 0, Integer.MAX_VALUE))
                .subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                .observeOn(io.reactivex.rxjava3.android.schedulers.AndroidSchedulers.mainThread())
                .subscribe(feed -> {
                    if (feed == null || feed.getItems() == null) {
                        return;
                    }
                    de.danoeh.antennapod.model.feed.FeedItem match = null;
                    for (de.danoeh.antennapod.model.feed.FeedItem fi : feed.getItems()) {
                        if (fi.getId() == feedItemId) {
                            match = fi;
                            break;
                        }
                    }
                    if (match == null) {
                        return;
                    }
                    loadChildFragment(
                            de.danoeh.antennapod.ui.screen.episode.ItemPagerFragment
                                    .newInstance(feed.getItems(), match));
                }, error -> Log.e(TAG, "openFeedItemAfterFeed failed", error));
    }

    private void handleNavIntent() {
        Log.d(TAG, "handleNavIntent()");
        Intent intent = getIntent();
        if (intent.hasExtra(MainActivityStarter.EXTRA_FEED_ID)) {
            long feedId = intent.getLongExtra(MainActivityStarter.EXTRA_FEED_ID, 0);
            long feedItemId = intent.getLongExtra(MainActivityStarter.EXTRA_FEED_ITEM_ID, 0);
            Bundle args = intent.getBundleExtra(MainActivityStarter.EXTRA_FRAGMENT_ARGS);
            if (feedId > 0) {
                if (intent.getBooleanExtra(MainActivityStarter.EXTRA_CLEAR_BACK_STACK, false)) {
                    loadFeedFragmentById(feedId, args);
                } else {
                    loadChildFragment(FeedItemlistFragment.newInstance(feedId));
                }
                if (feedItemId > 0) {
                    openFeedItemAfterFeed(feedId, feedItemId);
                }
            }
            sheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        } else if (intent.hasExtra(MainActivityStarter.EXTRA_FRAGMENT_TAG)) {
            String tag = intent.getStringExtra(MainActivityStarter.EXTRA_FRAGMENT_TAG);
            Bundle args = intent.getBundleExtra(MainActivityStarter.EXTRA_FRAGMENT_ARGS);
            if (tag != null) {
                if (intent.getBooleanExtra(MainActivityStarter.EXTRA_CLEAR_BACK_STACK, false)) {
                    loadFragment(tag, null);
                } else {
                    loadChildFragment(createFragmentInstance(tag, args), TransitionEffect.NONE, tag);
                }
            }
            sheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        } else if (intent.getBooleanExtra(MainActivityStarter.EXTRA_OPEN_PLAYER, false)) {
            sheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            bottomSheetCallback.onSlide(null, 1.0f);
        } else {
            handleDeeplink(intent.getData());
        }

        if (intent.getBooleanExtra(MainActivityStarter.EXTRA_OPEN_DRAWER, false) && drawerLayout != null) {
            drawerLayout.open();
        }
        if (intent.getBooleanExtra(MainActivityStarter.EXTRA_OPEN_DOWNLOAD_LOGS, false)) {
            new DownloadLogFragment().show(getSupportFragmentManager(), DownloadLogFragment.TAG);
        }
        if (intent.getBooleanExtra(EXTRA_REFRESH_ON_START, false)) {
            FeedUpdateManager.getInstance().runOnceOrAsk(this);
        }
        // to avoid handling the intent twice when the configuration changes
        setIntent(new Intent(MainActivity.this, MainActivity.class));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNavIntent();
    }

    /**
     * Handles the deep link incoming via App Actions.
     * Performs an in-app search or opens the relevant feature of the app
     * depending on the query.
     *
     * @param uri incoming deep link
     */
    private void handleDeeplink(Uri uri) {
        if (uri == null || uri.getPath() == null) {
            return;
        }
        Log.d(TAG, "Handling deeplink: " + uri.toString());
        switch (uri.getPath()) {
            case "/deeplink/search":
                String query = uri.getQueryParameter("query");
                if (query == null) {
                    return;
                }

                this.loadChildFragment(SearchFragment.newInstance(query));
                break;
            case "/deeplink/main":
                String feature = uri.getQueryParameter("page");
                if (feature == null) {
                    return;
                }
                switch (feature) {
                    case "DOWNLOADS":
                        loadFragment(CompletedDownloadsFragment.TAG, null);
                        break;
                    case "HISTORY":
                        loadFragment(PlaybackHistoryFragment.TAG, null);
                        break;
                    case "EPISODES":
                        loadFragment(AllEpisodesFragment.TAG, null);
                        break;
                    case "QUEUE":
                        loadFragment(QueueFragment.TAG, null);
                        break;
                    case "SUBSCRIPTIONS":
                        loadFragment(SubscriptionFragment.TAG, null);
                        break;
                    default:
                        EventBus.getDefault().post(new MessageEvent(getString(R.string.app_action_not_found, feature)));
                        return;
                }
                break;
            default:
                break;
        }
    }
  
    // Volume-up at the system ceiling boosts the current podcast. We intercept the
    // hardware key directly (it reaches the foreground activity even when volume is
    // already at max) instead of relying on a VOLUME_CHANGED broadcast, which many
    // devices don't emit for a no-op press at max — that broadcast gap is exactly
    // why the boost gesture silently stopped working. Only consumed when at max with
    // playback running and boost supported, so normal volume-raising is untouched.
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && !(getCurrentFocus() instanceof EditText)
                && de.danoeh.antennapod.playback.service.PlaybackService.isRunning
                && de.danoeh.antennapod.model.feed.VolumeAdaptionSetting.isBoostSupported()) {
            AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audioManager != null) {
                int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                if (max > 0 && cur >= max) {
                    EventBus.getDefault().post(
                            new de.danoeh.antennapod.event.VolumeBoostStepRequestedEvent(true));
                    return true;
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    //Hardware keyboard support
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        View currentFocus = getCurrentFocus();
        if (currentFocus instanceof EditText) {
            return super.onKeyUp(keyCode, event);
        }

        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        Integer customKeyCode = null;
        EventBus.getDefault().post(event);

        switch (keyCode) {
            case KeyEvent.KEYCODE_P:
                customKeyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
                break;
            case KeyEvent.KEYCODE_J: //Fallthrough
            case KeyEvent.KEYCODE_A:
            case KeyEvent.KEYCODE_COMMA:
                customKeyCode = KeyEvent.KEYCODE_MEDIA_REWIND;
                break;
            case KeyEvent.KEYCODE_K: //Fallthrough
            case KeyEvent.KEYCODE_D:
            case KeyEvent.KEYCODE_PERIOD:
                customKeyCode = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
                break;
            case KeyEvent.KEYCODE_PLUS: //Fallthrough
            case KeyEvent.KEYCODE_W:
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return true;
            case KeyEvent.KEYCODE_MINUS: //Fallthrough
            case KeyEvent.KEYCODE_S:
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return true;
            case KeyEvent.KEYCODE_M:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                    return true;
                }
                break;
            default:
                break;
        }

        if (customKeyCode != null) {
            sendBroadcast(MediaButtonStarter.createIntent(this, customKeyCode));
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
}
