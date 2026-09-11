package de.danoeh.antennapod;

import android.content.Context;
import de.danoeh.antennapod.event.AnalyticsEvent;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * Free-flavour analytics: deliberately does nothing.
 *
 * The `free` flavour exists so the app can be built with no proprietary Google
 * libraries — that is the hard requirement for F-Droid, IzzyOnDroid and the FOSS
 * app lists, and it is the audience most likely to want an open-source podcast
 * player that skips ads. So this build sends nothing anywhere: no Firebase, no
 * analytics, no crash reports.
 *
 * It still subscribes to {@link AnalyticsEvent} and discards the events, rather
 * than making the caller conditional. PodcastApp registers this on the EventBus
 * unconditionally, and EventBus requires a registered subscriber to declare at
 * least one @Subscribe method or it throws EventBusException at registration.
 *
 * Keep the public API identical to the `play` twin in
 * `app/src/play/java/de/danoeh/antennapod/TrimAnalytics.java`.
 */
public class TrimAnalytics {

    public TrimAnalytics(Context context) {
        // No-op: nothing to initialise when nothing is collected.
    }

    /** No-op. The free flavour ships no crash reporter. */
    public static void enableCrashReporting() {
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onAnalyticsEvent(AnalyticsEvent event) {
        // Dropped on the floor by design.
    }
}
