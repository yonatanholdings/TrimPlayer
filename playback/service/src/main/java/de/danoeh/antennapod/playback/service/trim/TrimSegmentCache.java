package de.danoeh.antennapod.playback.service.trim;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Persists segments per episode GUID so the warm path skips the network call.
 * Entries expire after {@link #TTL_MS} so server-side analysis improvements
 * eventually propagate to clients.
 */
public final class TrimSegmentCache {
    private static final String TAG = "TrimSegmentCache";
    private static final String PREFS = "trim_segment_cache";
    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;

    private TrimSegmentCache() { }

    public static List<TrimClient.Segment> get(Context ctx, String guid) {
        if (ctx == null || guid == null || guid.isEmpty()) return null;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(guid, null);
        if (raw == null) return null;
        try {
            JSONObject root = new JSONObject(raw);
            long ts = root.optLong("ts", 0);
            if (System.currentTimeMillis() - ts > TTL_MS) {
                return null;
            }
            JSONArray arr = root.getJSONArray("segments");
            List<TrimClient.Segment> segs = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                TrimClient.Segment s = new TrimClient.Segment();
                s.start = (float) obj.getDouble("start");
                s.end = (float) obj.getDouble("end");
                s.type = obj.optString("type", null);
                segs.add(s);
            }
            return segs;
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse cached segments for guid=" + guid, e);
            return null;
        }
    }

    public static void put(Context ctx, String guid, List<TrimClient.Segment> segments) {
        if (ctx == null || guid == null || guid.isEmpty() || segments == null || segments.isEmpty()) {
            return;
        }
        try {
            JSONArray arr = new JSONArray();
            for (TrimClient.Segment s : segments) {
                JSONObject obj = new JSONObject();
                obj.put("start", s.start);
                obj.put("end", s.end);
                if (s.type != null) obj.put("type", s.type);
                arr.put(obj);
            }
            JSONObject root = new JSONObject();
            root.put("ts", System.currentTimeMillis());
            root.put("segments", arr);
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(guid, root.toString()).apply();
        } catch (JSONException e) {
            Log.w(TAG, "Failed to serialize segments for guid=" + guid, e);
        }
    }
}
