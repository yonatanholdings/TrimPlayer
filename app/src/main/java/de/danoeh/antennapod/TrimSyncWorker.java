package de.danoeh.antennapod;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.playback.service.trim.TrimClient;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.database.LongList;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pushes the phone's library (subscriptions, listening queue, playback progress)
 * to the TrimBrain account so the web player and other devices stay in sync, and
 * stores the returned delta cursor.
 *
 * <p>Scope (v1): this is the <b>push</b> half plus cursor bookkeeping. Progress is
 * stamped with the media's real last-played time, so last-writer-wins resolves it
 * correctly against web edits. Subscriptions and the queue are currently pushed
 * <i>phone-authoritative</i> (stamped "now"); that's fine while the phone is the
 * primary place a user manages their library, but it means a web-side
 * unsubscribe/dequeue can be re-asserted by the phone on the next run.
 *
 * <p>TODO(sync-phase-2): apply the server delta back to the local DB
 * ({@code resp.subscriptions/progress/queue}) — update {@link FeedMedia} positions,
 * mark played, reconcile queue order, and auto-subscribe — and add a local change
 * journal so web-authoritative subscription/queue edits are honored instead of
 * clobbered. That half writes to the DB and must be validated on-device.
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

        TrimClient.SyncRequest req = new TrimClient.SyncRequest();
        req.cursor = UserPreferences.getTrimSyncCursor();
        req.subscriptions = buildSubscriptions(now);
        List<FeedItem> queue = DBReader.getQueue();
        req.queue = buildQueue(queue, now);
        req.progress = buildProgress(queue, now);

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
        // mid-apply just re-pulls (apply is idempotent + LWW-guarded). v1 applies
        // playback progress only; queue reorder + auto-subscribe stay deferred
        // (they mutate the subscription set and need on-device validation).
        int appliedProgress = applyProgress(resp.body.progress);
        int appliedQueue = applyQueue(getApplicationContext(), resp.body.queue);
        UserPreferences.setTrimSyncCursor(resp.body.cursor);
        Log.d(TAG, "sync ok: pushed subs=" + req.subscriptions.size()
                + " queue=" + req.queue.size() + " progress=" + req.progress.size()
                + "; applied progress=" + appliedProgress + " queue=" + appliedQueue
                + ", cursor=" + resp.body.cursor);
        return Result.success();
    }

    /** Apply server-side queue changes to the local queue (delta only, so we
     *  touch just the items that changed server-side rather than rebuilding the
     *  queue). Conservative for v1: web-side removals and additions propagate,
     *  but reordering of already-queued items is left to the phone (precise
     *  cross-device reorder needs the change-journal — deferred). Episodes not
     *  present locally are skipped (queue auto-subscribe deferred). */
    private static int applyQueue(android.content.Context ctx,
                                  List<TrimClient.QueueChange> queue) {
        if (queue == null || queue.isEmpty()) {
            return 0;
        }
        LongList ids = DBReader.getQueueIDList();
        Set<Long> inQueue = new HashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            inQueue.add(ids.get(i));
        }
        int changed = 0;
        for (TrimClient.QueueChange q : queue) {
            if (q == null || q.episode_url == null) {
                continue;
            }
            FeedItem item = DBReader.getFeedItemByGuidOrEpisodeUrl(q.guid, q.episode_url);
            if (item == null) {
                continue; // not subscribed/present locally
            }
            boolean present = inQueue.contains(item.getId());
            try {
                if (q.deleted) {
                    if (present) {
                        DBWriter.removeQueueItem(ctx, false, item).get();
                        inQueue.remove(item.getId());
                        changed++;
                    }
                } else if (!present) {
                    int size = inQueue.size();
                    int idx = q.position == null ? size : Math.max(0, Math.min(q.position, size));
                    DBWriter.addQueueItemAt(ctx, item.getId(), idx).get();
                    inQueue.add(item.getId());
                    changed++;
                }
            } catch (Exception e) {
                Log.w(TAG, "queue apply failed for " + q.episode_url + ": " + e.getMessage());
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

    private static List<TrimClient.SubscriptionChange> buildSubscriptions(long ts) {
        List<TrimClient.SubscriptionChange> out = new ArrayList<>();
        for (Feed f : DBReader.getFeedList()) {
            if (f.getDownloadUrl() == null || f.getDownloadUrl().isEmpty()) {
                continue;
            }
            TrimClient.SubscriptionChange s = new TrimClient.SubscriptionChange();
            s.rss_url = f.getDownloadUrl();
            s.title = f.getTitle();
            s.deleted = false;
            s.client_ts = ts;
            out.add(s);
        }
        return out;
    }

    private static List<TrimClient.QueueChange> buildQueue(List<FeedItem> queue, long ts) {
        List<TrimClient.QueueChange> out = new ArrayList<>();
        for (int i = 0; i < queue.size(); i++) {
            FeedItem item = queue.get(i);
            FeedMedia media = item.getMedia();
            if (media == null || media.getDownloadUrl() == null || media.getDownloadUrl().isEmpty()) {
                continue;
            }
            TrimClient.QueueChange q = new TrimClient.QueueChange();
            q.episode_url = media.getDownloadUrl();
            q.rss_url = item.getFeed() != null ? item.getFeed().getDownloadUrl() : null;
            q.guid = item.getItemIdentifier();
            q.position = i;
            q.deleted = false;
            q.client_ts = ts;
            out.add(q);
        }
        return out;
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
