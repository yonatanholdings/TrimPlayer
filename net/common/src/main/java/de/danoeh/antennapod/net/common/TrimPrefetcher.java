package de.danoeh.antennapod.net.common;

import android.util.Log;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import java.io.IOException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * HTTP client and helpers for talking to the TrimBrain backend.
 *
 * Owns the shared {@link OkHttpClient} used by both this class and the in-app
 * Retrofit client ({@code TrimClient}), so a single TLS handshake / connection
 * pool is reused across prefetch, segments, analyze, and job-status calls.
 */
public final class TrimPrefetcher {
    private static final String TAG = "TrimPrefetcher";
    private static final String API_KEY = "cb074974aeebc39c58692adbaa6d070c237282900a9962366c3f828cd7a87fd6";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .addInterceptor(new Interceptor() {
                @Override
                public Response intercept(Chain chain) throws IOException {
                    Request request = chain.request().newBuilder()
                            .header("X-Api-Key", API_KEY)
                            .build();
                    return chain.proceed(request);
                }
            })
            .build();

    private TrimPrefetcher() { }

    /** Shared HTTP client. The Retrofit-based TrimClient wraps this one too. */
    public static OkHttpClient client() {
        return client;
    }

    /**
     * Open a TCP+TLS connection to the trim server so the first user-facing
     * call doesn't pay the handshake cost. Sends a cheap GET that nginx will
     * answer with 404 or 200; we don't care about the body.
     */
    public static void prewarm() {
        String baseUrl = UserPreferences.getTrimServerUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            return;
        }
        Request request = new Request.Builder()
                .url(baseUrl)
                .get()
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.d(TAG, "Prewarm failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                Log.d(TAG, "Prewarm -> " + response.code());
                response.close();
            }
        });
    }

    /**
     * Simple value object describing one queued episode. Fields mirror what
     * the backend's POST /api/v1/queue expects.
     */
    public static final class QueueItem {
        public final String rssUrl;
        public final String episodeUrl;
        public final String episodeGuid;

        public QueueItem(String rssUrl, String episodeUrl, String episodeGuid) {
            this.rssUrl = rssUrl;
            this.episodeUrl = episodeUrl;
            this.episodeGuid = episodeGuid;
        }
    }

    /**
     * Send the user's current playback-queue snapshot to the trim backend so
     * the analyze pipeline can prioritize episodes about to be played. Fire
     * and forget — failures are logged at debug level only since the backend
     * will just fall back to its own popularity heuristics if no queue is
     * present.
     */
    public static void postQueue(String clientId, List<QueueItem> items) {
        if (clientId == null || clientId.isEmpty()) {
            return;
        }
        String baseUrl = UserPreferences.getTrimServerUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            return;
        }
        String body;
        try {
            JSONArray arr = new JSONArray();
            if (items != null) {
                for (QueueItem it : items) {
                    if (it.rssUrl == null || it.episodeUrl == null) {
                        continue;
                    }
                    JSONObject o = new JSONObject();
                    o.put("rss_url", it.rssUrl);
                    o.put("episode_url", it.episodeUrl);
                    if (it.episodeGuid != null) {
                        o.put("episode_guid", it.episodeGuid);
                    }
                    arr.put(o);
                }
            }
            JSONObject json = new JSONObject();
            json.put("client_id", clientId);
            json.put("items", arr);
            body = json.toString();
        } catch (JSONException e) {
            Log.w(TAG, "Failed to build queue snapshot body", e);
            return;
        }
        Request request = new Request.Builder()
                .url(baseUrl + "queue")
                .post(RequestBody.create(body, JSON))
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.d(TAG, "postQueue failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                Log.d(TAG, "postQueue -> " + response.code());
                response.close();
            }
        });
    }

    public static void prefetchAnalyze(String rssUrl, String episodeUrl, String episodeGuid) {
        if (rssUrl == null || rssUrl.isEmpty() || episodeUrl == null || episodeUrl.isEmpty()) {
            return;
        }
        String baseUrl = UserPreferences.getTrimServerUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            return;
        }
        String body;
        try {
            JSONObject json = new JSONObject();
            json.put("rss_url", rssUrl);
            json.put("episode_url", episodeUrl);
            if (episodeGuid != null) {
                json.put("episode_guid", episodeGuid);
            }
            body = json.toString();
        } catch (JSONException e) {
            Log.w(TAG, "Failed to build analyze body", e);
            return;
        }
        Request request = new Request.Builder()
                .url(baseUrl + "analyze")
                .post(RequestBody.create(body, JSON))
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.d(TAG, "Prefetch analyze failed for " + episodeUrl + ": " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                Log.d(TAG, "Prefetch analyze " + episodeUrl + " -> " + response.code());
                response.close();
            }
        });
    }
}
