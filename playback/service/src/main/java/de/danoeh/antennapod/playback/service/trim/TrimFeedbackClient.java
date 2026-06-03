package de.danoeh.antennapod.playback.service.trim;

import android.content.Context;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.danoeh.antennapod.storage.preferences.UserPreferences;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Thin wrapper around the {@code /feedback*} endpoints. Two surfaces:
 *
 * <ul>
 *   <li>{@link #submit} — fire-and-forget POST from the in-app form. A failure
 *       falls back to a callback so the UI can surface "couldn't send, try
 *       again" instead of pretending the report landed.</li>
 *   <li>{@link #fetchThreads} — polls the user's inbox so the bug-report
 *       screen and the settings unread badge stay fresh.</li>
 * </ul>
 *
 * <p>All calls carry the per-install {@code client_id} so dev replies route
 * back to the same install without a login.
 */
public final class TrimFeedbackClient {
    private static final String TAG = "TrimFeedbackClient";

    private TrimFeedbackClient() {
    }

    public interface SubmitCallback {
        @MainThread void onSuccess(long threadId);

        @MainThread void onFailure(@Nullable String reason);
    }

    public interface FetchCallback {
        @MainThread void onThreads(@NonNull List<TrimClient.FeedbackThread> threads);

        @MainThread void onFailure();
    }

    /** Submit a new feedback thread. category is bug | feature | other. */
    public static void submit(@NonNull Context ctx, @NonNull String category,
                              @NonNull String title, @NonNull String body,
                              @Nullable String envJson, @Nullable String crashLog,
                              @NonNull SubmitCallback callback) {
        TrimClient.FeedbackSubmitRequest req = new TrimClient.FeedbackSubmitRequest();
        req.client_id = UserPreferences.getOrCreateTrimClientId();
        req.category = category;
        req.title = title;
        req.body = body;
        req.env_json = envJson;
        req.crash_log = crashLog;

        TrimClient.getInstance().submitFeedback(req).enqueue(
                new Callback<TrimClient.FeedbackSubmitResponse>() {
                    @Override
                    public void onResponse(Call<TrimClient.FeedbackSubmitResponse> call,
                                           Response<TrimClient.FeedbackSubmitResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            Log.d(TAG, "Feedback submitted, thread=" + response.body().thread_id);
                            callback.onSuccess(response.body().thread_id);
                        } else {
                            Log.w(TAG, "Feedback rejected: " + response.code());
                            callback.onFailure("HTTP " + response.code());
                        }
                    }

                    @Override
                    public void onFailure(Call<TrimClient.FeedbackSubmitResponse> call, Throwable t) {
                        // t.getMessage() is null for many low-level exceptions
                        // (SSL, socket reset). Bubble the class name too so the
                        // snackbar tells the user something useful.
                        String msg = t.getClass().getSimpleName();
                        if (t.getMessage() != null) {
                            msg = msg + ": " + t.getMessage();
                        }
                        Log.w(TAG, "Feedback submit failed: " + msg, t);
                        callback.onFailure(msg);
                    }
                });
    }

    /** Fetch the user's threads. The successful callback also caches the total
     *  unread count so the settings-screen badge can read it synchronously. */
    public static void fetchThreads(@NonNull Context ctx, @NonNull FetchCallback callback) {
        String clientId = UserPreferences.getOrCreateTrimClientId();
        TrimClient.getInstance().getFeedbackThreads(clientId).enqueue(
                new Callback<TrimClient.FeedbackThreadsResponse>() {
                    @Override
                    public void onResponse(Call<TrimClient.FeedbackThreadsResponse> call,
                                           Response<TrimClient.FeedbackThreadsResponse> response) {
                        if (response.isSuccessful() && response.body() != null
                                && response.body().threads != null) {
                            List<TrimClient.FeedbackThread> threads = response.body().threads;
                            UserPreferences.setFeedbackUnreadCount(totalUnread(threads));
                            callback.onThreads(threads);
                        } else {
                            Log.w(TAG, "Threads fetch failed: " + response.code());
                            callback.onFailure();
                        }
                    }

                    @Override
                    public void onFailure(Call<TrimClient.FeedbackThreadsResponse> call, Throwable t) {
                        Log.d(TAG, "Threads fetch failed: " + t.getMessage());
                        callback.onFailure();
                    }
                });
    }

    /** Fire-and-forget mark-read. The cached unread count is decremented on
     *  the next successful fetch — no need to optimistically adjust it here. */
    public static void markRead(@NonNull Context ctx, long threadId) {
        String clientId = UserPreferences.getOrCreateTrimClientId();
        TrimClient.getInstance().markFeedbackThreadRead(threadId,
                new TrimClient.MarkReadRequest(clientId)).enqueue(
                new Callback<Void>() {
                    @Override
                    public void onResponse(Call<Void> call, Response<Void> response) {
                        // Best-effort; the next /feedback/threads poll will reconcile.
                    }

                    @Override
                    public void onFailure(Call<Void> call, Throwable t) {
                        Log.d(TAG, "markRead failed: " + t.getMessage());
                    }
                });
    }

    /** Synchronous variant for the periodic upload worker. Returns true iff the
     *  unread count was successfully refreshed (so the worker can know whether
     *  to retry). Caller must invoke this off the main thread. */
    public static boolean fetchThreadsSync(@NonNull Context ctx) {
        String clientId = UserPreferences.getOrCreateTrimClientId();
        try {
            Response<TrimClient.FeedbackThreadsResponse> response =
                    TrimClient.getInstance().getFeedbackThreads(clientId).execute();
            if (response.isSuccessful() && response.body() != null
                    && response.body().threads != null) {
                UserPreferences.setFeedbackUnreadCount(totalUnread(response.body().threads));
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.d(TAG, "fetchThreadsSync failed: " + e.getMessage());
            return false;
        }
    }

    private static int totalUnread(@NonNull List<TrimClient.FeedbackThread> threads) {
        int total = 0;
        for (TrimClient.FeedbackThread t : threads) {
            total += Math.max(0, t.unread_for_user);
        }
        return total;
    }
}
