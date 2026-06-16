package de.danoeh.antennapod.garmin;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists a {@link GarminRenderManifest} per episode guid so that when the watch
 * later reports a playback position (in rendered time), the companion can map it
 * back to original episode time.
 *
 * <p>Written when the phone renders an episode for the watch; read when a PortCast
 * progress document comes back over BLE (see {@link GarminPortcastBridge}).
 * SharedPreferences-backed — the data is tiny (a handful of ranges per synced
 * episode) and must survive process death between render and the next watch sync.
 */
public class GarminRenderManifestStore implements GarminManifestLookup {

    private static final String PREFS_NAME = "garmin_render_manifests";

    private final SharedPreferences prefs;

    public GarminRenderManifestStore(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void put(GarminRenderManifest manifest) {
        try {
            JSONArray ranges = new JSONArray();
            for (GarminRenderManifest.Range r : manifest.keptRanges) {
                ranges.put(new JSONArray().put(r.startSeconds).put(r.endSeconds));
            }
            JSONObject o = new JSONObject();
            o.put("guid", manifest.guid);
            o.put("speed", manifest.speed);
            o.put("keptRanges", ranges);
            prefs.edit().putString(manifest.guid, o.toString()).apply();
        } catch (JSONException e) {
            // Tiny, well-formed payload — serialization can't realistically fail.
            throw new IllegalStateException("Failed to serialize render manifest", e);
        }
    }

    @Nullable
    public GarminRenderManifest get(String guid) {
        String raw = prefs.getString(guid, null);
        if (raw == null) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(raw);
            JSONArray ranges = o.getJSONArray("keptRanges");
            List<GarminRenderManifest.Range> kept = new ArrayList<>();
            for (int i = 0; i < ranges.length(); i++) {
                JSONArray pair = ranges.getJSONArray(i);
                kept.add(new GarminRenderManifest.Range(pair.getDouble(0), pair.getDouble(1)));
            }
            return new GarminRenderManifest(o.getString("guid"), o.getDouble("speed"), kept);
        } catch (JSONException e) {
            return null;
        }
    }

    public void remove(String guid) {
        prefs.edit().remove(guid).apply();
    }
}
