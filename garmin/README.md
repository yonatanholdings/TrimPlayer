# `:garmin` — TRIM Player ↔ Garmin watch companion

The phone half of the Garmin integration (watch app lives in the separate
`trimplayer-garmin` Connect IQ repo). Full design: that repo's
`docs/android-companion-spec.md`.

The watch is a Connect IQ **Audio Content Provider** that plays already-trimmed,
already-speed-adjusted audio at 1× and exchanges listen state over BLE as
[PortCast](https://portcast.org) documents. This module:

1. maps the watch's **rendered-time** playback positions back to original episode
   time, and
2. applies received PortCast progress to the library (reusing `PortcastImporter`).

## What's implemented and verified

| Class | Role | Status |
|---|---|---|
| `GarminRenderManifest` | per-episode record of how it was rendered (kept ranges + speed) | pure Java, **unit-tested** |
| `GarminPositionMapper` | rendered-time → original-time inversion (the subtle math) | pure Java, **unit-tested** (`GarminPositionMapperTest`, 8 cases incl. round-trip) |
| `GarminRenderManifestStore` | persist manifests (guid → manifest) across render→sync | SharedPreferences |
| `GarminPortcastBridge` | received watch doc → remap positions → `PortcastImporter` | reuses existing import path |
| `GarminAudioRenderPlan` | build the ffmpeg trim+speed graph + manifest from segments | pure Java, **unit-tested** |
| `GarminRenderer` | orchestrate render (pluggable `FfmpegExecutor`) + persist manifest | thin wrapper |

Both the mapper and the render-plan tests were validated against the **real ffmpeg
render** used end-to-end (2:36 source, drop [0,20]+[60,75], 1.5× → 1:20.69 / 1,291,619
bytes). The render plan's filter graph is byte-for-byte identical to the backend
(`app/garmin.py`) and the watch repo's stub, so phone- and server-rendered files
behave the same — verified by running the planner's generated graph through ffmpeg.

## Still to wire (needs the Garmin AAR + an Android build)

**1. Connect IQ Mobile SDK dependency.** Not on Maven Central — download the
Android Companion SDK AAR from developer.garmin.com, drop it in `garmin/libs/`, add
a `flatDir` repo to `settings.gradle`'s `dependencyResolutionManagement`, and
uncomment the dependency in `garmin/build.gradle`.

**2. `GarminConnectIqManager`** — the BLE transport. Reference implementation to
add as `src/main/java/.../GarminConnectIqManager.java` once the SDK resolves:

```java
package de.danoeh.antennapod.garmin;

import android.content.Context;
import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.IQApp;
import com.garmin.android.connectiq.IQDevice;
import java.util.List;
import java.util.Map;

/** Connects to the TRIM Player watch app and bridges PortCast over BLE. */
public class GarminConnectIqManager {
    // Must match manifest.xml id in the trimplayer-garmin watch repo.
    private static final String WATCH_APP_ID = "50484e2303d1f3bf95a607033cb57079";

    private final ConnectIQ connectIQ;
    private final GarminPortcastBridge bridge;
    private IQDevice device;
    private IQApp app;

    public GarminConnectIqManager(Context ctx) {
        this.connectIQ = ConnectIQ.getInstance(ctx, ConnectIQ.IQConnectType.WIRELESS);
        this.bridge = new GarminPortcastBridge(ctx);
    }

    public void start(Context ctx) {
        connectIQ.initialize(ctx, true, new ConnectIQ.ConnectIQListener() {
            public void onSdkReady() { attachFirstDevice(); }
            public void onInitializeError(ConnectIQ.IQSdkErrorStatus s) { /* log */ }
            public void onSdkShutDown() { }
        });
    }

    private void attachFirstDevice() {
        try {
            List<IQDevice> known = connectIQ.getKnownDevices();
            if (known == null || known.isEmpty()) return;
            device = known.get(0);
            app = new IQApp(WATCH_APP_ID);
            // Receive: watch -> phone (PortCast progress)
            connectIQ.registerForAppEvents(device, app, (d, a, message, status) -> {
                for (Object o : message) {
                    if (o instanceof Map) {
                        //noinspection unchecked
                        bridge.applyFromWatchMessage((Map<String, Object>) o);
                    }
                }
            });
        } catch (Exception e) { /* log */ }
    }

    /** Send: phone -> watch (resume points / sync hints) as a PortCast document. */
    public void send(Map<String, Object> portcastDoc) {
        try {
            connectIQ.sendMessage(device, app, portcastDoc, (d, a, status) -> { /* log */ });
        } catch (Exception e) { /* log */ }
    }
}
```

**3. Render** is implemented: `GarminAudioRenderPlan` builds the verified ffmpeg
graph + manifest, and `GarminRenderer` runs it (via a pluggable `FfmpegExecutor`)
and persists the manifest. Two things remain to connect it:
   - provide an `FfmpegExecutor` backed by a maintained ffmpeg lib (ffmpeg-kit is
     retired) or MediaCodec+SoundTouch;
   - feed it real inputs: skip ranges from `TrimClient`/`TrimSegmentCache`, speed
     from `UserPreferences`, source file from the downloaded `FeedMedia`.

**4. Delivery** (the remaining piece): upload the rendered file to the thin HTTPS
bucket and expose the listing the watch fetches (`guid`/`subscriptionRef`/
`enclosureUrl` + signed `url`). See the watch repo spec §3.

## Tests

`GarminPositionMapperTest` is plain JUnit (no Android), runnable via the module's
`testImplementation junit` — or standalone with `javac`/`java`.
