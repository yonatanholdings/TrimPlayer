package de.danoeh.antennapod.garmin;

import android.content.Context;
import android.util.Log;

import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.IQApp;
import com.garmin.android.connectiq.IQDevice;
import com.garmin.android.connectiq.exception.InvalidStateException;
import com.garmin.android.connectiq.exception.ServiceUnavailableException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BLE link to the TRIM Player Garmin watch app, via the Connect IQ Mobile SDK
 * (which relays through the Garmin Connect Mobile app — no direct BLE handling
 * here). Receives the PortCast progress documents the watch transmits
 * ({@code Communications.transmit} on the watch side) and hands each to the
 * supplied handler as the nested {@link Map} the SDK delivers.
 *
 * <p>Failure-tolerant by design: users without Garmin Connect installed (or with
 * no paired watch) just get a log line — initialization errors must never affect
 * the rest of the app. Everything is lazy: the SDK binds once at {@link #start}
 * and re-registers per device as devices connect.
 */
public final class GarminCompanionManager {

    private static final String TAG = "GarminCompanion";

    /** The watch app's UUID — `id` in trimplayer-garmin/manifest.xml. */
    static final String WATCH_APP_ID = "50484e2303d1f3bf95a607033cb57079";

    /** Receives each PortCast document transmitted by the watch. */
    public interface WatchMessageHandler {
        void onWatchMessage(Map<String, Object> portcastDoc);
    }

    private static GarminCompanionManager instance;

    private final Context context;
    private final WatchMessageHandler handler;
    private ConnectIQ connectIQ;
    private final IQApp watchApp = new IQApp(WATCH_APP_ID);
    private final Map<Long, IQDevice> registered = new HashMap<>();

    private GarminCompanionManager(Context context, WatchMessageHandler handler) {
        this.context = context.getApplicationContext();
        this.handler = handler;
    }

    /** Bind the Connect IQ SDK and start listening for watch messages. Safe to
     *  call once from Application.onCreate; a missing Garmin Connect app or SDK
     *  failure is logged and ignored. */
    public static synchronized void start(Context context, WatchMessageHandler handler) {
        if (instance != null) {
            return;
        }
        instance = new GarminCompanionManager(context, handler);
        instance.initialize();
    }

    private void initialize() {
        try {
            connectIQ = ConnectIQ.getInstance(context, ConnectIQ.IQConnectType.WIRELESS);
            connectIQ.initialize(context, false, new ConnectIQ.ConnectIQListener() {
                @Override
                public void onSdkReady() {
                    Log.i(TAG, "Connect IQ SDK ready");
                    registerConnectedDevices();
                }

                @Override
                public void onInitializeError(ConnectIQ.IQSdkErrorStatus status) {
                    // Typical: GCM_NOT_INSTALLED. Fine — most users have no watch.
                    Log.i(TAG, "Connect IQ unavailable: " + status);
                }

                @Override
                public void onSdkShutDown() {
                    Log.i(TAG, "Connect IQ SDK shut down");
                    synchronized (registered) {
                        registered.clear();
                    }
                }
            });
        } catch (Throwable t) {
            // The SDK can throw on devices with no BLE stack / broken GCM installs.
            Log.w(TAG, "Connect IQ init failed: " + t.getMessage());
        }
    }

    /** Register for app events on every known device, and watch device status so
     *  a watch that connects later gets registered too. */
    private void registerConnectedDevices() {
        try {
            List<IQDevice> devices = connectIQ.getKnownDevices();
            if (devices == null || devices.isEmpty()) {
                Log.i(TAG, "No paired Garmin devices");
                return;
            }
            for (IQDevice device : devices) {
                connectIQ.registerForDeviceEvents(device, (d, status) -> {
                    if (status == IQDevice.IQDeviceStatus.CONNECTED) {
                        registerAppEvents(d);
                    }
                });
                registerAppEvents(device);
            }
        } catch (InvalidStateException | ServiceUnavailableException e) {
            Log.w(TAG, "Device enumeration failed: " + e.getMessage());
        }
    }

    private void registerAppEvents(IQDevice device) {
        synchronized (registered) {
            if (registered.containsKey(device.getDeviceIdentifier())) {
                return; // already listening on this device
            }
            registered.put(device.getDeviceIdentifier(), device);
        }
        try {
            connectIQ.registerForAppEvents(device, watchApp,
                    (d, app, message, status) -> onMessage(message, status));
            Log.i(TAG, "Listening for watch messages on " + device.getFriendlyName());
        } catch (InvalidStateException | ServiceUnavailableException e) {
            synchronized (registered) {
                registered.remove(device.getDeviceIdentifier());
            }
            Log.w(TAG, "registerForAppEvents failed: " + e.getMessage());
        }
    }

    /** Ask the watch to flush its buffered listen progress now (it answers by
     *  transmitting a PortCast doc, which arrives via the normal receive path).
     *  Best-effort: sent to every registered device; delivery requires the TRIM
     *  Player watch app to be running (e.g. mid-playback or recently used). */
    public static void requestProgressFlush() {
        GarminCompanionManager mgr;
        synchronized (GarminCompanionManager.class) {
            mgr = instance;
        }
        if (mgr == null || mgr.connectIQ == null) {
            Log.i(TAG, "requestProgressFlush: companion not started");
            return;
        }
        Map<String, Object> req = new HashMap<>();
        req.put("action", "flushProgress");
        List<IQDevice> targets;
        synchronized (mgr.registered) {
            targets = new java.util.ArrayList<>(mgr.registered.values());
        }
        for (IQDevice device : targets) {
            try {
                mgr.connectIQ.sendMessage(device, mgr.watchApp, req,
                        (d, a, status) -> Log.i(TAG, "flush request -> "
                                + d.getFriendlyName() + ": " + status));
            } catch (Exception e) {
                Log.w(TAG, "flush request failed for " + device.getFriendlyName()
                        + ": " + e.getMessage());
            }
        }
    }

    /** The watch transmits one PortCast document (a Monkey C Dictionary); the SDK
     *  delivers it as a single-element List containing a nested Map. */
    private void onMessage(List<Object> message, ConnectIQ.IQMessageStatus status) {
        if (status != ConnectIQ.IQMessageStatus.SUCCESS || message == null) {
            Log.w(TAG, "Watch message dropped (status " + status + ")");
            return;
        }
        for (Object part : message) {
            if (!(part instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = (Map<String, Object>) part;
            try {
                handler.onWatchMessage(doc);
            } catch (Exception e) {
                Log.e(TAG, "Watch message handler failed", e);
            }
        }
    }
}
