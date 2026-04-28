package de.danoeh.antennapod.playback.service.trim;

import java.util.List;
import retrofit2.Call;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

public class TrimClient {
    private static TrimClient instance;
    private final TrimApi api;
    private final String currentBaseUrl;

    private TrimClient(String baseUrl) {
        this.currentBaseUrl = baseUrl;
        this.api = new Retrofit.Builder()
                .baseUrl(baseUrl)
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

    public Call<EpisodeSegmentsResponse> getSegments(String episodeUrl) {
        return api.getSegments(episodeUrl);
    }

    public Call<AnalyzeResponse> analyze(String rssUrl) {
        return api.analyze(new AnalyzeRequest(rssUrl));
    }

    public Call<JobStatusResponse> getJobStatus(String jobId) {
        return api.getJobStatus(jobId);
    }

    public interface TrimApi {
        @GET("segments")
        Call<EpisodeSegmentsResponse> getSegments(@Query("episode_url") String episodeUrl);

        @POST("analyze")
        Call<AnalyzeResponse> analyze(@Body AnalyzeRequest request);

        @GET("job/{job_id}")
        Call<JobStatusResponse> getJobStatus(@Path("job_id") String jobId);
    }

    public static class EpisodeSegmentsResponse {
        public String episode_url;
        public String episode_guid;
        public List<Segment> segments;
    }

    public static class Segment {
        public float start;
        public float end;
        public String type;
    }

    public static class AnalyzeRequest {
        public String rss_url;

        public AnalyzeRequest(String rssUrl) {
            this.rss_url = rssUrl;
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
}
