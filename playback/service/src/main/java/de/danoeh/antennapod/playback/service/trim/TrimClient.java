package de.danoeh.antennapod.playback.service.trim;

import de.danoeh.antennapod.net.common.TrimPrefetcher;
import java.util.List;
import retrofit2.Call;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.Body;
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

    public Call<JobStatusResponse> getJobStatus(String jobId) {
        return api.getJobStatus(jobId);
    }

    public interface TrimApi {
        @GET("segments")
        Call<EpisodeSegmentsResponse> getSegments(
                @Query("episode_url") String episodeUrl,
                @Query("episode_guid") String episodeGuid,
                @Header("X-Client-Id") String clientId,
                @Header("X-Pro-Token") String proToken);

        @POST("billing/verify")
        Call<BillingVerifyResponse> billingVerify(@Body BillingVerifyRequest request);

        @GET("supporter/digest")
        Call<SupporterDigest> getSupporterDigest(
                @Header("X-Client-Id") String clientId,
                @Header("X-Pro-Token") String proToken);

        @POST("analyze")
        Call<AnalyzeResponse> analyze(@Body AnalyzeRequest request);

        @GET("job/{job_id}")
        Call<JobStatusResponse> getJobStatus(@Path("job_id") String jobId);
    }

    public static class EpisodeSegmentsResponse {
        public String episode_url;
        public String episode_guid;
        public List<Segment> segments;
        // Soft-paywall signal added 2026-05-19. May be null on responses from
        // older backends (and on responses where we didn't send X-Client-Id).
        public EntitlementStatus entitlement;
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
        public float start;
        public float end;
        public String type;
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
}
