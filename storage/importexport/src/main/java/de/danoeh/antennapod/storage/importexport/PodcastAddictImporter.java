package de.danoeh.antennapod.storage.importexport;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.storage.database.DBReader;

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
    static final String KEY_QUEUE = "import_queue";
    static final String KEY_CURRENTLY_PLAYING = "import_currently_playing";
    /** Stash of PaFeed list + per-feed prefs for the background subscribe worker. */
    static final String KEY_PENDING_FEEDS = "import_pending_feeds";

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
        /** Per-podcast intro skip duration in seconds (PA's pref_podcastOffset_<id>).
         *  Maps directly to FeedPreferences.feedSkipIntro. 0 means no override. */
        public int skipIntroSec;
        /** Per-podcast outro skip duration in seconds (PA's pref_podcastOutroOffset_<id>).
         *  Maps directly to FeedPreferences.feedSkipEnding. 0 means no override. */
        public int skipOutroSec;
        /** Category string from PA's podcasts.category column. Split on "/" into
         *  TrimPlayer tags. null/empty = no tags. */
        public String category;
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
        /** PA play queue in rank order. Limited at extraction time to MAX_QUEUE_IMPORT
         *  to avoid blowing up AP's queue from PA's auto-add-all-new-episodes mode. */
        public List<QueueEntry> queue = new ArrayList<>();
        /** Currently/last playing episode in PA, identified by guid + downloadUrl
         *  for resolution against our DB. null if PA had no playback history. */
        public QueueEntry currentlyPlaying;
    }

    /** Minimal identity payload for an episode in the queue or current-playing slot. */
    public static class QueueEntry {
        public String guid;
        public String downloadUrl;
    }

    /** Cap on imported queue size — PA users often have thousands of queued
     *  episodes from auto-add-on-publish. Bringing all 5k+ into AP would be
     *  noise; the first N (typically the next-to-play ones) are what matters. */
    public static final int MAX_QUEUE_IMPORT = 200;

    /** Per-podcast preferences extracted from PA's SharedPreferences XML. */
    private static class PaPrefs {
        /** Resolved playback speed per PA podcast id; key -1 holds the PA global. */
        Map<Long, Float> effectiveSpeedByPodcastId;
        /** PA pref_podcastOffset_<id> → seconds to skip at start. */
        Map<Long, Integer> skipIntroSecByPodcastId;
        /** PA pref_podcastOutroOffset_<id> → seconds to skip at end. */
        Map<Long, Integer> skipOutroSecByPodcastId;
        /** PA pref_lastPlayedAudioEpisode — internal PA episode _id of last-played
         *  audio episode. 0 means PA had no audio playback history. */
        long lastPlayedAudioEpisodeId;

        static PaPrefs empty() {
            PaPrefs p = new PaPrefs();
            p.effectiveSpeedByPodcastId = Collections.emptyMap();
            p.skipIntroSecByPodcastId = Collections.emptyMap();
            p.skipOutroSecByPodcastId = Collections.emptyMap();
            p.lastPlayedAudioEpisodeId = 0;
            return p;
        }
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
            PaPrefs paPrefs = prefsFile.exists() ? parsePrefs(prefsFile) : PaPrefs.empty();
            return buildPreview(context, dbFile, paPrefs);
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
        // Gradual import: stash everything to SharedPreferences (fast), then enqueue
        // a background worker that subscribes feeds one-by-one with a progress
        // notification. The UI dialog can dismiss immediately — the user is free
        // to navigate the app while feeds, currently-playing, queue, and episode
        // states materialize in the background in that order.
        //
        // The subscribe loop USED to run synchronously here. For ~60 feeds it
        // took 10-30s during which the UI was modal-blocked.

        // Collect states: non-conflicting + user-resolved conflicts
        List<EpisodeState> statesToApply = new ArrayList<>(preview.nonConflictingStates);
        for (ConflictEpisode conflict : preview.conflicts) {
            if (conflict.usePodcastAddict) {
                statesToApply.add(conflict.paState);
            }
        }

        savePendingFeeds(context, preview.feeds);
        saveEpisodeStates(context, statesToApply);
        saveQueueAndCurrentlyPlaying(context, preview.queue, preview.currentlyPlaying);
        // The subscribe worker kicks FeedUpdateManager.runOnce + the state worker
        // once it finishes subscribing all feeds. Don't start them from here so
        // they don't race with the subscribe writes.
        PodcastAddictSubscribeWorker.enqueue(context);
    }

    // -------------------------------------------------------------------------

    private static ImportPreview buildPreview(Context context, File dbFile,
                                              PaPrefs paPrefs) throws Exception {
        Map<Long, Float> speedByPodcastId = paPrefs.effectiveSpeedByPodcastId;
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
                    "SELECT _id, feed_url, name, custom_name, category FROM podcasts "
                    + "WHERE subscribed_status = 1",
                    null)) {
                while (cur.moveToNext()) {
                    long id = cur.getLong(0);
                    String feedUrl = cur.getString(1);
                    if (feedUrl == null || feedUrl.isEmpty()) {
                        continue;
                    }
                    String name = cur.getString(2);
                    String customName = cur.getString(3);
                    String category = cur.getString(4);
                    String title = (customName != null && !customName.isEmpty()) ? customName : name;
                    podcastTitleById.put(id, title != null ? title : "Unknown");

                    PaFeed paFeed = new PaFeed();
                    paFeed.url = feedUrl;
                    paFeed.title = title != null ? title : "Unknown";
                    paFeed.category = category;
                    // Per-podcast speed: prefer this podcast's setting, fall back to
                    // PA's global speed (-1 key). 0 means PA had no speed configured
                    // at all → leave the new feed inheriting our app-level default.
                    Float paSpeed = speedByPodcastId.containsKey(id)
                            ? speedByPodcastId.get(id)
                            : speedByPodcastId.get(-1L);
                    if (paSpeed != null && paSpeed > 0.0f) {
                        paFeed.playbackSpeed = paSpeed;
                    }
                    // PA's per-podcast intro/outro skip seconds. 0 means PA had no
                    // override; leave the feed inheriting our app-level default.
                    Integer introSec = paPrefs.skipIntroSecByPodcastId.get(id);
                    if (introSec != null && introSec > 0) {
                        paFeed.skipIntroSec = introSec;
                    }
                    Integer outroSec = paPrefs.skipOutroSecByPodcastId.get(id);
                    if (outroSec != null && outroSec > 0) {
                        paFeed.skipOutroSec = outroSec;
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

            // ---- Play queue ----
            // PA stores the play queue in ordered_list with type=1, ranked.
            // Episodes referenced here may or may not be in our subscribed-feed
            // set, so we just collect their guid + downloadUrl and resolve them
            // against the AP DB later (after the feed refresh has populated
            // episode rows).
            try (Cursor cur = paDb.rawQuery(
                    "SELECT e.guid, e.download_url FROM ordered_list o "
                    + "INNER JOIN episodes e ON e._id = o.id "
                    + "WHERE o.type = 1 "
                    + "ORDER BY o.rank ASC LIMIT " + MAX_QUEUE_IMPORT,
                    null)) {
                while (cur.moveToNext()) {
                    String guid = cur.getString(0);
                    String url  = cur.getString(1);
                    if ((guid == null || guid.isEmpty()) && (url == null || url.isEmpty())) {
                        continue;
                    }
                    QueueEntry q = new QueueEntry();
                    q.guid = guid;
                    q.downloadUrl = url;
                    preview.queue.add(q);
                }
            }

            // ---- Currently-playing ----
            if (paPrefs.lastPlayedAudioEpisodeId > 0) {
                try (Cursor cur = paDb.rawQuery(
                        "SELECT guid, download_url FROM episodes WHERE _id = ? LIMIT 1",
                        new String[]{String.valueOf(paPrefs.lastPlayedAudioEpisodeId)})) {
                    if (cur.moveToFirst()) {
                        String guid = cur.getString(0);
                        String url  = cur.getString(1);
                        if ((guid != null && !guid.isEmpty()) || (url != null && !url.isEmpty())) {
                            QueueEntry cp = new QueueEntry();
                            cp.guid = guid;
                            cp.downloadUrl = url;
                            preview.currentlyPlaying = cp;
                        }
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
     * Parses per-podcast prefs from Podcast Addict's SharedPreferences XML.
     * Returns all the per-podcast values we know how to map onto TrimPlayer's
     * FeedPreferences. Walking the file once is cheaper than four passes and
     * avoids re-allocating the file buffer.
     */
    private static PaPrefs parsePrefs(File prefsFile) throws IOException {
        Map<Long, Float> speedEnabled = new HashMap<>();  // id → explicit on/off (1.0/0.0)
        Map<Long, Float> speedValue = new HashMap<>();    // id → speed float
        Map<Long, Integer> introSec = new HashMap<>();    // id → pref_podcastOffset_<id>
        Map<Long, Integer> outroSec = new HashMap<>();    // id → pref_podcastOutroOffset_<id>
        float globalSpeed = 1.0f;

        String content = new String(readFile(prefsFile), "UTF-8");

        // Global speed
        Matcher m = Pattern
                .compile("name=\"pref_speedAdjustment\" value=\"([0-9.]+)\"")
                .matcher(content);
        if (m.find()) {
            try { globalSpeed = Float.parseFloat(m.group(1)); } catch (NumberFormatException ignored) {
                // intentionally ignored
            }
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
            } catch (NumberFormatException ignored) {
                // intentionally ignored
            }
        }

        // Per-podcast speed on/off
        m = Pattern
                .compile("name=\"pref_speedPlaybackOn_([0-9]+)\" value=\"(true|false)\"")
                .matcher(content);
        while (m.find()) {
            try {
                long id = Long.parseLong(m.group(1));
                speedEnabled.put(id, "true".equals(m.group(2)) ? 1.0f : 0.0f);
            } catch (NumberFormatException ignored) {
                // intentionally ignored
            }
        }

        // Per-podcast intro skip seconds (PA: pref_podcastOffset_<id>)
        m = Pattern
                .compile("name=\"pref_podcastOffset_([0-9]+)\" value=\"([0-9]+)\"")
                .matcher(content);
        while (m.find()) {
            try {
                long id = Long.parseLong(m.group(1));
                int sec = Integer.parseInt(m.group(2));
                if (sec > 0) {
                    introSec.put(id, sec);
                }
            } catch (NumberFormatException ignored) {
                // intentionally ignored
            }
        }

        // Per-podcast outro skip seconds (PA: pref_podcastOutroOffset_<id>)
        m = Pattern
                .compile("name=\"pref_podcastOutroOffset_([0-9]+)\" value=\"([0-9]+)\"")
                .matcher(content);
        while (m.find()) {
            try {
                long id = Long.parseLong(m.group(1));
                int sec = Integer.parseInt(m.group(2));
                if (sec > 0) {
                    outroSec.put(id, sec);
                }
            } catch (NumberFormatException ignored) {
                // intentionally ignored
            }
        }

        // Last-played audio episode (PA's internal episode _id)
        long lastPlayedAudioId = 0L;
        m = Pattern
                .compile("name=\"pref_lastPlayedAudioEpisode\" value=\"([0-9]+)\"")
                .matcher(content);
        if (m.find()) {
            try { lastPlayedAudioId = Long.parseLong(m.group(1)); } catch (NumberFormatException ignored) {
                // intentionally ignored
            }
        }

        // Build effective speed map
        Map<Long, Float> effectiveSpeed = new HashMap<>();
        Set<Long> allIds = new HashSet<>();
        allIds.addAll(speedEnabled.keySet());
        allIds.addAll(speedValue.keySet());
        for (long id : allIds) {
            Float enabled = speedEnabled.get(id);
            if (enabled != null && enabled == 0.0f) {
                effectiveSpeed.put(id, 1.0f); // explicitly disabled — no override
            } else {
                // enabled explicitly or no override — use per-podcast value or global
                float speed = speedValue.containsKey(id) ? speedValue.get(id) : globalSpeed;
                effectiveSpeed.put(id, speed);
            }
        }
        // Store global speed under key -1 for podcasts with no override
        effectiveSpeed.put(-1L, globalSpeed);

        PaPrefs out = new PaPrefs();
        out.effectiveSpeedByPodcastId = effectiveSpeed;
        out.skipIntroSecByPodcastId = introSec;
        out.skipOutroSecByPodcastId = outroSec;
        out.lastPlayedAudioEpisodeId = lastPlayedAudioId;
        return out;
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

    /** Stash queue + currently-playing for PodcastAddictStateWorker to resolve
     *  and apply after the feed refresh has materialized FeedItems in our DB. */
    static void saveQueueAndCurrentlyPlaying(Context context, List<QueueEntry> queue,
                                             QueueEntry currentlyPlaying) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        JSONArray array = new JSONArray();
        for (QueueEntry q : queue) {
            JSONObject obj = new JSONObject();
            obj.put("guid", q.guid != null ? q.guid : "");
            obj.put("downloadUrl", q.downloadUrl != null ? q.downloadUrl : "");
            array.put(obj);
        }
        SharedPreferences.Editor edit = prefs.edit()
                .putString(KEY_QUEUE, array.toString());
        if (currentlyPlaying != null) {
            JSONObject obj = new JSONObject();
            obj.put("guid", currentlyPlaying.guid != null ? currentlyPlaying.guid : "");
            obj.put("downloadUrl", currentlyPlaying.downloadUrl != null
                    ? currentlyPlaying.downloadUrl : "");
            edit.putString(KEY_CURRENTLY_PLAYING, obj.toString());
        } else {
            edit.remove(KEY_CURRENTLY_PLAYING);
        }
        edit.apply();
    }

    public static List<QueueEntry> loadQueue(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_QUEUE, null);
        if (json == null) {
            return Collections.emptyList();
        }
        List<QueueEntry> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                QueueEntry q = new QueueEntry();
                q.guid = obj.optString("guid", "");
                q.downloadUrl = obj.optString("downloadUrl", "");
                out.add(q);
            }
        } catch (Exception ignored) {
            // intentionally ignored
        }
        return out;
    }

    /** Stash the list of PaFeeds (with per-feed prefs) for the subscribe worker. */
    static void savePendingFeeds(Context context, List<PaFeed> feeds) throws Exception {
        JSONArray array = new JSONArray();
        for (PaFeed f : feeds) {
            JSONObject obj = new JSONObject();
            obj.put("url",            f.url != null ? f.url : "");
            obj.put("title",          f.title != null ? f.title : "");
            obj.put("playbackSpeed",  f.playbackSpeed);
            obj.put("skipIntroSec",   f.skipIntroSec);
            obj.put("skipOutroSec",   f.skipOutroSec);
            obj.put("category",       f.category != null ? f.category : "");
            array.put(obj);
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_PENDING_FEEDS, array.toString()).apply();
    }

    public static List<PaFeed> loadPendingFeeds(Context context) {
        String json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PENDING_FEEDS, null);
        if (json == null) {
            return Collections.emptyList();
        }
        List<PaFeed> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                PaFeed f = new PaFeed();
                f.url           = obj.optString("url", "");
                f.title         = obj.optString("title", "");
                f.playbackSpeed = (float) obj.optDouble("playbackSpeed", 0.0);
                f.skipIntroSec  = obj.optInt("skipIntroSec", 0);
                f.skipOutroSec  = obj.optInt("skipOutroSec", 0);
                f.category      = obj.optString("category", "");
                out.add(f);
            }
        } catch (Exception ignored) {
            // intentionally ignored
        }
        return out;
    }

    public static void clearPendingFeeds(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_PENDING_FEEDS).apply();
    }

    public static QueueEntry loadCurrentlyPlaying(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_CURRENTLY_PLAYING, null);
        if (json == null) {
            return null;
        }
        try {
            JSONObject obj = new JSONObject(json);
            QueueEntry q = new QueueEntry();
            q.guid = obj.optString("guid", "");
            q.downloadUrl = obj.optString("downloadUrl", "");
            return q;
        } catch (Exception ignored) {
            return null;
        }
    }
}
