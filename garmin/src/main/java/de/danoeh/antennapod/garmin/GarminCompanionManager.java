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

    /** The watch app's UUID — `id` in trimplayer-garmin/manifest.xml. Only
     *  SIDELOADED dev builds identify by this: the Connect IQ store assigns an
     *  uploaded app its own UUID, and store-installed builds identify by that
     *  instead. Register/send for both or one distribution channel goes deaf. */
    static final String WATCH_APP_ID = "50484e2303d1f3bf95a607033cb57079";

    /** The store-assigned UUID — from the Connect IQ store listing URL
     *  (apps.garmin.com/apps/21c3f78d-a0b6-458f-8d5a-fed1956f84ec). This is
     *  the identity of the PRODUCTION watch app users install from the store. */
    static final String STORE_APP_ID = "21c3f78da0b6458f8d5afed1956f84ec";

    /** Receives each PortCast document transmitted by the watch. */
    public interface WatchMessageHandler {
        void onWatchMessage(Map<String, Object> portcastDoc);
    }

    private static GarminCompanionManager instance;

    private final Context context;
    private final WatchMessageHandler handler;
    private ConnectIQ connectIQ;
    private final IQApp watchApp = new IQApp(WATCH_APP_ID);
    private final IQApp storeApp = new IQApp(STORE_APP_ID);
    private final Map<Long, IQDevice> registered = new HashMap<>();
    /**
     * True once the binder-service receive path is active (see {@link #registerReceivePath}).
     */
    private boolean binderServiceActive;

    /** The app-global binder-path message listener. One shared instance so the
     *  early reflective attach and the official registration install the same
     *  object (see {@link #attachBinderListenerEarly}). */
    private final ConnectIQ.IQApplicationEventListener binderListener =
            (d, app, message, status) -> {
                // The binder listener is app-global; keep only our watch app
                // in case GCM ever fans out other UUIDs to it.
                if (app == null || app.getApplicationId() == null
                        || WATCH_APP_ID.equalsIgnoreCase(app.getApplicationId())
                        || STORE_APP_ID.equalsIgnoreCase(app.getApplicationId())) {
                    onMessage(message, status);
                }
            };

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
            attachBinderListenerEarly();
            connectIQ.initialize(context, false, new ConnectIQ.ConnectIQListener() {
                @Override
                public void onSdkReady() {
                    Log.i(TAG, "Connect IQ SDK ready");
                    registerReceivePath();
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

    /** Register the path Garmin Connect actually uses to hand us watch messages.
     *
     *  <p>Current GCM (observed on 5.26) delivers watch→phone messages ONLY by
     *  binding into the SDK's exported {@code IQGarminBindingService} — a path
     *  the app must opt into via {@code registerAppToUseBinderService} plus the
     *  one-arg {@code registerForAppEvents} overload. The legacy per-device
     *  {@code registerForAppEvents} registration is still accepted but never
     *  routed: the watch's {@code Communications.transmit} sits pending and
     *  eventually fails while phone→watch messages flow fine — which is exactly
     *  the asymmetry that made progress sync look broken on-device. */
    private void registerReceivePath() {
        // Both identities: store-installed builds report the store UUID,
        // sideloaded dev builds the manifest UUID.
        boolean bound = false;
        for (String appId : new String[] {STORE_APP_ID, WATCH_APP_ID}) {
            try {
                connectIQ.registerAppToUseBinderService(appId);
                bound = true;
            } catch (IllegalArgumentException e) {
                // The SDK ends this call by unconditionally unregistering its
                // legacy broadcast receiver, so every call after the first
                // throws "Receiver not registered" — AFTER the binding-service
                // registration for this appId has already succeeded. Letting
                // this escape used to abort before the listener below was
                // attached, leaving GCM delivering into a null listener.
                bound = true;
            } catch (Exception e) {
                Log.w(TAG, "Binder-service registration failed for " + appId
                        + ": " + e.getMessage());
            }
        }
        if (!bound) {
            // Old Garmin Connect without the binder service — the legacy
            // per-device registration in registerAppEvents still applies there.
            Log.w(TAG, "Binder service unavailable, using legacy receive path");
            return;
        }
        try {
            connectIQ.registerForAppEvents(binderListener);
            binderServiceActive = true;
            Log.i(TAG, "Binder-service receive path registered (store + sideload ids)");
        } catch (Exception e) {
            Log.w(TAG, "Binder-service listener registration failed, using legacy path: "
                    + e.getMessage());
        }
    }

    /** Close the SDK's cold-start delivery hole. When the watch transmits while
     *  this app isn't running, GCM cold-starts us by binding the SDK's exported
     *  {@code IQGarminBindingService} — but that service drops any message that
     *  arrives before {@code registerForAppEvents(listener)} has run, and still
     *  acks it as SUCCESS, so the watch prunes the state as delivered (verified
     *  in the SDK 2.4.0 bytecode: {@code transferData} null-checks the listener
     *  field, warns "Application event listener is not set." and returns
     *  success). The official setter is gated on the SDK's async initialization
     *  (mInitialized flips only around onSdkReady), so it cannot run early —
     *  but the dispatch consults only the private listener field, which we can
     *  plant right here in Application.onCreate, guaranteed by Android to
     *  complete before any binder transaction reaches the service. onSdkReady
     *  then re-registers the same instance through the official path. */
    private void attachBinderListenerEarly() {
        try {
            java.lang.reflect.Field field = ConnectIQ.class
                    .getDeclaredField("mBindingServiceApplicationEventListener");
            field.setAccessible(true);
            field.set(connectIQ, binderListener);
            Log.i(TAG, "Binder listener attached early (cold-start deliveries safe)");
        } catch (Exception e) {
            // Non-fatal: the official registration in registerReceivePath still
            // happens; only messages delivered during app cold start can drop.
            Log.w(TAG, "Early binder-listener attach failed: " + e);
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
        // Diagnostic: log Garmin Connect's OWN view of the watch app. GCM's
        // message router silently drops watch→phone transmits for apps missing
        // from its Connect IQ registry (store-synced) — inbound keeps working,
        // so this asymmetry is otherwise invisible. NOT_INSTALLED here while
        // the app demonstrably runs on the watch = sideload isn't routable and
        // the app needs a store (beta) registration.
        for (String appId : new String[] {STORE_APP_ID, WATCH_APP_ID}) {
            try {
                connectIQ.getApplicationInfo(appId, device,
                        new ConnectIQ.IQApplicationInfoListener() {
                            @Override
                            public void onApplicationInfoReceived(IQApp app) {
                                Log.i(TAG, "GCM app registry: " + app.getApplicationId()
                                        + " status=" + app.getStatus()
                                        + " version=" + app.version());
                            }

                            @Override
                            public void onApplicationNotInstalled(String applicationId) {
                                Log.w(TAG, "GCM app registry: " + applicationId
                                        + " NOT INSTALLED (unknown to GCM)");
                            }
                        });
            } catch (Exception e) {
                Log.w(TAG, "getApplicationInfo failed: " + e.getMessage());
            }
        }
        if (binderServiceActive) {
            // Receive goes through the app-global binder path; this map only
            // tracks devices as sendMessage targets (requestProgressFlush).
            Log.i(TAG, "Tracking device " + device.getFriendlyName()
                    + " (binder-service receive active)");
            return;
        }
        try {
            connectIQ.registerForAppEvents(device, watchApp,
                    (d, app, message, status) -> onMessage(message, status));
            connectIQ.registerForAppEvents(device, storeApp,
                    (d, app, message, status) -> onMessage(message, status));
            Log.i(TAG, "Listening for watch messages on " + device.getFriendlyName());
        } catch (InvalidStateException | ServiceUnavailableException e) {
            synchronized (registered) {
                registered.remove(device.getDeviceIdentifier());
            }
            Log.w(TAG, "registerForAppEvents failed: " + e.getMessage());
        }
    }

    /** Outcome of a {@link #requestProgressFlush} delivery attempt, reported to
     *  the caller's listener so UI can say what actually happened instead of
     *  waiting blind. */
    public static final int SEND_UNAVAILABLE = 0;  // SDK not up (no Garmin Connect)
    public static final int SEND_NO_WATCH = 1;     // no paired/registered device
    public static final int SEND_DELIVERED = 2;    // watch accepted the message
    public static final int SEND_FAILED = 3;       // delivery failed (app not running?)

    /** Delivery-status callback for {@link #requestProgressFlush}. Called once
     *  per request with the best outcome across devices (delivered wins). May be
     *  invoked on a binder thread — marshal to main before touching UI. */
    public interface SendStatusListener {
        void onSendResult(int result);
    }

    /** Ask the watch to flush its buffered listen progress now (it answers by
     *  transmitting a PortCast doc, which arrives via the normal receive path).
     *  Delivery requires the TRIM Player watch app to be RUNNING on the watch
     *  (mid-playback or on screen); the listener hears how delivery went. */
    public static void requestProgressFlush(SendStatusListener listener) {
        GarminCompanionManager mgr;
        synchronized (GarminCompanionManager.class) {
            mgr = instance;
        }
        if (mgr == null || mgr.connectIQ == null) {
            Log.i(TAG, "requestProgressFlush: companion not started");
            listener.onSendResult(SEND_UNAVAILABLE);
            return;
        }
        List<IQDevice> targets;
        synchronized (mgr.registered) {
            targets = new java.util.ArrayList<>(mgr.registered.values());
        }
        if (targets.isEmpty()) {
            listener.onSendResult(SEND_NO_WATCH);
            return;
        }

        Map<String, Object> req = new HashMap<>();
        req.put("action", "flushProgress");
        // Address BOTH app identities — the watch runs either the store build
        // (store UUID) or a sideloaded dev build (manifest UUID); the copy
        // that isn't installed just fails its send. Report once: DELIVERED as
        // soon as any send lands; FAILED only after all have answered.
        IQApp[] apps = {mgr.storeApp, mgr.watchApp};
        final int[] remaining = {targets.size() * apps.length};
        final boolean[] reported = {false};
        for (IQDevice device : targets) {
            for (IQApp app : apps) {
                try {
                    mgr.connectIQ.sendMessage(device, app, req, (d, a, status) -> {
                        Log.i(TAG, "flush request -> " + d.getFriendlyName() + ": " + status);
                        synchronized (remaining) {
                            remaining[0]--;
                            if (reported[0]) {
                                return;
                            }
                            if (status == ConnectIQ.IQMessageStatus.SUCCESS) {
                                reported[0] = true;
                                listener.onSendResult(SEND_DELIVERED);
                            } else if (remaining[0] == 0) {
                                reported[0] = true;
                                listener.onSendResult(SEND_FAILED);
                            }
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "flush request failed for " + device.getFriendlyName()
                            + ": " + e.getMessage());
                    synchronized (remaining) {
                        remaining[0]--;
                        if (!reported[0] && remaining[0] == 0) {
                            reported[0] = true;
                            listener.onSendResult(SEND_FAILED);
                        }
                    }
                }
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
