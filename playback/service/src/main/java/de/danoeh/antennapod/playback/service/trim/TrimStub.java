package de.danoeh.antennapod.playback.service.trim;

import android.content.Context;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Loads offline segment stubs from assets/stub_segments.json for testing skip
 * functionality without a running backend. The file is a JSON object keyed by
 * episode URL, each value matching {@link TrimClient.EpisodeSegmentsResponse}.
 *
 * <p>Enabled/disabled via Settings > Playback > Stub skip segments (debug builds only).
 */
public class TrimStub {
    private static final String TAG = "TrimStub";
    private static final String ASSET_FILE = "stub_segments.json";

    private static Map<String, TrimClient.EpisodeSegmentsResponse> cache;
    private static boolean loaded = false;

    public static List<TrimClient.Segment> getSegments(Context context, String episodeUrl) {
        if (!de.danoeh.antennapod.storage.preferences.UserPreferences.isTrimStubEnabled()) {
            return null;
        }
        ensureLoaded(context);
        if (cache == null || episodeUrl == null) {
            return null;
        }
        TrimClient.EpisodeSegmentsResponse entry = cache.get(episodeUrl);
        if (entry == null || entry.segments == null || entry.segments.isEmpty()) {
            return null;
        }
        Log.d(TAG, "Stub hit for url=" + episodeUrl + " - " + entry.segments.size() + " segments");
        return entry.segments;
    }

    public static boolean hasSegments(Context context, String episodeUrl) {
        if (!de.danoeh.antennapod.storage.preferences.UserPreferences.isTrimStubEnabled()) {
            return false;
        }
        ensureLoaded(context);
        if (cache == null || episodeUrl == null) {
            return false;
        }
        TrimClient.EpisodeSegmentsResponse entry = cache.get(episodeUrl);
        return entry != null && entry.segments != null && !entry.segments.isEmpty();
    }

    private static void ensureLoaded(Context context) {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            InputStream is = context.getAssets().open(ASSET_FILE);
            InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
            Type type = new TypeToken<Map<String, TrimClient.EpisodeSegmentsResponse>>() {}.getType();
            cache = new Gson().fromJson(reader, type);
            reader.close();
            Log.d(TAG, "Loaded stub file with " + (cache != null ? cache.size() : 0) + " episode(s)");
        } catch (java.io.FileNotFoundException e) {
            Log.d(TAG, "No stub file found - stub mode inactive");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load stub file", e);
        }
    }
}
