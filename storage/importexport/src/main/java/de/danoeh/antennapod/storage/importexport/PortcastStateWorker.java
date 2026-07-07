package de.danoeh.antennapod.storage.importexport;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.database.LongList;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

/**
 * After the PortCast subscribe worker has subscribed all feeds and
 * {@link de.danoeh.antennapod.net.download.serviceinterface.FeedUpdateManager} has
 * refreshed them, this worker resolves the imported episode states + queue
 * against the now-materialized FeedItems and applies them. It also applies any
 * global playback preferences carried by the import.
 *
 * <p>Counterpart of {@link PodcastAddictStateWorker}.
 */
public class PortcastStateWorker extends Worker {
    private static final String TAG = "PortcastStateWorker";
    /** Unique-work name; public so the in-app status banner can observe it. */
    public static final String WORK_ID = "de.danoeh.antennapod.PortcastStateApply";
    private static final int MAX_ATTEMPTS = 20; // 30s linear backoff, like the PA worker

    public static void enqueue(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(PortcastStateWorker.class)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_ID, ExistingWorkPolicy.REPLACE, request);
    }

    public PortcastStateWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        List<PortcastImporter.EpisodeState> states = PortcastImporter.loadEpisodeStates(ctx);
        List<PortcastImporter.QueueEntry> queue = PortcastImporter.loadQueue(ctx);
        PortcastImporter.GlobalPrefs globals = PortcastImporter.loadGlobalPrefs(ctx);

        // Global prefs are independent of feed materialization, so apply on the
        // first attempt and never again (clear after).
        if (globals != null && getRunAttemptCount() == 0) {
            applyGlobalPrefs(globals);
            PortcastImporter.clearGlobalPrefs(ctx);
        }

        if (states.isEmpty() && queue.isEmpty()) {
            return Result.success();
        }

        Log.d(TAG, "Attempt " + (getRunAttemptCount() + 1) + "/" + MAX_ATTEMPTS
                + " (" + states.size() + " states, " + queue.size() + " queue pending)");

        // One-pass index of every FeedItem in the DB: by guid, by enclosure
        // URL, and — for Spotify-sourced states that carry only a title — by
        // (normalized feed URL → normalized title → item).
        Map<String, FeedItem> itemByGuid = new HashMap<>();
        Map<String, FeedItem> itemByUrl = new HashMap<>();
        Map<String, Map<String, FeedItem>> itemByFeedAndTitle = new HashMap<>();
        for (Feed feed : DBReader.getFeedList()) {
            List<FeedItem> items = DBReader.getFeedItemList(feed, FeedItemFilter.unfiltered(),
                    SortOrder.DATE_NEW_OLD, 0, Integer.MAX_VALUE);
            Map<String, FeedItem> titleMap = itemByFeedAndTitle.computeIfAbsent(
                    PortcastImporter.normalizeFeedUrl(feed.getDownloadUrl()), k -> new HashMap<>());
            for (FeedItem item : items) {
                if (item.getItemIdentifier() != null && !item.getItemIdentifier().isEmpty()) {
                    itemByGuid.put(item.getItemIdentifier(), item);
                }
                if (item.getMedia() != null && item.getMedia().getDownloadUrl() != null) {
                    itemByUrl.put(item.getMedia().getDownloadUrl(), item);
                }
                String t = PortcastImporter.normalizeTitle(item.getTitle());
                // First occurrence wins; DATE_NEW_OLD means that's the newest
                // when two episodes in one feed share a title (rare).
                if (!t.isEmpty()) {
                    titleMap.putIfAbsent(t, item);
                }
            }
        }

        // Apply queue first so users see something happening before per-episode states.
        List<PortcastImporter.QueueEntry> remainingQueue = applyQueue(queue, itemByGuid, itemByUrl);

        List<PortcastImporter.EpisodeState> remaining = new ArrayList<>();
        int applied = 0;
        for (PortcastImporter.EpisodeState state : states) {
            FeedItem item = resolveItemForState(state, itemByGuid, itemByUrl, itemByFeedAndTitle);
            if (item == null) {
                remaining.add(state);
                continue;
            }
            applyState(item, state);
            applied++;
        }
        Log.d(TAG, "Applied " + applied + " states, " + remaining.size() + " still pending");

        boolean allDone = remaining.isEmpty() && remainingQueue.isEmpty();
        if (allDone) {
            PortcastImporter.clearEpisodeStates(ctx);
            PortcastImporter.clearQueue(ctx);
            return Result.success();
        }
        if (getRunAttemptCount() >= MAX_ATTEMPTS - 1) {
            Log.d(TAG, "Max attempts reached, giving up on " + remaining.size()
                    + " states, " + remainingQueue.size() + " queue");
            PortcastImporter.clearEpisodeStates(ctx);
            PortcastImporter.clearQueue(ctx);
            return Result.success();
        }
        try {
            PortcastImporter.saveEpisodeStates(ctx, remaining);
            PortcastImporter.saveQueue(ctx, remainingQueue);
        } catch (Exception e) {
            Log.e(TAG, "Failed to persist remaining state", e);
        }
        return Result.retry();
    }

    private void applyGlobalPrefs(PortcastImporter.GlobalPrefs gp) {
        if (gp.playbackRate > 0) {
            UserPreferences.setPlaybackSpeed(gp.playbackRate);
        }
        if (gp.skipForwardSeconds > 0) {
            UserPreferences.setFastForwardSecs(gp.skipForwardSeconds);
        }
        if (gp.skipBackwardSeconds > 0) {
            UserPreferences.setRewindSecs(gp.skipBackwardSeconds);
        }
        if (gp.trimSilence != null) {
            UserPreferences.setSkipSilence(gp.trimSilence);
        }
    }

    private List<PortcastImporter.QueueEntry> applyQueue(
            List<PortcastImporter.QueueEntry> entries,
            Map<String, FeedItem> itemByGuid,
            Map<String, FeedItem> itemByUrl) {
        if (entries.isEmpty()) {
            return new ArrayList<>();
        }

        Set<Long> alreadyQueued = new HashSet<>();
        LongList queueIds = DBReader.getQueueIDList();
        for (int i = 0; i < queueIds.size(); i++) {
            alreadyQueued.add(queueIds.get(i));
        }

        List<FeedItem> toEnqueue = new ArrayList<>();
        List<PortcastImporter.QueueEntry> remaining = new ArrayList<>();
        for (PortcastImporter.QueueEntry q : entries) {
            FeedItem item = resolveEntry(q, itemByGuid, itemByUrl);
            if (item == null) {
                remaining.add(q);
                continue;
            }
            if (alreadyQueued.contains(item.getId())) {
                continue;
            }
            toEnqueue.add(item);
            alreadyQueued.add(item.getId());
        }
        if (!toEnqueue.isEmpty()) {
            try {
                DBWriter.addQueueItem(getApplicationContext(),
                        toEnqueue.toArray(new FeedItem[0])).get();
                Log.d(TAG, "Appended " + toEnqueue.size() + " items to queue ("
                        + remaining.size() + " unresolved)");
            } catch (Exception e) {
                Log.e(TAG, "Failed to enqueue items", e);
            }
        }
        return remaining;
    }

    private static FeedItem resolveEntry(PortcastImporter.QueueEntry q,
                                         Map<String, FeedItem> itemByGuid,
                                         Map<String, FeedItem> itemByUrl) {
        if (!q.guid.isEmpty() && itemByGuid.containsKey(q.guid)) {
            return itemByGuid.get(q.guid);
        }
        if (!q.enclosureUrl.isEmpty() && itemByUrl.containsKey(q.enclosureUrl)) {
            return itemByUrl.get(q.enclosureUrl);
        }
        return null;
    }

    /** Resolve the DB FeedItem an imported state targets. RSS-sourced states
     *  match by guid then enclosure URL; Spotify-sourced states (no guid/url)
     *  fall back to a feed-scoped title match. Title hits are removed from the
     *  index so two states can't claim the same item and a retry re-evaluates
     *  cleanly. */
    private static FeedItem resolveItemForState(PortcastImporter.EpisodeState state,
            Map<String, FeedItem> itemByGuid,
            Map<String, FeedItem> itemByUrl,
            Map<String, Map<String, FeedItem>> itemByFeedAndTitle) {
        if (!state.guid.isEmpty() && itemByGuid.containsKey(state.guid)) {
            return itemByGuid.get(state.guid);
        }
        if (!state.enclosureUrl.isEmpty() && itemByUrl.containsKey(state.enclosureUrl)) {
            return itemByUrl.get(state.enclosureUrl);
        }
        FeedItem byTitle = PortcastImporter.matchByFeedAndTitle(
                state.feedUrl, state.title, itemByFeedAndTitle);
        if (byTitle != null) {
            return byTitle;
        }
        return null;
    }

    private void applyState(FeedItem item, PortcastImporter.EpisodeState state) {
        // status enum mapping:
        //   completed → mark played
        //   in_progress → set position
        //   archived → mark played (closest local analogue; we don't have an
        //     "archived" state, and dropping the data would silently lose history)
        //   unplayed → only carry over favorite + duration
        boolean treatAsPlayed = "completed".equals(state.status) || "archived".equals(state.status);
        if (treatAsPlayed) {
            DBWriter.markItemPlayed(item, FeedItem.PLAYED, false);
            if (item.getMedia() != null) {
                FeedMedia media = item.getMedia();
                if (state.durationMs > 0 && media.getDuration() == 0) {
                    media.setDuration((int) state.durationMs);
                }
                int playedMs = state.durationMs > 0 ? (int) state.durationMs : media.getDuration();
                media.setPlayedDuration(playedMs);
                // Same chart-attribution guard as the PA worker: only stamp the
                // statistics date when we have a real one, else this episode
                // would land on "this month" in Time Saved.
                if (state.lastPlayedMs > 0) {
                    media.setLastPlayedTimeStatistics(state.lastPlayedMs);
                    media.setLastPlayedTimeHistory(new Date(state.lastPlayedMs));
                } else {
                    media.setLastPlayedTimeHistory(new Date(System.currentTimeMillis()));
                }
                media.setPosition(0);
                DBWriter.setFeedMediaPlaybackInformation(media);
            }
        } else if ("in_progress".equals(state.status) && state.positionMs > 0
                && item.getMedia() != null) {
            FeedMedia media = item.getMedia();
            media.setPosition(state.positionMs);
            if (state.durationMs > 0 && media.getDuration() == 0) {
                media.setDuration((int) state.durationMs);
            }
            if (media.getLastPlayedTimeHistory() == null) {
                long playbackDate = state.lastPlayedMs > 0
                        ? state.lastPlayedMs : System.currentTimeMillis();
                media.setLastPlayedTimeHistory(new Date(playbackDate));
                if (state.lastPlayedMs > 0) {
                    media.setLastPlayedTimeStatistics(state.lastPlayedMs);
                }
            }
            DBWriter.setFeedMediaPlaybackInformation(media);
        }

        if (state.starred) {
            DBWriter.addFavoriteItem(item);
        }

        if (state.bookmarks != null && !state.bookmarks.isEmpty()) {
            // Re-running the import must not duplicate bookmarks: skip any
            // position the episode already has one at.
            Set<Integer> existingPositions = new HashSet<>();
            for (de.danoeh.antennapod.model.feed.Bookmark existing : DBReader.getBookmarks(item.getId())) {
                existingPositions.add(existing.getPosition());
            }
            for (PortcastImporter.BookmarkState bookmark : state.bookmarks) {
                if (!existingPositions.add(bookmark.positionMs)) {
                    continue;
                }
                long createdAt = bookmark.createdAtMs > 0
                        ? bookmark.createdAtMs : System.currentTimeMillis();
                DBWriter.addBookmark(item.getId(), bookmark.positionMs, bookmark.note, createdAt);
            }
        }
    }
}
