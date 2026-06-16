package de.danoeh.antennapod.garmin;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import de.danoeh.antennapod.storage.importexport.PortcastImporter;

/**
 * Applies a PortCast progress document received from the Garmin watch (over BLE)
 * to the app's library.
 *
 * <p>The watch reports {@code positionSeconds} in the <em>rendered</em> timeline
 * (trimmed + speed-adjusted). Before handing the document to the existing
 * {@link PortcastImporter} — which writes episode state to the DB and resolves
 * conflicts — we rewrite each episode's {@code positionSeconds} back to original
 * episode time using the stored {@link GarminRenderManifest}. That way the rest of
 * the app (and any onward PortCast sync) sees positions in the canonical timeline.
 *
 * <p>The Connect IQ Mobile SDK delivers the watch's transmitted dictionary as a
 * nested {@link Map}/{@link List}, so the remap ({@link GarminProgressRemapper})
 * operates directly on those collections — pure Java, independently unit-tested.
 * Only the final serialize-and-import step touches Android.
 */
public class GarminPortcastBridge {

    private static final String TAG = "GarminPortcastBridge";

    private final Context context;
    private final GarminManifestLookup manifests;

    public GarminPortcastBridge(Context context) {
        this(context, new GarminRenderManifestStore(context));
    }

    GarminPortcastBridge(Context context, GarminManifestLookup manifests) {
        this.context = context.getApplicationContext();
        this.manifests = manifests;
    }

    /**
     * Handle a PortCast document delivered by the Connect IQ Mobile SDK: remap
     * rendered-time positions to original time, then import.
     *
     * @param message the top-level PortCast document as a nested Map (from the SDK)
     */
    public void applyFromWatchMessage(Map<String, Object> message) {
        try {
            GarminProgressRemapper.remap(message, manifests);
            byte[] bytes = new JSONObject(message).toString().getBytes(StandardCharsets.UTF_8);
            PortcastImporter.ImportPreview preview =
                    PortcastImporter.previewImport(context, new ByteArrayInputStream(bytes));
            PortcastImporter.executeImport(context, preview);
            Log.i(TAG, "Applied watch PortCast doc: " + preview.nonConflictingStates.size()
                    + " episode state(s)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply PortCast doc from watch", e);
        }
    }
}
