package de.danoeh.antennapod;

import android.app.Application;
import android.util.Log;

import com.google.android.material.color.DynamicColors;
import com.google.firebase.crashlytics.FirebaseCrashlytics;
import org.greenrobot.eventbus.EventBus;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.EventBusException;

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
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true);
        EventBus.getDefault().register(new TrimAnalytics(this));
        EventBus.getDefault().register(new TrimPrefetchSubscriber());
        de.danoeh.antennapod.net.common.TrimPrefetcher.prewarm();
        scheduleTrimEventsUpload();
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
}
