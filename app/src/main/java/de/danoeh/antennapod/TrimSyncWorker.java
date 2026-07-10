package de.danoeh.antennapod;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.model.feed.SortOrder;
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
import java.util.concurrent.TimeUnit;

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
    // Upper bound on favorites we reconcile per sync (favorites are normally few).
    private static final int FAV_LIMIT = 1000;
    // How many consecutive no-progress runs we tolerate while queue/progress rows
    // stay deferred (their episode never appears locally) before we give up holding
    // the cursor and let sync advance — so a row for an episode that's genuinely
    // gone from its feed can't wedge sync forever.
    private static final int MAX_DEFER_NOPROGRESS_RUNS = 5;

    /** Outcome of applying one entity's server delta: how many local rows we
     *  actually changed, and how many we had to DEFER because the referenced
     *  episode isn't in the local DB yet (feeds are fetched asynchronously after
     *  an auto-subscribe, so the episode arrives a moment later). */
    private static final class ApplyResult {
        int changed;
        int deferred;
    }

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

        // Favorites -> PortCast episodes[].starred. Change-journalled like the queue:
        // curFav is the current favorite episode-urls; changedFav (toggles since the
        // last push) get a fresh client_ts so the star change wins LWW even when the
        // episode's position/played (and thus its normal client_ts) didn't change.
        List<FeedItem> favItems = DBReader.getEpisodes(0, FAV_LIMIT,
                new FeedItemFilter(FeedItemFilter.IS_FAVORITE), SortOrder.DATE_NEW_OLD);
        java.util.Set<String> curFav = mediaUrlSet(favItems);
        java.util.Set<String> prevFav = new HashSet<>(parseQueueSnapshot(
                UserPreferences.getTrimSyncFavSnapshot()));
        java.util.Set<String> changedFav = new HashSet<>();
        for (String u : curFav) {
            if (!prevFav.contains(u)) {
                changedFav.add(u);
            }
        }
        for (String u : prevFav) {
            if (!curFav.contains(u)) {
                changedFav.add(u);
            }
        }

        // Bookmarks -> BookmarkChange rows keyed by the row's stable sync_id.
        // Journalled as sync_id -> note (position/createdAt are immutable, so a
        // note change is the only possible edit; an id appearing/disappearing is
        // an add/delete).
        Map<String, String> prevBookmarks = parseSubsSnapshot(
                UserPreferences.getTrimSyncBookmarkSnapshot());
        List<DBReader.BookmarkWithItem> bookmarkRows = DBReader.getAllBookmarksWithItems();

        TrimClient.SyncRequest req = new TrimClient.SyncRequest();
        req.cursor = UserPreferences.getTrimSyncCursor();
        req.subscriptions = diffSubscriptions(prevSubs, curSubs, now);
        req.queue = diffQueue(prevQueue, queue, curQueueUrls, now);
        req.progress = buildProgress(queue, favItems, curFav, changedFav, now);
        req.prefs = diffPrefs(prevPrefs, curPrefs, now);
        req.bookmarks = diffBookmarks(prevBookmarks, bookmarkRows, now);

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
        java.util.Set<Long> favIds = new HashSet<>();
        for (FeedItem f : favItems) {
            favIds.add(f.getId());
        }
        ApplyResult progressRes = applyProgress(resp.body.progress, favIds);
        ApplyResult queueRes = applyQueue(getApplicationContext(), resp.body.queue);
        int appliedPrefs = applyPrefs(resp.body.prefs);
        Set<String> adoptedAwayIds = new HashSet<>();
        ApplyResult bookmarkRes = applyBookmarks(resp.body.bookmarks, adoptedAwayIds);

        // Cursor-advance guard. Queue/progress rows whose episode isn't in the local
        // DB yet are DEFERRED, not applied — which happens routinely right after an
        // auto-subscribe, because feed episodes are fetched asynchronously
        // (FeedUpdateManager.runOnce enqueues a WorkManager job). If we advanced the
        // cursor past those rows, the server would never re-send them and the queue +
        // history would be permanently lost on this device while the account still
        // holds them. So when items are deferred we HOLD the cursor (re-pull next run,
        // once the episodes exist) and nudge a follow-up sync. Bounded by a
        // no-progress retry cap so a row for an episode that's gone from its feed
        // can't wedge sync forever.
        int deferred = progressRes.deferred + queueRes.deferred + bookmarkRes.deferred;
        boolean progressed = (progressRes.changed + queueRes.changed + bookmarkRes.changed) > 0;
        long newCursor = resp.body.cursor;
        if (deferred > 0) {
            int noProgressRuns = progressed ? 0 : UserPreferences.getTrimSyncDeferRetries() + 1;
            if (noProgressRuns <= MAX_DEFER_NOPROGRESS_RUNS) {
                newCursor = req.cursor; // hold — don't advance past the deferred rows
                UserPreferences.setTrimSyncDeferRetries(noProgressRuns);
                scheduleFollowupSync(getApplicationContext());
            } else {
                UserPreferences.setTrimSyncDeferRetries(0);
                Log.w(TAG, "advancing cursor past " + deferred + " unresolved item(s) after "
                        + noProgressRuns + " no-progress runs");
            }
        } else {
            UserPreferences.setTrimSyncDeferRetries(0);
        }
        UserPreferences.setTrimSyncCursor(newCursor);
        // Snapshot the reconciled (post-apply) local state so the next diff is
        // clean — i.e. we don't echo server-applied changes back as local ones.
        List<Feed> reconciled = DBReader.getFeedList();
        UserPreferences.setTrimSyncSubsSnapshot(serializeSubs(currentSubs(reconciled)));
        UserPreferences.setTrimSyncQueueSnapshot(serializeQueue(currentQueueUrls(DBReader.getQueue())));
        UserPreferences.setTrimSyncPrefsSnapshot(serializePrefs(currentPrefs(reconciled)));
        UserPreferences.setTrimSyncFavSnapshot(serializeQueue(new ArrayList<>(mediaUrlSet(
                DBReader.getEpisodes(0, FAV_LIMIT,
                        new FeedItemFilter(FeedItemFilter.IS_FAVORITE), SortOrder.DATE_NEW_OLD)))));
        // Bookmark snapshot = the DB state, PLUS the ids this run re-keyed onto
        // another device's id ("adopted away"). Those old ids were pushed to the
        // server (possibly this very run) but no longer exist locally — keeping
        // them in the snapshot makes the next diff emit their tombstones, so the
        // server doesn't keep a duplicate row alive forever.
        Map<String, String> bookmarkSnap = new HashMap<>();
        for (de.danoeh.antennapod.model.feed.Bookmark b : DBReader.getAllBookmarks()) {
            if (b.getSyncId() != null) {
                bookmarkSnap.put(b.getSyncId(), b.getNote());
            }
        }
        for (String adopted : adoptedAwayIds) {
            bookmarkSnap.put(adopted, "");
        }
        UserPreferences.setTrimSyncBookmarkSnapshot(serializeSubs(bookmarkSnap));
        Log.d(TAG, "sync ok: pushed subs=" + req.subscriptions.size()
                + " queue=" + req.queue.size() + " progress=" + req.progress.size()
                + " prefs=" + req.prefs.size() + " bookmarks=" + req.bookmarks.size()
                + "; applied subs=" + appliedSubs + " progress=" + progressRes.changed
                + " queue=" + queueRes.changed + " prefs=" + appliedPrefs
                + " bookmarks=" + bookmarkRes.changed
                + "; deferred=" + deferred + ", cursor=" + newCursor);
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
    private static ApplyResult applyQueue(android.content.Context ctx,
                                  List<TrimClient.QueueChange> queue) {
        ApplyResult r = new ApplyResult();
        if (queue == null || queue.isEmpty()) {
            return r;
        }
        // Ordered mirror of the queue — updated alongside every DB mutation so
        // subsequent indices stay correct without re-reading the DB each step.
        LongList ids = DBReader.getQueueIDList();
        List<Long> order = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            order.add(ids.get(i));
        }

        // Pass 1: removals and additions.
        List<long[]> reorders = new ArrayList<>(); // [itemId, targetPosition]
        for (TrimClient.QueueChange q : queue) {
            if (q == null || q.episode_url == null) {
                continue;
            }
            FeedItem item = DBReader.getFeedItemByGuidOrEpisodeUrl(q.guid, q.episode_url);
            if (item == null) {
                // A removal of an absent item is already satisfied; but an add/move
                // we can't apply because the episode isn't fetched locally yet is a
                // deferral — hold the cursor so it re-pulls once the episode exists.
                if (!q.deleted) {
                    r.deferred++;
                }
                continue;
            }
            boolean present = order.contains(item.getId());
            try {
                if (q.deleted) {
                    if (present) {
                        DBWriter.removeQueueItem(ctx, false, item).get();
                        order.remove(Long.valueOf(item.getId()));
                        r.changed++;
                    }
                } else if (!present) {
                    int idx = q.position == null ? order.size()
                            : Math.max(0, Math.min(q.position, order.size()));
                    DBWriter.addQueueItemAt(ctx, item.getId(), idx).get();
                    order.add(idx, item.getId());
                    r.changed++;
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
                r.changed++;
            } catch (Exception e) {
                Log.w(TAG, "queue reorder failed for item " + itemId + ": " + e.getMessage());
            }
        }
        return r;
    }

    /** Apply server-side progress to the local DB. Returns how many rows changed.
     *  Last-writer-wins by the media's local last-played time, so newer on-device
     *  progress is never clobbered by an older web update. Episodes not present
     *  locally are skipped (auto-subscribe is deferred). */
    private static ApplyResult applyProgress(List<TrimClient.ProgressChange> progress,
                                             Set<Long> favIds) {
        ApplyResult r = new ApplyResult();
        if (progress == null) {
            return r;
        }
        for (TrimClient.ProgressChange p : progress) {
            if (p == null || p.episode_url == null || p.deleted) {
                continue;
            }
            FeedItem item = DBReader.getFeedItemByGuidOrEpisodeUrl(p.guid, p.episode_url);
            if (item == null || item.getMedia() == null) {
                r.deferred++; // episode not fetched locally yet — retry after feed update
                continue;
            }
            FeedMedia media = item.getMedia();
            // LWW: skip if local progress is at least as recent as the server's.
            if (p.client_ts <= lastLocalPlaybackTs(media)) {
                continue;
            }
            // Rows that carry no playback evidence (not played, position 0/absent)
            // are star-toggle snapshots: the push side stamps a favorite change
            // with a fresh client_ts even when position/played didn't move, and a
            // device that never played the episode snapshots position 0. Applying
            // that position would reset real progress on this device to the
            // beginning (and un-mark played episodes), so such rows reconcile ONLY
            // the starred flag below.
            if (representsPlayback(p)) {
                if (p.position_ms != null) {
                    long pos = Math.max(0, Math.min(p.position_ms, Integer.MAX_VALUE));
                    media.setPosition((int) pos);
                }
                // Restore the "played-at" timestamps from the server's client_ts
                // (which the push side stamps from the media's last-played time).
                // Without this a synced play carries only the read flag + position,
                // with no timestamp — so it never appears in Playback History
                // (filtered on playback_completion_date > 0) or in listening
                // statistics. The LWW guard above means p.client_ts is strictly
                // newer, so we never move them back.
                if (p.client_ts > 0) {
                    media.setLastPlayedTimeStatistics(p.client_ts);
                    media.setLastPlayedTimeHistory(new java.util.Date(p.client_ts));
                }
                DBWriter.setFeedMedia(media);
                if (p.played != item.isPlayed()) {
                    DBWriter.markItemPlayed(p.played ? FeedItem.PLAYED : FeedItem.UNPLAYED,
                            false, item.getId());
                }
            }
            // Reconcile the local Favorite tag with the server's starred flag (null =
            // an older client that doesn't send it — leave the local favorite as-is).
            if (p.starred != null) {
                boolean isFav = favIds.contains(item.getId());
                try {
                    if (p.starred && !isFav) {
                        DBWriter.addFavoriteItem(item).get();
                        favIds.add(item.getId());
                    } else if (!p.starred && isFav) {
                        DBWriter.removeFavoriteItem(item).get();
                        favIds.remove(item.getId());
                    }
                } catch (Exception e) {
                    Log.w(TAG, "favorite apply failed for " + p.episode_url + ": " + e.getMessage());
                }
            }
            r.changed++;
        }
        return r;
    }

    /** Whether a server progress row carries actual playback evidence (a played
     *  flag or a real position), as opposed to a star-toggle snapshot from a
     *  device that never played the episode. Only playback rows may write
     *  position/played/timestamps locally. Package-visible for unit tests. */
    static boolean representsPlayback(TrimClient.ProgressChange p) {
        return p.played || (p.position_ms != null && p.position_ms > 0);
    }

    /** The freshest local playback timestamp — the LWW key for progress rows,
     *  on both the push (client_ts) and apply (guard) sides. Uses the history
     *  date as well as the statistics one because import paths (watch flush,
     *  PortCast file import) stamp history but deliberately leave statistics
     *  alone for chart attribution; taking the max keeps a just-applied import
     *  position from being pushed with a stale client_ts (which the server
     *  would reject) or clobbered by an older server row with a newer ts.
     *  Package-visible for unit tests. */
    static long lastLocalPlaybackTs(FeedMedia media) {
        java.util.Date history = media.getLastPlayedTimeHistory();
        return Math.max(media.getLastPlayedTimeStatistics(),
                history != null ? history.getTime() : 0);
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

    /** Kick an account sync shortly after a local change (bookmark add/edit/delete,
     *  etc.) instead of waiting for the ~2h periodic run. The short initial delay +
     *  REPLACE on a unique name debounces bursts into a single sync and survives
     *  process death (WorkManager persists the request); a no-op server-side when
     *  logged out (see {@link #doWork()}). */
    public static void requestSyncSoon(Context ctx) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
                "trimAccountSyncNow", ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(TrimSyncWorker.class)
                        .setInitialDelay(5, TimeUnit.SECONDS)
                        .setConstraints(new Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build());
    }

    /** Nudge another sync a short while out so deferred queue/progress rows — whose
     *  episodes are being fetched asynchronously after an auto-subscribe — get
     *  re-pulled and applied promptly, instead of waiting for the periodic sync. */
    private static void scheduleFollowupSync(Context ctx) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
                "trimAccountSyncRetry", ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(TrimSyncWorker.class)
                        .setInitialDelay(45, TimeUnit.SECONDS)
                        .setConstraints(new Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build());
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

    /** Bookmarks that are new (client_ts = the bookmark's creation time) or whose
     *  note changed (client_ts = now), plus tombstones for ids that disappeared
     *  locally (deleted or adopted away). Rows whose episode has no enclosure url
     *  are unsyncable and skipped entirely — they never enter the journal, so
     *  they can't emit spurious tombstones either. */
    private static List<TrimClient.BookmarkChange> diffBookmarks(
            Map<String, String> prev, List<DBReader.BookmarkWithItem> rows, long now) {
        List<TrimClient.BookmarkChange> out = new ArrayList<>();
        Set<String> currentIds = new HashSet<>();
        for (DBReader.BookmarkWithItem row : rows) {
            String syncId = row.bookmark.getSyncId();
            FeedMedia media = row.item.getMedia();
            String url = media != null ? media.getDownloadUrl() : null;
            if (syncId == null || url == null || url.isEmpty()) {
                continue;
            }
            currentIds.add(syncId);
            boolean isNew = !prev.containsKey(syncId);
            if (!isNew && equalsNullable(prev.get(syncId), row.bookmark.getNote())) {
                continue; // unchanged
            }
            TrimClient.BookmarkChange b = new TrimClient.BookmarkChange();
            b.bookmark_id = syncId;
            b.episode_url = url;
            b.guid = row.item.getItemIdentifier();
            b.at_ms = row.bookmark.getPosition();
            b.note = row.bookmark.getNote();
            b.deleted = false;
            b.client_ts = isNew && row.bookmark.getCreatedAt() > 0
                    ? row.bookmark.getCreatedAt() : now;
            out.add(b);
        }
        for (String syncId : prev.keySet()) {
            if (!currentIds.contains(syncId)) {
                TrimClient.BookmarkChange b = new TrimClient.BookmarkChange();
                b.bookmark_id = syncId;
                b.deleted = true;
                b.client_ts = now;
                out.add(b);
            }
        }
        return out;
    }

    /** Apply server-side bookmark changes: deletes and note edits by sync id,
     *  inserts for unknown ids (keeping the wire id + timestamp). An unknown id
     *  landing on an episode+position that already has a local bookmark is the
     *  same bookmark created independently on two devices (e.g. both imported
     *  the same PortCast file) — instead of duplicating, the row is re-keyed to
     *  whichever id sorts lower (both devices converge on it) and the loser id
     *  is tombstoned via {@code adoptedAwayIds} + the journal on the next run.
     *  Episodes not present locally are deferred like queue/progress rows. */
    private static ApplyResult applyBookmarks(List<TrimClient.BookmarkChange> changes,
                                              Set<String> adoptedAwayIds) {
        ApplyResult r = new ApplyResult();
        if (changes == null || changes.isEmpty()) {
            return r;
        }
        Map<String, de.danoeh.antennapod.model.feed.Bookmark> bySyncId = new HashMap<>();
        Map<String, de.danoeh.antennapod.model.feed.Bookmark> byItemPos = new HashMap<>();
        for (de.danoeh.antennapod.model.feed.Bookmark b : DBReader.getAllBookmarks()) {
            if (b.getSyncId() != null) {
                bySyncId.put(b.getSyncId(), b);
            }
            byItemPos.put(b.getFeedItemId() + "#" + b.getPosition(), b);
        }
        for (TrimClient.BookmarkChange c : changes) {
            if (c == null || c.bookmark_id == null || c.bookmark_id.isEmpty()) {
                continue;
            }
            de.danoeh.antennapod.model.feed.Bookmark local = bySyncId.get(c.bookmark_id);
            String note = c.note == null ? "" : c.note;
            try {
                if (c.deleted) {
                    if (local != null) {
                        DBWriter.deleteBookmark(local.getId(), local.getFeedItemId()).get();
                        bySyncId.remove(c.bookmark_id);
                        byItemPos.remove(local.getFeedItemId() + "#" + local.getPosition());
                        r.changed++;
                    }
                    continue;
                }
                if (local != null) {
                    if (!note.equals(local.getNote())) {
                        DBWriter.updateBookmarkNote(local.getId(), local.getFeedItemId(), note).get();
                        r.changed++;
                    }
                    continue;
                }
                if ((c.guid == null || c.guid.isEmpty())
                        && (c.episode_url == null || c.episode_url.isEmpty())) {
                    continue; // nothing to resolve the episode by
                }
                FeedItem item = DBReader.getFeedItemByGuidOrEpisodeUrl(c.guid, c.episode_url);
                if (item == null) {
                    r.deferred++; // episode not fetched locally yet — retry after feed update
                    continue;
                }
                int positionMs = (int) Math.max(0, Math.min(c.at_ms, Integer.MAX_VALUE));
                String posKey = item.getId() + "#" + positionMs;
                de.danoeh.antennapod.model.feed.Bookmark dup = byItemPos.get(posKey);
                if (dup != null) {
                    if (dup.getSyncId() == null || c.bookmark_id.compareTo(dup.getSyncId()) < 0) {
                        DBWriter.adoptBookmarkSyncId(dup.getId(), item.getId(),
                                c.bookmark_id, note).get();
                        if (dup.getSyncId() != null) {
                            adoptedAwayIds.add(dup.getSyncId());
                            bySyncId.remove(dup.getSyncId());
                        }
                        bySyncId.put(c.bookmark_id, dup);
                        r.changed++;
                    }
                    // else: our id sorts lower — the other device adopts ours.
                    continue;
                }
                long createdAt = c.client_ts > 0 ? c.client_ts : System.currentTimeMillis();
                DBWriter.addSyncedBookmark(item.getId(), positionMs, note,
                        createdAt, c.bookmark_id).get();
                de.danoeh.antennapod.model.feed.Bookmark inserted =
                        new de.danoeh.antennapod.model.feed.Bookmark(
                                0, item.getId(), positionMs, note, createdAt, c.bookmark_id);
                bySyncId.put(c.bookmark_id, inserted);
                byItemPos.put(posKey, inserted);
                r.changed++;
            } catch (Exception e) {
                Log.w(TAG, "bookmark apply failed for " + c.bookmark_id + ": " + e.getMessage());
            }
        }
        return r;
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

    /** Progress for queued + recently-played + favorited episodes, deduped by URL.
     *  Each row is stamped with the media's real last-played time so it resolves
     *  correctly against web playback under last-writer-wins — except a favorite that
     *  was just toggled ({@code changedFav}), which is stamped {@code now} so the star
     *  change wins even though its position/played didn't move. {@code starred} rides
     *  every row so the account mirrors the phone's Favorites. */
    private static List<TrimClient.ProgressChange> buildProgress(
            List<FeedItem> queue, List<FeedItem> favItems, Set<String> curFav,
            Set<String> changedFav, long now) {
        List<TrimClient.ProgressChange> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        List<FeedItem> candidates = new ArrayList<>(queue);
        candidates.addAll(DBReader.getEpisodesPlayedInPeriod(now - PROGRESS_WINDOW_MS, now));
        candidates.addAll(favItems);

        for (FeedItem item : candidates) {
            FeedMedia media = item.getMedia();
            if (media == null || media.getDownloadUrl() == null || media.getDownloadUrl().isEmpty()) {
                continue;
            }
            String url = media.getDownloadUrl();
            if (!seen.add(url)) {
                continue;
            }
            boolean touched = media.getPosition() > 0 || item.isPlayed();
            boolean favChanged = changedFav.contains(url);
            // Skip untouched episodes (no position, never played) whose favorite
            // state also didn't change — nothing worth pushing.
            if (!touched && !favChanged) {
                continue;
            }
            TrimClient.ProgressChange p = new TrimClient.ProgressChange();
            p.episode_url = url;
            p.rss_url = item.getFeed() != null ? item.getFeed().getDownloadUrl() : null;
            p.guid = item.getItemIdentifier();
            p.position_ms = (long) media.getPosition();
            p.duration_ms = (long) media.getDuration();
            p.played = item.isPlayed();
            p.starred = curFav.contains(url);
            long lastPlayed = lastLocalPlaybackTs(media);
            // A favorite toggle must win LWW even if position/played are unchanged.
            p.client_ts = favChanged ? now : (lastPlayed > 0 ? lastPlayed : now);
            out.add(p);
        }
        return out;
    }

    /** Media download-urls of the given items (the sync key), skipping any without
     *  a usable enclosure url. */
    private static Set<String> mediaUrlSet(List<FeedItem> items) {
        Set<String> urls = new HashSet<>();
        for (FeedItem item : items) {
            FeedMedia media = item.getMedia();
            if (media != null && media.getDownloadUrl() != null && !media.getDownloadUrl().isEmpty()) {
                urls.add(media.getDownloadUrl());
            }
        }
        return urls;
    }
}
