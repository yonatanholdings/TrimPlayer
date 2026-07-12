package de.danoeh.antennapod.storage.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import de.danoeh.antennapod.model.feed.FeedOrder;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.danoeh.antennapod.model.download.ProxyConfig;
import de.danoeh.antennapod.model.feed.FeedCounter;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.model.feed.SubscriptionsFilter;

/**
 * Provides access to preferences set by the user in the settings screen. A
 * private instance of this class must first be instantiated via
 * init() or otherwise every public method will throw an Exception
 * when called.
 */
public abstract class UserPreferences {
    private static final String TAG = "UserPreferences";

    // User Interface
    public static final String PREF_THEME = "prefTheme";
    public static final String PREF_THEME_BLACK = "prefThemeBlack";
    public static final String PREF_TINTED_COLORS = "prefTintedColors";
    public static final String PREF_HIDDEN_DRAWER_ITEMS = "prefHiddenDrawerItems";
    public static final String PREF_DRAWER_ITEM_ORDER = "prefDrawerItemOrder";
    public static final String PREF_DRAWER_FEED_ORDER = "prefDrawerFeedOrder";
    public static final String PREF_DRAWER_FEED_COUNTER = "prefDrawerFeedIndicator";
    public static final String PREF_EXPANDED_NOTIFICATION = "prefExpandNotify";
    public static final String PREF_USE_EPISODE_COVER = "prefEpisodeCover";
    public static final String PREF_SHOW_TIME_LEFT = "showTimeLeft";
    private static final String PREF_PERSISTENT_NOTIFICATION = "prefPersistNotify";
    public static final String PREF_FULL_NOTIFICATION_BUTTONS = "prefFullNotificationButtons";
    private static final String PREF_SHOW_DOWNLOAD_REPORT = "prefShowDownloadReport";
    public static final String PREF_DEFAULT_PAGE = "prefDefaultPage";
    public static final String PREF_FILTER_FEED = "prefSubscriptionsFilter";
    public static final String PREF_SUBSCRIPTION_TITLE = "prefSubscriptionTitle";
    public static final String PREF_BACK_OPENS_DRAWER = "prefBackButtonOpensDrawer";
    public static final String PREF_BOTTOM_NAVIGATION = "prefBottomNavigation";

    public static final String PREF_QUEUE_KEEP_SORTED = "prefQueueKeepSorted";
    public static final String PREF_QUEUE_KEEP_SORTED_ORDER = "prefQueueKeepSortedOrder";
    public static final String PREF_NEW_EPISODES_ACTION = "prefNewEpisodesAction";
    private static final String PREF_DOWNLOADS_SORTED_ORDER = "prefDownloadSortedOrder";
    private static final String PREF_INBOX_SORTED_ORDER = "prefInboxSortedOrder";
    private static final String PREF_HISTORY_SORTED_ORDER = "prefHistorySortedOrder";

    // Episode
    public static final String PREF_SORT_ALL_EPISODES = "prefEpisodesSort";
    public static final String PREF_FILTER_ALL_EPISODES = "prefEpisodesFilter";

    // Playback
    public static final String PREF_PAUSE_ON_HEADSET_DISCONNECT = "prefPauseOnHeadsetDisconnect";
    public static final String PREF_UNPAUSE_ON_HEADSET_RECONNECT = "prefUnpauseOnHeadsetReconnect";
    public static final String PREF_UNPAUSE_ON_BLUETOOTH_RECONNECT = "prefUnpauseOnBluetoothReconnect";
    public static final String PREF_PAUSE_ON_MUTE = "prefPauseOnMute";
    public static final String PREF_HARDWARE_FORWARD_BUTTON = "prefHardwareForwardButton";
    public static final String PREF_HARDWARE_PREVIOUS_BUTTON = "prefHardwarePreviousButton";
    public static final String PREF_FOLLOW_QUEUE = "prefFollowQueue";
    public static final String PREF_SKIP_KEEPS_EPISODE = "prefSkipKeepsEpisode";
    public static final String PREF_FAVORITE_KEEPS_EPISODE = "prefFavoriteKeepsEpisode";
    public static final String PREF_AUTO_DELETE = "prefAutoDelete";
    private static final String PREF_AUTO_DELETE_LOCAL = "prefAutoDeleteLocal";
    public static final String PREF_SMART_MARK_AS_PLAYED_SECS = "prefSmartMarkAsPlayedSecs";
    private static final String PREF_PLAYBACK_SPEED_ARRAY = "prefPlaybackSpeedArray";
    public static final String PREF_PAUSE_PLAYBACK_FOR_FOCUS_LOSS = "prefPauseForFocusLoss";
    private static final String PREF_TIME_RESPECTS_SPEED = "prefPlaybackTimeRespectsSpeed";
    public static final String PREF_STREAM_OVER_DOWNLOAD = "prefStreamOverDownload";

    // Network
    private static final String PREF_ENQUEUE_DOWNLOADED = "prefEnqueueDownloaded";
    public static final String PREF_ENQUEUE_LOCATION = "prefEnqueueLocation";
    public static final String PREF_UPDATE_INTERVAL_MINUTES = "prefAutoUpdateIntervall";
    public static final String PREF_MOBILE_UPDATE = "prefMobileUpdateTypes";
    public static final String PREF_EPISODE_CLEANUP = "prefEpisodeCleanup";
    public static final String PREF_EPISODE_CACHE_SIZE = "prefEpisodeCacheSize";
    public static final String PREF_AUTODL_GLOBAL = "prefEnableAutoDl";
    public static final String PREF_AUTODL_QUEUE = "prefEnableAutoDlQueue";
    public static final String PREF_ENABLE_AUTODL_ON_BATTERY = "prefEnableAutoDownloadOnBattery";
    private static final String PREF_PROXY_TYPE = "prefProxyType";
    private static final String PREF_PROXY_HOST = "prefProxyHost";
    private static final String PREF_PROXY_PORT = "prefProxyPort";
    private static final String PREF_PROXY_USER = "prefProxyUser";
    private static final String PREF_PROXY_PASSWORD = "prefProxyPassword";

    // Services
    private static final String PREF_GPODNET_NOTIFICATIONS = "pref_gpodnet_notifications";

    // Other
    private static final String PREF_DATA_FOLDER = "prefDataFolder";
    public static final String PREF_DELETE_REMOVES_FROM_QUEUE = "prefDeleteRemovesFromQueue";
    public static final String PREF_DOWNLOADS_BUTTON_ACTION = "prefDownloadsButtonAction";

    // Mediaplayer
    private static final String PREF_PLAYBACK_SPEED = "prefPlaybackSpeed";
    public static final String PREF_PLAYBACK_SKIP_SILENCE = "prefSkipSilence";
    private static final String PREF_SKIP_SILENCE_DEFAULT_TRUE_MIGRATED = "prefSkipSilenceDefaultTrueMigrated";
    private static final String PREF_FAST_FORWARD_SECS = "prefFastForwardSecs";
    private static final String PREF_REWIND_SECS = "prefRewindSecs";
    private static final String PREF_QUEUE_LOCKED = "prefQueueLocked";

    // Experimental
    public static final int EPISODE_CLEANUP_QUEUE = -1;
    public static final int EPISODE_CLEANUP_NULL = -2;
    public static final int EPISODE_CLEANUP_EXCEPT_FAVORITE = -3;
    public static final int EPISODE_CLEANUP_DEFAULT = 0;

    // Constants
    public static final int NOTIFICATION_BUTTON_SKIP = 2;
    public static final int NOTIFICATION_BUTTON_NEXT_CHAPTER = 3;
    public static final int NOTIFICATION_BUTTON_PLAYBACK_SPEED = 4;
    public static final int NOTIFICATION_BUTTON_SLEEP_TIMER = 5;
    public static final int EPISODE_CACHE_SIZE_UNLIMITED = -1;
    public static final String DEFAULT_PAGE_REMEMBER = "remember";

    private static Context context;
    private static SharedPreferences prefs;

    /**
     * Sets up the UserPreferences class.
     *
     * @throws IllegalArgumentException if context is null
     */
    public static void init(@NonNull Context context) {
        Log.d(TAG, "Creating new instance of UserPreferences");

        UserPreferences.context = context.getApplicationContext();
        // Initialize SharedPreferences before accessing
        UserPreferences.prefs = PreferenceManager.getDefaultSharedPreferences(context);
        // One-time migration: force skip silence on for all users (fresh installs and existing
        // users who had it disabled by the old upstream default). The migration flag ensures we
        // only do this once per install — after that, the user's toggle is respected.
        if (!prefs.getBoolean(PREF_SKIP_SILENCE_DEFAULT_TRUE_MIGRATED, false)) {
            prefs.edit()
                    .putBoolean(PREF_PLAYBACK_SKIP_SILENCE, true)
                    .putBoolean(PREF_SKIP_SILENCE_DEFAULT_TRUE_MIGRATED, true)
                    .apply();
        }
        createNoMediaFile();
    }

    public enum ThemePreference {
        LIGHT, DARK, BLACK, SYSTEM
    }

    public static void setTheme(ThemePreference theme) {
        switch (theme) {
            case LIGHT:
                prefs.edit().putString(PREF_THEME, "0").apply();
                break;
            case DARK:
                prefs.edit().putString(PREF_THEME, "1").apply();
                break;
            default:
                prefs.edit().putString(PREF_THEME, "system").apply();
                break;
        }
    }

    public static ThemePreference getTheme() {
        switch (prefs.getString(PREF_THEME, "system")) {
            case "0":
                return ThemePreference.LIGHT;
            case "1":
                return ThemePreference.DARK;
            default:
                return ThemePreference.SYSTEM;
        }
    }

    public static boolean getIsBlackTheme() {
        return prefs.getBoolean(PREF_THEME_BLACK, false);
    }

    public static boolean getIsThemeColorTinted() {
        return Build.VERSION.SDK_INT >= 31 && prefs.getBoolean(PREF_TINTED_COLORS, false);
    }

    public static List<String> getHiddenDrawerItems() {
        String hiddenItems = prefs.getString(PREF_HIDDEN_DRAWER_ITEMS, "");
        return new ArrayList<>(Arrays.asList(TextUtils.split(hiddenItems, ",")));
    }

    public static List<String> getVisibleDrawerItemOrder() {
        String itemOrderStr = prefs.getString(PREF_DRAWER_ITEM_ORDER, "");
        List<String> itemOrderTags = new ArrayList<>(Arrays.asList(TextUtils.split(itemOrderStr, ",")));
        List<String> hiddenItemTags = getHiddenDrawerItems();
        String[] sectionTags = context.getResources().getStringArray(R.array.nav_drawer_section_tags);
        Arrays.sort(sectionTags, (String a, String b) -> Integer.signum(
                indexOfOrMaxValue(itemOrderTags, a) - indexOfOrMaxValue(itemOrderTags, b)));
        List<String> finalItemTags = new ArrayList<>();
        for (String sectionTag: sectionTags) {
            if (hiddenItemTags.contains(sectionTag)) {
                continue;
            }
            finalItemTags.add(sectionTag);
        }
        return finalItemTags;
    }

    private static int indexOfOrMaxValue(List<String> haystack, String needle) {
        int index = haystack.indexOf(needle);
        return index == -1 ? Integer.MAX_VALUE : index;
    }

    public static void setDrawerItemOrder(List<String> hiddenItems, List<String> visibleItemsOrder) {
        prefs.edit().putString(PREF_HIDDEN_DRAWER_ITEMS, TextUtils.join(",", hiddenItems)).apply();
        prefs.edit().putString(PREF_DRAWER_ITEM_ORDER, TextUtils.join(",", visibleItemsOrder)).apply();
    }

    public static List<Integer> getFullNotificationButtons() {
        String[] buttons = TextUtils.split(
            prefs.getString(PREF_FULL_NOTIFICATION_BUTTONS,
                NOTIFICATION_BUTTON_SKIP + "," + NOTIFICATION_BUTTON_PLAYBACK_SPEED), ",");

        List<Integer> notificationButtons = new ArrayList<>();
        for (String button : buttons) {
            notificationButtons.add(Integer.parseInt(button));
        }
        return notificationButtons;
    }

    /**
     * Helper function to return whether the specified button should be shown on full
     * notifications.
     *
     * @param buttonId Either NOTIFICATION_BUTTON_REWIND, NOTIFICATION_BUTTON_FAST_FORWARD,
     *                 NOTIFICATION_BUTTON_SKIP, NOTIFICATION_BUTTON_PLAYBACK_SPEED
     *                 or NOTIFICATION_BUTTON_NEXT_CHAPTER.
     * @return {@code true} if button should be shown, {@code false}  otherwise
     */
    private static boolean showButtonOnFullNotification(int buttonId) {
        return getFullNotificationButtons().contains(buttonId);
    }

    public static boolean showSkipOnFullNotification() {
        return showButtonOnFullNotification(NOTIFICATION_BUTTON_SKIP);
    }

    public static boolean showNextChapterOnFullNotification() {
        return showButtonOnFullNotification(NOTIFICATION_BUTTON_NEXT_CHAPTER);
    }

    public static boolean showPlaybackSpeedOnFullNotification() {
        return showButtonOnFullNotification(NOTIFICATION_BUTTON_PLAYBACK_SPEED);
    }

    public static boolean showSleepTimerOnFullNotification() {
        return showButtonOnFullNotification(NOTIFICATION_BUTTON_SLEEP_TIMER);
    }

    public static FeedOrder getFeedOrder() {
        String value = prefs.getString(PREF_DRAWER_FEED_ORDER, "" + FeedOrder.COUNTER.id);
        return FeedOrder.fromOrdinal(Integer.parseInt(value));
    }

    public static void setFeedOrder(FeedOrder feedOrder) {
        prefs.edit().putString(PREF_DRAWER_FEED_ORDER, "" + feedOrder.id).apply();
    }

    public static FeedCounter getFeedCounterSetting() {
        String value = prefs.getString(PREF_DRAWER_FEED_COUNTER, "" + FeedCounter.SHOW_NEW.id);
        return FeedCounter.fromOrdinal(Integer.parseInt(value));
    }

    public static void setFeedCounterSetting(FeedCounter counter) {
        prefs.edit().putString(PREF_DRAWER_FEED_COUNTER, "" + counter.id).apply();
    }

    /**
     * @return {@code true} if episodes should use their own cover, {@code false}  otherwise
     */
    public static boolean getUseEpisodeCoverSetting() {
        return prefs.getBoolean(PREF_USE_EPISODE_COVER, true);
    }

    /**
     * @return {@code true} if we should show remaining time or the duration
     */
    public static boolean shouldShowRemainingTime() {
        return prefs.getBoolean(PREF_SHOW_TIME_LEFT, false);
    }

    /**
     * Sets the preference for whether we show the remain time, if not show the duration. This will
     * send out events so the current playing screen, queue and the episode list would refresh
     *
     * @return {@code true} if we should show remaining time or the duration
     */
    public static void setShowRemainTimeSetting(Boolean showRemain) {
        prefs.edit().putBoolean(PREF_SHOW_TIME_LEFT, showRemain).apply();
    }

    /**
     * Returns notification priority.
     *
     * @return NotificationCompat.PRIORITY_MAX or NotificationCompat.PRIORITY_DEFAULT
     */
    public static int getNotifyPriority() {
        if (prefs.getBoolean(PREF_EXPANDED_NOTIFICATION, false)) {
            return NotificationCompat.PRIORITY_MAX;
        } else {
            return NotificationCompat.PRIORITY_DEFAULT;
        }
    }

    /**
     * Returns true if notifications are persistent
     *
     * @return {@code true} if notifications are persistent, {@code false}  otherwise
     */
    public static boolean isPersistNotify() {
        return prefs.getBoolean(PREF_PERSISTENT_NOTIFICATION, true);
    }

    /**
     * Used for migration of the preference to system notification channels.
     */
    public static boolean getShowDownloadReportRaw() {
        return prefs.getBoolean(PREF_SHOW_DOWNLOAD_REPORT, true);
    }

    public static boolean enqueueDownloadedEpisodes() {
        return prefs.getBoolean(PREF_ENQUEUE_DOWNLOADED, true);
    }

    public enum EnqueueLocation {
        BACK, FRONT, AFTER_CURRENTLY_PLAYING, RANDOM
    }

    @NonNull
    public static EnqueueLocation getEnqueueLocation() {
        String valStr = prefs.getString(PREF_ENQUEUE_LOCATION, EnqueueLocation.BACK.name());
        try {
            return EnqueueLocation.valueOf(valStr);
        } catch (Throwable t) {
            // should never happen but just in case
            Log.e(TAG, "getEnqueueLocation: invalid value '" + valStr + "' Use default.", t);
            return EnqueueLocation.BACK;
        }
    }

    public static void setEnqueueLocation(@NonNull EnqueueLocation location) {
        prefs.edit()
                .putString(PREF_ENQUEUE_LOCATION, location.name())
                .apply();
    }

    public static boolean isPauseOnHeadsetDisconnect() {
        return prefs.getBoolean(PREF_PAUSE_ON_HEADSET_DISCONNECT, true);
    }

    public static boolean isPauseOnMute() {
        return prefs.getBoolean(PREF_PAUSE_ON_MUTE, true);
    }

    public static boolean isUnpauseOnHeadsetReconnect() {
        return prefs.getBoolean(PREF_UNPAUSE_ON_HEADSET_RECONNECT, true);
    }

    public static boolean isUnpauseOnBluetoothReconnect() {
        return prefs.getBoolean(PREF_UNPAUSE_ON_BLUETOOTH_RECONNECT, false);
    }

    public static int getHardwareForwardButton() {
        return Integer.parseInt(prefs.getString(PREF_HARDWARE_FORWARD_BUTTON,
                String.valueOf(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)));
    }

    public static int getHardwarePreviousButton() {
        return Integer.parseInt(prefs.getString(PREF_HARDWARE_PREVIOUS_BUTTON,
                String.valueOf(KeyEvent.KEYCODE_MEDIA_REWIND)));
    }


    public static boolean isFollowQueue() {
        return prefs.getBoolean(PREF_FOLLOW_QUEUE, true);
    }

    /**
     * Set to true to enable Continuous Playback
     */
    public static void setFollowQueue(boolean value) {
        prefs.edit().putBoolean(UserPreferences.PREF_FOLLOW_QUEUE, value).apply();
    }

    public static boolean shouldSkipKeepEpisode() {
        return prefs.getBoolean(PREF_SKIP_KEEPS_EPISODE, true);
    }

    public static boolean shouldFavoriteKeepEpisode() {
        return prefs.getBoolean(PREF_FAVORITE_KEEPS_EPISODE, true);
    }

    public static boolean isAutoDelete() {
        return prefs.getBoolean(PREF_AUTO_DELETE, false);
    }

    public static boolean isAutoDeleteLocal() {
        return prefs.getBoolean(PREF_AUTO_DELETE_LOCAL, false);
    }

    public static int getSmartMarkAsPlayedSecs() {
        return Integer.parseInt(prefs.getString(PREF_SMART_MARK_AS_PLAYED_SECS, "30"));
    }

    public static boolean shouldDeleteRemoveFromQueue() {
        return prefs.getBoolean(PREF_DELETE_REMOVES_FROM_QUEUE, false);
    }

    public static boolean shouldDownloadsButtonActionPlay() {
        return prefs.getBoolean(PREF_DOWNLOADS_BUTTON_ACTION, false);
    }

    public static float getPlaybackSpeed() {
        try {
            // TrimPlayer default: 1.25× — the whole product premise is "save time
            // on intros/ads/silence/speed", so first-run users get a head start.
            // Existing users who picked 1.00 see no change (their pref is set).
            return Float.parseFloat(prefs.getString(PREF_PLAYBACK_SPEED, "1.25"));
        } catch (NumberFormatException e) {
            Log.e(TAG, Log.getStackTraceString(e));
            UserPreferences.setPlaybackSpeed(1.25f);
            return 1.25f;
        }
    }

    public static boolean isSkipSilence() {
        return prefs.getBoolean(PREF_PLAYBACK_SKIP_SILENCE, true);
    }

    public static List<Float> getPlaybackSpeedArray() {
        return readPlaybackSpeedArray(prefs.getString(PREF_PLAYBACK_SPEED_ARRAY, null));
    }

    public static boolean shouldPauseForFocusLoss() {
        return prefs.getBoolean(PREF_PAUSE_PLAYBACK_FOR_FOCUS_LOSS, true);
    }

    public static long getUpdateInterval() {
        // Parse as long: the value is a long (see setUpdateInterval) and a past migration bug
        // could inflate it past Integer.MAX_VALUE, which made Integer.parseInt crash on startup.
        return Long.parseLong(prefs.getString(PREF_UPDATE_INTERVAL_MINUTES, "720"));
    }

    public static void setUpdateInterval(long interval) {
        prefs.edit().putString(PREF_UPDATE_INTERVAL_MINUTES, String.valueOf(interval)).apply();
    }

    public static boolean isAutoUpdateDisabled() {
        return getUpdateInterval() == 0;
    }

    private static boolean isAllowMobileFor(String type) {
        HashSet<String> defaultValue = new HashSet<>();
        defaultValue.add("images");
        defaultValue.add("streaming");
        Set<String> allowed = prefs.getStringSet(PREF_MOBILE_UPDATE, defaultValue);
        return allowed.contains(type);
    }

    public static boolean isAllowMobileFeedRefresh() {
        return isAllowMobileFor("feed_refresh");
    }

    public static boolean isAllowMobileSync() {
        return isAllowMobileFor("sync");
    }

    public static boolean isAllowMobileEpisodeDownload() {
        return isAllowMobileFor("episode_download");
    }

    public static boolean isAllowMobileAutoDownload() {
        return isAllowMobileFor("auto_download");
    }

    public static boolean isAllowMobileStreaming() {
        return isAllowMobileFor("streaming");
    }

    public static boolean isAllowMobileImages() {
        return isAllowMobileFor("images");
    }

    private static void setAllowMobileFor(String type, boolean allow) {
        HashSet<String> defaultValue = new HashSet<>();
        defaultValue.add("images");
        defaultValue.add("streaming");
        final Set<String> getValueStringSet = prefs.getStringSet(PREF_MOBILE_UPDATE, defaultValue);
        final Set<String> allowed = new HashSet<>(getValueStringSet);
        if (allow) {
            allowed.add(type);
        } else {
            allowed.remove(type);
        }
        prefs.edit().putStringSet(PREF_MOBILE_UPDATE, allowed).apply();
    }

    public static void setAllowMobileFeedRefresh(boolean allow) {
        setAllowMobileFor("feed_refresh", allow);
    }

    public static void setAllowMobileEpisodeDownload(boolean allow) {
        setAllowMobileFor("episode_download", allow);
    }

    public static void setAllowMobileAutoDownload(boolean allow) {
        setAllowMobileFor("auto_download", allow);
    }

    public static void setAllowMobileStreaming(boolean allow) {
        setAllowMobileFor("streaming", allow);
    }

    public static void setAllowMobileImages(boolean allow) {
        setAllowMobileFor("images", allow);
    }

    public static void setAllowMobileSync(boolean allow) {
        setAllowMobileFor("sync", allow);
    }

    /**
     * Returns the capacity of the episode cache. This method will return the
     * negative integer EPISODE_CACHE_SIZE_UNLIMITED if the cache size is set to
     * 'unlimited'.
     */
    public static int getEpisodeCacheSize() {
        return Integer.parseInt(prefs.getString(PREF_EPISODE_CACHE_SIZE, "20"));
    }

    public static boolean isEnableAutodownloadGlobal() {
        return prefs.getBoolean(PREF_AUTODL_GLOBAL, false);
    }

    public static boolean isEnableAutodownloadQueue() {
        return prefs.getBoolean(PREF_AUTODL_QUEUE, false);
    }

    public static boolean isEnableAutodownloadOnBattery() {
        return prefs.getBoolean(PREF_ENABLE_AUTODL_ON_BATTERY, true);
    }

    public static int getFastForwardSecs() {
        return prefs.getInt(PREF_FAST_FORWARD_SECS, 30);
    }

    public static int getRewindSecs() {
        return prefs.getInt(PREF_REWIND_SECS, 10);
    }

    public static void setProxyConfig(ProxyConfig config) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(PREF_PROXY_TYPE, config.type.name());
        if (TextUtils.isEmpty(config.host)) {
            editor.remove(PREF_PROXY_HOST);
        } else {
            editor.putString(PREF_PROXY_HOST, config.host);
        }
        if (config.port <= 0 || config.port > 65535) {
            editor.remove(PREF_PROXY_PORT);
        } else {
            editor.putInt(PREF_PROXY_PORT, config.port);
        }
        if (TextUtils.isEmpty(config.username)) {
            editor.remove(PREF_PROXY_USER);
        } else {
            editor.putString(PREF_PROXY_USER, config.username);
        }
        if (TextUtils.isEmpty(config.password)) {
            editor.remove(PREF_PROXY_PASSWORD);
        } else {
            editor.putString(PREF_PROXY_PASSWORD, config.password);
        }
        editor.apply();
    }

    public static ProxyConfig getProxyConfig() {
        Proxy.Type type = Proxy.Type.valueOf(prefs.getString(PREF_PROXY_TYPE, Proxy.Type.DIRECT.name()));
        String host = prefs.getString(PREF_PROXY_HOST, null);
        int port = prefs.getInt(PREF_PROXY_PORT, 0);
        String username = prefs.getString(PREF_PROXY_USER, null);
        String password = prefs.getString(PREF_PROXY_PASSWORD, null);
        return new ProxyConfig(type, host, port, username, password);
    }

    public static boolean isQueueLocked() {
        return prefs.getBoolean(PREF_QUEUE_LOCKED, false);
    }

    public static void setFastForwardSecs(int secs) {
        prefs.edit().putInt(PREF_FAST_FORWARD_SECS, secs).apply();
    }

    public static void setRewindSecs(int secs) {
        prefs.edit().putInt(PREF_REWIND_SECS, secs).apply();
    }

    public static void setPlaybackSpeed(float speed) {
        prefs.edit().putString(PREF_PLAYBACK_SPEED, String.valueOf(speed)).apply();
    }

    public static void setSkipSilence(boolean skipSilence) {
        prefs.edit().putBoolean(PREF_PLAYBACK_SKIP_SILENCE, skipSilence).apply();
    }

    public static void setPlaybackSpeedArray(List<Float> speeds) {
        DecimalFormatSymbols format = new DecimalFormatSymbols(Locale.US);
        format.setDecimalSeparator('.');
        DecimalFormat speedFormat = new DecimalFormat("0.00", format);
        // 1.0× is the default speed and must always be available as a one-tap
        // option, even if older code (or a hand-edited prefs file) left it out
        // of the saved array. Add it back if missing; the UI layer also blocks
        // direct removal, this is the data-side safety net.
        List<Float> guarded = new ArrayList<>(speeds);
        boolean hasDefault = false;
        for (Float s : guarded) {
            if (s != null && Math.abs(s - 1.0f) < 0.001f) {
                hasDefault = true;
                break;
            }
        }
        if (!hasDefault) {
            guarded.add(1.0f);
            Collections.sort(guarded);
        }
        JSONArray jsonArray = new JSONArray();
        for (float speed : guarded) {
            jsonArray.put(speedFormat.format(speed));
        }
        prefs.edit().putString(PREF_PLAYBACK_SPEED_ARRAY, jsonArray.toString()).apply();
    }

    public static boolean gpodnetNotificationsEnabled() {
        if (Build.VERSION.SDK_INT >= 26) {
            return true; // System handles notification preferences
        }
        return prefs.getBoolean(PREF_GPODNET_NOTIFICATIONS, true);
    }

    /**
     * Used for migration of the preference to system notification channels.
     */
    public static boolean getGpodnetNotificationsEnabledRaw() {
        return prefs.getBoolean(PREF_GPODNET_NOTIFICATIONS, true);
    }

    public static void setGpodnetNotificationsEnabled() {
        prefs.edit().putBoolean(PREF_GPODNET_NOTIFICATIONS, true).apply();
    }

    public static void setFullNotificationButtons(List<Integer> items) {
        String str = TextUtils.join(",", items);
        prefs.edit().putString(PREF_FULL_NOTIFICATION_BUTTONS, str).apply();
    }

    public static void setQueueLocked(boolean locked) {
        prefs.edit().putBoolean(PREF_QUEUE_LOCKED, locked).apply();
    }

    private static List<Float> readPlaybackSpeedArray(String valueFromPrefs) {
        if (valueFromPrefs != null) {
            try {
                JSONArray jsonArray = new JSONArray(valueFromPrefs);
                List<Float> selectedSpeeds = new ArrayList<>();
                for (int i = 0; i < jsonArray.length(); i++) {
                    selectedSpeeds.add((float) jsonArray.getDouble(i));
                }
                return selectedSpeeds;
            } catch (JSONException e) {
                Log.e(TAG, "Got JSON error when trying to get speeds from JSONArray");
                e.printStackTrace();
            }
        }
        // If this preference hasn't been set yet, return the default options.
        // TrimPlayer presets focus on time-saving speeds; 2.0× is included as a
        // common power-user target. 4 presets land as a clean 3+1 wrap on a
        // 3-column grid; no layout pressure on phones.
        return Arrays.asList(1.0f, 1.25f, 1.5f, 2.0f);
    }

    public static int getEpisodeCleanupValue() {
        // Parse defensively: a past migration bug could compound this past Integer.MAX_VALUE, which
        // made Integer.parseInt throw here (at startup and during auto-cleanup). Treat any
        // unparseable / out-of-int-range value as "disabled" so it self-heals instead of crashing.
        try {
            return Integer.parseInt(prefs.getString(PREF_EPISODE_CLEANUP, "" + EPISODE_CLEANUP_NULL));
        } catch (NumberFormatException e) {
            return EPISODE_CLEANUP_NULL;
        }
    }

    public static void setEpisodeCleanupValue(int episodeCleanupValue) {
        prefs.edit().putString(PREF_EPISODE_CLEANUP, Integer.toString(episodeCleanupValue)).apply();
    }

    /**
     * Return the folder where the app stores all of its data. This method will
     * return the standard data folder if none has been set by the user.
     *
     * @param type The name of the folder inside the data folder. May be null
     *             when accessing the root of the data folder.
     * @return The data folder that has been requested or null if the folder could not be created.
     */
    public static File getDataFolder(@Nullable String type) {
        File dataFolder = getTypeDir(prefs.getString(PREF_DATA_FOLDER, null), type);
        if (dataFolder == null || !dataFolder.canWrite()) {
            Log.d(TAG, "User data folder not writable or not set. Trying default.");
            dataFolder = context.getExternalFilesDir(type);
        }
        if (dataFolder == null || !dataFolder.canWrite()) {
            Log.d(TAG, "Default data folder not available or not writable. Falling back to internal memory.");
            dataFolder = getTypeDir(context.getFilesDir().getAbsolutePath(), type);
        }
        return dataFolder;
    }

    @Nullable
    private static File getTypeDir(@Nullable String baseDirPath, @Nullable String type) {
        if (baseDirPath == null) {
            return null;
        }
        File baseDir = new File(baseDirPath);
        File typeDir = type == null ? baseDir : new File(baseDir, type);
        if (!typeDir.exists()) {
            if (!baseDir.canWrite()) {
                Log.e(TAG, "Base dir is not writable " + baseDir.getAbsolutePath());
                return null;
            }
            if (!typeDir.mkdirs()) {
                Log.e(TAG, "Could not create type dir " + typeDir.getAbsolutePath());
                return null;
            }
        }
        return typeDir;
    }

    public static void setDataFolder(String dir) {
        Log.d(TAG, "setDataFolder(dir: " + dir + ")");
        prefs.edit().putString(PREF_DATA_FOLDER, dir).apply();
    }

    /**
     * Create a .nomedia file to prevent scanning by the media scanner.
     */
    private static void createNoMediaFile() {
        File f = new File(context.getExternalFilesDir(null), ".nomedia");
        if (!f.exists()) {
            try {
                f.createNewFile();
            } catch (IOException e) {
                Log.e(TAG, "Could not create .nomedia file");
                e.printStackTrace();
            }
            Log.d(TAG, ".nomedia file created");
        }
    }

    public static String getDefaultPage() {
        return prefs.getString(PREF_DEFAULT_PAGE, "HomeFragment");
    }

    public static void setDefaultPage(String defaultPage) {
        prefs.edit().putString(PREF_DEFAULT_PAGE, defaultPage).apply();
    }

    public static boolean backButtonOpensDrawer() {
        return prefs.getBoolean(PREF_BACK_OPENS_DRAWER, false);
    }

    public static boolean isBottomNavigationEnabled() {
        return prefs.getBoolean(PREF_BOTTOM_NAVIGATION, false);
    }

    public static void setBottomNavigationEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_BOTTOM_NAVIGATION, enabled).apply();
    }

    public static boolean timeRespectsSpeed() {
        return prefs.getBoolean(PREF_TIME_RESPECTS_SPEED, false);
    }

    public static boolean isStreamOverDownload() {
        return prefs.getBoolean(PREF_STREAM_OVER_DOWNLOAD, false);
    }

    public static void setStreamOverDownload(boolean stream) {
        prefs.edit().putBoolean(PREF_STREAM_OVER_DOWNLOAD, stream).apply();
    }

    /**
     * Returns if the queue is in keep sorted mode.
     *
     * @see #getQueueKeepSortedOrder()
     */
    public static boolean isQueueKeepSorted() {
        return prefs.getBoolean(PREF_QUEUE_KEEP_SORTED, false);
    }

    /**
     * Enables/disables the keep sorted mode of the queue.
     *
     * @see #setQueueKeepSortedOrder(SortOrder)
     */
    public static void setQueueKeepSorted(boolean keepSorted) {
        prefs.edit().putBoolean(PREF_QUEUE_KEEP_SORTED, keepSorted).apply();
    }

    /**
     * Returns the sort order for the queue keep sorted mode.
     * Note: This value is stored independently from the keep sorted state.
     *
     * @see #isQueueKeepSorted()
     */
    public static SortOrder getQueueKeepSortedOrder() {
        String sortOrderStr = prefs.getString(PREF_QUEUE_KEEP_SORTED_ORDER, "use-default");
        return SortOrder.parseWithDefault(sortOrderStr, SortOrder.DATE_NEW_OLD);
    }

    /**
     * Sets the sort order for the queue keep sorted mode.
     *
     * @see #setQueueKeepSorted(boolean)
     */
    public static void setQueueKeepSortedOrder(SortOrder sortOrder) {
        if (sortOrder == null) {
            return;
        }
        prefs.edit().putString(PREF_QUEUE_KEEP_SORTED_ORDER, sortOrder.name()).apply();
    }

    public static FeedPreferences.NewEpisodesAction getNewEpisodesAction() {
        String str = prefs.getString(PREF_NEW_EPISODES_ACTION,
                "" + FeedPreferences.NewEpisodesAction.ADD_TO_INBOX.code);
        return FeedPreferences.NewEpisodesAction.fromCode(Integer.parseInt(str));
    }

    /**
     * Returns the sort order for the downloads.
     */
    public static SortOrder getDownloadsSortedOrder() {
        String sortOrderStr = prefs.getString(PREF_DOWNLOADS_SORTED_ORDER, "" + SortOrder.DATE_NEW_OLD.code);
        return SortOrder.fromCodeString(sortOrderStr);
    }

    /**
     * Sets the sort order for the downloads.
     */
    public static void setDownloadsSortedOrder(SortOrder sortOrder) {
        prefs.edit().putString(PREF_DOWNLOADS_SORTED_ORDER, "" + sortOrder.code).apply();
    }

    public static SortOrder getInboxSortedOrder() {
        String sortOrderStr = prefs.getString(PREF_INBOX_SORTED_ORDER, "" + SortOrder.DATE_NEW_OLD.code);
        return SortOrder.fromCodeString(sortOrderStr);
    }

    public static void setInboxSortedOrder(SortOrder sortOrder) {
        prefs.edit().putString(PREF_INBOX_SORTED_ORDER, "" + sortOrder.code).apply();
    }

    public static SortOrder getHistorySortedOrder() {
        String sortOrderStr = prefs.getString(PREF_HISTORY_SORTED_ORDER,
                "" + SortOrder.COMPLETION_DATE_NEW_OLD.code);
        return SortOrder.fromCodeString(sortOrderStr);
    }

    public static void setHistorySortedOrder(SortOrder sortOrder) {
        prefs.edit().putString(PREF_HISTORY_SORTED_ORDER, "" + sortOrder.code).apply();
    }

    public static SubscriptionsFilter getSubscriptionsFilter() {
        String value = prefs.getString(PREF_FILTER_FEED, "");
        return new SubscriptionsFilter(value);
    }

    public static void setSubscriptionsFilter(SubscriptionsFilter value) {
        prefs.edit().putString(PREF_FILTER_FEED, value.serialize()).apply();
    }

    public static boolean shouldShowSubscriptionTitle() {
        return prefs.getBoolean(PREF_SUBSCRIPTION_TITLE, false);
    }

    public static void setShouldShowSubscriptionTitle(boolean show) {
        prefs.edit().putBoolean(PREF_SUBSCRIPTION_TITLE, show).apply();
    }

    public static void setAllEpisodesSortOrder(SortOrder s) {
        prefs.edit().putString(PREF_SORT_ALL_EPISODES, "" + s.code).apply();
    }

    public static SortOrder getAllEpisodesSortOrder() {
        return SortOrder.fromCodeString(prefs.getString(PREF_SORT_ALL_EPISODES,
                "" + SortOrder.DATE_NEW_OLD.code));
    }

    public static String getPrefFilterAllEpisodes() {
        return prefs.getString(PREF_FILTER_ALL_EPISODES, "");
    }

    public static void setPrefFilterAllEpisodes(String filter) {
        prefs.edit().putString(PREF_FILTER_ALL_EPISODES, filter).apply();
    }

    public static final String PREF_TRIM_SERVER_URL = "prefTrimServerUrl";
    public static final String DEFAULT_TRIM_SERVER_URL = "https://api.trimplayer.com/api/v1/";
    /** The XML preference's defaultValue prior to commit 6dad0f4a8 was the
     *  emulator-to-host loopback, which AndroidX persists to SharedPreferences
     *  the first time the Downloads preference screen is shown. Real-device
     *  installs that opened that screen got the bad URL stuck and never reach
     *  the backend. Rewrite that exact stuck value on read so existing installs
     *  self-heal on next launch without a manual pref edit or app-data wipe. */
    private static final String LEGACY_EMULATOR_TRIM_SERVER_URL = "http://10.0.2.2:8000/api/v1/";

    public static String getTrimServerUrl() {
        String url = prefs.getString(PREF_TRIM_SERVER_URL, DEFAULT_TRIM_SERVER_URL);
        if (url == null || url.trim().isEmpty()
                || LEGACY_EMULATOR_TRIM_SERVER_URL.equals(url.trim())) {
            // Repersist so the Downloads preference screen also shows the
            // corrected value — leaving the pref alone would let it show the
            // bad URL next time the user opens the dialog.
            setTrimServerUrl(DEFAULT_TRIM_SERVER_URL);
            return DEFAULT_TRIM_SERVER_URL;
        }
        return url.endsWith("/") ? url : url + "/";
    }

    public static void setTrimServerUrl(String url) {
        prefs.edit().putString(PREF_TRIM_SERVER_URL, url).apply();
    }

    public static final String PREF_TRIM_STUB_ENABLED = "prefTrimStubEnabled";

    public static boolean isTrimStubEnabled() {
        return prefs.getBoolean(PREF_TRIM_STUB_ENABLED, true);
    }

    public static void setTrimStubEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_TRIM_STUB_ENABLED, enabled).apply();
    }

    // --- TrimPlayer account (web/phone library sync) -----------------------
    // Session token + email from /auth, and the per-account delta-sync cursor
    // (highest rev applied from /account/sync). Empty token == logged out.
    public static final String PREF_TRIM_ACCOUNT_TOKEN = "prefTrimAccountToken";
    public static final String PREF_TRIM_ACCOUNT_EMAIL = "prefTrimAccountEmail";
    public static final String PREF_TRIM_SYNC_CURSOR = "prefTrimSyncCursor";
    // Consecutive no-progress sync runs during which the worker held the cursor
    // because queue/progress rows referenced episodes not yet fetched locally. Caps
    // the hold so a permanently-absent episode can't wedge sync forever.
    public static final String PREF_TRIM_SYNC_DEFER_RETRIES = "prefTrimSyncDeferRetries";
    // One-time history backfill: the phone normally pushes only the last 60 days of
    // playback (PROGRESS_WINDOW_MS), so a user's older listening never reaches the
    // account — leaving the web player + a freshly-signed-in device with statistics
    // that look empty despite years of history. These two prefs page that pre-window
    // history up to the account in bounded chunks: BACKFILL_CEIL is the exclusive
    // upper time bound of the region still to push (paged newest→oldest); BACKFILL_DONE
    // flips once the whole history has been sent, after which only the window syncs.
    public static final String PREF_TRIM_SYNC_BACKFILL_DONE = "prefTrimSyncBackfillDone";
    public static final String PREF_TRIM_SYNC_BACKFILL_CEIL = "prefTrimSyncBackfillCeil";

    public static String getTrimAccountToken() {
        return prefs.getString(PREF_TRIM_ACCOUNT_TOKEN, "");
    }

    public static boolean isTrimAccountLoggedIn() {
        String t = getTrimAccountToken();
        return t != null && !t.isEmpty();
    }

    public static String getTrimAccountEmail() {
        return prefs.getString(PREF_TRIM_ACCOUNT_EMAIL, "");
    }

    /** Persist the session after a successful /auth/signup|login. */
    public static void setTrimAccount(String token, String email) {
        prefs.edit()
                .putString(PREF_TRIM_ACCOUNT_TOKEN, token == null ? "" : token)
                .putString(PREF_TRIM_ACCOUNT_EMAIL, email == null ? "" : email)
                .apply();
    }

    /** Clear the session on logout (cursor + change-journal snapshots reset so a
     *  re-login does a full pull and re-seeds the account from local state). */
    public static void clearTrimAccount() {
        prefs.edit()
                .remove(PREF_TRIM_ACCOUNT_TOKEN)
                .remove(PREF_TRIM_ACCOUNT_EMAIL)
                .remove(PREF_TRIM_SYNC_CURSOR)
                .remove(PREF_TRIM_SYNC_DEFER_RETRIES)
                .remove(PREF_TRIM_SYNC_BACKFILL_DONE)
                .remove(PREF_TRIM_SYNC_BACKFILL_CEIL)
                .remove(PREF_TRIM_SYNC_SNAP_SUBS)
                .remove(PREF_TRIM_SYNC_SNAP_QUEUE)
                .remove(PREF_TRIM_SYNC_SNAP_PREFS)
                .remove(PREF_TRIM_SYNC_SNAP_FAV)
                .apply();
    }

    // Change-journal snapshots: the subscription set + queue as last successfully
    // pushed to the account. The sync worker diffs current local state against
    // these so it only pushes genuine local changes (adds/edits/removals) instead
    // of re-asserting everything every run — which would clobber web-side edits.
    public static final String PREF_TRIM_SYNC_SNAP_SUBS = "prefTrimSyncSnapSubs";
    public static final String PREF_TRIM_SYNC_SNAP_QUEUE = "prefTrimSyncSnapQueue";
    public static final String PREF_TRIM_SYNC_SNAP_PREFS = "prefTrimSyncSnapPrefs";
    // Episode download-urls that were favorited as of the last push, so the worker
    // pushes only genuine favorite toggles (mirrors PortCast episodes[].starred).
    public static final String PREF_TRIM_SYNC_SNAP_FAV = "prefTrimSyncSnapFav";
    public static final String PREF_TRIM_SYNC_SNAP_BOOKMARKS = "prefTrimSyncSnapBookmarks";

    public static String getTrimSyncSubsSnapshot() {
        return prefs.getString(PREF_TRIM_SYNC_SNAP_SUBS, "");
    }

    public static void setTrimSyncSubsSnapshot(String json) {
        prefs.edit().putString(PREF_TRIM_SYNC_SNAP_SUBS, json == null ? "" : json).apply();
    }

    public static String getTrimSyncPrefsSnapshot() {
        return prefs.getString(PREF_TRIM_SYNC_SNAP_PREFS, "");
    }

    public static void setTrimSyncPrefsSnapshot(String json) {
        prefs.edit().putString(PREF_TRIM_SYNC_SNAP_PREFS, json == null ? "" : json).apply();
    }

    public static String getTrimSyncQueueSnapshot() {
        return prefs.getString(PREF_TRIM_SYNC_SNAP_QUEUE, "");
    }

    public static void setTrimSyncQueueSnapshot(String json) {
        prefs.edit().putString(PREF_TRIM_SYNC_SNAP_QUEUE, json == null ? "" : json).apply();
    }

    public static String getTrimSyncFavSnapshot() {
        return prefs.getString(PREF_TRIM_SYNC_SNAP_FAV, "");
    }

    public static void setTrimSyncFavSnapshot(String json) {
        prefs.edit().putString(PREF_TRIM_SYNC_SNAP_FAV, json == null ? "" : json).apply();
    }

    public static String getTrimSyncBookmarkSnapshot() {
        return prefs.getString(PREF_TRIM_SYNC_SNAP_BOOKMARKS, "");
    }

    public static void setTrimSyncBookmarkSnapshot(String json) {
        prefs.edit().putString(PREF_TRIM_SYNC_SNAP_BOOKMARKS, json == null ? "" : json).apply();
    }

    public static long getTrimSyncCursor() {
        return prefs.getLong(PREF_TRIM_SYNC_CURSOR, 0L);
    }

    public static void setTrimSyncCursor(long cursor) {
        prefs.edit().putLong(PREF_TRIM_SYNC_CURSOR, cursor).apply();
    }

    public static int getTrimSyncDeferRetries() {
        return prefs.getInt(PREF_TRIM_SYNC_DEFER_RETRIES, 0);
    }

    public static void setTrimSyncDeferRetries(int n) {
        prefs.edit().putInt(PREF_TRIM_SYNC_DEFER_RETRIES, n).apply();
    }

    /** Whether the one-time pre-window listening-history backfill has finished
     *  pushing the phone's full play history to the account. */
    public static boolean isTrimSyncBackfillDone() {
        return prefs.getBoolean(PREF_TRIM_SYNC_BACKFILL_DONE, false);
    }

    public static void setTrimSyncBackfillDone(boolean done) {
        prefs.edit().putBoolean(PREF_TRIM_SYNC_BACKFILL_DONE, done).apply();
    }

    /** Exclusive upper time bound (epoch ms) of the history region still to backfill;
     *  0 means "not started" (the worker seeds it to the bottom of the 60-day window). */
    public static long getTrimSyncBackfillCeil() {
        return prefs.getLong(PREF_TRIM_SYNC_BACKFILL_CEIL, 0L);
    }

    public static void setTrimSyncBackfillCeil(long ceilMs) {
        prefs.edit().putLong(PREF_TRIM_SYNC_BACKFILL_CEIL, ceilMs).apply();
    }

    // Per-type auto-skip toggles. Each defaults to true (preserves existing behaviour
    // for users who upgrade). The SharedPreferences keys match the preferences_playback
    // entries so an XML SwitchPreferenceCompat reads/writes them directly.
    public static final String PREF_TRIM_SKIP_INTROS = "prefTrimSkipIntros";
    public static final String PREF_TRIM_SKIP_ADS    = "prefTrimSkipAds";
    public static final String PREF_TRIM_SKIP_OUTROS = "prefTrimSkipOutros";

    public static boolean isTrimSkipIntrosEnabled() {
        return prefs.getBoolean(PREF_TRIM_SKIP_INTROS, true);
    }

    public static boolean isTrimSkipAdsEnabled() {
        return prefs.getBoolean(PREF_TRIM_SKIP_ADS, true);
    }

    public static boolean isTrimSkipOutrosEnabled() {
        return prefs.getBoolean(PREF_TRIM_SKIP_OUTROS, true);
    }

    public static void setTrimSkipIntrosEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_TRIM_SKIP_INTROS, enabled).apply();
    }

    public static void setTrimSkipAdsEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_TRIM_SKIP_ADS, enabled).apply();
    }

    public static void setTrimSkipOutrosEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_TRIM_SKIP_OUTROS, enabled).apply();
    }

    /** Returns true when the auto-skip pref for this segment type is enabled.
     *  Unknown types default to enabled so we never silently skip skipping. */
    public static boolean isTrimSkipEnabledForType(String segmentType) {
        if (segmentType == null) {
            return true;
        }
        switch (segmentType.toLowerCase()) {
            case "intro": return isTrimSkipIntrosEnabled();
            case "ad":    return isTrimSkipAdsEnabled();
            case "outro": return isTrimSkipOutrosEnabled();
            default:      return true;
        }
    }

    // Anonymous, app-install-scoped client id for aggregated telemetry. Used as a
    // dedup key by the backend's /events endpoint. Generated lazily on first read.
    public static final String PREF_TRIM_CLIENT_ID = "prefTrimClientId";
    public static final String PREF_TRIM_LAST_UPLOADED_SKIP_EVENT_ID = "prefTrimLastUploadedSkipEventId";

    public static String getOrCreateTrimClientId() {
        String id = prefs.getString(PREF_TRIM_CLIENT_ID, null);
        if (id == null || id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString();
            prefs.edit().putString(PREF_TRIM_CLIENT_ID, id).apply();
        }
        return id;
    }

    public static long getLastUploadedSkipEventId() {
        return prefs.getLong(PREF_TRIM_LAST_UPLOADED_SKIP_EVENT_ID, 0);
    }

    public static void setLastUploadedSkipEventId(long id) {
        prefs.edit().putLong(PREF_TRIM_LAST_UPLOADED_SKIP_EVENT_ID, id).apply();
    }

    // Cached count of unread admin replies across all in-app feedback threads.
    // Refreshed by TrimFeedbackClient.fetchThreads* whenever the report screen
    // opens or the upload worker piggybacks a poll; drives the Settings badge.
    public static final String PREF_TRIM_FEEDBACK_UNREAD = "prefTrimFeedbackUnreadCount";

    public static int getFeedbackUnreadCount() {
        return prefs.getInt(PREF_TRIM_FEEDBACK_UNREAD, 0);
    }

    public static void setFeedbackUnreadCount(int count) {
        prefs.edit().putInt(PREF_TRIM_FEEDBACK_UNREAD, Math.max(0, count)).apply();
    }

    // Last successful /community/impact response, cached verbatim so the
    // Community Impact screen renders instantly and survives offline. Replaced
    // on each successful fetch; null/empty until the first one.
    public static final String PREF_TRIM_COMMUNITY_IMPACT = "prefTrimCommunityImpact";

    public static String getCommunityImpactCache() {
        return prefs.getString(PREF_TRIM_COMMUNITY_IMPACT, null);
    }

    public static void setCommunityImpactCache(String json) {
        prefs.edit().putString(PREF_TRIM_COMMUNITY_IMPACT, json).apply();
    }

    // -----------------------------------------------------------------------
    // Pro entitlement (Phase 1, 2026-05-19). Cached locally so the UI can
    // render Pro state without waiting for /segments to come back. Source of
    // truth is still the backend — these are last-seen values.
    // -----------------------------------------------------------------------
    public static final String PREF_TRIM_PRO_STATUS = "prefTrimProStatus";          // ok | quota_exceeded | pro | (absent)
    public static final String PREF_TRIM_PRO_SOURCE = "prefTrimProSource";          // play_subscription | play_lifetime | beta_grandfather | (absent)
    public static final String PREF_TRIM_PRO_TOKEN = "prefTrimProToken";            // JWT minted by /billing/verify
    public static final String PREF_TRIM_PRO_TOKEN_EXPIRES_MS = "prefTrimProTokenExpiresMs";  // unix-ms; 0 = unknown
    public static final String PREF_TRIM_QUOTA_USED = "prefTrimQuotaUsed";          // free-tier auto-trims used this month
    public static final String PREF_TRIM_QUOTA_LIMIT = "prefTrimQuotaLimit";        // free-tier monthly quota (server-driven)
    public static final String PREF_TRIM_QUOTA_RESETS_AT = "prefTrimQuotaResetsAt"; // ISO-8601 string of next reset
    public static final String PREF_TRIM_BETA_GRANDFATHER_WELCOMED = "prefTrimBetaGrandfatherWelcomed";
    public static final String PREF_TRIM_PRO_UI_VISIBLE = "prefTrimProUiVisible";   // server-driven kill-switch for Pro UI surfaces

    public static String getTrimProStatus() {
        return prefs.getString(PREF_TRIM_PRO_STATUS, null);
    }

    public static String getTrimProSource() {
        return prefs.getString(PREF_TRIM_PRO_SOURCE, null);
    }

    public static String getTrimProToken() {
        return prefs.getString(PREF_TRIM_PRO_TOKEN, null);
    }

    public static long getTrimProTokenExpiresMs() {
        return prefs.getLong(PREF_TRIM_PRO_TOKEN_EXPIRES_MS, 0);
    }

    public static int getTrimQuotaUsed() {
        return prefs.getInt(PREF_TRIM_QUOTA_USED, 0);
    }

    public static int getTrimQuotaLimit() {
        return prefs.getInt(PREF_TRIM_QUOTA_LIMIT, 3);  // mirrors backend FREE_AUTO_TRIM_QUOTA default
    }

    public static String getTrimQuotaResetsAt() {
        return prefs.getString(PREF_TRIM_QUOTA_RESETS_AT, null);
    }

    /** True if we've already shown the one-shot "you're a beta grandfather" dialog. */
    public static boolean wasBetaGrandfatherWelcomed() {
        return prefs.getBoolean(PREF_TRIM_BETA_GRANDFATHER_WELCOMED, false);
    }

    public static void markBetaGrandfatherWelcomed() {
        prefs.edit().putBoolean(PREF_TRIM_BETA_GRANDFATHER_WELCOMED, true).apply();
    }

    /** Server-driven flag controlling whether in-app Pro UI surfaces are
     *  visible. False on first launch (hides everything) until /segments
     *  returns pro_ui_visible=true. */
    public static boolean getTrimProUiVisible() {
        return prefs.getBoolean(PREF_TRIM_PRO_UI_VISIBLE, false);
    }

    /** Snapshot of last-seen entitlement values, written by EntitlementStore. */
    public static void writeTrimEntitlementSnapshot(
            String status, String source,
            Integer quotaUsed, Integer quotaLimit, String resetsAt,
            boolean proUiVisible) {
        android.content.SharedPreferences.Editor e = prefs.edit();
        if (status == null) e.remove(PREF_TRIM_PRO_STATUS); else e.putString(PREF_TRIM_PRO_STATUS, status);
        if (source == null) e.remove(PREF_TRIM_PRO_SOURCE); else e.putString(PREF_TRIM_PRO_SOURCE, source);
        if (quotaUsed != null) {
            e.putInt(PREF_TRIM_QUOTA_USED,  quotaUsed);
        }
        if (quotaLimit != null) {
            e.putInt(PREF_TRIM_QUOTA_LIMIT, quotaLimit);
        }
        if (resetsAt == null) e.remove(PREF_TRIM_QUOTA_RESETS_AT); else e.putString(PREF_TRIM_QUOTA_RESETS_AT, resetsAt);
        e.putBoolean(PREF_TRIM_PRO_UI_VISIBLE, proUiVisible);
        e.apply();
    }

    /** Persist a freshly-minted Pro JWT. tokenExpiresMs may be 0 if unknown. */
    public static void writeTrimProToken(String token, long tokenExpiresMs) {
        android.content.SharedPreferences.Editor e = prefs.edit();
        if (token == null || token.isEmpty()) {
            e.remove(PREF_TRIM_PRO_TOKEN);
            e.remove(PREF_TRIM_PRO_TOKEN_EXPIRES_MS);
        } else {
            e.putString(PREF_TRIM_PRO_TOKEN, token);
            e.putLong(PREF_TRIM_PRO_TOKEN_EXPIRES_MS, tokenExpiresMs);
        }
        e.apply();
    }
}
