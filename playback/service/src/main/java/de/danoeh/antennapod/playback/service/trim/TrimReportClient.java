package de.danoeh.antennapod.playback.service.trim;

import android.content.Context;
import android.util.Log;

import de.danoeh.antennapod.storage.preferences.UserPreferences;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Fire-and-forget sender for crowd-sourced segment reports (Scope B).
 *
 * <p>The local edit always lands in {@link TrimSegmentCache} first (Scope A), so a
 * failed or offline report never blocks the user's edit — this just submits the
 * correction to the backend's moderation queue, where it only affects other
 * users once an admin approves it. Sends the request-time client id so reports
 * are attributable per install without a login.
 */
public final class TrimReportClient {
    private static final String TAG = "TrimReportClient";

    private TrimReportClient() {
    }

    /**
     * Report a correction to a served segment.
     *
     * @param action   confirm | adjust | remove | missing
     * @param newStart proposed start for adjust/missing, else null
     * @param newEnd   proposed end for adjust/missing, else null
     */
    public static void report(Context ctx, String episodeUrl, String episodeGuid,
                              float origStart, float origEnd, String type,
                              String action, Float newStart, Float newEnd) {
        if (ctx == null || (episodeUrl == null && episodeGuid == null)) {
            return;
        }
        TrimClient.SegmentReportRequest req = new TrimClient.SegmentReportRequest();
        req.client_id = UserPreferences.getOrCreateTrimClientId();
        req.episode_url = episodeUrl;
        req.episode_guid = episodeGuid;
        req.orig_start = origStart;
        req.orig_end = origEnd;
        req.type = type != null ? type : "ad";
        req.action = action;
        req.new_start = newStart;
        req.new_end = newEnd;

        TrimClient.getInstance().reportSegment(req).enqueue(
                new Callback<TrimClient.SegmentReportResponse>() {
                    @Override
                    public void onResponse(Call<TrimClient.SegmentReportResponse> call,
                                           Response<TrimClient.SegmentReportResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            Log.d(TAG, "Segment report '" + action + "' queued for review, status="
                                    + response.body().status);
                        } else {
                            Log.w(TAG, "Segment report '" + action + "' rejected: " + response.code());
                        }
                    }

                    @Override
                    public void onFailure(Call<TrimClient.SegmentReportResponse> call, Throwable t) {
                        // Best-effort — the local edit already applied. Drop silently.
                        Log.d(TAG, "Segment report '" + action + "' failed to send: " + t.getMessage());
                    }
                });
    }
}
