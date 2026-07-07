package de.danoeh.antennapod.storage.importexport;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import de.danoeh.antennapod.model.feed.Bookmark;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.SynchronizationCredentials;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

/**
 * Writes a PortCast v0.1.0 document (portcast.org) describing the user's library.
 *
 * <p>The PortCast protocol is a JSON interchange format for podcast data
 * (subscriptions, episode state, queue, preferences). This exporter is the
 * TrimPlayer reference producer. Anything that doesn't map cleanly onto a spec
 * field lives under reverse-DNS "com.trimplayer.*" extension namespaces so the
 * data round-trips when imported back into TrimPlayer.
 *
 * <p>Off-main-thread: DBReader calls hit SQLite.
 */
public class PortcastExporter {

    private static final String TAG = "PortcastExporter";
    private static final String SPEC_VERSION = "0.1.0";
    private static final String GENERATOR_NAME = "TrimPlayer";
    private static final String GENERATOR_URL = "https://trimplayer.com";
    private static final String DEFAULT_OWNER_NAME = "TrimPlayer User";

    /** Reverse-DNS extension namespaces (per spec §Extensions). */
    /** Per-episode mid-episode bookmarks; package-visible so the importer parses the same key. */
    static final String EXT_EPISODE_BOOKMARKS = "com.trimplayer.bookmarks";
    private static final String EXT_FEED_SKIPS = "com.trimplayer.skips";
    private static final String EXT_FEED_SKIP_SILENCE = "com.trimplayer.feedSkipSilence";
    private static final String EXT_FEED_VOLUME_ADAPTION = "com.trimplayer.volumeAdaption";
    private static final String EXT_FEED_AUTO_DELETE = "com.trimplayer.autoDeleteAction";
    private static final String EXT_FEED_NEW_EPISODES = "com.trimplayer.newEpisodesAction";

    public static void writeDocument(@NonNull Writer writer,
                                     @NonNull String generatorVersion) throws IOException {
        Log.d(TAG, "Starting PortCast export");

        List<Feed> feeds = DBReader.getFeedList();
        List<FeedItem> allEpisodes = DBReader.getEpisodes(0, Integer.MAX_VALUE,
                FeedItemFilter.unfiltered(), SortOrder.DATE_NEW_OLD);
        List<FeedItem> queue = DBReader.getQueue();
        // FeedItem has no public tag getter, so fetch favorite IDs as a side query.
        Set<Long> favoriteIds = new HashSet<>();
        for (FeedItem fav : DBReader.getEpisodes(0, Integer.MAX_VALUE,
                new FeedItemFilter(FeedItemFilter.IS_FAVORITE), SortOrder.DATE_NEW_OLD)) {
            favoriteIds.add(fav.getId());
        }
        Map<Long, List<Bookmark>> bookmarksByItemId = new HashMap<>();
        for (Bookmark bookmark : DBReader.getAllBookmarks()) {
            List<Bookmark> forItem = bookmarksByItemId.get(bookmark.getFeedItemId());
            if (forItem == null) {
                forItem = new ArrayList<>();
                bookmarksByItemId.put(bookmark.getFeedItemId(), forItem);
            }
            forItem.add(bookmark);
        }
        GlobalPrefs globals = new GlobalPrefs(
                UserPreferences.getPlaybackSpeed(),
                UserPreferences.getFastForwardSecs(),
                UserPreferences.getRewindSecs(),
                UserPreferences.isSkipSilence());
        String gpodder = SynchronizationCredentials.getUsername();
        String ownerDisplayName = (gpodder != null && !gpodder.isEmpty())
                ? gpodder : DEFAULT_OWNER_NAME;

        JSONObject root = buildDocument(feeds, allEpisodes, queue, favoriteIds,
                bookmarksByItemId, ownerDisplayName, globals, generatorVersion);

        // org.json's compact toString is fine; consumers normalize on parse.
        // Pretty-printing inflates the file ~30% for no functional gain.
        writer.write(root.toString());
        Log.d(TAG, "Finished PortCast export: "
                + feeds.size() + " feeds, "
                + allEpisodes.size() + " episodes, "
                + queue.size() + " queued");
    }

    /** Convenience overload without bookmarks, kept for tests of unrelated sections. */
    static JSONObject buildDocument(@NonNull List<Feed> feeds,
                                    @NonNull List<FeedItem> allEpisodes,
                                    @NonNull List<FeedItem> queue,
                                    @NonNull Set<Long> favoriteIds,
                                    @NonNull String ownerDisplayName,
                                    @NonNull GlobalPrefs globals,
                                    @NonNull String generatorVersion) throws IOException {
        return buildDocument(feeds, allEpisodes, queue, favoriteIds, new HashMap<>(),
                ownerDisplayName, globals, generatorVersion);
    }

    /** Pure-Java entry point used by writeDocument and tests. No Android dependencies. */
    static JSONObject buildDocument(@NonNull List<Feed> feeds,
                                    @NonNull List<FeedItem> allEpisodes,
                                    @NonNull List<FeedItem> queue,
                                    @NonNull Set<Long> favoriteIds,
                                    @NonNull Map<Long, List<Bookmark>> bookmarksByItemId,
                                    @NonNull String ownerDisplayName,
                                    @NonNull GlobalPrefs globals,
                                    @NonNull String generatorVersion) throws IOException {
        // Group episodes by feedId so we can look up the parent Feed for each item
        // without re-querying. Items from non-subscribed feeds are dropped.
        Map<Long, Feed> subscribedById = new HashMap<>();
        for (Feed f : feeds) {
            if (f.getState() == Feed.STATE_SUBSCRIBED && hasIdentity(f)) {
                subscribedById.put(f.getId(), f);
            }
        }

        JSONObject root = new JSONObject();
        try {
            root.put("portcast", SPEC_VERSION);
            root.put("generatedAt", nowRfc3339());
            root.put("generator", buildGenerator(generatorVersion));
            root.put("owner", buildOwner(ownerDisplayName));
            root.put("subscriptions", buildSubscriptions(subscribedById.values()));
            root.put("episodes", buildEpisodes(allEpisodes, subscribedById, favoriteIds, bookmarksByItemId));
            root.put("queue", buildQueue(queue));
            root.put("preferences", buildPreferences(subscribedById.values(), globals));
        } catch (JSONException e) {
            throw new IOException("Failed to build PortCast document", e);
        }
        return root;
    }

    /** Plain-data wrapper so tests can pass globals without static UserPreferences state. */
    static final class GlobalPrefs {
        final float playbackRate;
        final int skipForwardSeconds;
        final int skipBackwardSeconds;
        final boolean trimSilence;

        GlobalPrefs(float playbackRate, int skipForwardSeconds,
                    int skipBackwardSeconds, boolean trimSilence) {
            this.playbackRate = playbackRate;
            this.skipForwardSeconds = skipForwardSeconds;
            this.skipBackwardSeconds = skipBackwardSeconds;
            this.trimSilence = trimSilence;
        }
    }

    // ── Sections ─────────────────────────────────────────────────────────────

    private static JSONObject buildGenerator(String version) throws JSONException {
        JSONObject g = new JSONObject();
        g.put("name", GENERATOR_NAME);
        if (version != null && !version.isEmpty()) {
            g.put("version", version);
        }
        g.put("url", GENERATOR_URL);
        return g;
    }

    private static JSONObject buildOwner(@NonNull String displayName) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("displayName", displayName);
        return o;
    }

    private static JSONArray buildSubscriptions(@NonNull Iterable<Feed> subscribed) throws JSONException {
        JSONArray arr = new JSONArray();
        String now = nowRfc3339();
        for (Feed feed : subscribed) {
            JSONObject s = new JSONObject();
            s.put("subscriptionId", "urn:trimplayer:feed:" + feed.getId());
            s.put("feedUrl", feed.getDownloadUrl());
            s.put("title", nonEmpty(feed.getTitle(), "Untitled"));
            putIfPresent(s, "author", feed.getAuthor());
            putIfPresent(s, "imageUrl", feed.getImageUrl());
            // subscribedAt: TrimPlayer doesn't record this; omit (optional field).

            FeedPreferences prefs = feed.getPreferences();
            if (prefs != null) {
                JSONArray tags = new JSONArray();
                for (String tag : prefs.getTags()) {
                    if (tag == null) {
                        continue;
                    }
                    String trimmed = tag.trim();
                    if (trimmed.isEmpty() || FeedPreferences.TAG_ROOT.equals(trimmed)) {
                        continue;
                    }
                    tags.put(trimmed);
                }
                if (tags.length() > 0) {
                    s.put("tags", tags);
                }
                s.put("notificationsEnabled", prefs.getShowEpisodeNotification());
            }

            s.put("updatedAt", now);
            arr.put(s);
        }
        return arr;
    }

    private static JSONArray buildEpisodes(@NonNull List<FeedItem> items,
                                           @NonNull Map<Long, Feed> subscribedById,
                                           @NonNull Set<Long> favoriteIds,
                                           @NonNull Map<Long, List<Bookmark>> bookmarksByItemId)
            throws JSONException {
        JSONArray arr = new JSONArray();
        String now = nowRfc3339();
        for (FeedItem item : items) {
            Feed feed = subscribedById.get(item.getFeedId());
            if (feed == null) continue; // not a subscribed feed → skip

            String guid = item.getItemIdentifier();
            FeedMedia media = item.getMedia();
            String enclosureUrl = media != null ? media.getDownloadUrl() : null;
            // Spec requires at least one of guid/enclosureUrl on every episode.
            if (isBlank(guid) && isBlank(enclosureUrl)) {
                continue;
            }

            JSONObject e = new JSONObject();
            e.put("episodeStateId", "urn:trimplayer:item:" + item.getId());

            JSONObject ref = new JSONObject();
            ref.put("feedUrl", feed.getDownloadUrl());
            e.put("subscriptionRef", ref);

            putIfPresent(e, "guid", guid);
            putIfPresent(e, "enclosureUrl", enclosureUrl);
            putIfPresent(e, "title", item.getTitle());
            if (item.getPubDate() != null) {
                e.put("publishedAt", formatRfc3339(item.getPubDate()));
            }

            // status / position / completion
            int positionMs = media != null ? media.getPosition() : 0;
            long lastPlayedMs = media != null ? media.getLastPlayedTimeStatistics() : 0;
            String status;
            if (item.isPlayed()) {
                status = "completed";
            } else if (positionMs > 0) {
                status = "in_progress";
            } else {
                status = "unplayed";
            }
            e.put("status", status);
            if ("in_progress".equals(status)) {
                // Spec MUST: positionSeconds is required when status is in_progress.
                e.put("positionSeconds", positionMs / 1000.0);
            }
            if (media != null && media.getDuration() > 0) {
                e.put("durationSeconds", media.getDuration() / 1000.0);
            }
            if ("completed".equals(status) && lastPlayedMs > 0) {
                e.put("completedAt", formatRfc3339(new Date(lastPlayedMs)));
            }
            if (lastPlayedMs > 0) {
                e.put("lastPlayedAt", formatRfc3339(new Date(lastPlayedMs)));
            }

            if (favoriteIds.contains(item.getId())) {
                e.put("starred", true);
            }

            List<Bookmark> bookmarks = bookmarksByItemId.get(item.getId());
            if (bookmarks != null && !bookmarks.isEmpty()) {
                JSONArray bookmarkArr = new JSONArray();
                for (Bookmark bookmark : bookmarks) {
                    JSONObject b = new JSONObject();
                    b.put("positionSeconds", bookmark.getPosition() / 1000.0);
                    putIfPresent(b, "note", bookmark.getNote());
                    if (bookmark.getCreatedAt() > 0) {
                        b.put("createdAt", formatRfc3339(new Date(bookmark.getCreatedAt())));
                    }
                    bookmarkArr.put(b);
                }
                JSONObject extensions = new JSONObject();
                extensions.put(EXT_EPISODE_BOOKMARKS, bookmarkArr);
                e.put("extensions", extensions);
            }

            e.put("updatedAt", now);
            arr.put(e);
        }
        return arr;
    }

    private static JSONArray buildQueue(@NonNull List<FeedItem> queue) throws JSONException {
        JSONArray arr = new JSONArray();
        int position = 1;
        for (FeedItem item : queue) {
            String guid = item.getItemIdentifier();
            FeedMedia media = item.getMedia();
            String enclosureUrl = media != null ? media.getDownloadUrl() : null;
            if (isBlank(guid) && isBlank(enclosureUrl)) {
                continue;
            }

            JSONObject q = new JSONObject();
            q.put("position", position++);
            JSONObject ref = new JSONObject();
            putIfPresent(ref, "guid", guid);
            putIfPresent(ref, "enclosureUrl", enclosureUrl);
            q.put("episodeRef", ref);
            q.put("source", "manual");
            arr.put(q);
        }
        return arr;
    }

    private static JSONObject buildPreferences(@NonNull Iterable<Feed> subscribed,
                                                @NonNull GlobalPrefs globals) throws JSONException {
        JSONObject prefs = new JSONObject();

        JSONObject global = new JSONObject();
        global.put("playbackRate", (double) globals.playbackRate);
        global.put("skipForwardSeconds", globals.skipForwardSeconds);
        global.put("skipBackwardSeconds", globals.skipBackwardSeconds);
        global.put("trimSilence", globals.trimSilence);
        prefs.put("global", global);

        JSONObject perFeed = new JSONObject();
        for (Feed feed : subscribed) {
            FeedPreferences fp = feed.getPreferences();
            if (fp == null) {
                continue;
            }
            JSONObject entry = new JSONObject();
            boolean any = false;
            if (fp.getFeedPlaybackSpeed() > 0
                    && fp.getFeedPlaybackSpeed() != FeedPreferences.SPEED_USE_GLOBAL) {
                entry.put("playbackRate", fp.getFeedPlaybackSpeed());
                any = true;
            }
            if (fp.getFeedSkipIntro() > 0) {
                entry.put("skipIntroSeconds", fp.getFeedSkipIntro());
                any = true;
            }
            if (fp.getFeedSkipEnding() > 0) {
                entry.put("skipOutroSeconds", fp.getFeedSkipEnding());
                any = true;
            }

            JSONObject extensions = buildFeedExtensions(fp);
            if (extensions.length() > 0) {
                entry.put("extensions", extensions);
                any = true;
            }
            if (any) {
                perFeed.put(feed.getDownloadUrl(), entry);
            }
        }
        if (perFeed.length() > 0) {
            prefs.put("perFeed", perFeed);
        }
        return prefs;
    }

    private static JSONObject buildFeedExtensions(@NonNull FeedPreferences fp) throws JSONException {
        JSONObject extensions = new JSONObject();

        // TrimPlayer-specific skip flags (segment overlay)
        JSONObject skips = new JSONObject();
        skips.put("trimSkipIntros", fp.isTrimSkipIntros());
        skips.put("trimSkipAds", fp.isTrimSkipAds());
        skips.put("trimSkipOutros", fp.isTrimSkipOutros());
        extensions.put(EXT_FEED_SKIPS, skips);

        if (fp.getFeedSkipSilence() != null) {
            JSONObject silence = new JSONObject();
            silence.put("mode", fp.getFeedSkipSilence().name());
            extensions.put(EXT_FEED_SKIP_SILENCE, silence);
        }
        if (fp.getVolumeAdaptionSetting() != null) {
            JSONObject va = new JSONObject();
            va.put("setting", fp.getVolumeAdaptionSetting().name());
            extensions.put(EXT_FEED_VOLUME_ADAPTION, va);
        }
        if (fp.getAutoDeleteAction() != null) {
            JSONObject ad = new JSONObject();
            ad.put("action", fp.getAutoDeleteAction().name());
            extensions.put(EXT_FEED_AUTO_DELETE, ad);
        }
        if (fp.getNewEpisodesAction() != null) {
            JSONObject ne = new JSONObject();
            ne.put("action", fp.getNewEpisodesAction().name());
            extensions.put(EXT_FEED_NEW_EPISODES, ne);
        }
        return extensions;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean hasIdentity(Feed feed) {
        return !isBlank(feed.getDownloadUrl());
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isEmpty();
    }

    private static String nonEmpty(@Nullable String s, String fallback) {
        return isBlank(s) ? fallback : s;
    }

    private static void putIfPresent(JSONObject o, String key, @Nullable String value) throws JSONException {
        if (!isBlank(value)) {
            o.put(key, value);
        }
    }

    private static String nowRfc3339() {
        return formatRfc3339(new Date());
    }

    /** RFC 3339 UTC: yyyy-MM-dd'T'HH:mm:ss'Z'. */
    private static String formatRfc3339(Date date) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(date);
    }
}
