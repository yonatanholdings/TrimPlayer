package de.danoeh.antennapod.net.common;

import android.util.Log;

import androidx.annotation.Nullable;

import de.danoeh.antennapod.storage.preferences.UserPreferences;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Reads the backend's {@code GET /community/impact} — the anonymous, pooled
 * trim-impact aggregates that power the Community Impact screen.
 *
 * <p>The payload carries two time bases: an <em>all-time</em> block (the
 * collective hero + breakdown) and a {@code windows} map keyed by chip label
 * ({@code 7d/30d/90d/1y}) for the tenure-fair "you vs community" comparison. See
 * {@code docs/community-impact-spec.md}.
 *
 * <p>Successful fetches are cached verbatim in {@link UserPreferences} so the
 * screen renders instantly and survives offline.
 */
public final class CommunityImpactClient {
    private static final String TAG = "CommunityImpact";

    private CommunityImpactClient() {
    }

    /** Per-window comparison totals (trailing window). */
    public static final class Window {
        public long adsMs;
        public long silenceMs;
        public long speedMs;
        public long introMs;
        public long outroMs;
        public long activeContributors;
    }

    /** Parsed {@code /community/impact} response. */
    public static final class CommunityImpact {
        public long totalMs;
        public long adsMs;
        public long silenceMs;
        public long speedMs;
        public long introMs;
        public long outroMs;
        public long contributors;
        /** Community mean playback speed; null until the backend has speed data. */
        @Nullable public Double avgPlaybackSpeed;
        @Nullable public String asOf;
        /** Keyed by chip label: "7d","30d","90d","1y". "All" uses the all-time fields. */
        public final Map<String, Window> windows = new HashMap<>();
    }

    /**
     * Blocking GET. Caller MUST be off the main thread. Returns null on any
     * network/parse failure (the screen then falls back to {@link #cached()}).
     * A successful response is written to the local cache.
     */
    @Nullable
    public static CommunityImpact fetchSync() {
        String baseUrl = UserPreferences.getTrimServerUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            return null;
        }
        OkHttpClient client = TrimPrefetcher.client();  // shared pool + X-Api-Key
        Request request = new Request.Builder()
                .url(baseUrl + "community/impact")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                Log.w(TAG, "fetch returned " + response.code());
                return null;
            }
            String json = body.string();
            CommunityImpact impact = parse(json);
            if (impact != null) {
                UserPreferences.setCommunityImpactCache(json);
            }
            return impact;
        } catch (IOException e) {
            Log.d(TAG, "fetch failed: " + e.getMessage());
            return null;
        }
    }

    /** Last cached response, or null if none/parse fails. Cheap; safe on any thread. */
    @Nullable
    public static CommunityImpact cached() {
        return parse(UserPreferences.getCommunityImpactCache());
    }

    @Nullable
    private static CommunityImpact parse(@Nullable String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(json);
            CommunityImpact c = new CommunityImpact();
            c.totalMs = o.optLong("total_ms");
            c.adsMs = o.optLong("ads_ms");
            c.silenceMs = o.optLong("silence_ms");
            c.speedMs = o.optLong("speed_ms");
            c.introMs = o.optLong("intro_ms");
            c.outroMs = o.optLong("outro_ms");
            c.contributors = o.optLong("contributors");
            if (o.has("avg_playback_speed") && !o.isNull("avg_playback_speed")) {
                c.avgPlaybackSpeed = o.optDouble("avg_playback_speed");
            }
            if (o.has("as_of") && !o.isNull("as_of")) {
                c.asOf = o.optString("as_of", null);
            }
            JSONObject windows = o.optJSONObject("windows");
            if (windows != null) {
                for (String label : new String[]{"7d", "30d", "90d", "1y"}) {
                    JSONObject w = windows.optJSONObject(label);
                    if (w == null) {
                        continue;
                    }
                    Window win = new Window();
                    win.adsMs = w.optLong("ads_ms");
                    win.silenceMs = w.optLong("silence_ms");
                    win.speedMs = w.optLong("speed_ms");
                    win.introMs = w.optLong("intro_ms");
                    win.outroMs = w.optLong("outro_ms");
                    win.activeContributors = w.optLong("active_contributors");
                    c.windows.put(label, win);
                }
            }
            return c;
        } catch (JSONException e) {
            Log.w(TAG, "parse failed: " + e.getMessage());
            return null;
        }
    }
}
