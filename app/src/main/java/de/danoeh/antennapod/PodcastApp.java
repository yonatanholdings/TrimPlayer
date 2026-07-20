package de.danoeh.antennapod;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.android.material.color.DynamicColors;
import com.google.firebase.crashlytics.FirebaseCrashlytics;
import org.greenrobot.eventbus.EventBus;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.EventBusException;

import de.danoeh.antennapod.event.AnalyticsEvent;

/** Main application class. */
public class PodcastApp extends Application {
    private static final String TAG = "PodcastApp";

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.setDefaultUncaughtExceptionHandler(new CrashReportExceptionHandler());
        RxJavaErrorHandlerSetup.setupRxJavaErrorHandler();

        try {
            // Robolectric calls onCreate for every test, which causes problems with static members
            EventBus.builder()
                    .addIndex(new ApEventBusIndex())
                    .logNoSubscriberMessages(false)
                    .sendNoSubscriberEvent(false)
                    .installDefaultEventBus();
        } catch (EventBusException e) {
            Log.d(TAG, e.getMessage());
        }

        DynamicColors.applyToActivitiesIfAvailable(this);
        ClientConfigurator.initialize(this);
        PreferenceUpgrader.checkUpgrades(this);
        runInboxDeprecationMigrationOnce();
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true);
        EventBus.getDefault().register(new TrimAnalytics(this));
        logFirstLaunchPlayClick();
        EventBus.getDefault().register(new TrimPrefetchSubscriber());
        EventBus.getDefault().register(new TrimQueueSubscriber(this));
        // Immediate (debounced) account sync on bookmark changes, so they reach the
        // other devices + web in seconds rather than on the 2h periodic cadence.
        EventBus.getDefault().register(new TrimSyncSubscriber(this));
        de.danoeh.antennapod.net.common.TrimPrefetcher.prewarm();
        scheduleTrimEventsUpload();
        scheduleTrimSync();
        // Warm the first few minutes of the next queued episodes on disk so playback survives a
        // connectivity gap at episode boundaries. Cached prefixes persist across restarts.
        QueuePrefetchManager.prefetchTopOfQueue(this);
        // Garmin watch companion: receive listen-progress PortCast docs over BLE
        // (relayed by Garmin Connect Mobile). No-op for users without a watch.
        try {
            TrimGarminWatchSync.start(this);
        } catch (Throwable t) {
            Log.w(TAG, "Garmin companion init failed: " + t.getMessage());
        }
        // Restore Pro entitlement on every cold start so a reinstall or a
        // device swap recovers Pro without the user re-paying. The call is
        // a no-op for free users (queryPurchasesAsync returns empty).
        try {
            de.danoeh.antennapod.billing.TrimBillingManager.get(this).connect();
        } catch (Throwable t) {
            // Defensive: BillingClient construction can fail on devices
            // without Play Services. Don't crash the app.
            Log.w(TAG, "Billing init failed (no Play Services?): " + t.getMessage());
        }
    }

    /**
     * Fire the {@code play_click} conversion event once, on the first launch
     * after install. This is the in-app counterpart of the website's
     * install-intent event so the conversion name is shared across the web and
     * app GA4 streams. Guarded by a one-shot flag; TrimAnalytics is already
     * registered above so the EventBus post is delivered. (Existing installs
     * upgrading into this build count as one first launch — a one-time event.)
     */
    private void logFirstLaunchPlayClick() {
        try {
            SharedPreferences prefs = getSharedPreferences("trim_analytics", MODE_PRIVATE);
            if (prefs.getBoolean("play_click_logged", false)) {
                return;
            }
            EventBus.getDefault().post(AnalyticsEvent.playClick("app"));
            prefs.edit().putBoolean("play_click_logged", true).apply();
        } catch (Throwable t) {
            // Analytics must never break app startup.
            Log.w(TAG, "first-launch play_click failed: " + t.getMessage());
        }
    }

    /** One-shot Inbox-deprecation migration (playlists-only model): converts
     *  legacy add-to-queue routing into synced rules and clears NEW flags.
     *  Off-main (DB work); guarded by a preference so it runs exactly once. */
    private void runInboxDeprecationMigrationOnce() {
        SharedPreferences prefs = getSharedPreferences("inbox_deprecation", MODE_PRIVATE);
        if (prefs.getBoolean("done", false)) {
            return;
        }
        new Thread(() -> {
            try {
                boolean globalIsQueue = de.danoeh.antennapod.storage.preferences.UserPreferences
                        .getNewEpisodesAction()
                        == de.danoeh.antennapod.model.feed.FeedPreferences.NewEpisodesAction.ADD_TO_QUEUE;
                de.danoeh.antennapod.storage.database.InboxDeprecationMigration.run(globalIsQueue);
                prefs.edit().putBoolean("done", true).apply();
            } catch (Throwable t) {
                Log.w(TAG, "Inbox deprecation migration failed (will retry next start): " + t);
            }
        }, "InboxDeprecationMigration").start();
    }

    private void scheduleTrimEventsUpload() {
        androidx.work.Constraints constraints = new androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build();
        androidx.work.PeriodicWorkRequest request = new androidx.work.PeriodicWorkRequest.Builder(
                TrimEventsUploadWorker.class, 6, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL,
                        15, java.util.concurrent.TimeUnit.MINUTES)
                .build();
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "trimEventsUpload", androidx.work.ExistingPeriodicWorkPolicy.KEEP, request);
    }

    private void scheduleTrimSync() {
        androidx.work.Constraints constraints = new androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build();
        androidx.work.PeriodicWorkRequest request = new androidx.work.PeriodicWorkRequest.Builder(
                TrimSyncWorker.class, 2, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL,
                        15, java.util.concurrent.TimeUnit.MINUTES)
                .build();
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "trimAccountSync", androidx.work.ExistingPeriodicWorkPolicy.KEEP, request);
    }
}
