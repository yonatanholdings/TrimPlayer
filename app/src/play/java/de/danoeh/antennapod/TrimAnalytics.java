package de.danoeh.antennapod;

import android.content.Context;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crashlytics.FirebaseCrashlytics;
import de.danoeh.antennapod.event.AnalyticsEvent;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * Play-flavour analytics: forwards {@link AnalyticsEvent}s to Firebase.
 *
 * This class is the ONLY place in the app that touches Firebase, and it lives in
 * the `play` source set so the `free` flavour can be built without any
 * proprietary Google library. Its twin in `app/src/free/` must keep the same
 * public API — the caller (PodcastApp) is flavour-agnostic and compiles against
 * whichever one is in the variant.
 */
public class TrimAnalytics {
    private final FirebaseAnalytics analytics;

    public TrimAnalytics(Context context) {
        analytics = FirebaseAnalytics.getInstance(context);
    }

    /** Opt in to crash reporting. Called once from PodcastApp. */
    public static void enableCrashReporting() {
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onAnalyticsEvent(AnalyticsEvent event) {
        analytics.logEvent(event.name, event.params);
    }
}
