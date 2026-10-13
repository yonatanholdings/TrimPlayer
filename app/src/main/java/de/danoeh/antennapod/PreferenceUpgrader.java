package de.danoeh.antennapod;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import androidx.core.app.NotificationManagerCompat;
import androidx.preference.PreferenceManager;

import de.danoeh.antennapod.model.feed.FeedCounter;
import de.danoeh.antennapod.system.CrashReportWriter;
import de.danoeh.antennapod.net.download.serviceinterface.FeedUpdateManager;
import org.apache.commons.lang3.StringUtils;

import java.util.concurrent.TimeUnit;

import de.danoeh.antennapod.storage.preferences.SleepTimerPreferences;
import de.danoeh.antennapod.ui.screen.AllEpisodesFragment;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences.EnqueueLocation;
import de.danoeh.antennapod.ui.screen.queue.QueueFragment;
import de.danoeh.antennapod.ui.swipeactions.SwipeAction;
import de.danoeh.antennapod.ui.swipeactions.SwipeActions;

public class PreferenceUpgrader {
    private static final String PREF_CONFIGURED_VERSION = "version_code";
    private static final String PREF_NAME = "app_version";
    // One-time guard for the hours->minutes refresh-interval migration. TrimPlayer reset its
    // versionCode below 3100000 in the rebrand, so the `oldVersion < 3100000` check is permanently
    // true and the migration would re-run (multiplying by 60) on every upgrade until it overflowed.
    private static final String PREF_MIGRATED_INTERVAL_TO_MINUTES = "migratedUpdateIntervalToMinutes";
    // Largest interval the picker offers (4320 min = 3 days); anything above is corrupted data.
    private static final long MAX_VALID_UPDATE_INTERVAL_MINUTES = TimeUnit.DAYS.toMinutes(3);
    // Same class of bug as the interval migration: the episode-cleanup x24 (oldVersion < 1070196)
    // also re-fired on every sub-offset build, compounding any positive cleanup value.
    private static final String PREF_MIGRATED_CLEANUP_TO_HOURS = "migratedEpisodeCleanupToHours";
    // Largest cleanup value the picker offers (168 h = 7 days); anything above is corrupted data.
    private static final int MAX_VALID_CLEANUP_HOURS = (int) TimeUnit.DAYS.toHours(7);
    // One-time repair of swipe prefs that still name the removed inbox action.
    // Flag-guarded rather than version-gated for the same reason as the two above.
    private static final String PREF_MIGRATED_INBOX_SWIPE_ACTIONS = "migratedInboxSwipeActions";
    // One-time move off the inbox feed counter, which can only ever read zero now.
    private static final String PREF_MIGRATED_INBOX_FEED_COUNTER = "migratedInboxFeedCounter";
    // One-time move off the "Inbox" start screen, whose fragment no longer exists.
    private static final String PREF_MIGRATED_INBOX_DEFAULT_PAGE = "migratedInboxDefaultPage";

    private static SharedPreferences prefs;

    public static void checkUpgrades(Context context) {
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences upgraderPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int oldVersion = upgraderPrefs.getInt(PREF_CONFIGURED_VERSION, -1);
        int newVersion = BuildConfig.VERSION_CODE;

        if (oldVersion != newVersion) {
            CrashReportWriter.getFile().delete();

            upgrade(oldVersion, newVersion, context);
            upgraderPrefs.edit().putInt(PREF_CONFIGURED_VERSION, newVersion).apply();
        }
    }

    private static void upgrade(int oldVersion, int newVersion, Context context) {
        if (oldVersion == -1) {
            //New installation
            return;
        }
        if (oldVersion < 1070196) {
            // Upstream this migrated episode cleanup from days to hours, but TrimPlayer forked from
            // AntennaPod 3.10.1 where the value was already in hours, so the x24 was never correct
            // here, and the rebrand versionCode reset made this block re-fire and compound it. Now
            // run once (flag-guarded) and only repair a value the old bug already corrupted.
            if (!prefs.getBoolean(PREF_MIGRATED_CLEANUP_TO_HOURS, false)) {
                // getEpisodeCleanupValue() now returns EPISODE_CLEANUP_NULL for an int-overflowed
                // (unparseable) stored string, so a too-large value or that sentinel both mean the
                // stored pref is corrupt. Rewrite it to disabled to flush the garbage string.
                int value = UserPreferences.getEpisodeCleanupValue();
                String defaultRaw = String.valueOf(UserPreferences.EPISODE_CLEANUP_NULL);
                String raw = prefs.getString(UserPreferences.PREF_EPISODE_CLEANUP, defaultRaw);
                boolean unparseable = !String.valueOf(value).equals(raw);
                if (value > MAX_VALID_CLEANUP_HOURS || unparseable) {
                    UserPreferences.setEpisodeCleanupValue(UserPreferences.EPISODE_CLEANUP_NULL);
                } // else 0, valid hours, or special negative values: no change needed
                prefs.edit().putBoolean(PREF_MIGRATED_CLEANUP_TO_HOURS, true).apply();
            }
        }
        if (oldVersion < 1070197) {
            if (prefs.getBoolean("prefMobileUpdate", false)) {
                prefs.edit().putString("prefMobileUpdateAllowed", "everything").apply();
            }
        }
        if (oldVersion < 1070300) {
            if (prefs.getBoolean("prefEnableAutoDownloadOnMobile", false)) {
                UserPreferences.setAllowMobileAutoDownload(true);
            }
            switch (prefs.getString("prefMobileUpdateAllowed", "images")) {
                case "everything":
                    UserPreferences.setAllowMobileFeedRefresh(true);
                    UserPreferences.setAllowMobileEpisodeDownload(true);
                    UserPreferences.setAllowMobileImages(true);
                    break;
                default: // Fall-through to "images"
                case "images":
                    UserPreferences.setAllowMobileImages(true);
                    break;
                case "nothing":
                    UserPreferences.setAllowMobileImages(false);
                    break;
            }
        }
        if (oldVersion < 1070400) {
            UserPreferences.ThemePreference theme = UserPreferences.getTheme();
            if (theme == UserPreferences.ThemePreference.LIGHT) {
                prefs.edit().putString(UserPreferences.PREF_THEME, "system").apply();
            }

            UserPreferences.setQueueLocked(false);
            UserPreferences.setStreamOverDownload(false);

            if (!prefs.contains(UserPreferences.PREF_ENQUEUE_LOCATION)) {
                final String keyOldPrefEnqueueFront = "prefQueueAddToFront";
                boolean enqueueAtFront = prefs.getBoolean(keyOldPrefEnqueueFront, false);
                EnqueueLocation enqueueLocation = enqueueAtFront ? EnqueueLocation.FRONT : EnqueueLocation.BACK;
                UserPreferences.setEnqueueLocation(enqueueLocation);
            }
        }
        if (oldVersion < 2010300) {
            // Migrate hardware button preferences
            if (prefs.getBoolean("prefHardwareForwardButtonSkips", false)) {
                prefs.edit().putString(UserPreferences.PREF_HARDWARE_FORWARD_BUTTON,
                        String.valueOf(KeyEvent.KEYCODE_MEDIA_NEXT)).apply();
            }
            if (prefs.getBoolean("prefHardwarePreviousButtonRestarts", false)) {
                prefs.edit().putString(UserPreferences.PREF_HARDWARE_PREVIOUS_BUTTON,
                        String.valueOf(KeyEvent.KEYCODE_MEDIA_PREVIOUS)).apply();
            }
        }
        if (oldVersion < 2040000) {
            SharedPreferences swipePrefs = context.getSharedPreferences(SwipeActions.PREF_NAME, Context.MODE_PRIVATE);
            swipePrefs.edit().putString(SwipeActions.KEY_PREFIX_SWIPEACTIONS + QueueFragment.TAG,
                    SwipeAction.REMOVE_FROM_QUEUE + "," + SwipeAction.REMOVE_FROM_QUEUE).apply();
        }
        if (oldVersion < 2050000) {
            prefs.edit().putBoolean(UserPreferences.PREF_PAUSE_PLAYBACK_FOR_FOCUS_LOSS, true).apply();
        }
        if (oldVersion < 2080000) {
            // Migrate drawer feed counter setting to reflect removal of
            // "unplayed and in inbox" (0), by changing it to "unplayed" (2)
            String feedCounterSetting = prefs.getString(UserPreferences.PREF_DRAWER_FEED_COUNTER, "1");
            if (feedCounterSetting.equals("0")) {
                prefs.edit().putString(UserPreferences.PREF_DRAWER_FEED_COUNTER, "2").apply();
            }

            SharedPreferences sleepTimerPreferences =
                    context.getSharedPreferences(SleepTimerPreferences.PREF_NAME, Context.MODE_PRIVATE);
            TimeUnit[] timeUnits = { TimeUnit.SECONDS, TimeUnit.MINUTES, TimeUnit.HOURS };
            long value = Long.parseLong(SleepTimerPreferences.lastTimerValue());
            TimeUnit unit = timeUnits[sleepTimerPreferences.getInt("LastTimeUnit", 1)];
            SleepTimerPreferences.setLastTimer(String.valueOf(unit.toMinutes(value)));

            if (prefs.getString(UserPreferences.PREF_EPISODE_CACHE_SIZE, "20")
                    .equals(context.getString(R.string.pref_episode_cache_unlimited))) {
                prefs.edit().putString(UserPreferences.PREF_EPISODE_CACHE_SIZE,
                        "" + UserPreferences.EPISODE_CACHE_SIZE_UNLIMITED).apply();
            }
        }
        if (oldVersion < 3000007) {
            if (prefs.getString("prefBackButtonBehavior", "").equals("drawer")) {
                prefs.edit().putBoolean(UserPreferences.PREF_BACK_OPENS_DRAWER, true).apply();
            }
        }
        if (oldVersion < 3010000) {
            if (prefs.getString(UserPreferences.PREF_THEME, "system").equals("2")) {
                prefs.edit()
                        .putString(UserPreferences.PREF_THEME, "1")
                        .putBoolean(UserPreferences.PREF_THEME_BLACK, true)
                        .apply();
            }
            UserPreferences.setAllowMobileSync(true);
            if (prefs.getString(UserPreferences.PREF_UPDATE_INTERVAL_MINUTES, ":").contains(":")) {
                // Unset or "time of day"
                prefs.edit().putString(UserPreferences.PREF_UPDATE_INTERVAL_MINUTES, "12").apply();
            }
        }
        if (oldVersion < 3020000) {
            NotificationManagerCompat.from(context).deleteNotificationChannel("auto_download");
        }

        if (oldVersion < 3030000) {
            SharedPreferences allEpisodesPreferences =
                    context.getSharedPreferences(AllEpisodesFragment.PREF_NAME, Context.MODE_PRIVATE);
            String oldEpisodeSort = allEpisodesPreferences.getString(UserPreferences.PREF_SORT_ALL_EPISODES, "");
            if (!StringUtils.isAllEmpty(oldEpisodeSort)) {
                prefs.edit().putString(UserPreferences.PREF_SORT_ALL_EPISODES, oldEpisodeSort).apply();
            }

            String oldEpisodeFilter = allEpisodesPreferences.getString("filter", "");
            if (!StringUtils.isAllEmpty(oldEpisodeFilter)) {
                prefs.edit().putString(UserPreferences.PREF_FILTER_ALL_EPISODES, oldEpisodeFilter).apply();
            }
        }
        if (oldVersion < 3070000) {
            // If autodownloads are enabled, we will start deleting episodes.
            // To prevent accidents, force off the deletions.
            if (!UserPreferences.isEnableAutodownloadGlobal()) {
                prefs.edit().putString(UserPreferences.PREF_EPISODE_CLEANUP,
                        "" + UserPreferences.EPISODE_CLEANUP_NULL).apply();
            }
        }
        if (newVersion == 3070003) {
            // Enable bottom navigation for beta users, so only this exact app version
            UserPreferences.setBottomNavigationEnabled(true);
        }
        if (oldVersion < 3100000) {
            // Upstream this migrated the refresh interval from hours to minutes, but TrimPlayer
            // forked from AntennaPod 3.10.1 where the value was already in minutes, so the x60 was
            // never correct here. Worse, the rebrand reset versionCode below 3100000, so this block
            // re-ran on every upgrade and the compounding x60 eventually overflowed int and crashed
            // the app at startup. Now it only runs once (flag-guarded) and just repairs any value
            // the old bug already corrupted past the picker's max.
            if (!prefs.getBoolean(PREF_MIGRATED_INTERVAL_TO_MINUTES, false)) {
                long minutes = UserPreferences.getUpdateInterval();
                if (minutes < 0 || minutes > MAX_VALID_UPDATE_INTERVAL_MINUTES) {
                    UserPreferences.setUpdateInterval(TimeUnit.HOURS.toMinutes(12)); // 720, the default
                }
                prefs.edit().putBoolean(PREF_MIGRATED_INTERVAL_TO_MINUTES, true).apply();
            }
            FeedUpdateManager.getInstance().restartUpdateAlarm(context, true);
        }
        migrateInboxSwipeActions(context);
        migrateInboxFeedCounter(context);
        migrateInboxDefaultPage();
    }

    /**
     * Move anyone whose start screen was the Inbox onto Home.
     *
     * <p>"NewEpisodesFragment" names a fragment that no longer exists, so
     * MainActivity#createFragmentInstance fell through to its default and opened
     * Home anyway — but the Settings row rendered blank, because the value is no
     * longer among the options. Store what actually happens.
     */
    private static void migrateInboxDefaultPage() {
        if (prefs.getBoolean(PREF_MIGRATED_INBOX_DEFAULT_PAGE, false)) {
            return;
        }
        if ("NewEpisodesFragment".equals(UserPreferences.getDefaultPage())) {
            UserPreferences.setDefaultPage("HomeFragment");
        }
        prefs.edit().putBoolean(PREF_MIGRATED_INBOX_DEFAULT_PAGE, true).apply();
    }

    /**
     * Move anyone still on the inbox feed counter onto the unplayed counter.
     *
     * <p>FeedCounter.SHOW_NEW (id 1) counts FeedItem.NEW. Nothing sets that flag
     * since the Inbox was retired, so the badge showed 0 on every subscription —
     * and because it was also the default, that was most users. The 2080000 block
     * above made the same substitution for the older "unplayed and in inbox"
     * value, so this follows that precedent.
     */
    private static void migrateInboxFeedCounter(Context context) {
        if (prefs.getBoolean(PREF_MIGRATED_INBOX_FEED_COUNTER, false)) {
            return;
        }
        String current = prefs.getString(UserPreferences.PREF_DRAWER_FEED_COUNTER,
                "" + FeedCounter.SHOW_NEW.id);
        if (current.equals("" + FeedCounter.SHOW_NEW.id)) {
            prefs.edit().putString(UserPreferences.PREF_DRAWER_FEED_COUNTER,
                    "" + FeedCounter.SHOW_UNPLAYED.id).apply();
        }
        prefs.edit().putBoolean(PREF_MIGRATED_INBOX_FEED_COUNTER, true).apply();
    }

    /**
     * Drop REMOVE_FROM_INBOX out of saved swipe preferences.
     *
     * <p>The inbox swipe action went away with the Inbox itself. SwipeActions#getAction
     * returns null for an id it no longer knows, and Actions#hasActions then reports
     * false, which silently disables BOTH swipe directions on every screen whose saved
     * pref mentions it — so leaving the stale value in place would break swiping for
     * exactly the users who had configured it. Rewrite each affected screen to its
     * default pair instead.
     */
    private static void migrateInboxSwipeActions(Context context) {
        if (prefs.getBoolean(PREF_MIGRATED_INBOX_SWIPE_ACTIONS, false)) {
            return;
        }
        SharedPreferences swipePrefs = context.getSharedPreferences(
                SwipeActions.PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = swipePrefs.edit();
        for (String key : swipePrefs.getAll().keySet()) {
            if (!key.startsWith(SwipeActions.KEY_PREFIX_SWIPEACTIONS)) {
                continue;
            }
            String value = swipePrefs.getString(key, null);
            if (value == null || !value.contains(SwipeAction.REMOVE_FROM_INBOX)) {
                continue;
            }
            // Keep whichever side the user chose deliberately; replace only the
            // dead half. If both sides were the inbox action, fall back to
            // marking the episode played, the nearest thing it used to do.
            String[] sides = value.split(",", -1);
            if (sides.length != 2) {
                editor.remove(key);
                continue;
            }
            String right = SwipeAction.REMOVE_FROM_INBOX.equals(sides[0])
                    ? SwipeAction.TOGGLE_PLAYED : sides[0];
            String left = SwipeAction.REMOVE_FROM_INBOX.equals(sides[1])
                    ? SwipeAction.TOGGLE_PLAYED : sides[1];
            editor.putString(key, right + "," + left);
        }
        editor.apply();
        prefs.edit().putBoolean(PREF_MIGRATED_INBOX_SWIPE_ACTIONS, true).apply();
    }
}
