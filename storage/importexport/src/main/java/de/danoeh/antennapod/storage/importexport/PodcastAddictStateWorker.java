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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;

public class PodcastAddictStateWorker extends Worker {
    private static final String TAG = "PAStateWorker";
    private static final String WORK_ID = "de.danoeh.antennapod.PodcastAddictStateApply";
    private static final int MAX_ATTEMPTS = 20; // 30 s linear backoff → retries at 30s, 60s, ..., 9.5 min; typically done in < 2 min

    /**
     * Enqueue the worker with no initial delay. If episodes aren't in the DB yet
     * (feeds still refreshing) it will retry every 30 seconds (linear backoff),
     * up to MAX_ATTEMPTS times.
     */
    public static void enqueue(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(PodcastAddictStateWorker.class)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_ID, ExistingWorkPolicy.REPLACE, request);
    }

    public PodcastAddictStateWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        List<PodcastAddictImporter.EpisodeState> states =
                PodcastAddictImporter.loadEpisodeStates(ctx);
        List<PodcastAddictImporter.QueueEntry> queueEntries = PodcastAddictImporter.loadQueue(ctx);
        PodcastAddictImporter.QueueEntry currentlyPlaying =
                PodcastAddictImporter.loadCurrentlyPlaying(ctx);

        if (states.isEmpty() && queueEntries.isEmpty() && currentlyPlaying == null) {
            return Result.success();
        }

        Log.d(TAG, "Attempt " + (getRunAttemptCount() + 1) + "/" + MAX_ATTEMPTS
                + " (" + states.size() + " states, " + queueEntries.size() + " queue, "
                + (currentlyPlaying != null ? "1" : "0") + " currently-playing pending)");

        // Build lookup maps for episode states
        Map<String, PodcastAddictImporter.EpisodeState> byGuid = new HashMap<>();
        Map<String, PodcastAddictImporter.EpisodeState> byUrl = new HashMap<>();
        for (PodcastAddictImporter.EpisodeState state : states) {
            if (state.guid != null && !state.guid.isEmpty()) {
                byGuid.put(state.guid, state);
            }
            if (state.downloadUrl != null && !state.downloadUrl.isEmpty()) {
                byUrl.put(state.downloadUrl, state);
            }
        }

        // Build a one-pass index of every FeedItem currently in the DB, keyed
        // by guid and downloadUrl. Used by both the episode-state apply loop
        // and the queue/currently-playing resolution below — building it once
        // avoids re-scanning DBReader for each.
        Map<String, FeedItem> itemByGuid = new HashMap<>();
        Map<String, FeedItem> itemByUrl = new HashMap<>();
        List<Feed> feeds = DBReader.getFeedList();
        for (Feed feed : feeds) {
            List<FeedItem> items = DBReader.getFeedItemList(feed, FeedItemFilter.unfiltered(),
                    SortOrder.DATE_NEW_OLD, 0, Integer.MAX_VALUE);
            for (FeedItem item : items) {
                if (item.getItemIdentifier() != null && !item.getItemIdentifier().isEmpty()) {
                    itemByGuid.put(item.getItemIdentifier(), item);
                }
                if (item.getMedia() != null && item.getMedia().getDownloadUrl() != null) {
                    itemByUrl.put(item.getMedia().getDownloadUrl(), item);
                }
            }
        }

        // ---- Episode states ----
        List<PodcastAddictImporter.EpisodeState> remaining = new ArrayList<>(states);
        for (FeedItem item : itemByGuid.values()) {
            PodcastAddictImporter.EpisodeState state = findState(item, byGuid, byUrl);
            if (state == null) continue;
            applyState(item, state);
            remaining.remove(state);
            if (state.guid != null) byGuid.remove(state.guid);
            if (state.downloadUrl != null) byUrl.remove(state.downloadUrl);
        }
        Log.d(TAG, "Applied " + (states.size() - remaining.size()) + " states, "
                + remaining.size() + " still pending");

        // ---- Queue ----
        List<PodcastAddictImporter.QueueEntry> remainingQueue =
                applyQueue(queueEntries, itemByGuid, itemByUrl);

        // ---- Currently-playing ----
        boolean currentlyPlayingResolved = false;
        if (currentlyPlaying != null) {
            FeedItem item = resolveEntry(currentlyPlaying, itemByGuid, itemByUrl);
            if (item != null && item.getMedia() != null) {
                de.danoeh.antennapod.storage.preferences.PlaybackPreferences
                        .writeMediaPlaying(item.getMedia());
                currentlyPlayingResolved = true;
                Log.d(TAG, "Set currently-playing to item " + item.getId());
            }
        }

        // ---- Decide retry vs done ----
        boolean allDone = remaining.isEmpty()
                && remainingQueue.isEmpty()
                && (currentlyPlaying == null || currentlyPlayingResolved);

        if (allDone) {
            PodcastAddictImporter.clearEpisodeStates(ctx);
            Log.d(TAG, "All states/queue/currently-playing applied — done");
            return Result.success();
        }

        if (getRunAttemptCount() >= MAX_ATTEMPTS - 1) {
            Log.d(TAG, "Max attempts reached, giving up on " + remaining.size() + " states, "
                    + remainingQueue.size() + " queue entries, currentlyPlaying="
                    + (currentlyPlaying != null && !currentlyPlayingResolved));
            PodcastAddictImporter.clearEpisodeStates(ctx);
            return Result.success();
        }

        // Save unmatched data and retry. Keep the currently-playing payload if
        // it didn't resolve so the next retry can try again.
        try {
            PodcastAddictImporter.saveEpisodeStates(ctx, remaining);
            PodcastAddictImporter.saveQueueAndCurrentlyPlaying(
                    ctx, remainingQueue,
                    currentlyPlayingResolved ? null : currentlyPlaying);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save remaining import state", e);
        }
        return Result.retry();
    }

    /** Resolve queue entries against the DB; appended to the existing queue in
     *  PA's order (duplicates skipped). Returns entries we couldn't resolve. */
    private List<PodcastAddictImporter.QueueEntry> applyQueue(
            List<PodcastAddictImporter.QueueEntry> entries,
            Map<String, FeedItem> itemByGuid,
            Map<String, FeedItem> itemByUrl) {
        if (entries.isEmpty()) return java.util.Collections.emptyList();

        // Existing queue IDs to skip duplicates we already imported. DBReader
        // returns a LongList (primitive-friendly), so copy out manually.
        java.util.Set<Long> alreadyQueued = new java.util.HashSet<>();
        de.danoeh.antennapod.storage.database.LongList queueIds = DBReader.getQueueIDList();
        for (int i = 0; i < queueIds.size(); i++) {
            alreadyQueued.add(queueIds.get(i));
        }

        List<FeedItem> toEnqueue = new ArrayList<>();
        List<PodcastAddictImporter.QueueEntry> remaining = new ArrayList<>();
        for (PodcastAddictImporter.QueueEntry q : entries) {
            FeedItem item = resolveEntry(q, itemByGuid, itemByUrl);
            if (item == null) {
                remaining.add(q);
                continue;
            }
            if (alreadyQueued.contains(item.getId())) continue;
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

    private static FeedItem resolveEntry(PodcastAddictImporter.QueueEntry q,
            Map<String, FeedItem> itemByGuid, Map<String, FeedItem> itemByUrl) {
        if (q.guid != null && !q.guid.isEmpty()) {
            FeedItem item = itemByGuid.get(q.guid);
            if (item != null) return item;
        }
        if (q.downloadUrl != null && !q.downloadUrl.isEmpty()) {
            FeedItem item = itemByUrl.get(q.downloadUrl);
            if (item != null) return item;
        }
        return null;
    }

    private PodcastAddictImporter.EpisodeState findState(FeedItem item,
            Map<String, PodcastAddictImporter.EpisodeState> byGuid,
            Map<String, PodcastAddictImporter.EpisodeState> byUrl) {
        String guid = item.getItemIdentifier();
        if (guid != null && byGuid.containsKey(guid)) {
            return byGuid.get(guid);
        }
        if (item.getMedia() != null) {
            String url = item.getMedia().getDownloadUrl();
            if (url != null && byUrl.containsKey(url)) {
                return byUrl.get(url);
            }
        }
        return null;
    }

    private void applyState(FeedItem item, PodcastAddictImporter.EpisodeState state) {
        if (state.played) {
            DBWriter.markItemPlayed(item, FeedItem.PLAYED, false);

            if (item.getMedia() != null) {
                FeedMedia media = item.getMedia();
                if (state.durationMs > 0 && media.getDuration() == 0) {
                    media.setDuration((int) state.durationMs);
                }
                int playedMs = state.durationMs > 0 ? (int) state.durationMs : media.getDuration();
                media.setPlayedDuration(playedMs);
                // Only attribute to a month in the chart if we have a real playback date.
                // Falling back to now would pile all imported history into the current month.
                if (state.playbackDateMs > 0) {
                    media.setLastPlayedTimeStatistics(state.playbackDateMs);
                    media.setLastPlayedTimeHistory(new Date(state.playbackDateMs));
                } else {
                    media.setLastPlayedTimeHistory(new Date(System.currentTimeMillis()));
                }
                media.setPosition(0);
                DBWriter.setFeedMediaPlaybackInformation(media);

                // Only record speed savings when we have a real playback date.
                // Using System.currentTimeMillis() as a fallback would pile all imported
                // history into the current month, inflating the Time Saved chart.
                if (state.playbackSpeed > 1.0f && state.playbackDateMs > 0) {
                    int durationMs = media.getDuration() > 0 ? media.getDuration() : (int) state.durationMs;
                    int speedSavedMs = (int) (durationMs * (1.0 - 1.0 / state.playbackSpeed));
                    if (speedSavedMs > 0) {
                        DBWriter.recordSkipEvent(item.getId(), "speed", speedSavedMs, state.playbackDateMs);
                    }
                }
            }
        } else if (state.positionMs > 0 && item.getMedia() != null) {
            FeedMedia media = item.getMedia();
            media.setPosition(state.positionMs);
            if (state.durationMs > 0 && media.getDuration() == 0) {
                media.setDuration((int) state.durationMs);
            }
            if (media.getLastPlayedTimeHistory() == null) {
                long playbackDate = state.playbackDateMs > 0
                        ? state.playbackDateMs : System.currentTimeMillis();
                media.setLastPlayedTimeHistory(new Date(playbackDate));
                if (state.playbackDateMs > 0) {
                    media.setLastPlayedTimeStatistics(state.playbackDateMs);
                }
            }
            DBWriter.setFeedMediaPlaybackInformation(media);
        }

        if (state.favorite) {
            DBWriter.addFavoriteItem(item);
        }
    }
}
