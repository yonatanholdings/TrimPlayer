package de.danoeh.antennapod;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.playback.service.trim.TrimClient;
import de.danoeh.antennapod.net.download.serviceinterface.FeedUpdateManager;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.database.FeedDatabaseWriter;
import de.danoeh.antennapod.storage.database.LongList;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import java.util.Collections;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Two-way library sync between the phone and the TrimBrain account (subscriptions,
 * listening queue, playback progress, per-podcast playback speed).
 *
 * <p><b>Push:</b> a change-journal (snapshots of what was last pushed) diffs the
 * current local state so only genuine local changes are sent — never blanket
 * re-assertions that would clobber web edits. Progress is stamped with the media's
 * real last-played time for correct last-writer-wins.
 *
 * <p><b>Apply (the pull half):</b> the server delta is applied to the local DB
 * before the cursor advances (idempotent + LWW-guarded, so a crash mid-apply just
 * re-pulls): progress positions/played flags, queue adds/removes/<b>reorders</b>,
 * per-feed speed, auto-subscribe of feeds added elsewhere, and unsubscribes of
 * feeds removed elsewhere (only when the feed was already known at the last sync,
 * so a stale tombstone can never wipe a fresh local subscribe).
 */
public class TrimSyncWorker extends Worker {
    private static final String TAG = "TrimSync";
    // Window for "recently active" episodes whose progress we push. Bounds the
    // payload so we don't ship the entire history every run.
    private static final long PROGRESS_WINDOW_MS = 60L * 24 * 60 * 60 * 1000; // 60 days

    public TrimSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @Override
    @NonNull
    public Result doWork() {
        if (!UserPreferences.isTrimAccountLoggedIn()) {
            return Result.success(); // nothing to sync until the user logs in
        }
        String bearer = "Bearer " + UserPreferences.getTrimAccountToken();
        long now = System.currentTimeMillis();

        // Change-journal: diff current local state against the snapshot of what we
        // last pushed, so we only send genuine local changes (adds / title edits /
        // reorders / removals) and never re-assert unchanged rows over a web edit.
        Map<String, String> prevSubs = parseSubsSnapshot(UserPreferences.getTrimSyncSubsSnapshot());
        List<String> prevQueue = parseQueueSnapshot(UserPreferences.getTrimSyncQueueSnapshot());
        Map<String, Float> prevPrefs = parsePrefsSnapshot(UserPreferences.getTrimSyncPrefsSnapshot());
        List<Feed> feeds = DBReader.getFeedList();
        List<FeedItem> queue = DBReader.getQueue();
        Map<String, String> curSubs = currentSubs(feeds);
        List<String> curQueueUrls = currentQueueUrls(queue);
        Map<String, Float> curPrefs = currentPrefs(feeds);

        TrimClient.SyncRequest req = new TrimClient.SyncRequest();
        req.cursor = UserPreferences.getTrimSyncCursor();
        req.subscriptions = diffSubscriptions(prevSubs, curSubs, now);
        req.queue = diffQueue(prevQueue, queue, curQueueUrls, now);
        req.progress = buildProgress(queue, now);
        req.prefs = diffPrefs(prevPrefs, curPrefs, now);

        TrimClient.SyncResult resp = TrimClient.getInstance().accountSyncBlocking(bearer, req);
        if (resp.networkError) {
            Log.w(TAG, "sync network failure; will retry");
            return Result.retry();
        }
        if (resp.code == 401) {
            // Session expired/revoked — drop it so the UI prompts re-login.
            UserPreferences.clearTrimAccount();
            return Result.success();
        }
        if (!resp.isSuccessful()) {
            Log.w(TAG, "sync returned " + resp.code + "; will retry");
            return Result.retry();
        }
        // Apply the server delta locally BEFORE advancing the cursor, so a crash
        // mid-apply just re-pulls (apply is idempotent + LWW-guarded).
        int appliedSubs = applySubscriptions(getApplicationContext(), resp.body.subscriptions,
                curSubs, prevSubs);
        int appliedProgress = applyProgress(resp.body.progress);
        int appliedQueue = applyQueue(getApplicationContext(), resp.body.queue);
        int appliedPrefs = applyPrefs(resp.body.prefs);
        UserPreferences.setTrimSyncCursor(resp.body.cursor);
        // Snapshot the reconciled (post-apply) local state so the next diff is
        // clean — i.e. we don't echo server-applied changes back as local ones.
        List<Feed> reconciled = DBReader.getFeedList();
        UserPreferences.setTrimSyncSubsSnapshot(serializeSubs(currentSubs(reconciled)));
        UserPreferences.setTrimSyncQueueSnapshot(serializeQueue(currentQueueUrls(DBReader.getQueue())));
        UserPreferences.setTrimSyncPrefsSnapshot(serializePrefs(currentPrefs(reconciled)));
        Log.d(TAG, "sync ok: pushed subs=" + req.subscriptions.size()
                + " queue=" + req.queue.size() + " progress=" + req.progress.size()
                + " prefs=" + req.prefs.size()
                + "; applied subs=" + appliedSubs + " progress=" + appliedProgress
                + " queue=" + appliedQueue + " prefs=" + appliedPrefs
                + ", cursor=" + resp.body.cursor);
        return Result.success();
    }

    /** Reconcile server-side subscription changes: auto-subscribe feeds present on
     *  the account but not locally (e.g. added from the web player), and apply
     *  web-side unsubscribes (deleted=true tombstones). Returns rows changed.
     *
     *  <p>Unsubscribe guard: a tombstone is honored only when the feed was already
     *  in the change-journal snapshot ({@code prevSubs}) — i.e. it was known at the
     *  last sync and hasn't just been (re-)added locally. A locally-fresh subscribe
     *  (in {@code localSubs} but not {@code prevSubs}) was pushed THIS run with a
     *  newer client_ts, so the server keeps it and the stale tombstone must not
     *  delete it here.
     *
     *  {@code localSubs} is the set of local feed download-urls captured before the
     *  push, so we only add genuinely-missing feeds. */
    private static int applySubscriptions(android.content.Context ctx,
                                          List<TrimClient.SubscriptionChange> subs,
                                          java.util.Map<String, String> localSubs,
                                          java.util.Map<String, String> prevSubs) {
        if (subs == null || subs.isEmpty()) {
            return 0;
        }
        int added = 0;
        int removed = 0;
        Map<String, Feed> feedsByUrl = null; // lazy — only built if a tombstone applies
        for (TrimClient.SubscriptionChange s : subs) {
            if (s == null || s.rss_url == null || s.rss_url.isEmpty()) {
                continue;
            }
            if (s.deleted) {
                if (!localSubs.containsKey(s.rss_url) || !prevSubs.containsKey(s.rss_url)) {
                    continue; // not present locally, or a fresh local add — keep it
                }
                try {
                    if (feedsByUrl == null) {
                        feedsByUrl = new HashMap<>();
                        for (Feed f : DBReader.getFeedList()) {
                            if (f.getDownloadUrl() != null) {
                                feedsByUrl.put(f.getDownloadUrl(), f);
                            }
                        }
                    }
                    Feed feed = feedsByUrl.get(s.rss_url);
                    if (feed != null) {
                        DBWriter.deleteFeed(ctx, feed.getId()).get();
                        localSubs.remove(s.rss_url);
                        removed++;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "unsubscribe apply failed for " + s.rss_url + ": " + e.getMessage());
                }
                continue;
            }
            if (localSubs.containsKey(s.rss_url)) {
                continue; // already subscribed locally
            }
            try {
                Feed feed = new Feed(s.rss_url, null, s.title);
                feed.setItems(Collections.emptyList());
                FeedDatabaseWriter.updateFeed(ctx, feed, false);
                localSubs.put(s.rss_url, s.title); // guard against dupes within this batch
                added++;
            } catch (Exception e) {
                Log.w(TAG, "auto-subscribe failed for " + s.rss_url + ": " + e.getMessage());
            }
        }
        if (added > 0) {
            // Fetch episodes for the newly-added feeds (and any others due).
            FeedUpdateManager.getInstance().runOnce(ctx);
        }
        return added + removed;
    }

    /** Apply server-side queue changes to the local queue (delta only, so we
     *  touch just the items that changed server-side rather than rebuilding the
     *  queue): removals, additions (at their server position), and reorders of
     *  already-queued items. Reorders are applied in ascending target-position
     *  order against a live mirror of the queue, so each move lands the item at
     *  its final index even when several rows move at once. Episodes not present
     *  locally are skipped (queue auto-subscribe deferred). */
    private static int applyQueue(android.content.Context ctx,
                                  List<TrimClient.QueueChange> queue) {
        if (queue == null || queue.isEmpty()) {
            return 0;
        }
        // Ordered mirror of the queue — updated alongside every DB mutation so
        // subsequent indices stay correct without re-reading the DB each step.
        LongList ids = DBReader.getQueueIDList();
        List<Long> order = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            order.add(ids.get(i));
        }

        int changed = 0;
        // Pass 1: removals and additions.
        List<long[]> reorders = new ArrayList<>(); // [itemId, targetPosition]
        for (TrimClient.QueueChange q : queue) {
            if (q == null || q.episode_url == null) {
                continue;
            }
            FeedItem item = DBReader.getFeedItemByGuidOrEpisodeUrl(q.guid, q.episode_url);
            if (item == null) {
                continue; // not subscribed/present locally
            }
            boolean present = order.contains(item.getId());
            try {
                if (q.deleted) {
                    if (present) {
                        DBWriter.removeQueueItem(ctx, false, item).get();
                        order.remove(Long.valueOf(item.getId()));
                        changed++;
                    }
                } else if (!present) {
                    int idx = q.position == null ? order.size()
                            : Math.max(0, Math.min(q.position, order.size()));
                    DBWriter.addQueueItemAt(ctx, item.getId(), idx).get();
                    order.add(idx, item.getId());
                    changed++;
                } else if (q.position != null) {
                    reorders.add(new long[] {item.getId(), q.position});
                }
            } catch (Exception e) {
                Log.w(TAG, "queue apply failed for " + q.episode_url + ": " + e.getMessage());
            }
        }
        // Pass 2: reorders of items already in the queue, smallest target first.
        Collections.sort(reorders, (a, b) -> Long.compare(a[1], b[1]));
        for (long[] move : reorders) {
            long itemId = move[0];
            int from = order.indexOf(itemId);
            int to = Math.max(0, Math.min((int) move[1], order.size() - 1));
            if (from < 0 || from == to) {
                continue;
            }
            try {
                DBWriter.moveQueueItem(from, to, false).get();
                order.remove(Long.valueOf(itemId));
                order.add(to, itemId);
                changed++;
            } catch (Exception e) {
                Log.w(TAG, "queue reorder failed for item " + itemId + ": " + e.getMessage());
            }
        }
        return changed;
    }

    /** Apply server-side progress to the local DB. Returns how many rows changed.
     *  Last-writer-wins by the media's local last-played time, so newer on-device
     *  progress is never clobbered by an older web update. Episodes not present
     *  locally are skipped (auto-subscribe is deferred). */
    private static int applyProgress(List<TrimClient.ProgressChange> progress) {
        if (progress == null) {
            return 0;
        }
        int applied = 0;
        for (TrimClient.ProgressChange p : progress) {
            if (p == null || p.episode_url == null || p.deleted) {
                continue;
            }
            FeedItem item = DBReader.getFeedItemByGuidOrEpisodeUrl(p.guid, p.episode_url);
            if (item == null || item.getMedia() == null) {
                continue;
            }
            FeedMedia media = item.getMedia();
            // LWW: skip if local progress is at least as recent as the server's.
            if (p.client_ts <= media.getLastPlayedTimeStatistics()) {
                continue;
            }
            if (p.position_ms != null) {
                long pos = Math.max(0, Math.min(p.position_ms, Integer.MAX_VALUE));
                media.setPosition((int) pos);
                DBWriter.setFeedMedia(media);
            }
            if (p.played != item.isPlayed()) {
                DBWriter.markItemPlayed(p.played ? FeedItem.PLAYED : FeedItem.UNPLAYED,
                        false, item.getId());
            }
            applied++;
        }
        return applied;
    }

    /** Apply server-side per-feed preferences (playback speed) to the local DB.
     *  Returns how many feeds changed. A {@code deleted} row (or a null
     *  playback_rate) clears the override back to {@link FeedPreferences#SPEED_USE_GLOBAL}.
     *  Feeds not subscribed locally are skipped.
     *
     *  <p>Prefs carry no local edit timestamp (unlike progress), so LWW is handled
     *  by ordering: the push above runs first, so a local override edited before
     *  this run is already on the server and comes back unchanged in the delta —
     *  {@code applyPrefs} then no-ops on it (equal-value skip). Only genuine
     *  other-device changes get written here. */
    private static int applyPrefs(List<TrimClient.PrefChange> prefs) {
        if (prefs == null || prefs.isEmpty()) {
            return 0;
        }
        Map<String, Feed> byUrl = new HashMap<>();
        for (Feed f : DBReader.getFeedList()) {
            if (f.getDownloadUrl() != null && !f.getDownloadUrl().isEmpty()) {
                byUrl.put(f.getDownloadUrl(), f);
            }
        }
        int applied = 0;
        for (TrimClient.PrefChange p : prefs) {
            if (p == null || p.rss_url == null) {
                continue;
            }
            Feed feed = byUrl.get(p.rss_url);
            if (feed == null || feed.getPreferences() == null) {
                continue; // not subscribed locally
            }
            float target = (p.deleted || p.playback_rate == null)
                    ? FeedPreferences.SPEED_USE_GLOBAL : p.playback_rate;
            FeedPreferences fp = feed.getPreferences();
            if (fp.getFeedPlaybackSpeed() == target) {
                continue; // already at the server value — nothing to write
            }
            fp.setFeedPlaybackSpeed(target);
            DBWriter.setFeedPreferences(fp);
            applied++;
        }
        return applied;
    }

    // --- change-journal: current state, snapshots, and diffs ------------------

    private static Map<String, String> currentSubs(List<Feed> feeds) {
        Map<String, String> m = new HashMap<>();
        for (Feed f : feeds) {
            String url = f.getDownloadUrl();
            if (url != null && !url.isEmpty()) {
                m.put(url, f.getTitle());
            }
        }
        return m;
    }

    /** Feeds with a per-podcast playback-speed override (i.e. not SPEED_USE_GLOBAL),
     *  keyed by feed download-url. Feeds on the global speed are omitted — a
     *  removal from this map is what emits a "cleared" tombstone in {@link #diffPrefs}. */
    private static Map<String, Float> currentPrefs(List<Feed> feeds) {
        Map<String, Float> m = new HashMap<>();
        for (Feed f : feeds) {
            String url = f.getDownloadUrl();
            FeedPreferences prefs = f.getPreferences();
            if (url == null || url.isEmpty() || prefs == null) {
                continue;
            }
            float speed = prefs.getFeedPlaybackSpeed();
            if (speed != FeedPreferences.SPEED_USE_GLOBAL) {
                m.put(url, speed);
            }
        }
        return m;
    }

    private static List<String> currentQueueUrls(List<FeedItem> queue) {
        List<String> urls = new ArrayList<>();
        for (FeedItem item : queue) {
            FeedMedia media = item.getMedia();
            if (media != null && media.getDownloadUrl() != null && !media.getDownloadUrl().isEmpty()) {
                urls.add(media.getDownloadUrl());
            }
        }
        return urls;
    }

    /** Subscriptions added or whose title changed (deleted=false), plus removals
     *  (deleted=true tombstones) — everything else is unchanged and omitted. */
    private static List<TrimClient.SubscriptionChange> diffSubscriptions(
            Map<String, String> prev, Map<String, String> cur, long ts) {
        List<TrimClient.SubscriptionChange> out = new ArrayList<>();
        for (Map.Entry<String, String> e : cur.entrySet()) {
            if (!prev.containsKey(e.getKey()) || !equalsNullable(prev.get(e.getKey()), e.getValue())) {
                TrimClient.SubscriptionChange s = new TrimClient.SubscriptionChange();
                s.rss_url = e.getKey();
                s.title = e.getValue();
                s.deleted = false;
                s.client_ts = ts;
                out.add(s);
            }
        }
        for (String url : prev.keySet()) {
            if (!cur.containsKey(url)) {
                TrimClient.SubscriptionChange s = new TrimClient.SubscriptionChange();
                s.rss_url = url;
                s.deleted = true;
                s.client_ts = ts;
                out.add(s);
            }
        }
        return out;
    }

    /** Queue items that are new or moved to a different position (deleted=false),
     *  plus removals (deleted=true). Unchanged-position items are omitted. */
    private static List<TrimClient.QueueChange> diffQueue(
            List<String> prev, List<FeedItem> curItems, List<String> curUrls, long ts) {
        List<TrimClient.QueueChange> out = new ArrayList<>();
        Map<String, Integer> prevIdx = new HashMap<>();
        for (int i = 0; i < prev.size(); i++) {
            prevIdx.put(prev.get(i), i);
        }
        Set<String> curSet = new HashSet<>(curUrls);
        for (int i = 0; i < curItems.size(); i++) {
            FeedItem item = curItems.get(i);
            FeedMedia media = item.getMedia();
            if (media == null || media.getDownloadUrl() == null || media.getDownloadUrl().isEmpty()) {
                continue;
            }
            String url = media.getDownloadUrl();
            Integer pIdx = prevIdx.get(url);
            if (pIdx == null || pIdx != i) {
                TrimClient.QueueChange q = new TrimClient.QueueChange();
                q.episode_url = url;
                q.rss_url = item.getFeed() != null ? item.getFeed().getDownloadUrl() : null;
                q.guid = item.getItemIdentifier();
                q.position = i;
                q.deleted = false;
                q.client_ts = ts;
                out.add(q);
            }
        }
        for (String url : prev) {
            if (!curSet.contains(url)) {
                TrimClient.QueueChange q = new TrimClient.QueueChange();
                q.episode_url = url;
                q.deleted = true;
                q.client_ts = ts;
                out.add(q);
            }
        }
        return out;
    }

    /** Per-feed speed overrides that are new or changed (deleted=false), plus
     *  clears (deleted=true) for feeds that dropped back to the global speed or
     *  were unsubscribed. Unchanged overrides are omitted. */
    private static List<TrimClient.PrefChange> diffPrefs(
            Map<String, Float> prev, Map<String, Float> cur, long ts) {
        List<TrimClient.PrefChange> out = new ArrayList<>();
        for (Map.Entry<String, Float> e : cur.entrySet()) {
            Float p = prev.get(e.getKey());
            if (p == null || !p.equals(e.getValue())) {
                TrimClient.PrefChange pc = new TrimClient.PrefChange();
                pc.rss_url = e.getKey();
                pc.playback_rate = e.getValue();
                pc.deleted = false;
                pc.client_ts = ts;
                out.add(pc);
            }
        }
        for (String url : prev.keySet()) {
            if (!cur.containsKey(url)) {
                TrimClient.PrefChange pc = new TrimClient.PrefChange();
                pc.rss_url = url;
                pc.playback_rate = null;
                pc.deleted = true;
                pc.client_ts = ts;
                out.add(pc);
            }
        }
        return out;
    }

    private static boolean equalsNullable(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static Map<String, String> parseSubsSnapshot(String json) {
        Map<String, String> m = new HashMap<>();
        if (json == null || json.isEmpty()) {
            return m;
        }
        try {
            JSONObject o = new JSONObject(json);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, o.isNull(k) ? null : o.optString(k));
            }
        } catch (JSONException e) {
            Log.w(TAG, "bad subs snapshot, treating as empty: " + e.getMessage());
        }
        return m;
    }

    private static String serializeSubs(Map<String, String> subs) {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, String> e : subs.entrySet()) {
                o.put(e.getKey(), e.getValue() == null ? JSONObject.NULL : e.getValue());
            }
        } catch (JSONException e) {
            Log.w(TAG, "failed to serialize subs snapshot: " + e.getMessage());
        }
        return o.toString();
    }

    private static List<String> parseQueueSnapshot(String json) {
        List<String> urls = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return urls;
        }
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                urls.add(a.optString(i));
            }
        } catch (JSONException e) {
            Log.w(TAG, "bad queue snapshot, treating as empty: " + e.getMessage());
        }
        return urls;
    }

    private static String serializeQueue(List<String> urls) {
        JSONArray a = new JSONArray();
        for (String u : urls) {
            a.put(u);
        }
        return a.toString();
    }

    private static Map<String, Float> parsePrefsSnapshot(String json) {
        Map<String, Float> m = new HashMap<>();
        if (json == null || json.isEmpty()) {
            return m;
        }
        try {
            JSONObject o = new JSONObject(json);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, (float) o.optDouble(k, FeedPreferences.SPEED_USE_GLOBAL));
            }
        } catch (JSONException e) {
            Log.w(TAG, "bad prefs snapshot, treating as empty: " + e.getMessage());
        }
        return m;
    }

    private static String serializePrefs(Map<String, Float> prefs) {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, Float> e : prefs.entrySet()) {
                o.put(e.getKey(), (double) e.getValue());
            }
        } catch (JSONException e) {
            Log.w(TAG, "failed to serialize prefs snapshot: " + e.getMessage());
        }
        return o.toString();
    }

    /** Progress for queued + recently-played episodes, deduped by episode URL.
     *  Each row is stamped with the media's real last-played time so it resolves
     *  correctly against web playback under last-writer-wins. */
    private static List<TrimClient.ProgressChange> buildProgress(List<FeedItem> queue, long now) {
        List<TrimClient.ProgressChange> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        List<FeedItem> candidates = new ArrayList<>(queue);
        candidates.addAll(DBReader.getEpisodesPlayedInPeriod(now - PROGRESS_WINDOW_MS, now));

        for (FeedItem item : candidates) {
            FeedMedia media = item.getMedia();
            if (media == null || media.getDownloadUrl() == null || media.getDownloadUrl().isEmpty()) {
                continue;
            }
            String url = media.getDownloadUrl();
            if (!seen.add(url)) {
                continue;
            }
            // Skip untouched episodes (no position, never played) to keep the push small.
            if (media.getPosition() <= 0 && !item.isPlayed()) {
                continue;
            }
            TrimClient.ProgressChange p = new TrimClient.ProgressChange();
            p.episode_url = url;
            p.rss_url = item.getFeed() != null ? item.getFeed().getDownloadUrl() : null;
            p.guid = item.getItemIdentifier();
            p.position_ms = (long) media.getPosition();
            p.duration_ms = (long) media.getDuration();
            p.played = item.isPlayed();
            long lastPlayed = media.getLastPlayedTimeStatistics();
            p.client_ts = lastPlayed > 0 ? lastPlayed : now;
            out.add(p);
        }
        return out;
    }
}
