package de.danoeh.antennapod.garmin;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
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
 */
public class GarminPortcastBridge {

    private static final String TAG = "GarminPortcastBridge";

    private final Context context;
    private final GarminRenderManifestStore manifests;

    public GarminPortcastBridge(Context context) {
        this.context = context.getApplicationContext();
        this.manifests = new GarminRenderManifestStore(this.context);
    }

    /**
     * Handle a PortCast document delivered by the Connect IQ Mobile SDK. The SDK
     * surfaces the watch's transmitted dictionary as a nested {@link Map}; we
     * re-serialize it to JSON, remap positions, and import.
     *
     * @param message the top-level PortCast document as a Map (from the SDK)
     */
    public void applyFromWatchMessage(Map<String, Object> message) {
        try {
            JSONObject doc = new JSONObject(message);
            applyDocument(doc);
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply PortCast doc from watch", e);
        }
    }

    /** Handle a PortCast document already in JSON form (e.g. for tests). */
    public void applyDocument(JSONObject doc) throws Exception {
        remapPositionsToOriginalTime(doc);

        byte[] bytes = doc.toString().getBytes(StandardCharsets.UTF_8);
        PortcastImporter.ImportPreview preview =
                PortcastImporter.previewImport(context, new ByteArrayInputStream(bytes));
        PortcastImporter.executeImport(context, preview);
        Log.i(TAG, "Applied watch PortCast doc: " + preview.nonConflictingStates.size()
                + " episode state(s)");
    }

    /**
     * Rewrite each episode's {@code positionSeconds} (and any event positions) from
     * rendered time to original episode time, in place. Episodes without a known
     * render manifest are left unchanged (they were synced unrendered, or by a
     * different installer).
     */
    void remapPositionsToOriginalTime(JSONObject doc) throws JSONException {
        JSONArray episodes = doc.optJSONArray("episodes");
        if (episodes == null) {
            return;
        }
        for (int i = 0; i < episodes.length(); i++) {
            JSONObject ep = episodes.getJSONObject(i);
            String guid = ep.optString("guid", null);
            if (guid == null) {
                continue;
            }
            GarminRenderManifest manifest = manifests.get(guid);
            if (manifest == null) {
                continue;
            }
            if (ep.has("positionSeconds")) {
                double rendered = ep.optDouble("positionSeconds", 0);
                ep.put("positionSeconds",
                        GarminPositionMapper.renderedToOriginal(rendered, manifest));
            }
            JSONArray events = ep.optJSONArray("events");
            if (events != null) {
                for (int j = 0; j < events.length(); j++) {
                    JSONObject ev = events.getJSONObject(j);
                    if (ev.has("positionSeconds")) {
                        double rendered = ev.optDouble("positionSeconds", 0);
                        ev.put("positionSeconds",
                                GarminPositionMapper.renderedToOriginal(rendered, manifest));
                    }
                }
            }
        }
    }
}
