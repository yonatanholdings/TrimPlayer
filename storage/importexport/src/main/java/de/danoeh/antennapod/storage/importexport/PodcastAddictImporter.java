package de.danoeh.antennapod.storage.importexport;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.net.download.serviceinterface.FeedUpdateManager;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.FeedDatabaseWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class PodcastAddictImporter {

    static final String PREFS_NAME = "podcast_addict_import";
    static final String KEY_EPISODE_STATES = "episode_states";

    public static class EpisodeState {
        public String guid;
        public String downloadUrl;
        public boolean played;
        public int positionMs;
        public boolean favorite;
        public long durationMs;      // episode duration for statistics
        public long playbackDateMs;  // when episode was completed (Unix ms) for statistics
        public float playbackSpeed;  // playback speed used (1.0 = unknown/no adjustment)
    }

    /** A podcast feed from the Podcast Addict backup. */
    public static class PaFeed {
        public String url;
        public String title;
        /** Per-podcast playback speed from PA SharedPreferences, or 0 if PA had no
         *  custom speed for this feed (inherit our global). Stored as float so
         *  e.g. 1.25 maps directly to FeedPreferences.feedPlaybackSpeed. */
        public float playbackSpeed;
    }

    /**
     * An episode whose state differs between Podcast Addict and the existing AntennaPod library.
     * The user must choose which version to keep.
     */
    public static class ConflictEpisode {
        public EpisodeState paState;
        public String episodeTitle;
        public String feedTitle;
        // Describe the existing AP state for display
        public String apStateDescription;
        // User's choice: true = use PA data, false = keep AP data
        public boolean usePodcastAddict = true;
    }

    public static class ImportPreview {
        public List<PaFeed> feeds = new ArrayList<>();
        public List<EpisodeState> nonConflictingStates = new ArrayList<>();
        public List<ConflictEpisode> conflicts = new ArrayList<>();
    }

    /**
     * Parse the backup file and build a preview of what will be imported,
     * including conflict detection against the existing AntennaPod library.
     * Must be called off the main thread.
     */
    public static ImportPreview previewImport(Context context, InputStream backupStream) throws Exception {
        File dbFile = new File(context.getCacheDir(), "pa_import.db");
        File prefsFile = new File(context.getCacheDir(), "pa_import_prefs.xml");
        extractFiles(backupStream, dbFile, prefsFile);
        try {
            Map<Long, Float> speedByPodcastId = prefsFile.exists()
                    ? parseSpeedSettings(prefsFile) : Collections.emptyMap();
            return buildPreview(context, dbFile, speedByPodcastId);
        } finally {
            dbFile.delete();
            prefsFile.delete();
        }
    }

    /**
     * Execute the import after the user has resolved any conflicts.
     * Subscribes feeds, saves episode states, triggers refresh, and enqueues the state-apply worker.
     * Must be called off the main thread.
     */
    public static void executeImport(Context context, ImportPreview preview) throws Exception {
        // Subscribe all feeds, then apply per-podcast playback speed if PA had one.
        // The subscribe path creates a new Feed with feedPlaybackSpeed=SPEED_USE_GLOBAL,
        // so we have to fetch the persisted Feed and update its preferences after.
        for (PaFeed paFeed : preview.feeds) {
            Feed feed = new Feed(paFeed.url, null, paFeed.title);
            feed.setItems(Collections.emptyList());
            Feed persisted = FeedDatabaseWriter.updateFeed(context, feed, false);
            if (paFeed.playbackSpeed > 0.0f && persisted != null
                    && persisted.getPreferences() != null) {
                de.danoeh.antennapod.model.feed.FeedPreferences prefs = persisted.getPreferences();
                prefs.setFeedPlaybackSpeed(paFeed.playbackSpeed);
                de.danoeh.antennapod.storage.database.DBWriter.setFeedPreferences(prefs);
            }
        }

        // Collect states: non-conflicting + user-resolved conflicts
        List<EpisodeState> statesToApply = new ArrayList<>(preview.nonConflictingStates);
        for (ConflictEpisode conflict : preview.conflicts) {
            if (conflict.usePodcastAddict) {
                statesToApply.add(conflict.paState);
            }
        }

        saveEpisodeStates(context, statesToApply);
        FeedUpdateManager.getInstance().runOnce(context);
        PodcastAddictStateWorker.enqueue(context);
    }

    // -------------------------------------------------------------------------

    private static ImportPreview buildPreview(Context context, File dbFile,
                                              Map<Long, Float> speedByPodcastId) throws Exception {
        ImportPreview preview = new ImportPreview();

        // Build lookup of existing AP episodes for conflict detection
        Map<String, FeedItem> apByGuid = new HashMap<>();
        Map<String, FeedItem> apByUrl = new HashMap<>();
        List<Feed> existingFeeds = DBReader.getFeedList();
        for (Feed feed : existingFeeds) {
            List<FeedItem> items = DBReader.getFeedItemList(feed, FeedItemFilter.unfiltered(),
                    SortOrder.DATE_NEW_OLD, 0, Integer.MAX_VALUE);
            for (FeedItem item : items) {
                if (item.getItemIdentifier() != null && !item.getItemIdentifier().isEmpty()) {
                    apByGuid.put(item.getItemIdentifier(), item);
                }
                if (item.getMedia() != null && item.getMedia().getDownloadUrl() != null) {
                    apByUrl.put(item.getMedia().getDownloadUrl(), item);
                }
            }
        }

        SQLiteDatabase paDb = SQLiteDatabase.openDatabase(
                dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            // Read subscribed podcasts
            Map<Long, String> podcastTitleById = new HashMap<>();
            try (Cursor cur = paDb.rawQuery(
                    "SELECT _id, feed_url, name, custom_name FROM podcasts WHERE subscribed_status = 1",
                    null)) {
                while (cur.moveToNext()) {
                    long id = cur.getLong(0);
                    String feedUrl = cur.getString(1);
                    if (feedUrl == null || feedUrl.isEmpty()) {
                        continue;
                    }
                    String name = cur.getString(2);
                    String customName = cur.getString(3);
                    String title = (customName != null && !customName.isEmpty()) ? customName : name;
                    podcastTitleById.put(id, title != null ? title : "Unknown");

                    PaFeed paFeed = new PaFeed();
                    paFeed.url = feedUrl;
                    paFeed.title = title != null ? title : "Unknown";
                    // Per-podcast speed: prefer this podcast's setting, fall back to
                    // PA's global speed (-1 key). 0 means PA had no speed configured
                    // at all → leave the new feed inheriting our app-level default.
                    Float paSpeed = speedByPodcastId.containsKey(id)
                            ? speedByPodcastId.get(id)
                            : speedByPodcastId.get(-1L);
                    if (paSpeed != null && paSpeed > 0.0f) {
                        paFeed.playbackSpeed = paSpeed;
                    }
                    preview.feeds.add(paFeed);
                }
            }

            // Read episode states for subscribed feeds
            try (Cursor cur = paDb.rawQuery(
                    "SELECT e.guid, e.download_url, e.seen_status, e.position_to_resume, e.favorite,"
                    + " e.duration_ms, e.playbackDate, e.podcast_id, e.name "
                    + "FROM episodes e "
                    + "INNER JOIN podcasts p ON e.podcast_id = p._id "
                    + "WHERE p.subscribed_status = 1 "
                    + "AND (e.seen_status = 1 OR e.position_to_resume > 0 OR e.favorite = 1)",
                    null)) {
                while (cur.moveToNext()) {
                    EpisodeState state = new EpisodeState();
                    state.guid = cur.getString(0);
                    state.downloadUrl = cur.getString(1);
                    state.played = cur.getInt(2) == 1;
                    state.positionMs = cur.getInt(3);
                    state.favorite = cur.getInt(4) == 1;
                    state.durationMs = cur.getLong(5);
                    state.playbackDateMs = cur.getLong(6);
                    long podcastId = cur.getLong(7);
                    String episodeTitle = cur.getString(8);

                    if (state.played) {
                        Float speed = speedByPodcastId.containsKey(podcastId)
                                ? speedByPodcastId.get(podcastId)
                                : speedByPodcastId.get(-1L); // global fallback
                        if (speed != null && speed > 1.0f) {
                            state.playbackSpeed = speed;
                        }
                    }

                    // Check for conflicts with existing AP episodes
                    FeedItem apItem = findApItem(state, apByGuid, apByUrl);
                    if (apItem != null && hasApPlayData(apItem)) {
                        ConflictEpisode conflict = new ConflictEpisode();
                        conflict.paState = state;
                        conflict.episodeTitle = (episodeTitle != null && !episodeTitle.isEmpty())
                                ? episodeTitle : (state.downloadUrl != null ? state.downloadUrl : "Unknown");
                        conflict.feedTitle = podcastTitleById.getOrDefault(podcastId, "Unknown Feed");
                        conflict.apStateDescription = describeApState(apItem);
                        preview.conflicts.add(conflict);
                    } else {
                        preview.nonConflictingStates.add(state);
                    }
                }
            }
        } finally {
            paDb.close();
        }

        return preview;
    }

    private static FeedItem findApItem(EpisodeState state,
                                       Map<String, FeedItem> apByGuid,
                                       Map<String, FeedItem> apByUrl) {
        if (state.guid != null && !state.guid.isEmpty() && apByGuid.containsKey(state.guid)) {
            return apByGuid.get(state.guid);
        }
        if (state.downloadUrl != null && !state.downloadUrl.isEmpty() && apByUrl.containsKey(state.downloadUrl)) {
            return apByUrl.get(state.downloadUrl);
        }
        return null;
    }

    private static boolean hasApPlayData(FeedItem item) {
        if (item.isPlayed()) {
            return true;
        }
        if (item.getMedia() != null) {
            return item.getMedia().getPosition() > 0
                    || item.getMedia().getLastPlayedTimeStatistics() > 0;
        }
        return false;
    }

    private static String describeApState(FeedItem item) {
        if (item.isPlayed()) {
            return "Played";
        }
        if (item.getMedia() != null && item.getMedia().getPosition() > 0) {
            int posSec = item.getMedia().getPosition() / 1000;
            return String.format("In progress at %d:%02d", posSec / 60, posSec % 60);
        }
        return "Has history";
    }

    // -------------------------------------------------------------------------

    private static void extractFiles(InputStream stream, File dbFile, File prefsFile) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(stream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                File dest = null;
                if (name.equals("podcastAddict.db")) {
                    dest = dbFile;
                } else if (name.equals("com.bambuna.podcastaddict_preferences.xml")) {
                    dest = prefsFile;
                }
                if (dest != null) {
                    try (FileOutputStream fos = new FileOutputStream(dest)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = zis.read(buf)) != -1) {
                            fos.write(buf, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
        if (!dbFile.exists()) {
            throw new IOException("podcastAddict.db not found in backup file");
        }
    }

    /**
     * Parses per-podcast playback speed from Podcast Addict's SharedPreferences XML.
     * Returns a map of PA podcast ID → effective speed (1.0 if speed is disabled for that podcast).
     * Podcasts not in the map should fall back to the global speed.
     */
    private static Map<Long, Float> parseSpeedSettings(File prefsFile) throws IOException {
        Map<Long, Float> speedEnabled = new HashMap<>();  // id → explicit on/off
        Map<Long, Float> speedValue = new HashMap<>();    // id → speed float
        float globalSpeed = 1.0f;

        String content = new String(readFile(prefsFile), "UTF-8");

        // Global speed
        Matcher m = Pattern
                .compile("name=\"pref_speedAdjustment\" value=\"([0-9.]+)\"")
                .matcher(content);
        if (m.find()) {
            try { globalSpeed = Float.parseFloat(m.group(1)); } catch (NumberFormatException ignored) {}
        }

        // Per-podcast speed values
        m = Pattern
                .compile("name=\"pref_speedAdjustment_([0-9]+)\" value=\"([0-9.]+)\"")
                .matcher(content);
        while (m.find()) {
            try {
                long id = Long.parseLong(m.group(1));
                float speed = Float.parseFloat(m.group(2));
                speedValue.put(id, speed);
            } catch (NumberFormatException ignored) {}
        }

        // Per-podcast speed on/off
        m = Pattern
                .compile("name=\"pref_speedPlaybackOn_([0-9]+)\" value=\"(true|false)\"")
                .matcher(content);
        while (m.find()) {
            try {
                long id = Long.parseLong(m.group(1));
                speedEnabled.put(id, "true".equals(m.group(2)) ? 1.0f : 0.0f);
            } catch (NumberFormatException ignored) {}
        }

        // Build effective speed map
        Map<Long, Float> result = new HashMap<>();
        Set<Long> allIds = new HashSet<>();
        allIds.addAll(speedEnabled.keySet());
        allIds.addAll(speedValue.keySet());
        for (long id : allIds) {
            Float enabled = speedEnabled.get(id);
            if (enabled != null && enabled == 0.0f) {
                result.put(id, 1.0f); // explicitly disabled
            } else {
                // enabled explicitly or no override — use per-podcast value or global
                float speed = speedValue.containsKey(id) ? speedValue.get(id) : globalSpeed;
                result.put(id, speed);
            }
        }
        // Store global speed under key -1 for podcasts with no override
        result.put(-1L, globalSpeed);
        return result;
    }

    private static byte[] readFile(File file) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
        }
        return bos.toByteArray();
    }

    static void saveEpisodeStates(Context context, List<EpisodeState> states) throws Exception {
        JSONArray array = new JSONArray();
        for (EpisodeState state : states) {
            JSONObject obj = new JSONObject();
            obj.put("guid", state.guid != null ? state.guid : "");
            obj.put("downloadUrl", state.downloadUrl != null ? state.downloadUrl : "");
            obj.put("played", state.played);
            obj.put("positionMs", state.positionMs);
            obj.put("favorite", state.favorite);
            obj.put("durationMs", state.durationMs);
            obj.put("playbackDateMs", state.playbackDateMs);
            obj.put("playbackSpeed", state.playbackSpeed);
            array.put(obj);
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_EPISODE_STATES, array.toString()).apply();
    }

    public static List<EpisodeState> loadEpisodeStates(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_EPISODE_STATES, null);
        if (json == null) {
            return Collections.emptyList();
        }
        List<EpisodeState> states = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                EpisodeState state = new EpisodeState();
                state.guid = obj.optString("guid", "");
                state.downloadUrl = obj.optString("downloadUrl", "");
                state.played = obj.optBoolean("played", false);
                state.positionMs = obj.optInt("positionMs", 0);
                state.favorite = obj.optBoolean("favorite", false);
                state.durationMs = obj.optLong("durationMs", 0);
                state.playbackDateMs = obj.optLong("playbackDateMs", 0);
                state.playbackSpeed = (float) obj.optDouble("playbackSpeed", 1.0);
                states.add(state);
            }
        } catch (Exception ignored) {
        }
        return states;
    }

    public static void clearEpisodeStates(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
