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
 * Persists per-episode segment analysis state so the warm path skips the network
 * call and so the UI can show a badge on episodes the backend has weighed in on.
 *
 * Three states (see {@link State}):
 *   - UNKNOWN: never queried / cache miss / expired
 *   - HAS_SEGMENTS: server returned a non-empty segment list
 *   - ANALYZED_EMPTY: server confirmed analysis is complete but produced no
 *     skippable segments (intentionally "nothing to trim", not still pending)
 *
 * Entries expire after {@link #TTL_MS} so server-side analysis improvements
 * eventually propagate to clients.
 */
public final class TrimSegmentCache {
    private static final String TAG = "TrimSegmentCache";
    private static final String PREFS = "trim_segment_cache";
    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;

    public enum State { UNKNOWN, HAS_SEGMENTS, ANALYZED_EMPTY }

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
            JSONArray arr = root.optJSONArray("segments");
            if (arr == null) return null;
            List<TrimClient.Segment> segs = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                TrimClient.Segment s = new TrimClient.Segment();
                s.id = obj.optString("id", null);
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

    /** Reads the analysis state for an episode without paying the segment-parse
     *  cost — cheap enough to call from list adapters. Returns UNKNOWN for
     *  expired or missing entries so stale state never paints a misleading badge. */
    public static State getState(Context ctx, String guid) {
        if (ctx == null || guid == null || guid.isEmpty()) return State.UNKNOWN;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(guid, null);
        if (raw == null) return State.UNKNOWN;
        try {
            JSONObject root = new JSONObject(raw);
            long ts = root.optLong("ts", 0);
            if (System.currentTimeMillis() - ts > TTL_MS) {
                return State.UNKNOWN;
            }
            JSONArray arr = root.optJSONArray("segments");
            if (arr != null && arr.length() > 0) {
                return State.HAS_SEGMENTS;
            }
            return root.optBoolean("analyzed", false)
                    ? State.ANALYZED_EMPTY
                    : State.UNKNOWN;
        } catch (JSONException e) {
            return State.UNKNOWN;
        }
    }

    public static void put(Context ctx, String guid, List<TrimClient.Segment> segments) {
        if (ctx == null || guid == null || guid.isEmpty() || segments == null || segments.isEmpty()) {
            return;
        }
        try {
            writeSegments(ctx, guid, segments);
        } catch (JSONException e) {
            Log.w(TAG, "Failed to serialize segments for guid=" + guid, e);
        }
    }

    /** Persist a freshly-edited segment, replacing any existing entry with the
     *  same {@link TrimClient.Segment#stableId()}. Used by the local edit sheet
     *  so dragged boundaries / relabels survive across sessions and feed the
     *  auto-skip loop on the next load. */
    public static void putSegment(Context ctx, String guid, TrimClient.Segment edited) {
        if (ctx == null || guid == null || guid.isEmpty() || edited == null) return;
        List<TrimClient.Segment> segs = get(ctx, guid);
        if (segs == null) segs = new ArrayList<>();
        String targetId = edited.stableId();
        boolean replaced = false;
        for (int i = 0; i < segs.size(); i++) {
            if (segs.get(i).stableId().equals(targetId)) {
                segs.set(i, edited);
                replaced = true;
                break;
            }
        }
        if (!replaced) segs.add(edited);
        try {
            writeSegments(ctx, guid, segs);
        } catch (JSONException e) {
            Log.w(TAG, "Failed to persist edited segment for guid=" + guid, e);
        }
    }

    /** Remove the segment with the given stable id (the "Not a skip" action).
     *  Leaves an analyzed-empty marker behind when the last segment is removed
     *  so the badge UI still reads "analyzed" rather than reverting to UNKNOWN. */
    public static void removeSegment(Context ctx, String guid, String stableId) {
        if (ctx == null || guid == null || guid.isEmpty() || stableId == null) return;
        List<TrimClient.Segment> segs = get(ctx, guid);
        if (segs == null || segs.isEmpty()) return;
        List<TrimClient.Segment> kept = new ArrayList<>(segs.size());
        for (TrimClient.Segment s : segs) {
            if (!s.stableId().equals(stableId)) kept.add(s);
        }
        if (kept.size() == segs.size()) return; // nothing matched
        if (kept.isEmpty()) {
            putAnalyzedEmpty(ctx, guid);
            return;
        }
        try {
            writeSegments(ctx, guid, kept);
        } catch (JSONException e) {
            Log.w(TAG, "Failed to remove segment for guid=" + guid, e);
        }
    }

    private static void writeSegments(Context ctx, String guid, List<TrimClient.Segment> segments)
            throws JSONException {
        JSONArray arr = new JSONArray();
        for (TrimClient.Segment s : segments) {
            JSONObject obj = new JSONObject();
            obj.put("id", s.stableId());
            obj.put("start", s.start);
            obj.put("end", s.end);
            if (s.type != null) obj.put("type", s.type);
            arr.put(obj);
        }
        JSONObject root = new JSONObject();
        root.put("ts", System.currentTimeMillis());
        root.put("segments", arr);
        root.put("analyzed", true);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(guid, root.toString()).apply();
    }

    /** Record that the server analyzed this episode and produced no skippable
     *  segments. Distinct from {@link State#UNKNOWN} — the badge UI uses this
     *  to render the "analyzed, nothing to trim" icon instead of nothing. */
    public static void putAnalyzedEmpty(Context ctx, String guid) {
        if (ctx == null || guid == null || guid.isEmpty()) return;
        try {
            JSONObject root = new JSONObject();
            root.put("ts", System.currentTimeMillis());
            root.put("segments", new JSONArray());
            root.put("analyzed", true);
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(guid, root.toString()).apply();
        } catch (JSONException e) {
            Log.w(TAG, "Failed to write analyzed-empty for guid=" + guid, e);
        }
    }
}
