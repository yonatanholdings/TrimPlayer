package de.danoeh.antennapod.storage.importexport;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.importexport.spotify.Resolution;
import de.danoeh.antennapod.storage.importexport.spotify.ResolverInput;
import de.danoeh.antennapod.storage.importexport.spotify.SpotifyShowResolver;

/**
 * Parses a PortCast v0.1.0 document (portcast.org) and applies it to the local
 * TrimPlayer library. Mirrors the structure of {@link PodcastAddictImporter}:
 *
 * <ol>
 *   <li>{@link #previewImport(Context, InputStream)} parses the JSON and walks
 *       the AP database to detect per-episode conflicts (we already have play
 *       data; the import would overwrite it).</li>
 *   <li>The UI lets the user resolve conflicts.</li>
 *   <li>{@link #executeImport(Context, ImportPreview)} stashes everything to
 *       SharedPreferences and enqueues {@link PortcastSubscribeWorker}, which
 *       subscribes feeds in the background and chains
 *       {@link PortcastStateWorker} to apply episode state + queue after the
 *       feed refresh materializes items.</li>
 * </ol>
 */
public class PortcastImporter {

    private static final String TAG = "PortcastImporter";

    static final String PREFS_NAME = "portcast_import";
    static final String KEY_PENDING_FEEDS = "import_pending_feeds";
    static final String KEY_EPISODE_STATES = "import_episode_states";
    static final String KEY_QUEUE = "import_queue";
    static final String KEY_GLOBAL_PREFS = "import_global_prefs";

    /** A subscription from the PortCast file. */
    public static class PortFeed {
        public String feedUrl;
        public String title;
        /** Stable, source-independent identifier from the PortCast document
         *  (subscriptionId field). Empty when the document didn't carry one.
         *  Used as the primary dedupe key on re-import via
         *  {@link SubscriptionIdIndex}, since the resolved {@link #feedUrl}
         *  can vary across exports for the same logical show. */
        public String subscriptionId = "";
        /** Optional author / publisher name from the PortCast document. Used
         *  by the resolver (PodcastIndex fuzzy match) and by the result
         *  screen when the show is unresolvable. */
        public String author = "";
        /** Optional cover image URL. Only used for unresolvable rows in the
         *  result screen. */
        public String imageUrl = "";
        /** Raw {@code platformRefs} URNs (e.g. "spotify:show:&lt;id&gt;"). When
         *  {@link #feedUrl} is empty, the importer calls
         *  {@link de.danoeh.antennapod.storage.importexport.spotify.SpotifyShowResolver
         *  SpotifyShowResolver} to map these to an RSS feed URL. */
        public List<String> platformRefs = new ArrayList<>();
        /** True when {@link #feedUrl} was empty at parse time and the
         *  importer needs to resolve the show from {@link #platformRefs}
         *  before the subscribe phase. Set during parsing; flipped back to
         *  false once resolution succeeds. */
        public boolean needsResolution;
        public List<String> tags = new ArrayList<>();
        /** 0 = no override (inherit global). */
        public float playbackSpeed;
        public int skipIntroSec;
        public int skipOutroSec;
        /** Raw `com.trimplayer.*` extensions, or null. Worker decides which to apply. */
        @Nullable public JSONObject extensions;
        /** Per-feed "show episode notifications" toggle from the subscription
         *  object. null = absent in the document (inherit the default). */
        @Nullable public Boolean notificationsEnabled;
    }

    /** Per-episode state extracted from the PortCast file. */
    public static class EpisodeState {
        public String guid;
        public String enclosureUrl;
        public String feedUrl; // for resolving subscriptionRef
        /** Episode title. Always carried; for Spotify-sourced episodes (no
         *  guid/enclosureUrl) it's the only join key, matched against the
         *  materialized feed's items in {@link PortcastStateWorker}. */
        public String title = "";
        /** Transient: the {@code spotify:show:<id>} URN from
         *  {@code subscriptionRef.platformRefs}, set during parsing when the
         *  episode carries no feedUrl. {@link #previewImport} maps it to a
         *  resolved {@link #feedUrl} via the resolved subscriptions, then it's
         *  no longer used (not persisted to SharedPreferences). */
        public String showRef = "";
        /** "unplayed" | "in_progress" | "completed" | "archived" */
        public String status;
        public int positionMs;
        public boolean starred;
        public long durationMs;
        public long lastPlayedMs;
    }

    /** Queue entry; resolved against materialized DB items after the feed refresh. */
    public static class QueueEntry {
        public String guid;
        public String enclosureUrl;
    }

    /** Global playback prefs to apply. */
    public static class GlobalPrefs {
        public float playbackRate;          // 0 = absent
        public int skipForwardSeconds;      // -1 = absent
        public int skipBackwardSeconds;     // -1 = absent
        @Nullable public Boolean trimSilence;
    }

    /**
     * Conflict shape that matches the PA importer so the existing
     * {@code ConflictAdapter} UI can render PortCast conflicts unchanged. The
     * field {@code usePodcastAddict} carries "use incoming" semantics for
     * either pipeline.
     */
    // Intentionally identical shape to PodcastAddictImporter.ConflictEpisode
    // so we can hand both to the same UI without forking the adapter.
    public static class ConflictEpisode {
        public EpisodeState incomingState;
        public String episodeTitle;
        public String feedTitle;
        public String apStateDescription;
        public boolean useIncoming = true;
    }

    public static class ImportPreview {
        public List<PortFeed> feeds = new ArrayList<>();
        public List<EpisodeState> nonConflictingStates = new ArrayList<>();
        public List<ConflictEpisode> conflicts = new ArrayList<>();
        public List<QueueEntry> queue = new ArrayList<>();
        public GlobalPrefs globalPrefs;
        /** Subscriptions that the importer couldn't map to a feed URL —
         *  i.e. Spotify-sourced rows whose {@code spotify:show:&lt;id&gt;}
         *  didn't resolve through any resolver in
         *  {@link de.danoeh.antennapod.storage.importexport.spotify.SpotifyShowResolver
         *  SpotifyShowResolver}'s chain. Surfaced in the result screen as a
         *  manual-search list. */
        public List<PortFeed> unresolvableFeeds = new ArrayList<>();
    }

    /** Optional reporter for the long phases of {@link #previewImport}. The
     *  current implementation only fires {@link #onResolverProgress} during
     *  the resolver loop; other phases are fast enough to not warrant a
     *  callback yet. Methods fire on worker threads — UI callers must
     *  marshal to main themselves. */
    public interface ProgressCallback {
        void onResolverProgress(int resolved, int total);
    }

    /** Parse the file and build a preview. Must be called off the main thread.
     *  Convenience overload with no progress callback. */
    public static ImportPreview previewImport(Context context, InputStream stream) throws Exception {
        return previewImport(context, stream, null);
    }

    /** Parse the file and build a preview. Must be called off the main thread. */
    public static ImportPreview previewImport(Context context, InputStream stream,
                                              @Nullable ProgressCallback progress) throws Exception {
        JSONObject root = readJson(stream);
        String version = root.optString("portcast", "");
        if (version.isEmpty()) {
            throw new IllegalArgumentException("Not a PortCast document (missing 'portcast' field)");
        }
        Log.d(TAG, "Parsing PortCast " + version);

        // Build the index of existing AP episodes once so per-state conflict
        // detection is O(1) instead of re-scanning the DB for every episode.
        Map<String, FeedItem> apByGuid = new HashMap<>();
        Map<String, FeedItem> apByUrl = new HashMap<>();
        for (Feed feed : DBReader.getFeedList()) {
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

        ImportPreview preview = new ImportPreview();
        // Map feedUrl → human title so conflict rows can label episodes by show.
        Map<String, String> feedTitleByUrl = new HashMap<>();
        JSONObject perFeedPrefs = root.optJSONObject("preferences") != null
                ? root.optJSONObject("preferences").optJSONObject("perFeed") : null;

        JSONArray subs = root.optJSONArray("subscriptions");
        if (subs != null) {
            for (int i = 0; i < subs.length(); i++) {
                PortFeed pf = parseSubscription(subs.getJSONObject(i), perFeedPrefs);
                if (pf == null) {
                    continue;
                }
                preview.feeds.add(pf);
            }
        }

        // Resolve Spotify-sourced subscriptions (those parsed with
        // needsResolution=true) to feed URLs. Two-stage: first short-circuit
        // any subscriptionIds we've already imported via the
        // SubscriptionIdIndex; then run the resolver chain on the rest.
        resolveFeeds(context, preview, progress);

        // Populate the feedTitleByUrl map AFTER resolution so episode rows
        // for newly-resolved Spotify feeds get the right label. Also build a
        // Spotify-show-id → resolved feedUrl map so episodes carrying only a
        // subscriptionRef.platformRefs (spotify:show:<id>) can be scoped to
        // the feed they'll match against by title.
        Map<String, String> feedUrlByShowId = new HashMap<>();
        for (PortFeed pf : preview.feeds) {
            if (pf.feedUrl != null && !pf.feedUrl.isEmpty()) {
                feedTitleByUrl.put(pf.feedUrl, pf.title);
                String showId = spotifyShowIdFrom(pf.platformRefs);
                if (showId != null) {
                    feedUrlByShowId.put(showId, pf.feedUrl);
                }
            }
        }

        JSONArray episodes = root.optJSONArray("episodes");
        Log.d(TAG, "Document has 'episodes' array: "
                + (episodes != null ? "yes, length=" + episodes.length() : "no / null"));
        int kept = 0;
        int droppedByParser = 0;
        int droppedUnresolvedShow = 0;
        if (episodes != null) {
            for (int i = 0; i < episodes.length(); i++) {
                JSONObject epJson = episodes.getJSONObject(i);
                EpisodeState state = parseEpisode(epJson);
                if (state == null) {
                    droppedByParser++;
                    continue;
                }
                // Spotify-sourced episode: map its show ref to the resolved
                // feedUrl so the state worker can join by feed+title. If the
                // show didn't resolve to a feed (unsubscribed), the episode is
                // unmatchable — drop it rather than persist dead state.
                if (state.feedUrl.isEmpty() && !state.showRef.isEmpty()) {
                    String showId = spotifyShowIdFrom(
                            Collections.singletonList(state.showRef));
                    String resolved = showId != null ? feedUrlByShowId.get(showId) : null;
                    if (resolved != null) {
                        state.feedUrl = resolved;
                    }
                }
                if (state.guid.isEmpty() && state.enclosureUrl.isEmpty()
                        && state.feedUrl.isEmpty()) {
                    droppedUnresolvedShow++;
                    continue;
                }
                kept++;
                FeedItem apItem = findApItem(state, apByGuid, apByUrl);
                if (apItem != null && hasApPlayData(apItem)) {
                    ConflictEpisode conflict = new ConflictEpisode();
                    conflict.incomingState = state;
                    conflict.episodeTitle = stateTitle(epJson, state);
                    conflict.feedTitle = feedTitleByUrl.getOrDefault(state.feedUrl, "Unknown Feed");
                    conflict.apStateDescription = describeApState(apItem);
                    // Default to whichever side has *more* progress, so a user
                    // who taps straight through the conflict screen never
                    // silently rewinds a position or un-completes an episode.
                    int localPos = apItem.getMedia() != null ? apItem.getMedia().getPosition() : 0;
                    conflict.useIncoming = preferIncomingByProgress(
                            state.status, state.positionMs, apItem.isPlayed(), localPos);
                    preview.conflicts.add(conflict);
                } else {
                    preview.nonConflictingStates.add(state);
                }
            }
            Log.d(TAG, "Episode parse summary: kept=" + kept
                    + " droppedByParser=" + droppedByParser
                    + " droppedUnresolvedShow=" + droppedUnresolvedShow);
        }

        JSONArray queue = root.optJSONArray("queue");
        if (queue != null) {
            // Honor the spec's `position` ordering rather than file ordering.
            List<JSONObject> sorted = new ArrayList<>(queue.length());
            for (int i = 0; i < queue.length(); i++) {
                sorted.add(queue.getJSONObject(i));
            }
            sorted.sort((a, b) -> Integer.compare(a.optInt("position", Integer.MAX_VALUE),
                    b.optInt("position", Integer.MAX_VALUE)));
            for (JSONObject q : sorted) {
                QueueEntry entry = parseQueueEntry(q);
                if (entry != null) {
                    preview.queue.add(entry);
                }
            }
        }

        JSONObject prefs = root.optJSONObject("preferences");
        if (prefs != null) {
            JSONObject global = prefs.optJSONObject("global");
            if (global != null) {
                GlobalPrefs gp = new GlobalPrefs();
                gp.playbackRate = (float) global.optDouble("playbackRate", 0);
                gp.skipForwardSeconds = global.optInt("skipForwardSeconds", -1);
                gp.skipBackwardSeconds = global.optInt("skipBackwardSeconds", -1);
                if (global.has("trimSilence")) {
                    gp.trimSilence = global.optBoolean("trimSilence", false);
                }
                preview.globalPrefs = gp;
            }
        }

        Log.d(TAG, "Parsed: " + preview.feeds.size() + " feeds, "
                + (preview.nonConflictingStates.size() + preview.conflicts.size()) + " states ("
                + preview.conflicts.size() + " conflicts), " + preview.queue.size() + " queue");
        return preview;
    }

    /** Stash the preview and enqueue the subscribe worker. Must be called off the main thread. */
    public static void executeImport(Context context, ImportPreview preview) throws Exception {
        List<EpisodeState> states = new ArrayList<>(preview.nonConflictingStates);
        for (ConflictEpisode c : preview.conflicts) {
            if (c.useIncoming) {
                states.add(c.incomingState);
            }
        }
        savePendingFeeds(context, preview.feeds);
        saveEpisodeStates(context, states);
        saveQueue(context, preview.queue);
        saveGlobalPrefs(context, preview.globalPrefs);
        PortcastSubscribeWorker.enqueue(context);
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    @Nullable
    static PortFeed parseSubscription(JSONObject sub, @Nullable JSONObject perFeedPrefs) {
        String feedUrl = sub.optString("feedUrl", "");
        JSONArray platformRefs = sub.optJSONArray("platformRefs");
        boolean hasPlatformRefs = platformRefs != null && platformRefs.length() > 0;
        // Spec allows feedUrl OR podcastGuid OR platformRefs. We accept the
        // first and last; podcastGuid-only subs remain unsupported (rare
        // enough that adding GUID-resolution isn't worth M2's complexity).
        if (TextUtils.isEmpty(feedUrl) && !hasPlatformRefs) {
            return null;
        }

        PortFeed pf = new PortFeed();
        pf.subscriptionId = sub.optString("subscriptionId", "");
        pf.feedUrl = feedUrl;
        pf.title = sub.optString("title", "");
        pf.author = sub.optString("author", "");
        pf.imageUrl = sub.optString("imageUrl", "");
        if (platformRefs != null) {
            for (int i = 0; i < platformRefs.length(); i++) {
                String ref = platformRefs.optString(i, "");
                if (!ref.isEmpty()) {
                    pf.platformRefs.add(ref);
                }
            }
        }
        pf.needsResolution = TextUtils.isEmpty(feedUrl);

        JSONArray tags = sub.optJSONArray("tags");
        if (tags != null) {
            for (int i = 0; i < tags.length(); i++) {
                String tag = tags.optString(i, "");
                if (!tag.isEmpty()) {
                    pf.tags.add(tag);
                }
            }
        }
        if (sub.has("notificationsEnabled")) {
            pf.notificationsEnabled = sub.optBoolean("notificationsEnabled", true);
        }
        // Pull per-feed overrides keyed by the same feedUrl. Skipped when
        // we don't have a feedUrl yet — Spotify-sourced docs have no
        // per-feed prefs block anyway.
        if (!TextUtils.isEmpty(feedUrl) && perFeedPrefs != null && perFeedPrefs.has(feedUrl)) {
            JSONObject perFeed = perFeedPrefs.optJSONObject(feedUrl);
            if (perFeed != null) {
                pf.playbackSpeed = (float) perFeed.optDouble("playbackRate", 0);
                pf.skipIntroSec = perFeed.optInt("skipIntroSeconds", 0);
                pf.skipOutroSec = perFeed.optInt("skipOutroSeconds", 0);
                pf.extensions = perFeed.optJSONObject("extensions");
            }
        }
        return pf;
    }

    /** Returns the first {@code spotify:show:<id>} URN in a JSON platformRefs
     *  array, or "" if none. Used to scope a Spotify-sourced episode (which
     *  carries no feedUrl) to its show so the importer can map it to the
     *  resolved feed URL. */
    static String firstShowRef(@Nullable JSONArray platformRefs) {
        if (platformRefs == null) return "";
        for (int i = 0; i < platformRefs.length(); i++) {
            String ref = platformRefs.optString(i, "");
            if (ref.startsWith("spotify:show:")) return ref;
        }
        return "";
    }

    /** Normalizes an episode title for cross-source matching: lowercased,
     *  trimmed, internal whitespace collapsed, and surrounding punctuation
     *  stripped. Spotify and RSS titles for the same episode are normally
     *  identical; this just absorbs casing/whitespace drift. Package-static so
     *  {@link PortcastStateWorker} shares the exact rule. */
    static String normalizeTitle(@Nullable String title) {
        if (title == null) return "";
        String s = title.toLowerCase(Locale.US).trim();
        s = s.replaceAll("\\s+", " ");
        s = s.replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "");
        return s;
    }

    /** Loose feed-URL key so a resolved feed URL still matches the URL AP
     *  stored after subscribe/refresh — scheme, {@code www.}, and trailing
     *  slash drift are common between the two. Package-static so
     *  {@link PortcastStateWorker} shares the exact rule. */
    static String normalizeFeedUrl(@Nullable String url) {
        if (url == null) return "";
        String s = url.trim().toLowerCase(Locale.US);
        s = s.replaceFirst("^https?://", "");
        s = s.replaceFirst("^www\\.", "");
        s = s.replaceAll("/+$", "");
        return s;
    }

    /**
     * The feed+title join used by {@link PortcastStateWorker} to attach a
     * Spotify-sourced episode state (which has no guid/enclosureUrl) to a
     * materialized item. Looks up {@code indexByFeedKey} with the normalized
     * feed URL, then the normalized title, and {@code remove}s the hit so two
     * states can't claim the same item and a retry re-evaluates cleanly.
     *
     * <p>Generic over the item handle so the matching is unit-testable without
     * an Android {@code FeedItem}. The worker passes the real items; tests pass
     * lightweight stand-ins. Returns null when {@code feedUrl}/{@code title} is
     * blank or nothing matches.
     */
    @Nullable
    public static <T> T matchByFeedAndTitle(@Nullable String feedUrl, @Nullable String title,
            Map<String, Map<String, T>> indexByFeedKey) {
        if (feedUrl == null || feedUrl.isEmpty() || title == null || title.isEmpty()) {
            return null;
        }
        Map<String, T> titleMap = indexByFeedKey.get(normalizeFeedUrl(feedUrl));
        if (titleMap == null) {
            return null;
        }
        return titleMap.remove(normalizeTitle(title));
    }

    /** Extracts the Spotify show ID from a list of platformRefs URNs.
     *  Returns null if no spotify:show: ref is present. */
    @Nullable
    static String spotifyShowIdFrom(List<String> platformRefs) {
        if (platformRefs == null) {
            return null;
        }
        final String prefix = "spotify:show:";
        for (String ref : platformRefs) {
            if (ref != null && ref.startsWith(prefix)) {
                return ref.substring(prefix.length());
            }
        }
        return null;
    }

    /**
     * For each {@link PortFeed} parsed with {@link PortFeed#needsResolution}
     * true (i.e. Spotify-sourced, no feedUrl in the document), either:
     *
     * <ul>
     *   <li>set {@code feedUrl} from {@link SubscriptionIdIndex} when we've
     *       imported this subscriptionId before (no resolver call), or</li>
     *   <li>run the resolver chain via {@link SpotifyShowResolver} and use
     *       its {@link Resolution.Resolved#feedUrl}, or</li>
     *   <li>move the row to {@link ImportPreview#unresolvableFeeds} when no
     *       resolver returned a hit within budget.</li>
     * </ul>
     *
     * <p>Resolved rows stay in {@link ImportPreview#feeds}; unresolvable rows
     * are removed from it and surface in the result screen for manual
     * search instead.
     */
    static void resolveFeeds(Context context, ImportPreview preview, @Nullable ProgressCallback progress) {
        if (preview.feeds.isEmpty()) {
            return;
        }

        // Phase 1: SubscriptionIdIndex fast-path. Skips the resolver call
        // entirely for subscriptions we've imported before.
        List<PortFeed> stillNeedResolution = new ArrayList<>();
        for (PortFeed pf : preview.feeds) {
            if (!pf.needsResolution) {
                continue;
            }
            String cached = SubscriptionIdIndex.lookup(context, pf.subscriptionId);
            if (cached != null && !cached.isEmpty()) {
                pf.feedUrl = cached;
                pf.needsResolution = false;
            } else {
                stillNeedResolution.add(pf);
            }
        }
        if (stillNeedResolution.isEmpty()) {
            return;
        }

        // Phase 2: run the resolver chain. Inputs and outputs are
        // index-aligned; resolver order is irrelevant for correctness.
        List<ResolverInput> inputs = new ArrayList<>(stillNeedResolution.size());
        for (PortFeed pf : stillNeedResolution) {
            inputs.add(new ResolverInput(spotifyShowIdFrom(pf.platformRefs), pf.title, pf.author));
        }
        Log.d(TAG, "Resolving " + inputs.size() + " Spotify-sourced subscriptions");
        SpotifyShowResolver.ProgressCallback resolverProgress = progress == null
                ? null
                : (done, total) -> progress.onResolverProgress(done, total);
        List<Resolution> resolutions = new SpotifyShowResolver().resolveAll(inputs, resolverProgress);

        List<PortFeed> toRemove = new ArrayList<>();
        for (int i = 0; i < stillNeedResolution.size(); i++) {
            PortFeed pf = stillNeedResolution.get(i);
            Resolution r = resolutions.get(i);
            if (r instanceof Resolution.Resolved) {
                pf.feedUrl = ((Resolution.Resolved) r).feedUrl;
                pf.needsResolution = false;
            } else {
                preview.unresolvableFeeds.add(pf);
                toRemove.add(pf);
            }
        }
        if (!toRemove.isEmpty()) {
            preview.feeds.removeAll(toRemove);
            Log.d(TAG, "Unresolvable Spotify subscriptions: " + toRemove.size());
        }
    }

    @Nullable
    static EpisodeState parseEpisode(JSONObject ep) {
        String guid = ep.optString("guid", "");
        String enclosureUrl = ep.optString("enclosureUrl", "");
        String title = ep.optString("title", "");
        JSONObject ref = ep.optJSONObject("subscriptionRef");
        String feedUrl = ref != null ? ref.optString("feedUrl", "") : "";
        // Spotify-sourced episodes carry no guid/enclosureUrl — only a title
        // and a show reference (subscriptionRef.platformRefs = spotify:show:id).
        // Accept them when there's a title plus something to scope it to (a
        // feedUrl now, or a show ref the caller will resolve to a feedUrl).
        // The actual join to a materialized FeedItem happens by feed+title in
        // PortcastStateWorker. RSS-sourced episodes still take the guid/url path.
        String showRef = feedUrl.isEmpty() && ref != null
                ? firstShowRef(ref.optJSONArray("platformRefs")) : "";
        boolean hasIdentity = !guid.isEmpty() || !enclosureUrl.isEmpty()
                || (!title.isEmpty() && (!feedUrl.isEmpty() || !showRef.isEmpty()));
        if (!hasIdentity) {
            return null;
        }

        EpisodeState state = new EpisodeState();
        state.guid = guid;
        state.enclosureUrl = enclosureUrl;
        state.title = title;
        state.feedUrl = feedUrl;
        state.showRef = showRef;
        state.status = ep.optString("status", "unplayed");
        state.positionMs = (int) Math.round(ep.optDouble("positionSeconds", 0) * 1000);
        state.starred = ep.optBoolean("starred", false);
        state.durationMs = Math.round(ep.optDouble("durationSeconds", 0) * 1000);
        // Prefer completedAt over lastPlayedAt for chart attribution, since
        // lastPlayedAt may have been touched by a no-op tap on the episode.
        long completedMs = parseRfc3339(ep.optString("completedAt", ""));
        long lastPlayedMs = parseRfc3339(ep.optString("lastPlayedAt", ""));
        state.lastPlayedMs = completedMs > 0 ? completedMs : lastPlayedMs;
        return state;
    }

    @Nullable
    static QueueEntry parseQueueEntry(JSONObject q) {
        JSONObject ref = q.optJSONObject("episodeRef");
        if (ref == null) {
            return null;
        }
        String guid = ref.optString("guid", "");
        String url = ref.optString("enclosureUrl", "");
        if (guid.isEmpty() && url.isEmpty()) {
            return null;
        }
        QueueEntry entry = new QueueEntry();
        entry.guid = guid;
        entry.enclosureUrl = url;
        return entry;
    }

    private static String stateTitle(JSONObject episodeJson, EpisodeState state) {
        String title = episodeJson.optString("title", "");
        if (!title.isEmpty()) {
            return title;
        }
        if (!state.enclosureUrl.isEmpty()) {
            return state.enclosureUrl;
        }
        return state.guid;
    }

    // ── Conflict detection ───────────────────────────────────────────────────

    @Nullable
    private static FeedItem findApItem(EpisodeState state,
                                       Map<String, FeedItem> apByGuid,
                                       Map<String, FeedItem> apByUrl) {
        if (!state.guid.isEmpty() && apByGuid.containsKey(state.guid)) {
            return apByGuid.get(state.guid);
        }
        if (!state.enclosureUrl.isEmpty() && apByUrl.containsKey(state.enclosureUrl)) {
            return apByUrl.get(state.enclosureUrl);
        }
        return null;
    }

    /**
     * Default conflict resolution: prefer whichever side represents <em>more</em>
     * listening progress. Progress ranks as {@code unplayed < in-progress(position)
     * < completed/archived}. Returns true when the incoming (imported) state should
     * win the conflict by default; false to keep the local state. This is only the
     * pre-selected default — the user can still flip any row in the conflict screen.
     *
     * <p>Pure (operates on primitives, no Android types) so it is unit-testable
     * without a {@code FeedItem}. The caller extracts the local position/played
     * flag from the matched item.
     *
     * @param incomingStatus    PortCast status of the imported episode
     *                          ("unplayed" | "in_progress" | "completed" | "archived")
     * @param incomingPositionMs imported resume position in ms
     * @param localPlayed       whether the local item is marked played/completed
     * @param localPositionMs   local resume position in ms
     */
    static boolean preferIncomingByProgress(String incomingStatus, int incomingPositionMs,
                                            boolean localPlayed, int localPositionMs) {
        boolean incomingCompleted = "completed".equals(incomingStatus)
                || "archived".equals(incomingStatus);
        if (incomingCompleted && !localPlayed) {
            return true;   // incoming finished it; local hadn't — incoming is further
        }
        if (localPlayed && !incomingCompleted) {
            return false;  // local finished it; don't un-complete from a partial import
        }
        if (localPlayed) {
            return false;  // both completed — nothing to gain, keep local untouched
        }
        // Neither completed: furthest resume position wins. Ties keep local.
        return incomingPositionMs > localPositionMs;
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
        if (item.isPlayed()) return "Played";
        if (item.getMedia() != null && item.getMedia().getPosition() > 0) {
            int posSec = item.getMedia().getPosition() / 1000;
            return String.format(Locale.US, "In progress at %d:%02d", posSec / 60, posSec % 60);
        }
        return "Has history";
    }

    // ── SharedPreferences stash ──────────────────────────────────────────────

    public static void savePendingFeeds(Context context, List<PortFeed> feeds) throws Exception {
        JSONArray arr = new JSONArray();
        for (PortFeed pf : feeds) {
            JSONObject o = new JSONObject();
            o.put("feedUrl", pf.feedUrl);
            o.put("title", pf.title != null ? pf.title : "");
            o.put("subscriptionId", pf.subscriptionId != null ? pf.subscriptionId : "");
            o.put("playbackSpeed", pf.playbackSpeed);
            o.put("skipIntroSec", pf.skipIntroSec);
            o.put("skipOutroSec", pf.skipOutroSec);
            if (!pf.tags.isEmpty()) {
                o.put("tags", new JSONArray(pf.tags));
            }
            if (pf.extensions != null) {
                o.put("extensions", pf.extensions);
            }
            if (pf.notificationsEnabled != null) {
                o.put("notificationsEnabled", (boolean) pf.notificationsEnabled);
            }
            arr.put(o);
        }
        prefs(context).edit().putString(KEY_PENDING_FEEDS, arr.toString()).apply();
    }

    public static List<PortFeed> loadPendingFeeds(Context context) {
        String json = prefs(context).getString(KEY_PENDING_FEEDS, null);
        if (json == null) {
            return Collections.emptyList();
        }
        List<PortFeed> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                PortFeed pf = new PortFeed();
                pf.feedUrl = o.optString("feedUrl", "");
                pf.title = o.optString("title", "");
                pf.subscriptionId = o.optString("subscriptionId", "");
                pf.playbackSpeed = (float) o.optDouble("playbackSpeed", 0);
                pf.skipIntroSec = o.optInt("skipIntroSec", 0);
                pf.skipOutroSec = o.optInt("skipOutroSec", 0);
                JSONArray tags = o.optJSONArray("tags");
                if (tags != null) {
                    for (int j = 0; j < tags.length(); j++) {
                        pf.tags.add(tags.optString(j, ""));
                    }
                }
                pf.extensions = o.optJSONObject("extensions");
                if (o.has("notificationsEnabled")) {
                    pf.notificationsEnabled = o.optBoolean("notificationsEnabled", true);
                }
                out.add(pf);
            }
        } catch (Exception ignored) {
            // intentionally ignored
        }
        return out;
    }

    public static void clearPendingFeeds(Context context) {
        prefs(context).edit().remove(KEY_PENDING_FEEDS).apply();
    }

    public static void saveEpisodeStates(Context context, List<EpisodeState> states) throws Exception {
        JSONArray arr = new JSONArray();
        for (EpisodeState s : states) {
            JSONObject o = new JSONObject();
            o.put("guid", s.guid != null ? s.guid : "");
            o.put("enclosureUrl", s.enclosureUrl != null ? s.enclosureUrl : "");
            o.put("feedUrl", s.feedUrl != null ? s.feedUrl : "");
            o.put("title", s.title != null ? s.title : "");
            o.put("status", s.status != null ? s.status : "unplayed");
            o.put("positionMs", s.positionMs);
            o.put("starred", s.starred);
            o.put("durationMs", s.durationMs);
            o.put("lastPlayedMs", s.lastPlayedMs);
            arr.put(o);
        }
        prefs(context).edit().putString(KEY_EPISODE_STATES, arr.toString()).apply();
    }

    public static List<EpisodeState> loadEpisodeStates(Context context) {
        String json = prefs(context).getString(KEY_EPISODE_STATES, null);
        if (json == null) {
            return Collections.emptyList();
        }
        List<EpisodeState> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                EpisodeState s = new EpisodeState();
                s.guid = o.optString("guid", "");
                s.enclosureUrl = o.optString("enclosureUrl", "");
                s.feedUrl = o.optString("feedUrl", "");
                s.title = o.optString("title", "");
                s.status = o.optString("status", "unplayed");
                s.positionMs = o.optInt("positionMs", 0);
                s.starred = o.optBoolean("starred", false);
                s.durationMs = o.optLong("durationMs", 0);
                s.lastPlayedMs = o.optLong("lastPlayedMs", 0);
                out.add(s);
            }
        } catch (Exception ignored) {
            // intentionally ignored
        }
        return out;
    }

    public static void clearEpisodeStates(Context context) {
        prefs(context).edit().remove(KEY_EPISODE_STATES).apply();
    }

    static void saveQueue(Context context, List<QueueEntry> queue) throws Exception {
        JSONArray arr = new JSONArray();
        for (QueueEntry q : queue) {
            JSONObject o = new JSONObject();
            o.put("guid", q.guid != null ? q.guid : "");
            o.put("enclosureUrl", q.enclosureUrl != null ? q.enclosureUrl : "");
            arr.put(o);
        }
        prefs(context).edit().putString(KEY_QUEUE, arr.toString()).apply();
    }

    public static List<QueueEntry> loadQueue(Context context) {
        String json = prefs(context).getString(KEY_QUEUE, null);
        if (json == null) {
            return Collections.emptyList();
        }
        List<QueueEntry> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                QueueEntry q = new QueueEntry();
                q.guid = o.optString("guid", "");
                q.enclosureUrl = o.optString("enclosureUrl", "");
                out.add(q);
            }
        } catch (Exception ignored) {
            // intentionally ignored
        }
        return out;
    }

    public static void clearQueue(Context context) {
        prefs(context).edit().remove(KEY_QUEUE).apply();
    }

    static void saveGlobalPrefs(Context context, @Nullable GlobalPrefs gp) throws Exception {
        if (gp == null) {
            prefs(context).edit().remove(KEY_GLOBAL_PREFS).apply();
            return;
        }
        JSONObject o = new JSONObject();
        o.put("playbackRate", gp.playbackRate);
        o.put("skipForwardSeconds", gp.skipForwardSeconds);
        o.put("skipBackwardSeconds", gp.skipBackwardSeconds);
        if (gp.trimSilence != null) {
            o.put("trimSilence", gp.trimSilence);
        }
        prefs(context).edit().putString(KEY_GLOBAL_PREFS, o.toString()).apply();
    }

    @Nullable
    public static GlobalPrefs loadGlobalPrefs(Context context) {
        String json = prefs(context).getString(KEY_GLOBAL_PREFS, null);
        if (json == null) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(json);
            GlobalPrefs gp = new GlobalPrefs();
            gp.playbackRate = (float) o.optDouble("playbackRate", 0);
            gp.skipForwardSeconds = o.optInt("skipForwardSeconds", -1);
            gp.skipBackwardSeconds = o.optInt("skipBackwardSeconds", -1);
            if (o.has("trimSilence")) {
                gp.trimSilence = o.optBoolean("trimSilence", false);
            }
            return gp;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void clearGlobalPrefs(Context context) {
        prefs(context).edit().remove(KEY_GLOBAL_PREFS).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static JSONObject readJson(InputStream stream) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = stream.read(buf)) != -1) {
            bos.write(buf, 0, len);
        }
        return new JSONObject(new String(bos.toByteArray(), "UTF-8"));
    }

    /** Parse RFC 3339 / ISO 8601. Returns 0 on empty or parse failure. */
    static long parseRfc3339(@NonNull String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // Try the most common shapes; we don't pull in java.time because
        // minSdk 23 lacks it on older devices outside desugaring scope.
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        };
        for (String p : patterns) {
            SimpleDateFormat fmt = new SimpleDateFormat(p, Locale.US);
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = fmt.parse(text, new ParsePosition(0));
            if (d != null) {
                return d.getTime();
            }
        }
        return 0;
    }
}
