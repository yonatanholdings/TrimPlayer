package de.danoeh.antennapod;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import de.danoeh.antennapod.net.common.TrimPrefetcher;
import de.danoeh.antennapod.playback.service.trim.TrimFeedbackClient;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import java.io.IOException;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Periodically uploads new rows from the local TrimSkipEvents table to the backend
 * /events endpoint. Local rows are NEVER deleted — the upload only advances a
 * high-water mark (last_uploaded_skip_event_id) so per-device stats keep using the
 * full table. The backend dedupes on (client_id, client_event_id) so retries are safe.
 */
public class TrimEventsUploadWorker extends Worker {
    private static final String TAG = "TrimEventsUpload";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int BATCH_SIZE = 500;
    private static final int MAX_BATCHES_PER_RUN = 10;

    public TrimEventsUploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @Override
    @NonNull
    public Result doWork() {
        String baseUrl = UserPreferences.getTrimServerUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            return Result.success();
        }
        String url = baseUrl + "events";
        String clientId = UserPreferences.getOrCreateTrimClientId();
        OkHttpClient client = TrimPrefetcher.client();  // shared pool + X-Api-Key interceptor

        // Piggyback a poll of the in-app feedback inbox so the unread badge
        // on Settings > Send feedback stays fresh between visits. Failures
        // are silent — the screen itself re-polls on open, and the badge
        // just keeps showing the last known count until the next worker run.
        TrimFeedbackClient.fetchThreadsSync(getApplicationContext());

        long highWater = UserPreferences.getLastUploadedSkipEventId();
        int batches = 0;
        while (batches++ < MAX_BATCHES_PER_RUN) {
            List<DBReader.SkipEventToUpload> batch = DBReader.getSkipEventsToUpload(highWater, BATCH_SIZE);
            if (batch.isEmpty()) {
                return Result.success();
            }
            String body;
            try {
                body = buildPayload(clientId, batch);
            } catch (JSONException e) {
                Log.e(TAG, "Failed to build JSON payload", e);
                return Result.failure();
            }
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body, JSON))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Log.w(TAG, "Upload returned " + response.code() + "; will retry");
                    return Result.retry();
                }
                highWater = batch.get(batch.size() - 1).id;
                UserPreferences.setLastUploadedSkipEventId(highWater);
                Log.d(TAG, "Uploaded " + batch.size() + " events, high-water now " + highWater);
            } catch (IOException e) {
                Log.w(TAG, "Network failure during upload: " + e.getMessage());
                return Result.retry();
            }
            if (batch.size() < BATCH_SIZE) {
                return Result.success();
            }
        }
        return Result.success();
    }

    private static String buildPayload(String clientId, List<DBReader.SkipEventToUpload> batch)
            throws JSONException {
        JSONArray events = new JSONArray();
        for (DBReader.SkipEventToUpload e : batch) {
            JSONObject obj = new JSONObject();
            obj.put("client_event_id", e.id);
            obj.put("skip_type", e.skipType);
            obj.put("duration_ms", e.durationMs);
            obj.put("client_ts", e.timestampMs);
            if (e.episodeGuid != null) obj.put("episode_guid", e.episodeGuid);
            if (e.episodeUrl != null)  obj.put("episode_url", e.episodeUrl);
            if (e.rssUrl != null)      obj.put("podcast_rss", e.rssUrl);
            events.put(obj);
        }
        JSONObject root = new JSONObject();
        root.put("client_id", clientId);
        root.put("events", events);
        // Community Impact: the user's typical playback speed, so the backend can
        // keep a community average for the "you vs community" speed comparison.
        // v1 proxy = the configured default speed (cheap, no playback hook). The
        // backend only reads this once its ClientEventsRequest model adds the
        // optional field; extra keys are otherwise ignored.
        root.put("avg_playback_speed", UserPreferences.getPlaybackSpeed());
        return root.toString();
    }
}
