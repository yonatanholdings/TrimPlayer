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
            JSONArray arr = root.optJSONArray("segments");
            // User-owned entries that still carry marks never expire — those marks
            // outrank server-side analysis refreshes. An *empty* user-owned set
            // (the listener deleted the last segment) is only honored within the
            // TTL, after which the episode becomes eligible for fresh analysis
            // again, so a single bad detection doesn't lock the episode forever.
            if (!neverExpires(root, arr) && System.currentTimeMillis() - ts > TTL_MS) {
                return null;
            }
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
            JSONArray arr = root.optJSONArray("segments");
            if (!neverExpires(root, arr) && System.currentTimeMillis() - ts > TTL_MS) {
                return State.UNKNOWN;
            }
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

    /** True once the listener has edited/marked/removed any segment for this
     *  episode. Such an entry is authoritative: the backend must not overwrite
     *  it and it never expires. */
    public static boolean isUserOwned(Context ctx, String guid) {
        if (ctx == null || guid == null || guid.isEmpty()) return false;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(guid, null);
        if (raw == null) return false;
        try {
            JSONObject root = new JSONObject(raw);
            if (!root.optBoolean("userOwned", false)) return false;
            JSONArray arr = root.optJSONArray("segments");
            // A user-owned set with marks stays authoritative indefinitely. An
            // empty user-owned set stops being authoritative once it ages out, so
            // PlaybackService is free to re-query/re-analyze the episode again.
            return neverExpires(root, arr)
                    || System.currentTimeMillis() - root.optLong("ts", 0) <= TTL_MS;
        } catch (JSONException e) {
            return false;
        }
    }

    /** True for entries that must never expire: a user-owned set that still
     *  carries at least one segment. An empty user-owned set is deliberately
     *  excluded so deleting the last segment doesn't lock the episode forever. */
    private static boolean neverExpires(JSONObject root, JSONArray segments) {
        return root.optBoolean("userOwned", false) && segments != null && segments.length() > 0;
    }

    public static void put(Context ctx, String guid, List<TrimClient.Segment> segments) {
        if (ctx == null || guid == null || guid.isEmpty() || segments == null || segments.isEmpty()) {
            return;
        }
        // The listener's own marks win: never let a backend refresh clobber an
        // episode the user has taken ownership of.
        if (isUserOwned(ctx, guid)) {
            return;
        }
        try {
            writeSegments(ctx, guid, segments, false);
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
            writeSegments(ctx, guid, segs, true);
        } catch (JSONException e) {
            Log.w(TAG, "Failed to persist edited segment for guid=" + guid, e);
        }
    }

    /** Remove the segment with the given stable id (the "Not a skip" action).
     *  Marks the entry user-owned; removing the last segment leaves a user-owned
     *  empty set so the badge still reads "analyzed" and the backend won't refill it. */
    public static void removeSegment(Context ctx, String guid, String stableId) {
        if (ctx == null || guid == null || guid.isEmpty() || stableId == null) return;
        List<TrimClient.Segment> segs = get(ctx, guid);
        if (segs == null || segs.isEmpty()) return;
        List<TrimClient.Segment> kept = new ArrayList<>(segs.size());
        for (TrimClient.Segment s : segs) {
            if (!s.stableId().equals(stableId)) kept.add(s);
        }
        if (kept.size() == segs.size()) return; // nothing matched
        try {
            // Removing the last segment leaves a user-owned empty set ("I checked,
            // there's nothing to skip here") that the backend must not refill.
            writeSegments(ctx, guid, kept, true);
        } catch (JSONException e) {
            Log.w(TAG, "Failed to remove segment for guid=" + guid, e);
        }
    }

    private static void writeSegments(Context ctx, String guid, List<TrimClient.Segment> segments,
                                      boolean userOwned) throws JSONException {
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
        root.put("userOwned", userOwned);
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
