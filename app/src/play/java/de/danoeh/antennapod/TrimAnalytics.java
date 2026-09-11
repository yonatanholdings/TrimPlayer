package de.danoeh.antennapod;

import android.content.Context;
import com.google.firebase.analytics.FirebaseAnalytics;
import de.danoeh.antennapod.event.AnalyticsEvent;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class TrimAnalytics {
    private final FirebaseAnalytics analytics;

    public TrimAnalytics(Context context) {
        analytics = FirebaseAnalytics.getInstance(context);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onAnalyticsEvent(AnalyticsEvent event) {
        analytics.logEvent(event.name, event.params);
    }
}
