package de.danoeh.antennapod.playback.service.trim;

import de.danoeh.antennapod.net.common.TrimPrefetcher;
import java.util.List;
import retrofit2.Call;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

public class TrimClient {
    private static TrimClient instance;
    private final TrimApi api;
    private final String currentBaseUrl;

    private TrimClient(String baseUrl) {
        this.currentBaseUrl = baseUrl;
        // Share OkHttp client (and its connection pool) with TrimPrefetcher.
        // The shared client already adds the X-Api-Key header via interceptor.
        this.api = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(TrimPrefetcher.client())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(TrimApi.class);
    }

    public static synchronized TrimClient getInstance() {
        String url = de.danoeh.antennapod.storage.preferences.UserPreferences.getTrimServerUrl();
        if (instance == null || !url.equals(instance.currentBaseUrl)) {
            instance = new TrimClient(url);
        }
        return instance;
    }

    /**
     * Resolves the request-time client_id + Pro JWT. Pulled at request-time
     * (not construction-time) so a token refresh in the middle of an in-flight
     * session is picked up by the next /segments call without rebuilding the
     * client.
     */
    public Call<EpisodeSegmentsResponse> getSegments(String episodeUrl, String episodeGuid,
                                                     String clientId, String proToken) {
        return api.getSegments(episodeUrl, episodeGuid, clientId, proToken);
    }

    /** Onboarding "Great first listens" rail: per curated show, its feed URL and
     *  the best demo episode to drop a brand-new listener onto. */
    public Call<FirstListensResponse> getFirstListens() {
        return api.getFirstListens();
    }

    /** Simple-callback wrapper around {@link #getFirstListens()} so callers in
     *  the app module don't need Retrofit types on their classpath. Delivered on
     *  the main thread; {@code result} is null on any failure. */
    public void fetchFirstListens(FirstListensCallback callback) {
        api.getFirstListens().enqueue(new retrofit2.Callback<FirstListensResponse>() {
            @Override
            public void onResponse(retrofit2.Call<FirstListensResponse> call,
                                   retrofit2.Response<FirstListensResponse> response) {
                callback.onResult(response.isSuccessful() ? response.body() : null);
            }

            @Override
            public void onFailure(retrofit2.Call<FirstListensResponse> call, Throwable t) {
                callback.onResult(null);
            }
        });
    }

    public interface FirstListensCallback {
        void onResult(FirstListensResponse result);
    }

    public Call<BillingVerifyResponse> billingVerify(String clientId, String productId,
                                                    String purchaseToken) {
        return api.billingVerify(new BillingVerifyRequest(clientId, productId, purchaseToken));
    }

    /** Fetch the monthly Supporter Digest. Requires a Pro JWT minted from a
     *  Supporter purchase — backend returns 403 for non-Supporter callers.
     *
     *  Note (2026-05-19): Supporter tier is hidden from v1 UI but the code
     *  path is preserved. If the backend ever assigns source='play_supporter'
     *  to an entitlement, the digest card surfaces automatically. */
    public Call<SupporterDigest> getSupporterDigest(String clientId, String proToken) {
        return api.getSupporterDigest(clientId, proToken);
    }

    public Call<AnalyzeResponse> analyze(String rssUrl, String episodeUrl, String episodeGuid) {
        return api.analyze(new AnalyzeRequest(rssUrl, episodeUrl, episodeGuid));
    }

    /** Report a correction to a served segment (Scope B). Fire-and-forget from
     *  the edit sheet — the backend queues the report for manual review; it only
     *  affects future /segments responses once an admin approves it. */
    public Call<SegmentReportResponse> reportSegment(SegmentReportRequest request) {
        return api.reportSegment(request);
    }

    /** Submit an in-app bug report / feature request / general feedback. The
     *  backend opens a thread; replies come back via {@link #getFeedbackThreads}. */
    public Call<FeedbackSubmitResponse> submitFeedback(FeedbackSubmitRequest request) {
        return api.submitFeedback(request);
    }

    /** Poll every thread this install has opened, with its full message history.
     *  Used by the in-app inbox and by the unread-count badge on Settings. */
    public Call<FeedbackThreadsResponse> getFeedbackThreads(String clientId) {
        return api.getFeedbackThreads(clientId);
    }

    /** Acknowledge dev replies on a thread so its unread counter resets. */
    public Call<Void> markFeedbackThreadRead(long threadId, MarkReadRequest body) {
        return api.markFeedbackThreadRead(threadId, body);
    }

    /** Soft-delete a thread from the user's inbox. The admin queue still sees
     *  it; only the user-facing list filters it out. Idempotent on the server. */
    public Call<Void> deleteFeedbackThread(long threadId, String clientId) {
        return api.deleteFeedbackThread(threadId, clientId);
    }

    public Call<JobStatusResponse> getJobStatus(String jobId) {
        return api.getJobStatus(jobId);
    }

    // --- Account auth + library sync ---------------------------------------

    /** Create an account. The X-Client-Id links this device to the new account
     *  so its existing client_id-scoped data (queue, entitlement) carries over. */
    public Call<AuthResponse> signup(String email, String password, String clientId) {
        return api.signup(new Credentials(email, password), clientId);
    }

    /** Log into an existing account (also links this device via X-Client-Id). */
    public Call<AuthResponse> login(String email, String password, String clientId) {
        return api.login(new Credentials(email, password), clientId);
    }

    /** Validate the stored session token. 401 -> the token is gone/expired. */
    public Call<AuthResponse> me(String bearer) {
        return api.me(bearer);
    }

    /** Two-way library delta sync. {@code bearer} is "Bearer &lt;session token&gt;". */
    public Call<SyncResponse> accountSync(String bearer, SyncRequest request) {
        return api.accountSync(bearer, request);
    }

    /** Blocking sync that keeps Retrofit types out of caller modules (the app
     *  module depends on playback:service via {@code implementation}, so it can
     *  see {@link SyncResult}/{@link SyncResponse} but not {@code retrofit2.*}).
     *  Never throws: HTTP failures land in {@link SyncResult#code}, transport
     *  failures set {@link SyncResult#networkError}. Call off the main thread. */
    public SyncResult accountSyncBlocking(String bearer, SyncRequest request) {
        SyncResult out = new SyncResult();
        try {
            retrofit2.Response<SyncResponse> r = api.accountSync(bearer, request).execute();
            out.code = r.code();
            out.body = r.body();
        } catch (java.io.IOException e) {
            out.networkError = true;
        }
        return out;
    }

    public interface TrimApi {
        @GET("segments")
        Call<EpisodeSegmentsResponse> getSegments(
                @Query("episode_url") String episodeUrl,
                @Query("episode_guid") String episodeGuid,
                @Header("X-Client-Id") String clientId,
                @Header("X-Pro-Token") String proToken);

        @GET("curated/first-listens")
        Call<FirstListensResponse> getFirstListens();

        @POST("billing/verify")
        Call<BillingVerifyResponse> billingVerify(@Body BillingVerifyRequest request);

        @GET("supporter/digest")
        Call<SupporterDigest> getSupporterDigest(
                @Header("X-Client-Id") String clientId,
                @Header("X-Pro-Token") String proToken);

        @POST("analyze")
        Call<AnalyzeResponse> analyze(@Body AnalyzeRequest request);

        @POST("segments/report")
        Call<SegmentReportResponse> reportSegment(@Body SegmentReportRequest request);

        @POST("feedback")
        Call<FeedbackSubmitResponse> submitFeedback(@Body FeedbackSubmitRequest request);

        @GET("feedback/threads")
        Call<FeedbackThreadsResponse> getFeedbackThreads(@Query("client_id") String clientId);

        @POST("feedback/threads/{thread_id}/read")
        Call<Void> markFeedbackThreadRead(@Path("thread_id") long threadId,
                                          @Body MarkReadRequest body);

        @DELETE("feedback/threads/{thread_id}")
        Call<Void> deleteFeedbackThread(@Path("thread_id") long threadId,
                                        @Query("client_id") String clientId);

        @GET("job/{job_id}")
        Call<JobStatusResponse> getJobStatus(@Path("job_id") String jobId);

        @POST("auth/signup")
        Call<AuthResponse> signup(@Body Credentials body, @Header("X-Client-Id") String clientId);

        @POST("auth/login")
        Call<AuthResponse> login(@Body Credentials body, @Header("X-Client-Id") String clientId);

        @GET("auth/me")
        Call<AuthResponse> me(@Header("Authorization") String bearer);

        @POST("account/sync")
        Call<SyncResponse> accountSync(@Header("Authorization") String bearer,
                                       @Body SyncRequest request);
    }

    // --- Account DTOs (mirror backend accounts.py / account_sync.py) --------

    public static class Credentials {
        public String email;
        public String password;

        public Credentials(String email, String password) {
            this.email = email;
            this.password = password;
        }
    }

    public static class AuthResponse {
        public String token;
        public String expires_at;
        public long account_id;
        public String email;
    }

    public static class SubscriptionChange {
        public String rss_url;
        public String title;
        public boolean deleted;
        public long client_ts;
    }

    public static class ProgressChange {
        public String episode_url;
        public String rss_url;
        public String guid;
        public Long position_ms;
        public Long duration_ms;
        public boolean played;
        public boolean deleted;
        public long client_ts;
    }

    public static class QueueChange {
        public String episode_url;
        public String rss_url;
        public String guid;
        public Integer position;
        public boolean deleted;
        public long client_ts;
    }

    public static class SyncRequest {
        public long cursor;
        public List<SubscriptionChange> subscriptions;
        public List<ProgressChange> progress;
        public List<QueueChange> queue;
    }

    public static class SyncResponse {
        public long cursor;
        public List<SubscriptionChange> subscriptions;
        public List<ProgressChange> progress;
        public List<QueueChange> queue;
    }

    /** Retrofit-free result wrapper so the app module can drive sync without a
     *  Retrofit compile dependency. {@code code} is the HTTP status (0 if the
     *  request never completed), {@code networkError} marks a transport failure. */
    public static class SyncResult {
        public int code;
        public boolean networkError;
        public SyncResponse body;

        public boolean isSuccessful() {
            return code >= 200 && code < 300 && body != null;
        }
    }

    /** GET /curated/first-listens — the onboarding curated rail. */
    public static class FirstListensResponse {
        public List<FirstListen> shows;
    }

    public static class FirstListen {
        public String name;
        public String feed_url;
        /** Null until the show has an analyzed demo episode — client then falls
         *  back to the normal directory-search subscribe flow for that tile. */
        public String episode_guid;
        public String episode_url;
        public String episode_title;
        public List<String> segment_types;
    }

    public static class EpisodeSegmentsResponse {
        public String episode_url;
        public String episode_guid;
        public List<Segment> segments;
        // Soft-paywall signal added 2026-05-19. May be null on responses from
        // older backends (and on responses where we didn't send X-Client-Id).
        public EntitlementStatus entitlement;
        // True when the backend has finished analyzing this episode, regardless
        // of whether any skippable segments were found. Null on responses from
        // pre-2026-05-29 backends — the client treats null as "unknown" so old
        // servers never paint the "analyzed, nothing to trim" badge.
        public Boolean analyzed;
    }

    /** Mirrors backend EntitlementStatus. status is the discriminator:
     *   - "ok"              free user under quota; quota/used/resets_at populated
     *   - "quota_exceeded"  free user over quota; segments will be empty
     *   - "pro"             entitled (source populated); quota fields may be null */
    public static class EntitlementStatus {
        public String status;
        public Integer quota;
        public Integer used;
        public String resets_at;
        public String source;
        /** Server-driven kill-switch for in-app Pro UI. Null on old-server
         *  responses (pre-2026-05-21) — clients treat null as false so the
         *  default is "hidden" until the server explicitly says otherwise. */
        public Boolean pro_ui_visible;
    }

    public static class BillingVerifyRequest {
        public String client_id;
        public String product_id;
        public String purchase_token;

        public BillingVerifyRequest(String clientId, String productId, String purchaseToken) {
            this.client_id = clientId;
            this.product_id = productId;
            this.purchase_token = purchaseToken;
        }
    }

    public static class BillingVerifyResponse {
        public String pro_token;
        public String expires_at;
        public String source;
        public String entitlement_expires_at;
    }

    public static class Segment {
        /** Stable per-episode identifier. The backend may populate this in the
         *  future (episode_segments.id); until then {@link TrimSegmentCache}
         *  synthesizes one from type+start so local edits can target a segment. */
        public String id;
        public float start;
        public float end;
        public String type;
        /** Pipeline confidence (Scope B field). Null on responses from older backends. */
        public Float confidence;

        /** Deterministic local id so the same segment keeps its identity across
         *  cache round-trips even when the server doesn't send one. */
        public String stableId() {
            if (id != null && !id.isEmpty()) {
                return id;
            }
            return (type != null ? type : "seg") + "@" + Math.round(start);
        }
    }

    public static class AnalyzeRequest {
        public String rss_url;
        public String episode_url;
        public String episode_guid;

        public AnalyzeRequest(String rssUrl, String episodeUrl, String episodeGuid) {
            this.rss_url = rssUrl;
            this.episode_url = episodeUrl;
            this.episode_guid = episodeGuid;
        }
    }

    public static class AnalyzeResponse {
        public String status;
        public String job_id;
    }

    /** Mirrors backend SegmentReportRequest. orig_start/orig_end are the served
     *  segment bounds the user acted on (used by the backend to cluster the
     *  report onto a segment); new_start/new_end carry the proposed bounds for
     *  action="adjust". action is one of confirm | adjust | remove | missing. */
    public static class SegmentReportRequest {
        public String client_id;
        public String episode_url;
        public String episode_guid;
        public float orig_start;
        public float orig_end;
        public String type;
        public String action;
        public Float new_start;
        public Float new_end;
    }

    public static class SegmentReportResponse {
        /** "pending" — the report was queued for manual review. */
        public String status;
    }

    public static class JobStatusResponse {
        public String status; // "pending", "running", "done", "error"
        public String message;
    }

    // -----------------------------------------------------------------------
    // Supporter Digest (Phase 1 client contract, 2026-05-19). Backend
    // implementation TBD — when the endpoint doesn't exist yet, Retrofit will
    // surface a 404 which the fragment treats as "no digest yet".
    // -----------------------------------------------------------------------

    /** Monthly digest shown only to Supporter-tier subscribers. Two halves:
     *   - community pulse  (auto-aggregated from /usage/report)
     *   - dev transparency (manually curated 'shipped' / 'next' lists +
     *                       declared funding numbers) */
    public static class SupporterDigest {
        public String period;                  // "YYYY-MM" of the digest window
        public CommunityPulse community;
        public DevUpdate dev;
    }

    public static class CommunityPulse {
        public Long total_minutes_saved;       // all clients, this period
        public Double pct_change_vs_prev;      // e.g. 0.18 for +18%
        public Long avg_listener_minutes_saved;
        public Long your_minutes_saved;
        public Integer your_percentile;        // 0-100
        public List<TopPodcast> top_podcasts;  // ordered, server caps the length

        // Aggregated insights (added 2026-05-19). All optional — client renders
        // each subsection only when its data is present.
        public SkipBreakdown skip_breakdown;
        public AlgorithmAccuracy accuracy;
        public CatalogGrowth catalog;
        public CumulativeStats cumulative;
    }

    public static class TopPodcast {
        public String title;
        public Long minutes_saved;
    }

    /** Counts of skipped segments by type, this period. Percentages computed
     *  client-side from these raw counts so the server isn't on the hook for
     *  rounding decisions. */
    public static class SkipBreakdown {
        public Long intros_count;
        public Long ads_count;
        public Long outros_count;
    }

    /** Auto-trim quality signal. accuracy_pct in [0,1]. hits = skips the user
     *  let through; misses = reverts + explicit miss-flags from the events
     *  stream. Server may compute accuracy_pct = hits / (hits + misses). */
    public static class AlgorithmAccuracy {
        public Double accuracy_pct;
        public Long hits;
        public Long misses;
    }

    /** Snapshot of catalog scale + this-period growth (TrimBrain learning
     *  more podcasts over time). Both totals + deltas so the client can show
     *  '+N this month · M total' on one line. */
    public static class CatalogGrowth {
        public Long podcasts_known;
        public Long podcasts_added_this_period;
        public Long canonical_segments_known;
        public Long canonical_segments_added_this_period;
    }

    /** Big-number psychology: everything saved across all users since launch. */
    public static class CumulativeStats {
        public Long minutes_saved_all_time;
        public String since_iso;               // ISO date the counter started from
    }

    public static class DevUpdate {
        public Double supporter_income_usd;
        public Double server_cost_usd;
        public Double net_to_dev_usd;
        public List<String> shipped;           // bullet items, dev-curated
        public List<String> next;              // bullet items, dev-curated
        public String note;                    // optional short paragraph
    }

    // -----------------------------------------------------------------------
    // In-app feedback (POST /feedback, GET /feedback/threads).
    // Replaces the old copy-to-clipboard + external email flow.
    // -----------------------------------------------------------------------

    public static class FeedbackSubmitRequest {
        public String client_id;
        public String category;                // bug | feature | other
        public String title;
        public String body;
        /** Optional device/app info blob the user opted to attach. Free-form
         *  text — server stores it verbatim and never parses it. */
        public String env_json;
        /** Optional crash log the user opted to attach, if one is available. */
        public String crash_log;
    }

    public static class FeedbackSubmitResponse {
        public long thread_id;
        public String status;                  // "received"
    }

    public static class FeedbackMessage {
        public long id;
        public String sender;                  // user | admin
        public String body;
        public String created_at;              // ISO-8601 UTC
    }

    public static class FeedbackThread {
        public long id;
        public String category;
        public String title;
        public String status;                  // open | resolved | closed
        public int unread_for_user;
        public String created_at;
        public String updated_at;
        public List<FeedbackMessage> messages;
    }

    public static class FeedbackThreadsResponse {
        public List<FeedbackThread> threads;
    }

    public static class MarkReadRequest {
        public String client_id;

        public MarkReadRequest(String clientId) {
            this.client_id = clientId;
        }
    }
}
