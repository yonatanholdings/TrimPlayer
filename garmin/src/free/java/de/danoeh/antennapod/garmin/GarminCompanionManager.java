package de.danoeh.antennapod.garmin;

import android.content.Context;
import android.util.Log;

import java.util.Map;

/**
 * Free-flavour Garmin companion: deliberately does nothing.
 *
 * The Connect IQ Mobile SDK is proprietary (Garmin's SDK licence), and it was the
 * last proprietary library in the free flavour once billing and Google sign-in
 * moved behind the play flavour. F-Droid, IzzyOnDroid and the FOSS app lists
 * reject any proprietary dependency, so the free build gets this twin instead.
 *
 * Behaviour matches a Play build on a phone without Garmin Connect installed:
 * {@link #start} logs and returns, and {@link #requestProgressFlush} reports
 * {@link #SEND_UNAVAILABLE}, which the pairing dialog already explains to the user.
 * The rest of the module (position mapping, PortCast bridge, rendering) has no
 * SDK dependency and is shared by both flavours.
 *
 * Keep the public API identical to the `play` twin in
 * garmin/src/play/java/de/danoeh/antennapod/garmin/GarminCompanionManager.java.
 */
public final class GarminCompanionManager {
    private static final String TAG = "GarminCompanion";

    /** Same shape as the play twin's handler; nothing ever calls it here. */
    public interface WatchMessageHandler {
        void onWatchMessage(Map<String, Object> portcastDoc);
    }

    public static final int SEND_UNAVAILABLE = 0;  // SDK not up (no Garmin Connect)
    public static final int SEND_NO_WATCH = 1;     // no paired/registered device
    public static final int SEND_DELIVERED = 2;    // watch accepted the message
    public static final int SEND_FAILED = 3;       // delivery failed (app not running?)

    /** Same shape as the play twin's listener. */
    public interface SendStatusListener {
        void onSendResult(int result);
    }

    private GarminCompanionManager() {
    }

    public static void start(Context context, WatchMessageHandler handler) {
        Log.i(TAG, "Garmin watch sync is not available in this build");
    }

    public static void requestProgressFlush(SendStatusListener listener) {
        listener.onSendResult(SEND_UNAVAILABLE);
    }
}
